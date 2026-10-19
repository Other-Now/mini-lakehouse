package lakehouse.index;

import java.util.Arrays;

/**
 * Open-addressing, linear-probing long->long hash map: the in-memory form of the record-level index
 * (record key -> file group id). No boxing, no per-entry objects; one probe sequence per lookup.
 */
public final class LongLongMap {
    public static final long MISSING = Long.MIN_VALUE;   // reserved: cannot be used as a key or a value

    private long[] keys;
    private long[] vals;
    private int mask;
    private int size;

    public LongLongMap() { this(16); }

    public LongLongMap(int expected) {
        int cap = Integer.highestOneBit(Math.max(4, expected * 2 - 1)) << 1;
        alloc(cap);
    }

    private void alloc(int cap) {
        keys = new long[cap];
        vals = new long[cap];
        Arrays.fill(keys, MISSING);
        mask = cap - 1;
    }

    public int size() { return size; }

    public long get(long key) {
        int i = (int) Hashing.mix64(key) & mask;
        while (true) {
            long k = keys[i];
            if (k == key) return vals[i];
            if (k == MISSING) return MISSING;
            i = (i + 1) & mask;
        }
    }

    public void put(long key, long val) {
        if (key == MISSING) throw new IllegalArgumentException("reserved key");
        if ((size + 1) * 2 > keys.length) grow();
        int i = (int) Hashing.mix64(key) & mask;
        while (true) {
            long k = keys[i];
            if (k == key) { vals[i] = val; return; }
            if (k == MISSING) { keys[i] = key; vals[i] = val; size++; return; }
            i = (i + 1) & mask;
        }
    }

    private void grow() {
        long[] ok = keys, ov = vals;
        alloc(ok.length * 2);
        size = 0;
        for (int i = 0; i < ok.length; i++) if (ok[i] != MISSING) put(ok[i], ov[i]);
    }

    public interface Visitor { void visit(long key, long val); }

    public void forEach(Visitor v) {
        for (int i = 0; i < keys.length; i++) if (keys[i] != MISSING) v.visit(keys[i], vals[i]);
    }

    public long memoryBytes() { return (long) keys.length * 16; }
}
