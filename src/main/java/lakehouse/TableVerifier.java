package lakehouse;

import lakehouse.index.LongLongMap;
import lakehouse.io.DataFiles;
import lakehouse.storage.Storage;

import java.io.IOException;
import java.util.*;

/**
 * Independent consistency check, used by tests and the crash harness. It opens the table cold, like a brand-new
 * reader, and checks the invariants a correct table must satisfy at its newest version.
 */
public final class TableVerifier {
    private TableVerifier() {}

    public record Report(long version, int files, long records, long sum, int duplicateKeys, int badSlices,
                         int indexMismatches, int partialBatches, String error) {
        public boolean ok() {
            return error == null && duplicateKeys == 0 && badSlices == 0 && indexMismatches == 0 && partialBatches == 0;
        }
    }

    /**
     * Checks: every commit parses, every referenced file reads and matches its slice metadata, no key appears
     * twice, the record-level index matches the data exactly, and every insert batch (payload "b:&lt;id&gt;:&lt;n&gt;")
     * is either fully present or absent. Returns the amount sum so callers can check conservation.
     */
    public static Report verify(Storage storage, TableConfig cfg) {
        try {
            Table t = Table.open(storage, cfg.withIndex(TableConfig.IndexType.HASH));
            Snapshot s = t.snapshot();
            LongLongMap seen = new LongLongMap((int) s.recordCount());
            Map<String, int[]> batches = new HashMap<>();
            long records = 0, sum = 0;
            int dups = 0, bad = 0;
            for (FileSlice f : s.slices()) {
                List<Record> rows = DataFiles.readAll(storage, f.path());
                if (rows.size() != f.count() || rows.isEmpty()
                        || rows.get(0).key() != f.minKey() || rows.get(rows.size() - 1).key() != f.maxKey()) bad++;
                for (Record r : rows) {
                    records++;
                    sum += r.amount();
                    if (seen.get(r.key()) != LongLongMap.MISSING) dups++;
                    seen.put(r.key(), f.fileGroup());
                    if (r.payload().startsWith("b:")) {
                        String[] p = r.payload().split(":");
                        batches.computeIfAbsent(p[1], x -> new int[]{Integer.parseInt(p[2]), 0})[1]++;
                    }
                }
            }
            int[] mismatches = {0};
            LongLongMap idx = t.index();
            if (idx.size() != seen.size()) mismatches[0] += Math.abs(idx.size() - seen.size());
            seen.forEach((k, fg) -> { if (idx.get(k) != fg) mismatches[0]++; });
            int partial = 0;
            for (int[] b : batches.values()) if (b[0] != b[1]) partial++;
            return new Report(s.version(), s.fileCount(), records, sum, dups, bad, mismatches[0], partial, null);
        } catch (Exception e) {
            return new Report(-1, 0, 0, 0, 0, 0, 0, 0, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * Every version in [from, to] must be a readable snapshot whose amounts sum to {@code expectedSum}.
     * Returns the first bad version, or -1. Files are read once and reused across versions.
     */
    public static long firstInconsistentVersion(Storage storage, TableConfig cfg, long from, long to, long expectedSum)
            throws IOException {
        Table t = Table.open(storage, cfg.withIndex(TableConfig.IndexType.SCAN));
        Map<String, Long> fileSums = new HashMap<>();
        Snapshot s = t.snapshotAt(from);
        for (long v = from; v <= to; v++) {
            if (v > from) s = s.apply(v, t.readCommit(v));
            long sum = 0;
            for (FileSlice f : s.slices()) {
                Long fs = fileSums.get(f.path());
                if (fs == null) {
                    long x = 0;
                    for (Record r : DataFiles.readAll(storage, f.path())) x += r.amount();
                    fileSums.put(f.path(), fs = x);
                }
                sum += fs;
            }
            if (sum != expectedSum) return v;
        }
        return -1;
    }
}
