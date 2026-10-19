package lakehouse;

import java.io.IOException;

/** A timeline file failed its checksum or did not parse. With CommitMode.LINK this should never happen. */
public final class CorruptTableException extends IOException {
    public CorruptTableException(String msg) { super(msg); }
}
