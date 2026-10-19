package lakehouse.index;

import lakehouse.Snapshot;

import java.io.IOException;

/** Answers the upsert question: which file group (if any) holds each of these keys in this snapshot? */
public interface KeyLocator {
    /** @return key -> file group id for keys that exist; absent keys are not in the map */
    LongLongMap locate(Snapshot snapshot, long[] keys, LookupStats stats) throws IOException;

    /** What a lookup cost, in the units that matter on object storage: files touched. */
    final class LookupStats {
        public long footersRead;       // Parquet footers opened (bloom + range metadata)
        public long filesKeyScanned;   // files whose key column was read
        public long keysScanned;       // keys read off those files
        public long candidatePairs;    // (key, file) pairs that passed range + bloom
        public long falsePositives;    // ...of which the file did not actually hold the key

        public void add(LookupStats o) {
            footersRead += o.footersRead;
            filesKeyScanned += o.filesKeyScanned;
            keysScanned += o.keysScanned;
            candidatePairs += o.candidatePairs;
            falsePositives += o.falsePositives;
        }
    }
}
