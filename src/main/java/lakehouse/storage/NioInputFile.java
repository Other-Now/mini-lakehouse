package lakehouse.storage;

import org.apache.parquet.io.InputFile;
import org.apache.parquet.io.SeekableInputStream;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * parquet's LocalInputFile uses RandomAccessFile, which on Windows opens without FILE_SHARE_DELETE,
 * so vacuum could not delete a file while any reader had it open. NIO channels allow the delete.
 */
final class NioInputFile implements InputFile {
    private final Path path;

    NioInputFile(Path path) { this.path = path; }

    @Override
    public long getLength() throws IOException { return Files.size(path); }

    @Override
    public SeekableInputStream newStream() throws IOException {
        FileChannel ch = FileChannel.open(path, StandardOpenOption.READ);
        return new SeekableInputStream() {
            @Override public long getPos() throws IOException { return ch.position(); }
            @Override public void seek(long n) throws IOException { ch.position(n); }
            @Override public int read() throws IOException {
                ByteBuffer b = ByteBuffer.allocate(1);
                return ch.read(b) <= 0 ? -1 : b.get(0) & 0xff;
            }
            @Override public int read(byte[] a, int off, int len) throws IOException {
                return ch.read(ByteBuffer.wrap(a, off, len));
            }
            @Override public void readFully(byte[] a) throws IOException { readFully(a, 0, a.length); }
            @Override public void readFully(byte[] a, int off, int len) throws IOException {
                readFully(ByteBuffer.wrap(a, off, len));
            }
            @Override public int read(ByteBuffer buf) throws IOException { return ch.read(buf); }
            @Override public void readFully(ByteBuffer buf) throws IOException {
                while (buf.hasRemaining()) if (ch.read(buf) < 0) throw new EOFException(path.toString());
            }
            @Override public void close() throws IOException { ch.close(); }
        };
    }
}
