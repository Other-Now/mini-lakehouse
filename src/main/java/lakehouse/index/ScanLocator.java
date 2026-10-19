package lakehouse.index;

import lakehouse.FileSlice;
import lakehouse.Snapshot;
import lakehouse.io.DataFiles;
import lakehouse.storage.Storage;

import java.io.IOException;

/** No index: read every file's key column and match it against the lookup set. Cost is O(table), whatever the batch. */
public final class ScanLocator implements KeyLocator {
    private final Storage storage;

    public ScanLocator(Storage storage) { this.storage = storage; }

    @Override
    public LongLongMap locate(Snapshot snapshot, long[] keys, LookupStats stats) throws IOException {
        LongLongMap want = new LongLongMap(keys.length);
        for (long k : keys) want.put(k, 0);
        LongLongMap out = new LongLongMap(keys.length);
        for (FileSlice s : snapshot.slices()) {
            long[] fileKeys = DataFiles.readKeys(storage, s.path());
            stats.filesKeyScanned++;
            stats.keysScanned += fileKeys.length;
            for (long k : fileKeys) if (want.get(k) != LongLongMap.MISSING) out.put(k, s.fileGroup());
        }
        return out;
    }
}
