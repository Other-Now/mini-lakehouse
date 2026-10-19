package lakehouse;

import lakehouse.index.BloomFilter;
import lakehouse.index.LongLongMap;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

class FormatTest {

    @Test
    void commitRoundTripsAndDetectsTornFiles() throws Exception {
        Commit c = new Commit("abc", Commit.Op.COMPACT, 41, 123, 7, "index/abc.idx",
                List.of(new FileSlice(0x7fff_0000_1234L, "data/x_abc.parquet", 10, -5, 99, -1)), List.of(3L, 0x7fffffffffffffffL));
        byte[] b = c.encode();
        assertEquals(c, Commit.decode(b, "t"));
        for (int cut = 0; cut < b.length; cut++) {
            byte[] torn = Arrays.copyOf(b, cut);
            assertThrows(CorruptTableException.class, () -> Commit.decode(torn, "t"), "prefix of length " + cut);
        }
        byte[] flipped = b.clone();
        flipped[20] ^= 1;
        assertThrows(CorruptTableException.class, () -> Commit.decode(flipped, "t"));
        assertTrue(new String(b, StandardCharsets.UTF_8).startsWith("LKC1\n"));
    }

    @Test
    void bloomFalsePositiveRateNearTarget() {
        SplittableRandom r = new SplittableRandom(1);
        int n = 100_000;
        BloomFilter bf = BloomFilter.create(n, 0.01);
        long[] in = new long[n];
        for (int i = 0; i < n; i++) bf.add(in[i] = r.nextLong());
        for (long k : in) assertTrue(bf.mightContain(k), "no false negatives");
        int fp = 0, probes = 200_000;
        for (int i = 0; i < probes; i++) if (bf.mightContain(r.nextLong())) fp++;
        double rate = (double) fp / probes;
        assertTrue(rate > 0.005 && rate < 0.015, "fp rate " + rate);
        BloomFilter back = BloomFilter.fromBase64(bf.toBase64());
        for (int i = 0; i < 1000; i++) assertTrue(back.mightContain(in[i]));
    }

    @Test
    void longLongMapAgainstReference() {
        SplittableRandom r = new SplittableRandom(2);
        LongLongMap m = new LongLongMap();
        java.util.HashMap<Long, Long> ref = new java.util.HashMap<>();
        for (int i = 0; i < 200_000; i++) {
            long k = r.nextInt(50_000) - 25_000, v = r.nextLong(1, Long.MAX_VALUE);
            m.put(k, v);
            ref.put(k, v);
        }
        assertEquals(ref.size(), m.size());
        ref.forEach((k, v) -> assertEquals(v, m.get(k)));
        assertEquals(LongLongMap.MISSING, m.get(10_000_000));
    }
}
