package lakehouse.io;

import lakehouse.FileSlice;
import lakehouse.Record;
import lakehouse.index.BloomFilter;
import lakehouse.storage.Storage;
import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.HadoopReadOptions;
import org.apache.parquet.ParquetReadOptions;
import org.apache.parquet.column.page.PageReadStore;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.api.WriteSupport;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.ColumnIOFactory;
import org.apache.parquet.io.OutputFile;
import org.apache.parquet.io.PositionOutputStream;
import org.apache.parquet.io.RecordReader;
import org.apache.parquet.io.api.*;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.MessageTypeParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parquet base files. Each file holds one file slice: the records of one file group, sorted by key.
 * Like Hudi, the footer's key/value metadata carries a bloom filter of the keys plus the min/max key,
 * so an index lookup can rule a file out by reading only its footer.
 */
public final class DataFiles {
    public static final MessageType SCHEMA = MessageTypeParser.parseMessageType(
            "message record { required int64 key; required int64 seq; required int64 amount; "
                    + "required binary payload (STRING); }");
    static final MessageType KEY_ONLY = MessageTypeParser.parseMessageType("message record { required int64 key; }");

    static final String META_BLOOM = "lakehouse.bloom";
    static final String META_MIN = "lakehouse.min_key";
    static final String META_MAX = "lakehouse.max_key";
    static final String META_COUNT = "lakehouse.count";

    /**
     * One shared, empty Hadoop Configuration. Without this, parquet-java builds a new Configuration per file and
     * parses Hadoop's default XML resources each time, which was ~3x the cost of the actual Parquet encode/decode.
     */
    private static final Configuration CONF = new Configuration(false);
    /**
     * Per thread, not shared: ParquetReadOptions owns a CodecFactory that caches decompressors, and it is not
     * thread-safe. One shared instance made concurrent readers fail on valid files with
     * "Corrupt file: Zero bytes read during decompression".
     */
    private static final ThreadLocal<ParquetReadOptions> READ_OPTIONS =
            ThreadLocal.withInitial(() -> HadoopReadOptions.builder(CONF).build());

    private DataFiles() {}

    public record Footer(long count, long minKey, long maxKey, BloomFilter bloom) {}

    /** Write {@code sorted} (ascending by key, unique) as one file slice of {@code fileGroup}. */
    public static FileSlice write(Storage storage, String path, long fileGroup, List<Record> sorted,
                                  double bloomFpp, CompressionCodecName codec) throws IOException {
        if (sorted.isEmpty()) throw new IllegalArgumentException("empty slice");
        BloomFilter bloom = BloomFilter.create(sorted.size(), bloomFpp);
        long prev = Long.MIN_VALUE;
        for (Record r : sorted) {
            if (r.key() <= prev) throw new IllegalArgumentException("keys not strictly ascending at " + r.key());
            prev = r.key();
            bloom.add(r.key());
        }
        long min = sorted.get(0).key(), max = sorted.get(sorted.size() - 1).key();
        Map<String, String> meta = new HashMap<>();
        meta.put(META_BLOOM, bloom.toBase64());
        meta.put(META_MIN, Long.toString(min));
        meta.put(META_MAX, Long.toString(max));
        meta.put(META_COUNT, Integer.toString(sorted.size()));

        BytesOutputFile out = new BytesOutputFile();
        try (ParquetWriter<Record> w = new Builder(out).withConf(CONF).withCompressionCodec(codec).withExtraMetaData(meta).build()) {
            for (Record r : sorted) w.write(r);
        }
        storage.put(path, out.bytes());
        return new FileSlice(fileGroup, path, sorted.size(), min, max, -1);
    }

    public static List<Record> readAll(Storage storage, String path) throws IOException {
        List<Record> out = new ArrayList<>();
        try (ParquetFileReader r = ParquetFileReader.open(storage.inputFile(path), READ_OPTIONS.get())) {
            MessageType fileSchema = r.getFooter().getFileMetaData().getSchema();
            RecordMat mat = new RecordMat();
            PageReadStore pages;
            while ((pages = r.readNextRowGroup()) != null) {
                RecordReader<Record> rr = new ColumnIOFactory().getColumnIO(SCHEMA, fileSchema).getRecordReader(pages, mat);
                for (long i = 0, n = pages.getRowCount(); i < n; i++) out.add(rr.read());
            }
        }
        return out;
    }

    /** Only the key column is read off disk (column projection), returned ascending. */
    public static long[] readKeys(Storage storage, String path) throws IOException {
        try (ParquetFileReader r = ParquetFileReader.open(storage.inputFile(path), READ_OPTIONS.get())) {
            MessageType fileSchema = r.getFooter().getFileMetaData().getSchema();
            r.setRequestedSchema(KEY_ONLY);
            KeyMat mat = new KeyMat((int) r.getRecordCount());
            PageReadStore pages;
            while ((pages = r.readNextRowGroup()) != null) {
                RecordReader<Object> rr = new ColumnIOFactory().getColumnIO(KEY_ONLY, fileSchema).getRecordReader(pages, mat);
                for (long i = 0, n = pages.getRowCount(); i < n; i++) rr.read();
            }
            return mat.keys();
        }
    }

    /** Opens the file and reads only the footer. */
    public static Footer readFooter(Storage storage, String path) throws IOException {
        try (ParquetFileReader r = ParquetFileReader.open(storage.inputFile(path), READ_OPTIONS.get())) {
            Map<String, String> kv = r.getFooter().getFileMetaData().getKeyValueMetaData();
            return new Footer(Long.parseLong(kv.get(META_COUNT)), Long.parseLong(kv.get(META_MIN)),
                    Long.parseLong(kv.get(META_MAX)), BloomFilter.fromBase64(kv.get(META_BLOOM)));
        }
    }

    // ---- writer plumbing ----

    private static final class Builder extends ParquetWriter.Builder<Record, Builder> {
        Builder(OutputFile f) { super(f); }
        @Override protected Builder self() { return this; }
        @Override protected WriteSupport<Record> getWriteSupport(Configuration conf) { return new RecordWriteSupport(); }
    }

    private static final class RecordWriteSupport extends WriteSupport<Record> {
        private RecordConsumer rc;

        @Override public WriteContext init(Configuration configuration) { return new WriteContext(SCHEMA, Map.of()); }
        @Override public void prepareForWrite(RecordConsumer recordConsumer) { this.rc = recordConsumer; }

        @Override public void write(Record r) {
            rc.startMessage();
            rc.startField("key", 0); rc.addLong(r.key()); rc.endField("key", 0);
            rc.startField("seq", 1); rc.addLong(r.seq()); rc.endField("seq", 1);
            rc.startField("amount", 2); rc.addLong(r.amount()); rc.endField("amount", 2);
            rc.startField("payload", 3); rc.addBinary(Binary.fromString(r.payload())); rc.endField("payload", 3);
            rc.endMessage();
        }
    }

    /** The data file is built in memory, then handed to Storage.put, which publishes it atomically. */
    private static final class BytesOutputFile implements OutputFile {
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream(64 * 1024);

        byte[] bytes() { return buf.toByteArray(); }

        @Override public PositionOutputStream create(long blockSizeHint) { return stream(); }
        @Override public PositionOutputStream createOrOverwrite(long blockSizeHint) { buf.reset(); return stream(); }
        @Override public boolean supportsBlockSize() { return false; }
        @Override public long defaultBlockSize() { return 0; }

        private PositionOutputStream stream() {
            return new PositionOutputStream() {
                @Override public long getPos() { return buf.size(); }
                @Override public void write(int b) { buf.write(b); }
                @Override public void write(byte[] b, int off, int len) { buf.write(b, off, len); }
            };
        }
    }

    // ---- reader plumbing: materialise straight into Records / a long[], no example Group objects ----

    private static final class RecordMat extends RecordMaterializer<Record> {
        long key, seq, amount;
        String payload;
        private final Converter[] fields = {
                new PrimitiveConverter() { @Override public void addLong(long v) { key = v; } },
                new PrimitiveConverter() { @Override public void addLong(long v) { seq = v; } },
                new PrimitiveConverter() { @Override public void addLong(long v) { amount = v; } },
                new PrimitiveConverter() { @Override public void addBinary(Binary v) { payload = v.toStringUsingUTF8(); } },
        };
        private final GroupConverter root = new GroupConverter() {
            @Override public Converter getConverter(int i) { return fields[i]; }
            @Override public void start() {}
            @Override public void end() {}
        };

        @Override public Record getCurrentRecord() { return new Record(key, seq, amount, payload); }
        @Override public GroupConverter getRootConverter() { return root; }
    }

    private static final class KeyMat extends RecordMaterializer<Object> {
        private long[] keys;
        private int n;
        private final Converter key = new PrimitiveConverter() {
            @Override public void addLong(long v) {
                if (n == keys.length) keys = Arrays.copyOf(keys, Math.max(16, n * 2));
                keys[n++] = v;
            }
        };
        private final GroupConverter root = new GroupConverter() {
            @Override public Converter getConverter(int i) { return key; }
            @Override public void start() {}
            @Override public void end() {}
        };

        KeyMat(int expected) { keys = new long[Math.max(expected, 16)]; }

        long[] keys() { return n == keys.length ? keys : Arrays.copyOf(keys, n); }

        @Override public Object getCurrentRecord() { return null; }
        @Override public GroupConverter getRootConverter() { return root; }
    }
}
