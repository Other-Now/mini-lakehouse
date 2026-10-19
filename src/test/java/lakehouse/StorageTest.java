package lakehouse;

import lakehouse.storage.CommitMode;
import lakehouse.storage.LocalStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.io.IOException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class StorageTest {

    /**
     * Many threads race for each slot; returns how many slots had more than one "winner".
     * An IOException from putIfAbsent counts as a loss (Windows refuses a rename onto a file another thread is
     * renaming onto at that instant), but must not kill the thread, or the others wait at the barrier forever.
     */
    static int race(lakehouse.storage.Storage st, int threads, int slots) throws Exception {
        AtomicInteger[] wins = new AtomicInteger[slots];
        for (int i = 0; i < slots; i++) wins[i] = new AtomicInteger();
        AtomicInteger ioErrors = new AtomicInteger();
        CyclicBarrier barrier = new CyclicBarrier(threads);
        Thread[] ts = new Thread[threads];
        Exception[] err = new Exception[1];
        for (int t = 0; t < threads; t++) {
            int id = t;
            ts[t] = new Thread(() -> {
                try {
                    for (int s = 0; s < slots; s++) {
                        barrier.await(30, TimeUnit.SECONDS);
                        try {
                            if (st.putIfAbsent("slot/" + s, ("writer-" + id).getBytes(StandardCharsets.UTF_8))) {
                                wins[s].incrementAndGet();
                            }
                        } catch (IOException e) {
                            ioErrors.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    err[0] = e;
                }
            });
            ts[t].start();
        }
        for (Thread t : ts) t.join();
        if (err[0] != null) throw err[0];
        if (ioErrors.get() > 0) System.out.println("putIfAbsent IOExceptions: " + ioErrors.get());
        int multi = 0;
        for (int s = 0; s < slots; s++) {
            assertTrue(wins[s].get() >= 1, "every slot has a winner");
            if (wins[s].get() > 1) multi++;
        }
        return multi;
    }

    @Test
    void linkIsExclusive(@TempDir Path dir) throws Exception {
        assertEquals(0, race(TestUtil.storage(dir, CommitMode.LINK), 8, 300));
    }

    @Test
    void directIsExclusive(@TempDir Path dir) throws Exception {
        assertEquals(0, race(TestUtil.storage(dir, CommitMode.DIRECT), 8, 300));
    }

    @Test
    void renameLetsTwoWritersWin(@TempDir Path dir) throws Exception {
        int multi = race(TestUtil.storage(dir, CommitMode.RENAME), 8, 300);
        System.out.println("RENAME: slots with >1 winner = " + multi + "/300");
        assertTrue(multi > 0, "check-then-rename should lose races under contention");
    }
}
