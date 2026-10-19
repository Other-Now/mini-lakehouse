package lakehouse.bench;

import lakehouse.*;
import lakehouse.Record;
import lakehouse.storage.CommitMode;
import lakehouse.storage.LocalStorage;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Crash test. Each round launches several writer JVMs against one table. Some are armed with a deterministic
 * crash point (Runtime.halt at the n-th time a named point in the commit path is reached); some are kill -9'd
 * from outside at a random moment; the rest run to their deadline. After every round, with all writers dead:
 * <ol>
 *   <li>the table opens cold and every referenced file reads and matches its metadata;</li>
 *   <li>no duplicate keys; the record-level index exactly matches the data; no partially visible insert batch;</li>
 *   <li>EVERY version committed during the round sums to the initial total (atomic, no lost updates);</li>
 *   <li>every commit a writer acknowledged is in the timeline;</li>
 *   <li>vacuum removes the crash debris (orphan files, commit temp files) and the table still verifies.</li>
 * </ol>
 * The same harness runs the controls: --mode RENAME / DIRECT, --conflict NONE / FILE_GROUP.
 */
public final class CrashTest {

    static TableConfig config(TableConfig.ConflictCheck conflict) {
        return TableConfig.defaults().withConflict(conflict).withCheckpointInterval(10).withMaxRecordsPerFile(2000);
    }

    public static void main(String[] a) throws Exception {
        Map<String, String> args = BenchUtil.args(a);
        int rounds = Integer.parseInt(args.getOrDefault("rounds", "40"));
        int writers = Integer.parseInt(args.getOrDefault("writers", "4"));
        long millis = Long.parseLong(args.getOrDefault("millis", "4000"));
        CommitMode mode = CommitMode.valueOf(args.getOrDefault("mode", "LINK"));
        TableConfig.ConflictCheck conflict = TableConfig.ConflictCheck.valueOf(args.getOrDefault("conflict", "FULL"));
        boolean stopOnFailure = Boolean.parseBoolean(args.getOrDefault("stop-on-failure", "false"));
        double armP = Double.parseDouble(args.getOrDefault("arm-probability", "0.5"));
        String onlyPoint = args.get("point");
        String insertSpace = args.getOrDefault("insert-space", "64");
        String insertClock = args.getOrDefault("insert-clock-ms", "0");
        long seed = Long.parseLong(args.getOrDefault("seed", "1"));
        String label = args.getOrDefault("label", mode + "-" + conflict);

        Path dir = BenchUtil.workDir(args, "crash-" + label);
        Path acks = dir.resolve("_acks");
        Files.createDirectories(acks);
        Path table = dir.resolve("table");
        LocalStorage st = new LocalStorage(table, mode, true);
        TableConfig cfg = config(conflict);
        int groups = 8, per = 50, seedKeys = groups * per;
        long total = (long) seedKeys * 1000;
        Table t = Table.create(st, cfg);
        for (int g = 0; g < groups; g++) {
            Txn tx = t.begin();
            for (int i = 0; i < per; i++) tx.upsert(new Record((long) g * per + i, 0, 1000, "seed"));
            tx.commit();
        }

        SplittableRandom rnd = new SplittableRandom(seed);
        String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        String cp = System.getProperty("java.class.path");
        Map<String, Integer> crashesByPoint = new TreeMap<>();
        int externalKills = 0, cleanExits = 0, writerErrors = 0, failedRounds = 0, roundsRun = 0;
        long lostAcks = 0, acked = 0, orphans = 0, temps = 0;
        List<String> failures = new ArrayList<>();
        long versionBefore = t.latestVersion();

        for (int round = 1; round <= rounds; round++) {
            roundsRun++;
            List<Process> procs = new ArrayList<>();
            List<String> armed = new ArrayList<>();
            List<Long> killAt = new ArrayList<>();
            for (int w = 0; w < writers; w++) {
                List<String> cmd = new ArrayList<>(List.of(java, "-Xmx256m", "-XX:TieredStopAtLevel=1"));
                String point = null;
                if (rnd.nextDouble() < armP) {
                    point = onlyPoint != null ? onlyPoint : Crash.POINTS.get(rnd.nextInt(Crash.POINTS.size()));
                    int n = point.equals(Crash.COMPACT_AFTER_WRITE) || point.equals(Crash.AFTER_CHECKPOINT)
                            ? rnd.nextInt(1, 3) : rnd.nextInt(1, 25);
                    cmd.add("-Dlakehouse.crash=" + point + "@" + n);
                }
                armed.add(point);
                cmd.addAll(List.of("-cp", cp, WriterMain.class.getName(), "--dir", table.toString(), "--mode", mode.name(),
                        "--conflict", conflict.name(), "--seed-keys", Integer.toString(seedKeys), "--millis", Long.toString(millis), "--insert-space", insertSpace, "--insert-clock-ms", insertClock,
                        "--ack", acks.resolve("r" + round + "-w" + w).toString()));
                procs.add(new ProcessBuilder(cmd).redirectErrorStream(true)
                        .redirectOutput(acks.resolve("r" + round + "-w" + w + ".log").toFile()).start());
                killAt.add(point == null && rnd.nextDouble() < 0.5 ? 800 + (long) rnd.nextInt((int) millis) : -1);
            }
            long started = System.currentTimeMillis();
            boolean anyAlive = true;
            while (anyAlive) {
                anyAlive = false;
                long now = System.currentTimeMillis() - started;
                for (int w = 0; w < writers; w++) {
                    Process p = procs.get(w);
                    if (!p.isAlive()) continue;
                    anyAlive = true;
                    if (killAt.get(w) > 0 && now >= killAt.get(w)) {
                        p.destroyForcibly();          // TerminateProcess on Windows, SIGKILL on Linux
                        killAt.set(w, -2L);
                    }
                }
                if (now > millis + 60_000) throw new IllegalStateException("writers hung in round " + round);
                Thread.sleep(20);
            }
            for (int w = 0; w < writers; w++) {
                Process p = procs.get(w);
                p.waitFor(5, TimeUnit.SECONDS);
                String log = Files.readString(acks.resolve("r" + round + "-w" + w + ".log"));
                if (killAt.get(w) == -2L && p.exitValue() != 0) externalKills++;
                else if (p.exitValue() == 137) crashesByPoint.merge(armed.get(w), 1, Integer::sum);
                else if (p.exitValue() == 0) cleanExits++;
                else { writerErrors++; failures.add("round " + round + " writer " + w + " exit " + p.exitValue() + ": " + log.strip()); }
            }

            // ---- verify ----
            List<String> problems = new ArrayList<>();
            TableVerifier.Report rep = TableVerifier.verify(st, cfg);
            if (rep.error() != null) problems.add("unreadable: " + rep.error());
            else {
                if (!rep.ok()) problems.add("verify: " + rep);
                if (rep.sum() != total) problems.add("sum " + rep.sum() + " != " + total);
                long bad = TableVerifier.firstInconsistentVersion(st, cfg, versionBefore, rep.version(), total);
                if (bad >= 0) problems.add("version " + bad + " does not sum to the total");
                Set<String> committed = new HashSet<>();
                Table fresh = Table.open(st, cfg);
                for (long v = 1; v <= rep.version(); v++) committed.add(txnOf(fresh, v));
                long lostThisRound = 0;
                for (int w = 0; w < writers; w++) {
                    Path ack = acks.resolve("r" + round + "-w" + w);
                    if (!Files.exists(ack)) continue;
                    for (String line : Files.readAllLines(ack, StandardCharsets.UTF_8)) {
                        if (line.isBlank() || line.startsWith("compaction@")) continue;
                        acked++;
                        if (!committed.contains(line.strip())) lostThisRound++;
                    }
                }
                lostAcks += lostThisRound;
                if (lostThisRound > 0) problems.add(lostThisRound + " acknowledged commits missing from the timeline");
                Maintenance.VacuumResult vr = Maintenance.vacuum(fresh, 10, 0);
                orphans += vr.orphansDeleted();
                temps += vr.tempsDeleted();
                TableVerifier.Report after = TableVerifier.verify(st, cfg);
                if (!after.ok() || after.sum() != total) problems.add("after vacuum: " + after);
                versionBefore = rep.version();
            }
            String line = String.format("round %2d: v%-5d files=%-3d records=%-6d %s", round, rep.version(), rep.files(),
                    rep.records(), problems.isEmpty() ? "OK" : "FAIL " + problems);
            System.out.println(line);
            if (!problems.isEmpty()) {
                failedRounds++;
                failures.add(line);
                if (stopOnFailure) break;
            }
        }

        long finalVersion;
        try {
            finalVersion = Table.open(st, cfg).latestVersion();
        } catch (Exception e) {
            finalVersion = -1;   // e.g. the DIRECT control: a torn commit makes the table unopenable
        }
        StringBuilder md = new StringBuilder();
        md.append(String.format("# Crash test: %s%n%n", label));
        md.append(String.format("%s. commit mode %s, conflict check %s, %d writer JVMs per round, %d ms per round, "
                + "%s.%n%n", BenchUtil.machine(), mode, conflict, writers, millis,
                insertClock.equals("0") ? "insert batches drawn from " + insertSpace + " shared bases"
                        : "every writer inserts the same new batch per " + insertClock + " ms time slot"));
        md.append("| | |\n|---|---:|\n");
        md.append(String.format("| rounds run | %d |%n| rounds failing verification | **%d** |%n", roundsRun, failedRounds));
        md.append(String.format("| table versions committed | %s |%n", finalVersion < 0 ? "table unreadable" : Long.toString(finalVersion)));
        md.append(String.format("| writers halted at an injected crash point | %d |%n", crashesByPoint.values().stream().mapToInt(i -> i).sum()));
        for (var e : crashesByPoint.entrySet()) md.append(String.format("| &nbsp;&nbsp;%s | %d |%n", e.getKey(), e.getValue()));
        md.append(String.format("| writers kill -9'd externally | %d |%n| writers exiting cleanly | %d |%n", externalKills, cleanExits));
        md.append(String.format("| writers failing with an error | %d |%n", writerErrors));
        md.append(String.format("| acknowledged commits checked | %d |%n| acknowledged commits lost | **%d** |%n", acked, lostAcks));
        md.append(String.format("| orphan files + commit temp files removed by vacuum | %d + %d |%n", orphans, temps));
        if (!failures.isEmpty()) {
            md.append("\nFirst failures:\n\n```\n");
            failures.stream().limit(8).forEach(f -> md.append(f.length() > 600 ? f.substring(0, 600) + "..." : f).append('\n'));
            md.append("```\n");
        }
        System.out.print(md);
        BenchUtil.write(args, "crash-" + label + ".md", md.toString());
    }

    private static String txnOf(Table t, long v) {
        try {
            byte[] b = t.storage().read(Table.commitPath(v));
            return Commit.decode(b, "v" + v).txn();
        } catch (Exception e) {
            return "";
        }
    }
}
