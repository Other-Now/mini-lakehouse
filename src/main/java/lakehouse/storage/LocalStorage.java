package lakehouse.storage;

import lakehouse.Crash;
import org.apache.parquet.io.InputFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;

public final class LocalStorage implements Storage {
    private final Path root;
    private final CommitMode mode;
    private final boolean fsync;

    public LocalStorage(Path root) throws IOException { this(root, CommitMode.LINK, true); }

    public LocalStorage(Path root, CommitMode mode, boolean fsync) throws IOException {
        this.root = root.toAbsolutePath();
        this.mode = mode;
        this.fsync = fsync;
        Files.createDirectories(this.root);
    }

    public Path root() { return root; }

    private Path p(String rel) { return root.resolve(rel); }

    private static Path tmpFor(Path target) {
        return target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID().toString().substring(0, 8));
    }

    @Override
    public boolean putIfAbsent(String path, byte[] data) throws IOException {
        Path target = p(path);
        Files.createDirectories(target.getParent());
        switch (mode) {
            case LINK -> {
                Path tmp = tmpFor(target);
                writeFile(tmp, data, true);
                try {
                    Files.createLink(target, tmp);
                    return true;
                } catch (FileAlreadyExistsException e) {
                    return false;
                } finally {
                    Files.deleteIfExists(tmp);
                }
            }
            case RENAME -> {
                if (Files.exists(target)) return false;          // check-then-act: the gap is the bug
                Path tmp = tmpFor(target);
                writeFile(tmp, data, true);
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);  // silently replaces a racing winner
                return true;
            }
            case DIRECT -> {
                try {
                    writeFile(target, data, true);
                    return true;
                } catch (FileAlreadyExistsException e) {
                    return false;
                }
            }
            default -> throw new AssertionError(mode);
        }
    }

    @Override
    public void put(String path, byte[] data) throws IOException {
        Path target = p(path);
        Files.createDirectories(target.getParent());
        Path tmp = tmpFor(target);
        writeFile(tmp, data, false);
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /** {@code commitFile} marks the writes that the "torn-commit" crash point may cut in half. */
    private void writeFile(Path f, byte[] data, boolean commitFile) throws IOException {
        try (FileChannel ch = FileChannel.open(f, CREATE_NEW, WRITE)) {
            if (commitFile && Crash.hit(Crash.TORN_COMMIT)) {
                ch.write(ByteBuffer.wrap(data, 0, data.length / 2));
                ch.force(true);
                Crash.halt();
            }
            ByteBuffer b = ByteBuffer.wrap(data);
            while (b.hasRemaining()) ch.write(b);
            if (fsync) ch.force(true);
        }
    }

    @Override
    public byte[] read(String path) throws IOException { return Files.readAllBytes(p(path)); }

    @Override
    public boolean exists(String path) { return Files.exists(p(path)); }

    @Override
    public List<FileEntry> list(String dir) throws IOException {
        Path d = p(dir);
        if (!Files.isDirectory(d)) return List.of();
        List<FileEntry> out = new ArrayList<>();
        String prefix = dir.endsWith("/") ? dir : dir + "/";
        try (Stream<Path> s = Files.list(d)) {
            s.forEach(f -> {
                try {
                    out.add(new FileEntry(prefix + f.getFileName(), Files.size(f),
                            Files.getLastModifiedTime(f).toMillis()));
                } catch (NoSuchFileException gone) {
                    // deleted between list and stat (a temp file, or one vacuum removed): skip it
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
        return out;
    }

    @Override
    public void delete(String path) throws IOException { Files.deleteIfExists(p(path)); }

    @Override
    public InputFile inputFile(String path) { return new NioInputFile(p(path)); }
}
