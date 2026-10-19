package lakehouse;

import lakehouse.storage.LocalStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MaintenanceTest {

    @Test
    void compactionMergesSmallFilesAndKeepsIndexExact(@TempDir Path dir) throws Exception {
        LocalStorage st = TestUtil.storage(dir);
        TableConfig cfg = TableConfig.defaults();
        Table t = TestUtil.seeded(st, cfg, 40, 25, 3);
        long before = TestUtil.sum(t, t.snapshot());
        Maintenance.CompactionResult r = Maintenance.compact(t, 100, 400);
        assertEquals(40, r.filesBefore());
        assertEquals(3, r.filesAfter());      // 1000 records / 400
        assertEquals(before, TestUtil.sum(t, t.snapshot()));
        assertTrue(TableVerifier.verify(st, cfg).ok());

        Txn tx = t.begin();                   // upserts after compaction land in the new groups
        tx.upsert(new Record(10, 0, 1, "x"));
        tx.commit();
        assertEquals(1, t.begin().get(10).get(10L).amount());
        assertEquals(3, t.snapshot().fileCount());
        assertTrue(TableVerifier.verify(st, cfg).ok());
    }

    @Test
    void vacuumRemovesOrphansAndExpiredButNotLiveFiles(@TempDir Path dir) throws Exception {
        LocalStorage st = TestUtil.storage(dir);
        TableConfig cfg = TableConfig.defaults();
        Table t = TestUtil.seeded(st, cfg, 4, 50, 1);
        Files.writeString(dir.resolve("data/deadbeef_orphan.parquet"), "crashed writer");
        Files.writeString(dir.resolve("timeline/00000000000000000099.commit.tmp-1234"), "half");
        for (int i = 0; i < 3; i++) {             // three rewrites of group 0 -> three expired slices
            Txn tx = t.begin();
            tx.upsert(new Record(1, 0, i, "u"));
            tx.commit();
        }
        Snapshot pinned = t.snapshot();
        Maintenance.VacuumResult r = Maintenance.vacuum(t, 0, 0);
        assertEquals(1, r.orphansDeleted());
        assertEquals(1, r.tempsDeleted());
        assertEquals(3, r.expiredDeleted());
        assertEquals(200, t.scan(pinned).size());
        assertTrue(TableVerifier.verify(st, cfg).ok());
        assertEquals(0, Maintenance.vacuum(t, 0, 0).expiredDeleted());
    }

    @Test
    void graceAndRetentionProtectInFlightAndPinned(@TempDir Path dir) throws Exception {
        LocalStorage st = TestUtil.storage(dir);
        TableConfig cfg = TableConfig.defaults();
        Table t = TestUtil.seeded(st, cfg, 2, 50, 1);
        Snapshot old = t.snapshot();
        Txn tx = t.begin();
        tx.upsert(new Record(1, 0, 9, "u"));
        tx.commit();
        Files.writeString(dir.resolve("data/inflight_x.parquet"), "being written");
        Maintenance.VacuumResult r = Maintenance.vacuum(t, 5, 60_000);
        assertEquals(0, r.orphansDeleted() + r.expiredDeleted());
        assertEquals(100, t.scan(old).size());
        Map<Long, Record> got = t.begin().get(1);
        assertEquals(9, got.get(1L).amount());
    }
}
