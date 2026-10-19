package lakehouse.storage;

import org.apache.parquet.io.InputFile;
import org.apache.parquet.io.SeekableInputStream;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.EOFException;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.List;

/**
 * S3 (or any S3-compatible store with conditional writes). The put-if-absent is a PUT with
 * {@code If-None-Match: *}: S3 answers 412 Precondition Failed if the key already exists, so exactly one of N
 * racing writers creates commit N. Before S3 had conditional writes (Aug 2024), Delta needed an external
 * DynamoDB-backed log store for this and Hudi needed a lock provider. Plain PUTs are atomic for readers.
 */
public final class S3Storage implements Storage {
    private final S3Client s3;
    private final String bucket;
    private final String prefix;

    public S3Storage(S3Client s3, String bucket, String prefix) {
        this.s3 = s3;
        this.bucket = bucket;
        this.prefix = prefix.isEmpty() || prefix.endsWith("/") ? prefix : prefix + "/";
    }

    /** Client for an S3-compatible endpoint such as MinIO (path-style addressing, static credentials). */
    public static S3Client client(String endpoint, String accessKey, String secretKey) {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .httpClient(UrlConnectionHttpClient.create())
                .build();
    }

    public void createBucketIfMissing() {
        try {
            s3.headBucket(b -> b.bucket(bucket));
        } catch (NoSuchBucketException e) {
            s3.createBucket(b -> b.bucket(bucket));
        }
    }

    private String key(String path) { return prefix + path; }

    @Override
    public boolean putIfAbsent(String path, byte[] data) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key(path)).ifNoneMatch("*").build(),
                        RequestBody.fromBytes(data));
                return true;
            } catch (S3Exception e) {
                if (e.statusCode() == 412) return false;
                // 409 ConditionalRequestConflict: another conditional write to this key is in flight. Retry: the
                // next attempt sees either 412 (they won) or success (they failed).
                if (e.statusCode() == 409 && attempt < 10) continue;
                throw new IOException("putIfAbsent " + path, e);
            }
        }
    }

    @Override
    public void put(String path, byte[] data) throws IOException {
        try {
            s3.putObject(b -> b.bucket(bucket).key(key(path)), RequestBody.fromBytes(data));
        } catch (S3Exception e) {
            throw new IOException("put " + path, e);
        }
    }

    @Override
    public byte[] read(String path) throws IOException {
        try {
            return s3.getObjectAsBytes(b -> b.bucket(bucket).key(key(path))).asByteArray();
        } catch (NoSuchKeyException e) {
            throw new NoSuchFileException(path);
        } catch (S3Exception e) {
            if (e.statusCode() == 404) throw new NoSuchFileException(path);
            throw new IOException("read " + path, e);
        }
    }

    @Override
    public boolean exists(String path) throws IOException {
        try {
            s3.headObject(b -> b.bucket(bucket).key(key(path)));
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) return false;
            throw new IOException("exists " + path, e);
        }
    }

    @Override
    public List<FileEntry> list(String dir) throws IOException {
        String p = key(dir.endsWith("/") ? dir : dir + "/");
        List<FileEntry> out = new ArrayList<>();
        try {
            for (ListObjectsV2Response page : s3.listObjectsV2Paginator(b -> b.bucket(bucket).prefix(p).delimiter("/"))) {
                for (S3Object o : page.contents()) {
                    out.add(new FileEntry(o.key().substring(prefix.length()), o.size(), o.lastModified().toEpochMilli()));
                }
            }
        } catch (S3Exception e) {
            throw new IOException("list " + dir, e);
        }
        return out;
    }

    @Override
    public void delete(String path) throws IOException {
        try {
            s3.deleteObject(b -> b.bucket(bucket).key(key(path)));
        } catch (S3Exception e) {
            throw new IOException("delete " + path, e);
        }
    }

    /** Whole-object GET, then seek in memory: data files are small, and Parquet reads the footer first anyway. */
    @Override
    public InputFile inputFile(String path) throws IOException {
        byte[] bytes = read(path);
        return new InputFile() {
            @Override public long getLength() { return bytes.length; }
            @Override public SeekableInputStream newStream() { return new BytesSeekable(bytes); }
        };
    }

    private static final class BytesSeekable extends SeekableInputStream {
        private final byte[] b;
        private int pos;

        BytesSeekable(byte[] b) { this.b = b; }

        @Override public long getPos() { return pos; }
        @Override public void seek(long n) { pos = (int) n; }
        @Override public int read() { return pos < b.length ? b[pos++] & 0xff : -1; }
        @Override public int read(byte[] a, int off, int len) {
            if (len == 0) return 0;
            if (pos >= b.length) return -1;
            int n = Math.min(len, b.length - pos);
            System.arraycopy(b, pos, a, off, n);
            pos += n;
            return n;
        }
        @Override public void readFully(byte[] a) throws IOException { readFully(a, 0, a.length); }
        @Override public void readFully(byte[] a, int off, int len) throws IOException {
            if (b.length - pos < len) throw new EOFException();
            System.arraycopy(b, pos, a, off, len);
            pos += len;
        }
        @Override public int read(ByteBuffer buf) {
            if (!buf.hasRemaining()) return 0;
            if (pos >= b.length) return -1;
            int n = Math.min(buf.remaining(), b.length - pos);
            buf.put(b, pos, n);
            pos += n;
            return n;
        }
        @Override public void readFully(ByteBuffer buf) throws IOException {
            if (b.length - pos < buf.remaining()) throw new EOFException();
            int n = buf.remaining();
            buf.put(b, pos, n);
            pos += n;
        }
    }
}
