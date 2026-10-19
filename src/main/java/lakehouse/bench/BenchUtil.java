package lakehouse.bench;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

final class BenchUtil {
    private BenchUtil() {}

    static Map<String, String> args(String[] a) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < a.length; i += 2) m.put(a[i].replaceFirst("^--", ""), a[i + 1]);
        return m;
    }

    static int[] ints(String csv) { return Arrays.stream(csv.split(",")).mapToInt(Integer::parseInt).toArray(); }

    static double pct(long[] sortedNanos, int n, double p) {
        if (n == 0) return Double.NaN;
        int i = (int) Math.min(n - 1, Math.max(0, Math.ceil(p / 100.0 * n) - 1));
        return sortedNanos[i] / 1e6;
    }

    static double median(double[] xs) {
        double[] c = xs.clone();
        Arrays.sort(c);
        return c.length % 2 == 1 ? c[c.length / 2] : (c[c.length / 2 - 1] + c[c.length / 2]) / 2;
    }

    static void rmrf(Path p) throws IOException {
        if (!Files.exists(p)) return;
        Files.walkFileTree(p, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                Files.delete(f);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static Path workDir(Map<String, String> args, String name) throws IOException {
        Path base = Paths.get(args.getOrDefault("work", System.getProperty("java.io.tmpdir") + "/mini-lakehouse"));
        Path d = base.resolve(name);
        rmrf(d);
        Files.createDirectories(d);
        return d;
    }

    static void write(Map<String, String> args, String file, String content) throws IOException {
        Path out = Paths.get(args.getOrDefault("out", "results")).resolve(file);
        Files.createDirectories(out.getParent());
        Files.writeString(out, content);
        System.out.println("wrote " + out);
    }

    static String machine() {
        return System.getProperty("os.name") + " " + System.getProperty("os.arch") + ", "
                + Runtime.getRuntime().availableProcessors() + " hw threads, Java " + System.getProperty("java.version");
    }
}
