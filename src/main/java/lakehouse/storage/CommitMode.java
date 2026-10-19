package lakehouse.storage;

/** How {@link LocalStorage#putIfAbsent} is implemented. Only LINK is correct; the other two are controls. */
public enum CommitMode {
    /** Write a temp file, then hard-link it to the target. link() fails if the target exists: exclusive and atomic. */
    LINK,
    /**
     * Check the target does not exist, write a temp file, rename it over the target.
     * The content is atomic, but rename replaces an existing file (POSIX rename(2), and Java's ATOMIC_MOVE on
     * Windows), so two racing writers can both "win" and one commit silently disappears.
     */
    RENAME,
    /** Open the target with CREATE_NEW and write into it. Exclusive, but a crash mid-write leaves a torn commit. */
    DIRECT
}
