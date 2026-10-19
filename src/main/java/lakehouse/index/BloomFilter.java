package lakehouse.index;

import java.nio.ByteBuffer;
import java.util.Base64;

/** Classic bloom filter over long keys, k probes by double hashing. Serialised into the Parquet footer. */
public final class BloomFilter {
    private final long[] bits;
    private final long m;
    private final int k;

    private BloomFilter(long[] bits, int k) {
        this.bits = bits;
        this.m = (long) bits.length * 64;
        this.k = k;
    }

    /** Sized for {@code n} keys at false-positive probability {@code fpp}. */
    public static BloomFilter create(int n, double fpp) {
        n = Math.max(n, 1);
        long m = (long) Math.ceil(-n * Math.log(fpp) / (Math.log(2) * Math.log(2)));
        int k = Math.max(1, (int) Math.round((double) m / n * Math.log(2)));
        return new BloomFilter(new long[(int) ((m + 63) / 64)], k);
    }

    public void add(long key) {
        long h1 = Hashing.mix64(key), h2 = Hashing.mix64(key ^ 0x9E3779B97F4A7C15L) | 1;
        for (int i = 0; i < k; i++) {
            long bit = Long.remainderUnsigned(h1 + i * h2, m);
            bits[(int) (bit >>> 6)] |= 1L << bit;
        }
    }

    public boolean mightContain(long key) {
        long h1 = Hashing.mix64(key), h2 = Hashing.mix64(key ^ 0x9E3779B97F4A7C15L) | 1;
        for (int i = 0; i < k; i++) {
            long bit = Long.remainderUnsigned(h1 + i * h2, m);
            if ((bits[(int) (bit >>> 6)] & (1L << bit)) == 0) return false;
        }
        return true;
    }

    public int sizeBytes() { return bits.length * 8; }

    public String toBase64() {
        ByteBuffer b = ByteBuffer.allocate(4 + bits.length * 8);
        b.putInt(k);
        for (long w : bits) b.putLong(w);
        return Base64.getEncoder().encodeToString(b.array());
    }

    public static BloomFilter fromBase64(String s) {
        ByteBuffer b = ByteBuffer.wrap(Base64.getDecoder().decode(s));
        int k = b.getInt();
        long[] bits = new long[b.remaining() / 8];
        for (int i = 0; i < bits.length; i++) bits[i] = b.getLong();
        return new BloomFilter(bits, k);
    }
}
