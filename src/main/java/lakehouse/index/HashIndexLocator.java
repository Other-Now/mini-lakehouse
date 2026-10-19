package lakehouse.index;

import lakehouse.Snapshot;

import java.util.function.Supplier;

/** Record-level index: one hash probe per key, zero files touched. The map must be at the snapshot's version. */
public final class HashIndexLocator implements KeyLocator {
    private final Supplier<LongLongMap> index;

    public HashIndexLocator(Supplier<LongLongMap> index) { this.index = index; }

    @Override
    public LongLongMap locate(Snapshot snapshot, long[] keys, LookupStats stats) {
        LongLongMap idx = index.get(), out = new LongLongMap(keys.length);
        for (long k : keys) {
            long fg = idx.get(k);
            if (fg != LongLongMap.MISSING) out.put(k, fg);
        }
        return out;
    }
}
