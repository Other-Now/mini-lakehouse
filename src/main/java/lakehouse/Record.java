package lakehouse;

/** One row. {@code key} is the record key that upserts match on and the index maps. */
public record Record(long key, long seq, long amount, String payload) {}
