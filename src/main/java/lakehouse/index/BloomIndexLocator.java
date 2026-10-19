package lakehouse.index;

import lakehouse.FileSlice;
import lakehouse.Snapshot;
import lakehouse.io.DataFiles;
import lakehouse.storage.Storage;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;

/**
 * Hudi's BLOOM index, in three steps:
 * 1. prune by the file's [min, max] key range;
 * 2. prune by the file's bloom filter;
 * 3. confirm the survivors by reading the file's key column, because bloom filters give false positives.
 * Steps 1 and 2 read only footers. With {@code footerCache} non-null, footers are read once and then kept.
 */
public final class BloomIndexLocator implements KeyLocator {
    private final Storage storage;
    private final Map<String, DataFiles.Footer> footerCache;

    public BloomIndexLocator(Storage storage, Map<String, DataFiles.Footer> footerCache) {
        this.storage = storage;
        this.footerCache = footerCache;
    }

    @Override
    public LongLongMap locate(Snapshot snapshot, long[] keys, LookupStats stats) throws IOException {
        LongLongMap out = new LongLongMap(keys.length);
        long[] cand = new long[keys.length];
        for (FileSlice s : snapshot.slices()) {
            int n = 0;
            DataFiles.Footer f = null;
            for (long k : keys) {
                if (k < s.minKey() || k > s.maxKey()) continue;   // range prune: slice metadata, free
                if (f == null) f = footer(s.path(), stats);
                if (f.bloom().mightContain(k)) cand[n++] = k;
            }
            if (n == 0) continue;
            stats.candidatePairs += n;
            long[] fileKeys = DataFiles.readKeys(storage, s.path());
            stats.filesKeyScanned++;
            stats.keysScanned += fileKeys.length;
            for (int i = 0; i < n; i++) {
                if (Arrays.binarySearch(fileKeys, cand[i]) >= 0) out.put(cand[i], s.fileGroup());
                else stats.falsePositives++;
            }
        }
        return out;
    }

    private DataFiles.Footer footer(String path, LookupStats stats) throws IOException {
        if (footerCache != null) {
            DataFiles.Footer f = footerCache.get(path);
            if (f != null) return f;
        }
        DataFiles.Footer f = DataFiles.readFooter(storage, path);
        stats.footersRead++;
        if (footerCache != null) footerCache.put(path, f);
        return f;
    }
}
