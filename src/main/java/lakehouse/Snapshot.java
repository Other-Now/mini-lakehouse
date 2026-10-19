package lakehouse;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * The table as of one timeline version: file group id -> current slice. Immutable, so a reader that holds a
 * Snapshot keeps seeing exactly that version however many commits land after it (snapshot isolation). The files
 * it names stay on disk until vacuum's retention window passes them.
 */
public final class Snapshot {
    private final long version;
    private final Map<Long, FileSlice> slices;

    private Snapshot(long version, Map<Long, FileSlice> slices) {
        this.version = version;
        this.slices = slices;
    }

    /** State right after the CREATE commit (version 0): no files. */
    static Snapshot empty() { return new Snapshot(0, Map.of()); }

    static Snapshot of(long version, Map<Long, FileSlice> slices) {
        return new Snapshot(version, Collections.unmodifiableMap(new HashMap<>(slices)));
    }

    Snapshot apply(long v, Commit c) {
        if (v != version + 1) throw new IllegalStateException("applying v" + v + " on top of v" + version);
        Map<Long, FileSlice> next = new HashMap<>(slices);
        for (long fg : c.removes()) next.remove(fg);
        for (FileSlice s : c.adds()) next.put(s.fileGroup(), s.atVersion(v));
        return new Snapshot(v, Collections.unmodifiableMap(next));
    }

    public long version() { return version; }
    public Collection<FileSlice> slices() { return slices.values(); }
    public FileSlice slice(long fileGroup) { return slices.get(fileGroup); }
    public int fileCount() { return slices.size(); }

    public long recordCount() {
        long n = 0;
        for (FileSlice s : slices.values()) n += s.count();
        return n;
    }
}
