package lakehouse;

import lakehouse.index.KeyLocator;
import lakehouse.index.LongLongMap;
import lakehouse.io.DataFiles;

import java.io.IOException;
import java.util.*;

/**
 * A copy-on-write transaction pinned to one snapshot. Reads see that snapshot only. Writes are staged and, at
 * commit: (1) keys are located through the index, (2) every touched file group gets a new slice (old records
 * merged with the updates), (3) new keys go into new file groups, (4) an index delta is written, (5) the commit
 * is published with OCC.
 */
public final class Txn {
    private final Table table;
    private final Snapshot snap;
    private final String id = Long.toHexString(UUID.randomUUID().getMostSignificantBits());
    private final LinkedHashMap<Long, Record> staged = new LinkedHashMap<>();
    private final Map<String, List<Record>> fileCache = new HashMap<>();
    public final KeyLocator.LookupStats lookupStats = new KeyLocator.LookupStats();
    private boolean done;

    Txn(Table table, Snapshot snap) {
        this.table = table;
        this.snap = snap;
    }

    public Snapshot snapshot() { return snap; }

    public String id() { return id; }

    /** Point reads at the pinned snapshot. */
    public Map<Long, Record> get(long... keys) throws IOException {
        LongLongMap loc = table.locator().locate(snap, keys, lookupStats);
        Map<Long, Record> out = new HashMap<>();
        for (long k : keys) {
            long fg = loc.get(k);
            if (fg == LongLongMap.MISSING) continue;
            List<Record> rows = load(snap.slice(fg));
            int i = find(rows, k);
            if (i >= 0) out.put(k, rows.get(i));
        }
        return out;
    }

    public void upsert(Record r) { staged.put(r.key(), r); }

    public void upsert(Collection<Record> rs) { for (Record r : rs) upsert(r); }

    private List<Record> load(FileSlice s) throws IOException {
        List<Record> rows = fileCache.get(s.path());
        if (rows == null) {
            rows = DataFiles.readAll(table.storage, s.path());
            fileCache.put(s.path(), rows);
        }
        return rows;
    }

    private static int find(List<Record> sorted, long key) {
        int lo = 0, hi = sorted.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long k = sorted.get(mid).key();
            if (k < key) lo = mid + 1; else if (k > key) hi = mid - 1; else return mid;
        }
        return -1;
    }

    private static long newFileGroupId() {
        return UUID.randomUUID().getLeastSignificantBits() & Long.MAX_VALUE | 1;
    }

    private String dataPath(long fg) { return "data/" + Long.toHexString(fg) + "_" + id + ".parquet"; }

    /** @return the version this transaction committed as */
    public long commit() throws IOException {
        if (done) throw new IllegalStateException("transaction already finished");
        done = true;
        if (staged.isEmpty()) return snap.version();
        TableConfig cfg = table.cfg;

        long[] keys = new long[staged.size()];
        int n = 0;
        for (long k : staged.keySet()) keys[n++] = k;
        LongLongMap loc = table.locator().locate(snap, keys, lookupStats);

        Map<Long, List<Record>> updates = new HashMap<>();
        List<Record> inserts = new ArrayList<>();
        for (Record r : staged.values()) {
            long fg = loc.get(r.key());
            if (fg == LongLongMap.MISSING) inserts.add(r);
            else updates.computeIfAbsent(fg, x -> new ArrayList<>()).add(r);
        }

        List<FileSlice> adds = new ArrayList<>();
        List<String> written = new ArrayList<>();
        for (Map.Entry<Long, List<Record>> e : updates.entrySet()) {
            FileSlice old = snap.slice(e.getKey());
            List<Record> merged = new ArrayList<>(load(old));
            for (Record r : e.getValue()) {
                int i = find(merged, r.key());
                if (i < 0) throw new IllegalStateException("index says key " + r.key() + " is in " + old.path());
                merged.set(i, r);
            }
            String path = dataPath(old.fileGroup());
            written.add(path);
            adds.add(DataFiles.write(table.storage, path, old.fileGroup(), merged, cfg.bloomFpp(), cfg.codec()));
        }

        inserts.sort(Comparator.comparingLong(Record::key));
        long[] insKeys = new long[inserts.size()], insGroups = new long[inserts.size()];
        for (int from = 0; from < inserts.size(); from += cfg.maxRecordsPerFile()) {
            int to = Math.min(inserts.size(), from + cfg.maxRecordsPerFile());
            long fg = newFileGroupId();
            String path = dataPath(fg);
            written.add(path);
            adds.add(DataFiles.write(table.storage, path, fg, inserts.subList(from, to), cfg.bloomFpp(), cfg.codec()));
            for (int i = from; i < to; i++) {
                insKeys[i] = inserts.get(i).key();
                insGroups[i] = fg;
            }
        }
        Crash.point(Crash.AFTER_DATA_WRITE);

        String indexPath = null;
        if (inserts.size() > 0) {
            indexPath = "index/" + id + ".idx";
            new IndexDelta(insKeys, insGroups).write(table.storage, indexPath);
            written.add(indexPath);
        }
        Crash.point(Crash.AFTER_INDEX_WRITE);

        Commit c = new Commit(id, Commit.Op.UPSERT, snap.version(), System.currentTimeMillis(), inserts.size(),
                indexPath, adds, List.of());
        return table.publish(c, insKeys, written);
    }

    /**
     * Small-file compaction: rewrite {@code victims} (whole file groups) into new file groups of up to
     * {@code targetRecords} each, in one commit that drops the old groups. Keys move, so the commit carries an
     * index delta for every moved key. Conflicts like any other write: if a writer touched a victim first,
     * compaction aborts (and vice versa).
     */
    long compact(Collection<FileSlice> victims, int targetRecords) throws IOException {
        if (done) throw new IllegalStateException("transaction already finished");
        done = true;
        TableConfig cfg = table.cfg;
        List<Record> all = new ArrayList<>();
        List<Long> removes = new ArrayList<>();
        for (FileSlice s : victims) {
            all.addAll(load(s));
            removes.add(s.fileGroup());
        }
        all.sort(Comparator.comparingLong(Record::key));
        long[] keys = new long[all.size()], groups = new long[all.size()];
        List<FileSlice> adds = new ArrayList<>();
        List<String> written = new ArrayList<>();
        for (int from = 0; from < all.size(); from += targetRecords) {
            int to = Math.min(all.size(), from + targetRecords);
            long fg = newFileGroupId();
            String path = dataPath(fg);
            written.add(path);
            adds.add(DataFiles.write(table.storage, path, fg, all.subList(from, to), cfg.bloomFpp(), cfg.codec()));
            for (int i = from; i < to; i++) {
                keys[i] = all.get(i).key();
                groups[i] = fg;
            }
        }
        String indexPath = "index/" + id + ".idx";
        new IndexDelta(keys, groups).write(table.storage, indexPath);
        written.add(indexPath);
        Crash.point(Crash.COMPACT_AFTER_WRITE);
        Commit c = new Commit(id, Commit.Op.COMPACT, snap.version(), System.currentTimeMillis(), 0, indexPath,
                adds, removes);
        return table.publish(c, new long[0], written);
    }
}
