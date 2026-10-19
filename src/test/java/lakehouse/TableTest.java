package lakehouse;

import lakehouse.storage.LocalStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class TableTest {

    @ParameterizedTest
    @EnumSource(TableConfig.IndexType.class)
    void upsertsUpdateInPlaceAndInsertNew(TableConfig.IndexType idx, @TempDir Path dir) throws Exception {
        LocalStorage st = TestUtil.storage(dir);
        Table t = TestUtil.seeded(st, TableConfig.defaults().withIndex(idx), 4, 100, 10);
        assertEquals(400, t.snapshot().recordCount());

        Txn tx = t.begin();
        tx.upsert(new Record(5, 1, 99, "updated"));      // group 0
        tx.upsert(new Record(305, 1, 99, "updated"));    // group 3
        tx.upsert(new Record(10_000, 1, 7, "new"));      // insert
        long v = tx.commit();

        Snapshot s = t.snapshot();
        assertEquals(v, s.version());
        assertEquals(401, s.recordCount());
        assertEquals(5, s.fileCount());
        Map<Long, Record> got = t.begin().get(5, 305, 10_000, 77, 123_456);
        assertEquals(99, got.get(5L).amount());
        assertEquals(99, got.get(305L).amount());
        assertEquals(7, got.get(10_000L).amount());
        assertEquals(10, got.get(77L).amount());
        assertFalse(got.containsKey(123_456L));
        assertTrue(TableVerifier.verify(st, t.config()).ok());
    }

    @Test
    void coldOpenFromCheckpointMatchesReplay(@TempDir Path dir) throws Exception {
        LocalStorage st = TestUtil.storage(dir);
        TableConfig cfg = TableConfig.defaults().withCheckpointInterval(5);
        Table t = TestUtil.seeded(st, cfg, 12, 50, 1);
        for (int i = 0; i < 7; i++) {
            Txn tx = t.begin();
            tx.upsert(new Record(i * 50 + 3, 0, 1000 + i, "u"));
            tx.commit();
        }
        long v = t.snapshot().version();
        assertTrue(st.exists(Table.checkpointPath(15)));
        Table cold = Table.open(st, cfg);
        assertEquals(v, cold.snapshot().version());
        assertEquals(new HashSet<>(t.snapshot().slices()), new HashSet<>(cold.snapshot().slices()));
        assertEquals(TestUtil.sum(t, t.snapshot()), TestUtil.sum(cold, cold.snapshot()));

        st.put(Table.checkpointPath(15), "garbage".getBytes());   // corrupt checkpoint -> replay from 0
        Table replayed = Table.open(st, cfg);
        assertEquals(new HashSet<>(t.snapshot().slices()), new HashSet<>(replayed.snapshot().slices()));
        assertTrue(TableVerifier.verify(st, cfg).ok());
    }

    @Test
    void pinnedSnapshotIsUnaffectedByLaterCommitsAndTimeTravelWorks(@TempDir Path dir) throws Exception {
        LocalStorage st = TestUtil.storage(dir);
        Table t = TestUtil.seeded(st, TableConfig.defaults(), 2, 100, 1);
        Snapshot pinned = t.snapshot();
        long before = TestUtil.sum(t, pinned);
        for (int i = 0; i < 5; i++) {
            Txn tx = t.begin();
            tx.upsert(new Record(i, 0, 500, "u"));
            tx.upsert(new Record(10_000 + i, 0, 1, "n"));
            tx.commit();
        }
        assertEquals(before, TestUtil.sum(t, pinned));
        assertNotEquals(before, TestUtil.sum(t, t.snapshot()));
        assertEquals(new HashSet<>(pinned.slices()), new HashSet<>(t.snapshotAt(pinned.version()).slices()));
    }
}
