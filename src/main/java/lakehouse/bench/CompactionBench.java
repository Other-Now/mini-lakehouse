package lakehouse.bench;

import lakehouse.*;
import lakehouse.Record;
import lakehouse.index.BloomIndexLocator;
import lakehouse.index.KeyLocator;
import lakehouse.storage.CommitMode;
import lakehouse.storage.LocalStorage;

import java.nio.file.Path;
import java.util.*;

/**
 * The small-file problem: streaming ingestion commits often, each insert commit makes a new small file group, and
 * every reader and every bloom lookup pays per file. Measure scan and lookup before and after compaction.
 */
public final class CompactionBench {

    public static void main(String[] a) throws Exception {
        Map<String, String> args = BenchUtil.args(a);
        int commits = Integer.parseInt(args.getOrDefault("commits", "500"));
        int perCommit = Integer.parseInt(args.getOrDefault("per-commit", "200"));
        int target = Integer.parseInt(args.getOrDefault("target", "20000"));
        int reps = Integer.parseInt(args.getOrDefault("reps", "5"));

        Path dir = BenchUtil.workDir(args, "compaction");
        LocalStorage st = new LocalStorage(dir, CommitMode.LINK, false);
        TableConfig cfg = TableConfig.defaults().withCheckpointInterval(50);
        Table t = Table.create(st, cfg);
        SplittableRandom rnd = new SplittableRandom(7);
        long[] some = new long[1000];
        for (int c = 0; c < commits; c++) {
            Txn tx = t.begin();
            for (int i = 0; i < perCommit; i++) {
                long k = rnd.nextLong(1, Long.MAX_VALUE);
                if (c * perCommit + i < some.length) some[c * perCommit + i] = k;
                else if (rnd.nextInt(commits) == 0) some[rnd.nextInt(some.length)] = k;
                tx.upsert(new Record(k, 0, 1, "event-" + c));
            }
            tx.commit();
        }
        long[] lookup = Arrays.copyOf(some, 100);

        double[] before = measure(t, st, lookup, reps);
        long c0 = System.nanoTime();
        Maintenance.CompactionResult cr = Maintenance.compact(t, target / 4, target);
        double compactS = (System.nanoTime() - c0) / 1e9;
        double[] after = measure(t, st, lookup, reps);
        Maintenance.VacuumResult vr = Maintenance.vacuum(t, 0, 0);
        TableVerifier.Report rep = TableVerifier.verify(st, cfg);
        if (!rep.ok() || rep.records() != (long) commits * perCommit) throw new AssertionError(rep.toString());

        String md = "# Small-file compaction\n\n"
                + String.format("%s. %d insert commits x %d random keys = %,d records.%n%n", BenchUtil.machine(), commits, perCommit, commits * perCommit)
                + "| | files | full scan ms | 100-key bloom lookup ms (footers read) |\n|---|---:|---:|---:|\n"
                + String.format("| before | %d | %.1f | %.1f (%d) |%n", cr.filesBefore(), before[0], before[1], (long) before[2])
                + String.format("| after  | %d | %.1f | %.1f (%d) |%n", cr.filesAfter(), after[0], after[1], (long) after[2])
                + String.format("| speed-up | | %.1fx | %.1fx |%n%n", before[0] / after[0], before[1] / after[1])
                + String.format("Compaction: one commit replaced %d file groups in %.2f s. Vacuum then deleted %d expired files "
                + "(%.1f MB). Verifier after vacuum: %,d records, 0 duplicates, index exact.%n", cr.victims(), compactS,
                vr.expiredDeleted(), vr.bytesFreed() / 1e6, rep.records());
        System.out.print(md);
        BenchUtil.write(args, "compaction.md", md);
        BenchUtil.rmrf(dir);
    }

    /** {full scan ms, cold bloom lookup ms, footers read} medians. */
    static double[] measure(Table t, LocalStorage st, long[] keys, int reps) throws Exception {
        Snapshot s = t.refresh();
        double[] scan = new double[reps], look = new double[reps];
        long footers = 0;
        for (int r = -1; r < reps; r++) {
            long t0 = System.nanoTime();
            List<Record> rows = Table.scan(st, s);
            long t1 = System.nanoTime();
            KeyLocator.LookupStats stats = new KeyLocator.LookupStats();
            new BloomIndexLocator(st, null).locate(s, keys, stats);
            long t2 = System.nanoTime();
            if (rows.size() != s.recordCount()) throw new AssertionError();
            if (r >= 0) { scan[r] = (t1 - t0) / 1e6; look[r] = (t2 - t1) / 1e6; footers = stats.footersRead; }
        }
        return new double[]{BenchUtil.median(scan), BenchUtil.median(look), footers};
    }
}
