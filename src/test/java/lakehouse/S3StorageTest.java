package lakehouse;

import lakehouse.storage.S3Storage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs only against a real S3-compatible endpoint (CI starts MinIO):
 * LAKEHOUSE_S3_ENDPOINT, LAKEHOUSE_S3_ACCESS_KEY, LAKEHOUSE_S3_SECRET_KEY.
 */
@EnabledIfEnvironmentVariable(named = "LAKEHOUSE_S3_ENDPOINT", matches = ".+")
class S3StorageTest {

    static S3Storage storage() {
        S3Client c = S3Storage.client(System.getenv("LAKEHOUSE_S3_ENDPOINT"),
                System.getenv("LAKEHOUSE_S3_ACCESS_KEY"), System.getenv("LAKEHOUSE_S3_SECRET_KEY"));
        S3Storage st = new S3Storage(c, "lakehouse-test", "run-" + UUID.randomUUID());
        st.createBucketIfMissing();
        return st;
    }

    @Test
    void conditionalPutIsExclusive() throws Exception {
        S3Storage st = storage();
        assertTrue(st.putIfAbsent("x", new byte[]{1}));
        assertFalse(st.putIfAbsent("x", new byte[]{2}));
        assertArrayEquals(new byte[]{1}, st.read("x"));
        int multi = StorageTest.race(st, 8, 100);
        assertEquals(0, multi, "If-None-Match:* must admit exactly one writer per key");
    }

    @Test
    void tableOperationsAndInvariantsOnS3() throws Exception {
        S3Storage st = storage();
        TableConfig cfg = TableConfig.defaults().withCheckpointInterval(10);
        Table t = TestUtil.seeded(st, cfg, 4, 50, 1);
        Txn tx = t.begin();
        tx.upsert(new Record(3, 0, 42, "u"));
        tx.upsert(new Record(99_999, 0, 7, "n"));
        tx.commit();
        Map<Long, Record> got = Table.open(st, cfg).begin().get(3, 99_999);
        assertEquals(42, got.get(3L).amount());
        assertEquals(7, got.get(99_999L).amount());
        Maintenance.compact(t, 100, 1000);
        assertTrue(TableVerifier.verify(st, cfg).ok());
        Maintenance.vacuum(t, 0, 0);
        assertTrue(TableVerifier.verify(st, cfg).ok());
    }

    @Test
    void transfersPreserveTotalOnS3() throws Exception {
        long[] r = ConcurrencyTest.transfers(storage(), TableConfig.defaults(), 4, 1, 5000);
        System.out.printf("S3 FULL: commits=%d aborts=%d scans=%d badScans=%d drift=%d%n", r[0], r[1], r[2], r[3], r[4]);
        assertTrue(r[0] > 5);
        assertEquals(0, r[3]);
        assertEquals(0, r[4]);
    }
}
