package lakehouse.bench;

import lakehouse.*;
import lakehouse.Record;
import lakehouse.index.*;
import lakehouse.io.DataFiles;
import lakehouse.storage.CommitMode;
import lakehouse.storage.LocalStorage;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The upsert-path question "which file group holds key k?", answered three ways over the same table:
 * full scan of every file's key column, bloom index (range + footer bloom + confirm), and the record-level hash
 * index. Two key layouts:
 * <ul>
 *   <li>ORDERED: each commit inserts a contiguous key range (time-ordered keys), so file key ranges are disjoint
 *       and range pruning alone eliminates almost every file;</li>
 *   <li>RANDOM: each commit inserts uniformly random keys, so every file's [min, max] spans the whole key space,
 *       range pruning does nothing, and only the bloom filter prunes.</li>
 * </ul>
 * Each lookup batch is half keys that exist and half that do not (the insert case, which a bloom filter rejects
 * cheaply but a scan cannot).
 */
public final class IndexBench {

    public static void main(String[] a) throws Exception {
        Map<String, String> args = BenchUtil.args(a);
        int records = Integer.parseInt(args.getOrDefault("records", "1000000"));
        int perFile = Integer.parseInt(args.getOrDefault("per-file", "10000"));
        int[] batches = BenchUtil.ints(args.getOrDefault("batches", "1,100,10000"));
        int reps = Integer.parseInt(args.getOrDefault("reps", "7"));

        StringBuilder md = new StringBuilder("# Index lookup benchmark\n\n");
        md.append(String.format("%s. %,d records in %d files of %,d. Batches are 50%% existing / 50%% absent keys. "
                + "Median of %d reps after 2 warm-up reps.%n%n", BenchUtil.machine(), records, records / perFile, perFile, reps));
        StringBuilder csv = new StringBuilder("layout,method,batch,median_ms,us_per_key,footers_read,files_key_scanned,keys_scanned,candidates,false_positives\n");

        for (String layout : List.of("ORDERED", "RANDOM")) {
            Path dir = BenchUtil.workDir(args, "index-" + layout);
            LocalStorage st = new LocalStorage(dir, CommitMode.LINK, false);
            TableConfig cfg = TableConfig.defaults().withMaxRecordsPerFile(perFile).withCheckpointInterval(25);
            Table t = Table.create(st, cfg);
            SplittableRandom rnd = new SplittableRandom(42);
            long[] present = new long[records];
            Set<Long> presentSet = new HashSet<>(records * 2);
            long tBuild = System.nanoTime();
            for (int f = 0, n = 0; f < records / perFile; f++) {
                Txn tx = t.begin();
                for (int i = 0; i < perFile; i++, n++) {
                    long k;
                    if (layout.equals("ORDERED")) k = 2L * n;                 // even keys; odd keys are absent
                    else do { k = rnd.nextLong(1, Long.MAX_VALUE) & ~1L; } while (!presentSet.add(k));
                    present[n] = k;
                    tx.upsert(new Record(k, 0, 1, "p"));
                }
                tx.commit();
            }
            double buildS = (System.nanoTime() - tBuild) / 1e9;
            Snapshot s = t.snapshot();
            long bloomBytes = 0, dataBytes = 0;
            for (FileSlice f : s.slices()) bloomBytes += DataFiles.readFooter(st, f.path()).bloom().sizeBytes();
            for (var e : st.list("data")) dataBytes += e.size();

            long c0 = System.nanoTime();
            Table cold = Table.open(st, cfg);
            double coldOpenMs = (System.nanoTime() - c0) / 1e6;
            LongLongMap idx = cold.recordIndex();

            Map<String, KeyLocator> methods = new LinkedHashMap<>();
            methods.put("hash index", new HashIndexLocator(() -> idx));
            methods.put("bloom (footers cached)", new BloomIndexLocator(st, new ConcurrentHashMap<>()));
            methods.put("bloom (footers read per lookup)", new BloomIndexLocator(st, null));
            methods.put("full scan", new ScanLocator(st));

            md.append(String.format("## %s keys%n%n", layout));
            md.append(String.format("Build: %.1f s for %d commits. Data %.1f MB; bloom filters %.2f MB total (fpp %.2f); "
                    + "hash index %,d entries, %.1f MB in memory; cold open (checkpoint + tail replay) %.0f ms.%n%n",
                    buildS, s.version(), dataBytes / 1e6, bloomBytes / 1e6, cfg.bloomFpp(), idx.size(), idx.memoryBytes() / 1e6, coldOpenMs));
            md.append("| batch | method | median ms | µs/key | footers read | files key-scanned | keys scanned | false positives |\n");
            md.append("|---:|---|---:|---:|---:|---:|---:|---:|\n");

            for (int b : batches) {
                long[] keys = new long[b];
                for (int i = 0; i < b; i++) {
                    if (i % 2 == 0) keys[i] = present[rnd.nextInt(records)];
                    else if (layout.equals("ORDERED")) keys[i] = 2L * rnd.nextInt(records) + 1;
                    else { long k; do { k = rnd.nextLong(1, Long.MAX_VALUE) | 1L; } while (presentSet.contains(k)); keys[i] = k; }
                }
                long expectedHits = Arrays.stream(keys).filter(k -> (k & 1) == 0).distinct().count();
                LongLongMap reference = null;
                for (Map.Entry<String, KeyLocator> m : methods.entrySet()) {
                    int r = m.getKey().equals("full scan") && b >= 10_000 ? Math.max(3, reps / 2) : reps;
                    double[] ms = new double[r];
                    KeyLocator.LookupStats last = null;
                    LongLongMap out = null;
                    for (int rep = -2; rep < r; rep++) {
                        KeyLocator.LookupStats stats = new KeyLocator.LookupStats();
                        long t0 = System.nanoTime();
                        out = m.getValue().locate(s, keys, stats);
                        long el = System.nanoTime() - t0;
                        if (rep >= 0) { ms[rep] = el / 1e6; last = stats; }
                    }
                    if (reference == null) reference = out;
                    else if (!same(reference, out, keys)) throw new AssertionError(m.getKey() + " disagrees with hash index");
                    if (out.size() != expectedHits) throw new AssertionError("expected " + expectedHits + " hits, got " + out.size());
                    double med = BenchUtil.median(ms);
                    System.out.printf("%-8s batch=%-6d %-32s %10.3f ms  footers=%d scanned=%d fp=%d%n", layout, b, m.getKey(), med,
                            last.footersRead, last.filesKeyScanned, last.falsePositives);
                    md.append(String.format("| %,d | %s | %.3f | %.2f | %d | %d | %,d | %d |%n", b, m.getKey(), med, med * 1000 / b,
                            last.footersRead, last.filesKeyScanned, last.keysScanned, last.falsePositives));
                    csv.append(String.format("%s,%s,%d,%.4f,%.3f,%d,%d,%d,%d,%d%n", layout, m.getKey(), b, med, med * 1000 / b,
                            last.footersRead, last.filesKeyScanned, last.keysScanned, last.candidatePairs, last.falsePositives));
                }
            }
            md.append('\n');
            BenchUtil.rmrf(dir);
        }
        md.append("\"files key-scanned\" counts files whose key column had to be read: the expensive step on object "
                + "storage. Every method returned the identical key -> file group map (checked per batch).\n");
        BenchUtil.write(args, "index.md", md.toString());
        BenchUtil.write(args, "index.csv", csv.toString());
    }

    private static boolean same(LongLongMap x, LongLongMap y, long[] keys) {
        if (x.size() != y.size()) return false;
        for (long k : keys) if (x.get(k) != y.get(k)) return false;
        return true;
    }
}
