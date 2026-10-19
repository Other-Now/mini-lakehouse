package lakehouse;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deterministic crash injection for the crash test. {@code -Dlakehouse.crash=<point>@<n>} halts the JVM
 * the n-th time execution reaches {@code <point>}: no shutdown hooks, no finally blocks, like kill -9.
 */
public final class Crash {
    public static final String AFTER_DATA_WRITE = "after-data-write";
    public static final String AFTER_INDEX_WRITE = "after-index-write";
    /** Inside a commit-file write, after half the bytes. Tears the commit in DIRECT mode, only a temp file in LINK. */
    public static final String TORN_COMMIT = "torn-commit";
    public static final String AFTER_COMMIT = "after-commit";
    public static final String AFTER_CHECKPOINT = "after-checkpoint";
    public static final String COMPACT_AFTER_WRITE = "compact-after-write";

    public static final List<String> POINTS = List.of(
            AFTER_DATA_WRITE, AFTER_INDEX_WRITE, TORN_COMMIT, AFTER_COMMIT, AFTER_CHECKPOINT, COMPACT_AFTER_WRITE);

    private static final String POINT;
    private static final int AT;
    private static final AtomicInteger HITS = new AtomicInteger();

    static {
        String spec = System.getProperty("lakehouse.crash", "");
        int at = spec.indexOf('@');
        POINT = at < 0 ? spec : spec.substring(0, at);
        AT = at < 0 ? 1 : Integer.parseInt(spec.substring(at + 1));
    }

    private Crash() {}

    public static boolean hit(String point) {
        return point.equals(POINT) && HITS.incrementAndGet() == AT;
    }

    public static void point(String point) {
        if (hit(point)) halt();
    }

    public static void halt() {
        Runtime.getRuntime().halt(137);
    }
}
