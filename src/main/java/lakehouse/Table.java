package lakehouse;

import lakehouse.index.*;
import lakehouse.io.DataFiles;
import lakehouse.storage.Storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.util.*;

/**
 * A handle on a table. Layout:
 * <pre>
 *   timeline/00000000000000000042.commit       one per version, created with putIfAbsent
 *   timeline/00000000000000000050.checkpoint   full state every N versions
 *   _last_checkpoint                           hint: newest checkpoint version
 *   data/&lt;fileGroup&gt;_&lt;txn&gt;.parquet               file slices
 *   index/&lt;txn&gt;.idx                             record-level index deltas
 * </pre>
 * The table is whatever the commits say. A data file is invisible until a commit names it, so a writer that dies
 * before its commit leaves only orphans, which vacuum removes.
 * <p>
 * A handle is used by one thread at a time. Concurrent writers each open their own handle (in-process or in
 * separate processes) and coordinate only through putIfAbsent on the next version number.
 */
public final class Table {
    static final String TIMELINE = "timeline/";
    static final String LAST_CHECKPOINT = "_last_checkpoint";

    final Storage storage;
    final TableConfig cfg;
    private Snapshot state;
    private LongLongMap index;                           // record key -> file group, at state.version(); null unless HASH
    private final Map<String, DataFiles.Footer> footers = new HashMap<>();
    private final KeyLocator locator;
    public final Metrics metrics = new Metrics();

    public static final class Metrics {
        public long commits, conflicts, rebases;
    }

    private Table(Storage storage, TableConfig cfg) {
        this.storage = storage;
        this.cfg = cfg;
        this.locator = switch (cfg.index()) {
            case HASH -> new HashIndexLocator(() -> index);
            case BLOOM -> new BloomIndexLocator(storage, footers);
            case SCAN -> new ScanLocator(storage);
        };
    }

    public static String commitPath(long v) { return TIMELINE + String.format("%020d", v) + ".commit"; }
    static String checkpointPath(long v) { return TIMELINE + String.format("%020d", v) + ".checkpoint"; }

    public static Table create(Storage storage, TableConfig cfg) throws IOException {
        if (!storage.putIfAbsent(commitPath(0), Commit.create().encode())) throw new IOException("table already exists");
        return open(storage, cfg);
    }

    public static Table open(Storage storage, TableConfig cfg) throws IOException {
        Table t = new Table(storage, cfg);
        t.loadCold();
        return t;
    }

    // ---------------------------------------------------------------- reading the timeline

    private void loadCold() throws IOException {
        state = null;
        long hint = readHint();
        if (hint > 0) {
            try {
                Checkpoint cp = Checkpoint.decode(storage.read(checkpointPath(hint)), checkpointPath(hint));
                if (cfg.index() != TableConfig.IndexType.HASH || cp.index != null) {
                    state = cp.snapshot;
                    index = cfg.index() == TableConfig.IndexType.HASH ? cp.index : null;
                }
            } catch (NoSuchFileException | CorruptTableException e) {
                state = null;   // fall back to replay from 0
            }
        }
        if (state == null) {
            readCommit(0);
            state = Snapshot.empty();
            index = cfg.index() == TableConfig.IndexType.HASH ? new LongLongMap() : null;
        }
        refresh();
    }

    private long readHint() throws IOException {
        try {
            return Long.parseLong(new String(storage.read(LAST_CHECKPOINT), StandardCharsets.UTF_8).trim());
        } catch (NoSuchFileException | NumberFormatException e) {
            return -1;
        }
    }

    Commit readCommit(long v) throws IOException {
        return Commit.decode(storage.read(commitPath(v)), commitPath(v));
    }

    private Commit tryReadCommit(long v) throws IOException {
        try {
            return readCommit(v);
        } catch (NoSuchFileException e) {
            return null;
        }
    }

    /** Advance this handle to the newest committed version. */
    public Snapshot refresh() throws IOException {
        for (Commit c; (c = tryReadCommit(state.version() + 1)) != null; ) apply(state.version() + 1, c);
        return state;
    }

    private void apply(long v, Commit c) throws IOException {
        state = state.apply(v, c);
        if (index != null && c.indexPath() != null) {
            IndexDelta d = IndexDelta.read(storage, c.indexPath());
            for (int i = 0; i < d.keys.length; i++) index.put(d.keys[i], d.fileGroups[i]);
        }
    }

    /** The version this handle is at, without touching storage. */
    public Snapshot snapshot() { return state; }

    /** An old version, rebuilt from the commits alone (time travel). Its files must not have been vacuumed. */
    public Snapshot snapshotAt(long v) throws IOException {
        Snapshot s = Snapshot.empty();
        for (long i = 1; i <= v; i++) s = s.apply(i, readCommit(i));
        return s;
    }

    public long latestVersion() throws IOException { return refresh().version(); }

    LongLongMap index() { return index; }

    /** The record-level hash index at this handle's version (null unless the table uses HASH). Do not mutate. */
    public LongLongMap recordIndex() { return index; }

    KeyLocator locator() { return locator; }

    // ---------------------------------------------------------------- reading data

    public static List<Record> scan(Storage storage, Snapshot s) throws IOException {
        List<Record> out = new ArrayList<>((int) s.recordCount());
        for (FileSlice f : s.slices()) out.addAll(DataFiles.readAll(storage, f.path()));
        return out;
    }

    public List<Record> scan(Snapshot s) throws IOException { return scan(storage, s); }

    public Storage storage() { return storage; }

    public TableConfig config() { return cfg; }

    // ---------------------------------------------------------------- writing

    /** Start a transaction on the newest version. */
    public Txn begin() throws IOException { return new Txn(this, refresh()); }

    public interface TxnBody<T> { T apply(Txn txn) throws IOException; }

    public record Result<T>(T value, long version, int attempts) {}

    /** Run {@code body} in a transaction, re-running it from a fresh snapshot each time OCC aborts it. */
    public <T> Result<T> run(TxnBody<T> body, int maxAttempts) throws IOException {
        for (int attempt = 1; ; attempt++) {
            Txn txn = begin();
            T value = body.apply(txn);
            try {
                return new Result<>(value, txn.commit(), attempt);
            } catch (ConflictException e) {
                if (attempt >= maxAttempts) throw e;
            }
        }
    }

    /**
     * Optimistic commit. Try to create version readVersion+1. If someone else already did, read their commit:
     * overlapping writes mean we abort, otherwise our commit is still valid on top of theirs and we try the next
     * version ("rebase"). The putIfAbsent that succeeds is the linearisation point.
     */
    long publish(Commit ours, long[] insertedKeysSorted, List<String> ownFiles) throws IOException {
        if (state.version() != ours.readVersion()) throw new IllegalStateException("handle moved during txn");
        byte[] bytes = ours.encode();
        long v = ours.readVersion() + 1;
        int rebases = 0;
        while (!storage.putIfAbsent(commitPath(v), bytes)) {
            Commit other = readCommit(v);
            String why = conflict(ours, insertedKeysSorted, other);
            apply(v, other);
            if (why != null) {
                for (String f : ownFiles) storage.delete(f);
                metrics.conflicts++;
                throw new ConflictException(v, why);
            }
            v++;
            rebases++;
        }
        Crash.point(Crash.AFTER_COMMIT);
        apply(v, ours);
        metrics.commits++;
        metrics.rebases += rebases;
        if (cfg.checkpointInterval() > 0 && v % cfg.checkpointInterval() == 0) writeCheckpoint(v);
        return v;
    }

    private String conflict(Commit ours, long[] ins, Commit other) throws IOException {
        if (cfg.conflict() == TableConfig.ConflictCheck.NONE || other.op() == Commit.Op.CREATE) return null;
        Set<Long> mine = ours.writeSet();
        for (long fg : other.writeSet()) {
            if (mine.contains(fg)) return "file group " + Long.toHexString(fg) + " also written by txn " + other.txn();
        }
        // File-group overlap misses one case: both commits insert the same *new* key, each into its own new file
        // group. Their write sets are disjoint, yet committing both would duplicate the key.
        if (cfg.conflict() == TableConfig.ConflictCheck.FULL && ins.length > 0 && other.inserted() > 0) {
            long[] theirs = IndexDelta.read(storage, other.indexPath()).keys;
            for (int i = 0, j = 0; i < ins.length && j < theirs.length; ) {
                if (ins[i] == theirs[j]) return "key " + ins[i] + " also inserted by txn " + other.txn();
                if (ins[i] < theirs[j]) i++; else j++;
            }
        }
        return null;
    }

    private void writeCheckpoint(long v) throws IOException {
        storage.putIfAbsent(checkpointPath(v), new Checkpoint(state, index).encode());
        Crash.point(Crash.AFTER_CHECKPOINT);
        storage.put(LAST_CHECKPOINT, Long.toString(v).getBytes(StandardCharsets.UTF_8));
    }
}
