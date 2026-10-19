package lakehouse;

import lakehouse.storage.CommitMode;
import lakehouse.storage.LocalStorage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class TestUtil {
    private TestUtil() {}

    static LocalStorage storage(Path dir) throws IOException { return new LocalStorage(dir, CommitMode.LINK, false); }

    static LocalStorage storage(Path dir, CommitMode mode) throws IOException { return new LocalStorage(dir, mode, false); }

    static List<Record> rows(long fromKey, int n, long amount) {
        List<Record> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(new Record(fromKey + i, 0, amount, "r" + (fromKey + i)));
        return out;
    }

    /** Insert {@code groups} file groups of {@code perGroup} sequential keys each, one commit per group. */
    static Table seeded(lakehouse.storage.Storage st, TableConfig cfg, int groups, int perGroup, long amount) throws IOException {
        Table t = Table.create(st, cfg);
        for (int g = 0; g < groups; g++) {
            Txn tx = t.begin();
            tx.upsert(rows((long) g * perGroup, perGroup, amount));
            tx.commit();
        }
        return t;
    }

    static long sum(Table t, Snapshot s) throws IOException {
        long x = 0;
        for (Record r : t.scan(s)) x += r.amount();
        return x;
    }
}
