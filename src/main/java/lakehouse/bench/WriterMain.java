package lakehouse.bench;

import lakehouse.*;
import lakehouse.Record;
import lakehouse.storage.CommitMode;
import lakehouse.storage.LocalStorage;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * One writer process for the crash test. Mixed workload until the deadline (or until it is killed):
 * 80% transfers between two of the seed keys (sum-preserving, two file groups), 15% insert batches of new keys
 * with amount 0 (payload "b:&lt;base&gt;:&lt;n&gt;" so the verifier can check batch atomicity), 5% compaction.
 * With --insert-space N the batches are drawn from N shared bases, so concurrent writers insert the same new keys.
 * After each commit returns, the txn id is appended to an ack file: an acknowledged commit must survive.
 */
public final class WriterMain {
    public static void main(String[] a) throws Exception {
        Map<String, String> args = BenchUtil.args(a);
        LocalStorage st = new LocalStorage(Paths.get(args.get("dir")), CommitMode.valueOf(args.get("mode")), true);
        TableConfig cfg = CrashTest.config(TableConfig.ConflictCheck.valueOf(args.get("conflict")));
        int seedKeys = Integer.parseInt(args.get("seed-keys"));
        int insertSpace = Integer.parseInt(args.getOrDefault("insert-space", "0"));
        long insertClockMs = Long.parseLong(args.getOrDefault("insert-clock-ms", "0"));
        long deadline = System.currentTimeMillis() + Long.parseLong(args.get("millis"));
        SplittableRandom rnd = new SplittableRandom();
        int commits = 0, aborts = 0;
        try (FileOutputStream ack = new FileOutputStream(args.get("ack"), true)) {
            Table t = Table.open(st, cfg);
            while (System.currentTimeMillis() < deadline) {
                int dice = rnd.nextInt(100);
                try {
                    String txn;
                    if (dice < 80) {
                        long from = rnd.nextInt(seedKeys), to = rnd.nextInt(seedKeys);
                        if (from == to) continue;
                        long amt = rnd.nextInt(1, 100);
                        Txn tx = t.begin();
                        Map<Long, Record> cur = tx.get(from, to);
                        Record f = cur.get(from), g = cur.get(to);
                        tx.upsert(new Record(from, f.seq() + 1, f.amount() - amt, f.payload()));
                        tx.upsert(new Record(to, g.seq() + 1, g.amount() + amt, g.payload()));
                        tx.commit();
                        txn = tx.id();
                    } else if (dice < 95) {
                        // insert-space > 0: batches come from a small shared pool, so writers race to insert the
                        // same new keys. A batch's size and payload depend only on its base: re-inserting is idempotent.
                        // insert-clock-ms > 0: every writer derives the base from the current time slot, so all of them
                        // race to insert the same brand-new keys, continuously.
                        boolean shared = insertSpace > 0 || insertClockMs > 0;
                        long base = insertClockMs > 0 ? (1L << 41) + ((System.currentTimeMillis() / insertClockMs) << 10)
                                : insertSpace > 0 ? (1L << 40) + ((long) rnd.nextInt(insertSpace) << 10)
                                : rnd.nextLong(1L << 20, 1L << 52) << 10;
                        int n = shared ? 1 + (int) ((base >>> 10) % 300) : rnd.nextInt(1, 300);
                        Txn tx = t.begin();
                        for (int i = 0; i < n; i++) tx.upsert(new Record(base + i, 0, 0, "b:" + base + ":" + n));
                        tx.commit();
                        txn = tx.id();
                    } else {
                        Maintenance.CompactionResult r = Maintenance.compact(t, 200, 2000);
                        if (r.victims() == 0) continue;
                        txn = "compaction@" + r.version();
                    }
                    commits++;
                    ack.write((txn + "\n").getBytes(StandardCharsets.UTF_8));
                    ack.flush();
                } catch (ConflictException e) {
                    aborts++;
                }
            }
        } catch (IOException e) {
            System.out.println("WRITER-ERROR " + e);
            System.exit(3);
        }
        System.out.println("DONE commits=" + commits + " aborts=" + aborts);
    }
}
