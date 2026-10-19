package lakehouse;

import org.apache.parquet.hadoop.metadata.CompressionCodecName;

/**
 * @param index              how upserts find which file group already holds a key
 * @param conflict           what optimistic concurrency control checks before letting a commit through
 * @param maxRecordsPerFile  inserts are cut into new file groups of at most this many records
 * @param checkpointInterval write a checkpoint every N versions (0 = never)
 */
public record TableConfig(IndexType index, ConflictCheck conflict, int maxRecordsPerFile, int checkpointInterval,
                          double bloomFpp, CompressionCodecName codec) {

    public enum IndexType {
        /** Record-level index: key -> file group hash map, persisted as per-commit deltas + checkpoints. */
        HASH,
        /** Per-file min/max key range + bloom filter in the Parquet footer; candidates confirmed by reading keys. */
        BLOOM,
        /** No index: read the key column of every file. The baseline. */
        SCAN
    }

    public enum ConflictCheck {
        /** File-group overlap, plus "two commits inserted the same new key". The correct setting. */
        FULL,
        /** File-group overlap only. Control: concurrent inserts of one new key both commit (duplicate keys). */
        FILE_GROUP,
        /** No check: last writer wins. Control: concurrent updates to one file group lose updates. */
        NONE
    }

    public static TableConfig defaults() {
        return new TableConfig(IndexType.HASH, ConflictCheck.FULL, 10_000, 50, 0.01, CompressionCodecName.SNAPPY);
    }

    public TableConfig withIndex(IndexType i) { return new TableConfig(i, conflict, maxRecordsPerFile, checkpointInterval, bloomFpp, codec); }
    public TableConfig withConflict(ConflictCheck c) { return new TableConfig(index, c, maxRecordsPerFile, checkpointInterval, bloomFpp, codec); }
    public TableConfig withMaxRecordsPerFile(int n) { return new TableConfig(index, conflict, n, checkpointInterval, bloomFpp, codec); }
    public TableConfig withCheckpointInterval(int n) { return new TableConfig(index, conflict, maxRecordsPerFile, n, bloomFpp, codec); }
    public TableConfig withBloomFpp(double p) { return new TableConfig(index, conflict, maxRecordsPerFile, checkpointInterval, p, codec); }
}
