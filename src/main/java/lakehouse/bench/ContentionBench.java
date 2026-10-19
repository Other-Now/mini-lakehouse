package lakehouse.bench;

import lakehouse.*;
import lakehouse.Record;
import lakehouse.storage.CommitMode;
import lakehouse.storage.LocalStorage;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OCC under rising contention. N writer threads, each with its own Table handle (they share nothing but the
 * directory), run read-modify-write transactions that each increment {@code keys} random keys drawn from the first
 * {@code hot} of {@code groups} file groups. Fewer hot groups = more transactions overlap = more aborts.
 * A conflicting transaction is retried from a fresh snapshot until it commits.
 * <p>
 * Lost-update check: the final sum must equal the initial sum plus every committed increment.
 */
public final class ContentionBench {

    record Row(int writers, int hot, long commits, double commitsPerSec, long aborts, double abortRate,
               double rebasesPerCommit, double p50ms, double p99ms, double attemptsP99, long drift) {}

    public static void main(String[] a) throws Exception {
        Map<String, String> args = BenchUtil.args(a);
        int[] writers = BenchUtil.ints(args.getOrDefault("writers", "1,2,4,8,16"));
        int[] hots = BenchUtil.ints(args.getOrDefault("hot", "64,16,4,1"));
        int groups = Integer.parseInt(args.getOrDefault("groups", "64"));
        int per = Integer.parseInt(args.getOrDefault("per", "1000"));
        int keys = Integer.parseInt(args.getOrDefault("keys", "2"));
        double seconds = Double.parseDouble(args.getOrDefault("seconds", "5"));
        boolean fsync = Boolean.parseBoolean(args.getOrDefault("fsync", "true"));

        List<Row> rows = new ArrayList<>();
        for (int h : hots) {
            for (int w : writers) {
                Row r = run(args, w, h, groups, per, keys, seconds, fsync);
                rows.add(r);
                System.out.printf("writers=%2d hot=%2d  %7.1f commits/s  abort %5.1f%%  rebase/commit %.2f  p50 %6.1f ms  p99 %7.1f ms  drift %d%n",
                        r.writers, r.hot, r.commitsPerSec, 100 * r.abortRate, r.rebasesPerCommit, r.p50ms, r.p99ms, r.drift);
            }
        }

        StringBuilder md = new StringBuilder();
        md.append("# OCC contention benchmark\n\n");
        md.append(String.format("%s. %d file groups x %d records, %d keys per txn, %.0f s per cell, fsync=%s.%n%n",
                BenchUtil.machine(), groups, per, keys, seconds, fsync));
        md.append("| hot groups | writers | commits/s | abort rate | rebases/commit | txn p50 ms | txn p99 ms | attempts p99 | lost updates |\n");
        md.append("|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        StringBuilder csv = new StringBuilder("hot,writers,commits,commits_per_sec,aborts,abort_rate,rebases_per_commit,p50_ms,p99_ms,attempts_p99,drift\n");
        for (Row r : rows) {
            md.append(String.format("| %d | %d | %.1f | %.1f%% | %.2f | %.1f | %.1f | %.0f | %d |%n", r.hot, r.writers,
                    r.commitsPerSec, 100 * r.abortRate, r.rebasesPerCommit, r.p50ms, r.p99ms, r.attemptsP99, r.drift));
            csv.append(String.format("%d,%d,%d,%.2f,%d,%.4f,%.3f,%.2f,%.2f,%.0f,%d%n", r.hot, r.writers, r.commits,
                    r.commitsPerSec, r.aborts, r.abortRate, r.rebasesPerCommit, r.p50ms, r.p99ms, r.attemptsP99, r.drift));
        }
        md.append("\nabort rate = aborted attempts / all attempts. A rebase is a lost putIfAbsent race against a "
                + "non-overlapping commit: no abort, just retry at the next version. txn latency includes retries.\n");
        BenchUtil.write(args, "contention.md", md.toString());
        BenchUtil.write(args, "contention.csv", csv.toString());
    }

    static Row run(Map<String, String> args, int writers, int hot, int groups, int per, int keys, double seconds,
                   boolean fsync) throws Exception {
        Path dir = BenchUtil.workDir(args, "contention-" + writers + "-" + hot);
        LocalStorage st = new LocalStorage(dir, CommitMode.LINK, fsync);
        TableConfig cfg = TableConfig.defaults().withMaxRecordsPerFile(per).withCheckpointInterval(100);
        Table seed = Table.create(st, cfg);
        for (int g = 0; g < groups; g++) {
            Txn tx = seed.begin();
            for (int i = 0; i < per; i++) tx.upsert(new Record((long) g * per + i, 0, 0, "seed"));
            tx.commit();
        }

        AtomicBoolean stop = new AtomicBoolean();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService ex = Executors.newFixedThreadPool(writers);
        List<Future<long[]>> fs = new ArrayList<>();
        List<long[]> latencies = Collections.synchronizedList(new ArrayList<>());
        List<long[]> attemptCounts = Collections.synchronizedList(new ArrayList<>());
        for (int w = 0; w < writers; w++) {
            fs.add(ex.submit(() -> {
                Table t = Table.open(st, cfg);
                SplittableRandom rnd = new SplittableRandom();
                long[] lat = new long[1 << 16], att = new long[1 << 16];
                int n = 0;
                long commits = 0, aborts = 0, increments = 0;
                start.await();
                while (!stop.get()) {
                    long[] ks = new long[keys];
                    for (int i = 0; i < keys; i++) ks[i] = (long) rnd.nextInt(hot) * per + rnd.nextInt(per);
                    ks = Arrays.stream(ks).distinct().toArray();
                    long t0 = System.nanoTime();
                    int attempts = 0;
                    while (true) {
                        attempts++;
                        Txn tx = t.begin();
                        Map<Long, Record> cur = tx.get(ks);
                        for (long k : ks) {
                            Record r = cur.get(k);
                            tx.upsert(new Record(k, r.seq() + 1, r.amount() + 1, r.payload()));
                        }
                        try {
                            tx.commit();
                            break;
                        } catch (ConflictException e) {
                            aborts++;
                            if (stop.get()) { attempts = -1; break; }
                        }
                    }
                    if (attempts < 0) break;
                    commits++;
                    increments += ks.length;
                    if (n < lat.length) {
                        lat[n] = System.nanoTime() - t0;
                        att[n++] = attempts;
                    }
                }
                latencies.add(Arrays.copyOf(lat, n));
                attemptCounts.add(Arrays.copyOf(att, n));
                return new long[]{commits, aborts, increments, t.metrics.rebases};
            }));
        }
        long t0 = System.nanoTime();
        start.countDown();
        Thread.sleep((long) (seconds * 1000));
        stop.set(true);
        long commits = 0, aborts = 0, increments = 0, rebases = 0;
        for (Future<long[]> f : fs) {
            long[] r = f.get();
            commits += r[0];
            aborts += r[1];
            increments += r[2];
            rebases += r[3];
        }
        double elapsed = (System.nanoTime() - t0) / 1e9;
        ex.shutdown();

        Table fin = Table.open(st, cfg);
        long sum = 0;
        for (Record r : fin.scan(fin.snapshot())) sum += r.amount();

        long[] all = latencies.stream().flatMapToLong(Arrays::stream).sorted().toArray();
        long[] att = attemptCounts.stream().flatMapToLong(Arrays::stream).sorted().toArray();
        BenchUtil.rmrf(dir);
        return new Row(writers, hot, commits, commits / elapsed, aborts, (double) aborts / Math.max(1, commits + aborts),
                (double) rebases / Math.max(1, commits), BenchUtil.pct(all, all.length, 50), BenchUtil.pct(all, all.length, 99),
                att.length == 0 ? 0 : att[(int) Math.min(att.length - 1, Math.ceil(0.99 * att.length) - 1)], sum - increments);
    }
}
