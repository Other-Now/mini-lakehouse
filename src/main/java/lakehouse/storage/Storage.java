package lakehouse.storage;

import org.apache.parquet.io.InputFile;

import java.io.IOException;
import java.util.List;

/**
 * The only thing the table format asks of a filesystem or object store.
 * Paths are relative and '/'-separated. Exactly one operation has to be atomic and exclusive:
 * {@link #putIfAbsent}. The whole commit protocol is built on it.
 */
public interface Storage {

    /** Create {@code path} holding {@code data} iff it does not exist, atomically. False if it already existed. */
    boolean putIfAbsent(String path, byte[] data) throws IOException;

    /** Write or replace {@code path}. Readers see the old or the new content, never a torn one. */
    void put(String path, byte[] data) throws IOException;

    /** @throws java.nio.file.NoSuchFileException if absent */
    byte[] read(String path) throws IOException;

    boolean exists(String path) throws IOException;

    /** Non-recursive listing of {@code dir}; empty if the directory does not exist. */
    List<FileEntry> list(String dir) throws IOException;

    void delete(String path) throws IOException;

    InputFile inputFile(String path) throws IOException;

    record FileEntry(String path, long size, long modifiedMillis) {}
}
