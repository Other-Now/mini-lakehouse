package lakehouse;

import lakehouse.storage.LocalStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class ConcurrencyTest {

    @Test
    void disjointFileGroupsBothCommitViaRebase(@TempDir Path dir) throws Exception {
        LocalStorage st = TestUtil.storage(dir);
        TableConfig cfg = TableConfig.defaults();
        TestUtil.seeded(st, cfg, 2, 100, 1);
        Table a = Table.open(st, cfg), b = Table.open(st, cfg);
        Txn ta = a.begin(), tb = b.begin();
        ta.upsert(new Record(1, 0, 50, "a"));     // group 0
        tb.upsert(new Record(150, 0, 50, "b"));   // group 1
        long va = ta.commit(), vb = tb.commit();
        assertEquals(va + 1, vb);
        assertEquals(1, b.metrics.rebases);
        Map<Long, Record> got = Table.open(st, cfg).begin().get(1, 150);
        assertEquals(50, got.get(1L).amount());
        assertEquals(50, got.get(150L).amount());
    }

    @Test
    void sameFileGroupSecondCommitAborts(@TempDir Path dir) throws Exception {
        LocalStorage st = TestUtil.storage(dir);
        TableConfig cfg = TableConfig.defaults();
        TestUtil.seeded(st, cfg, 2, 100, 1);
        Table a = Table.open(st, cfg), b = Table.open(st, cfg);
        Txn ta = a.begin(), tb = b.begin();
        ta.upsert(new Record(1, 0, 50, "a"));
        tb.upsert(new Record(2, 0, 50, "b"));     // different key, same file group
        ta.commit();
        ConflictException e = assertThrows(ConflictException.class, tb::commit);
        assertTrue(e.getMessage().contains("file group"), e.getMessage());
        assertTrue(TableVerifier.verify(st, cfg).ok());
    }

    @Test
    void concurrentInsertOfSameNewKey(@TempDir Path dir) throws Exception {
        for (TableConfig.ConflictCheck check : TableConfig.ConflictCheck.values()) {
            LocalStorage st = TestUtil.storage(dir.resolve(check.name()));
            TableConfig cfg = TableConfig.defaults().withConflict(check);
            Table.create(st, cfg);
            Table a = Table.open(st, cfg), b = Table.open(st, cfg);
            Txn ta = a.begin(), tb = b.begin();
            ta.upsert(new Record(42, 0, 1, "a"));
            tb.upsert(new Record(42, 0, 1, "b"));
            ta.commit();
            if (check == TableConfig.ConflictCheck.FULL) {
                ConflictException e = assertThrows(ConflictException.class, tb::commit);
                assertTrue(e.getMessage().contains("key 42"), e.getMessage());
                assertEquals(0, TableVerifier.verify(st, cfg).duplicateKeys());
            } else {
                tb.commit();   // write sets are two different brand-new file groups: no overlap
                assertEquals(1, TableVerifier.verify(st, cfg).duplicateKeys(), check.name());
            }
        }
    }

    @Test
    void compactionAndWriterConflictOnTheSameFileGroup(@TempDir Path dir) throws Exception {
        LocalStorage st = TestUtil.storage(dir);
        TableConfig cfg = TableConfig.defaults();
        TestUtil.seeded(st, cfg, 6, 20, 1);
        Table w = Table.open(st, cfg), c = Table.open(st, cfg);
        Txn tc = c.begin();                       // compaction plans against v6...
        Txn tw = w.begin();
        tw.upsert(new Record(3, 0, 77, "w"));
        tw.commit();                              // ...a writer rewrites group 0 as v7
        assertThrows(ConflictException.class, () -> tc.compact(tc.snapshot().slices(), 1000));
        assertEquals(77, Table.open(st, cfg).begin().get(3).get(3L).amount());
        assertTrue(TableVerifier.verify(st, cfg).ok());
    }

    /**
     * Bank-transfer workload: writers move amounts between random keys (both legs in one commit), readers keep
     * taking snapshots and summing them. Every snapshot must sum to the initial total (atomic multi-file-group
     * commits + snapshot isolation), and so must the final table (no lost updates).
     */
    static long[] transfers(lakehouse.storage.Storage st, TableConfig cfg, int writers, int readers, long millis)
            throws Exception {
        int groups = 16, per = 50;
        TestUtil.seeded(st, cfg, groups, per, 1000);
        long total = (long) groups * per * 1000;
        AtomicBoolean stop = new AtomicBoolean();
        AtomicLong commits = new AtomicLong(), aborts = new AtomicLong(), scans = new AtomicLong(), badScans = new AtomicLong();
        ExecutorService ex = Executors.newFixedThreadPool(writers + readers);
        List<Future<?>> fs = new ArrayList<>();
        for (int w = 0; w < writers; w++) {
            fs.add(ex.submit(() -> {
                Table t = Table.open(st, cfg);
                SplittableRandom r = new SplittableRandom();
                while (!stop.get()) {
                    long from = r.nextInt(groups * per), to = r.nextInt(groups * per);
                    if (from == to) continue;
                    long amt = r.nextInt(1, 50);
                    Txn tx = t.begin();
                    Map<Long, Record> cur = tx.get(from, to);
                    Record f = cur.get(from), g = cur.get(to);
                    tx.upsert(new Record(from, f.seq() + 1, f.amount() - amt, f.payload()));
                    tx.upsert(new Record(to, g.seq() + 1, g.amount() + amt, g.payload()));
                    try {
                        tx.commit();
                        commits.incrementAndGet();
                    } catch (ConflictException e) {
                        aborts.incrementAndGet();
                    }
                }
                return null;
            }));
        }
        for (int i = 0; i < readers; i++) {
            fs.add(ex.submit(() -> {
                Table t = Table.open(st, cfg);
                while (!stop.get()) {
                    Snapshot s = t.refresh();
                    if (TestUtil.sum(t, s) != total) badScans.incrementAndGet();
                    scans.incrementAndGet();
                }
                return null;
            }));
        }
        Thread.sleep(millis);
        stop.set(true);
        for (Future<?> f : fs) f.get();
        ex.shutdown();
        Table fin = Table.open(st, cfg);
        long finalSum = TestUtil.sum(fin, fin.snapshot());
        return new long[]{commits.get(), aborts.get(), scans.get(), badScans.get(), finalSum - total};
    }

    @Test
    void transfersPreserveTotalUnderContention(@TempDir Path dir) throws Exception {
        long[] r = transfers(TestUtil.storage(dir), TableConfig.defaults(), 6, 2, 3000);
        System.out.printf("FULL: commits=%d aborts=%d scans=%d badScans=%d drift=%d%n", r[0], r[1], r[2], r[3], r[4]);
        assertTrue(r[0] > 20 && r[1] > 0, "needs real contention");
        assertTrue(r[2] > 5);
        assertEquals(0, r[3], "every snapshot must be consistent");
        assertEquals(0, r[4], "no lost updates");
    }

    @Test
    void withoutConflictDetectionUpdatesAreLost(@TempDir Path dir) throws Exception {
        long[] r = transfers(TestUtil.storage(dir), TableConfig.defaults().withConflict(TableConfig.ConflictCheck.NONE), 6, 0, 3000);
        System.out.printf("NONE: commits=%d aborts=%d drift=%d%n", r[0], r[1], r[4]);
        assertEquals(0, r[1]);
        assertNotEquals(0, r[4], "last-writer-wins should break conservation");
    }
}
