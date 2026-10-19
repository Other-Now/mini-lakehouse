package lakehouse.index;

public final class Hashing {
    private Hashing() {}

    /** murmur3 fmix64: a full-avalanche bijection on 64 bits. */
    public static long mix64(long z) {
        z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
        z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return z ^ (z >>> 33);
    }
}
