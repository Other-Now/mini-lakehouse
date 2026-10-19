package lakehouse;

import lakehouse.storage.Storage;

import java.io.IOException;
import java.util.*;

/** Table services: small-file compaction and vacuum (orphan + expired file cleanup). */
public final class Maintenance {
    private Maintenance() {}

    public record CompactionResult(int filesBefore, int filesAfter, int victims, long version) {}

    /**
     * Rewrite every file group smaller than {@code smallFileRecords} into groups of {@code targetRecords}.
     * One OCC transaction: it either replaces all victims or (on conflict) none of them.
     */
    public static CompactionResult compact(Table table, long smallFileRecords, int targetRecords) throws IOException {
        Txn txn = table.begin();
        Snapshot s = txn.snapshot();
        List<FileSlice> victims = new ArrayList<>();
        for (FileSlice f : s.slices()) if (f.count() < smallFileRecords) victims.add(f);
        if (victims.size() < 2) return new CompactionResult(s.fileCount(), s.fileCount(), 0, s.version());
        victims.sort(Comparator.comparingLong(FileSlice::minKey));
        long v = txn.compact(victims, targetRecords);
        return new CompactionResult(s.fileCount(), table.snapshot().fileCount(), victims.size(), v);
    }

    public record VacuumResult(int orphansDeleted, int expiredDeleted, int tempsDeleted, long bytesFreed) {}

    /**
     * Delete files no reader can need:
     * <ul>
     *   <li>orphans: data/index files no commit ever named (writers that crashed or aborted), and stray commit temp
     *       files; only once older than {@code graceMillis}, because an in-flight writer's files look exactly like
     *       orphans until it commits;</li>
     *   <li>expired slices: data files that stopped being current more than {@code retainVersions} versions ago, so
     *       snapshots pinned within that window keep working.</li>
     * </ul>
     * Index deltas of committed transactions are kept: cold-opening without a checkpoint replays them.
     */
    public static VacuumResult vacuum(Table table, int retainVersions, long graceMillis) throws IOException {
        Storage st = table.storage;
        long latest = table.latestVersion();
        long keepFrom = Math.max(0, latest - retainVersions);
        Set<String> everNamed = new HashSet<>(), live = new HashSet<>();
        Snapshot s = Snapshot.empty();
        for (long v = 1; v <= latest; v++) {
            Commit c = table.readCommit(v);
            s = s.apply(v, c);
            for (FileSlice a : c.adds()) everNamed.add(a.path());
            if (c.indexPath() != null) everNamed.add(c.indexPath());
            if (v == keepFrom) for (FileSlice f : s.slices()) live.add(f.path());
            if (v > keepFrom) for (FileSlice a : c.adds()) live.add(a.path());
        }
        long now = System.currentTimeMillis();
        int orphans = 0, expired = 0, temps = 0;
        long bytes = 0;
        for (Storage.FileEntry f : st.list("data")) {
            boolean old = now - f.modifiedMillis() >= graceMillis;
            if (!everNamed.contains(f.path())) {
                if (old) { st.delete(f.path()); orphans++; bytes += f.size(); }
            } else if (!live.contains(f.path())) {
                st.delete(f.path()); expired++; bytes += f.size();
            }
        }
        for (Storage.FileEntry f : st.list("index")) {
            if (!everNamed.contains(f.path()) && now - f.modifiedMillis() >= graceMillis) {
                st.delete(f.path()); orphans++; bytes += f.size();
            }
        }
        for (Storage.FileEntry f : st.list("timeline")) {
            if (f.path().contains(".tmp-") && now - f.modifiedMillis() >= graceMillis) {
                st.delete(f.path()); temps++; bytes += f.size();
            }
        }
        return new VacuumResult(orphans, expired, temps, bytes);
    }
}
