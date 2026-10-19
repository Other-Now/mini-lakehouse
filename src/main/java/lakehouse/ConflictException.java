package lakehouse;

import java.io.IOException;

/** Optimistic concurrency control lost: a commit that landed after our read version overlaps our writes. */
public final class ConflictException extends IOException {
    public final long version;

    public ConflictException(long version, String why) {
        super("conflict with v" + version + ": " + why);
        this.version = version;
    }
}
