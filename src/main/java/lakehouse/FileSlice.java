package lakehouse;

/**
 * One version of a file group's base file. A file group is a stable id for a set of keys; every
 * copy-on-write rewrite of it produces a new slice (new path), and the timeline says which slice is current.
 * {@code version} is the timeline version that made this slice current (-1 before it is committed).
 */
public record FileSlice(long fileGroup, String path, long count, long minKey, long maxKey, long version) {
    FileSlice atVersion(long v) { return new FileSlice(fileGroup, path, count, minKey, maxKey, v); }
}
