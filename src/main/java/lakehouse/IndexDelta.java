package lakehouse;

import lakehouse.storage.Storage;

import java.io.*;
import java.util.zip.CRC32;

/**
 * Per-commit record-level index update: (key -> file group) for every key the commit inserted (UPSERT) or moved
 * (COMPACT), sorted by key. It is written before the commit and only becomes visible through the commit that
 * names it, the same rule as data files. The in-memory hash index is these deltas replayed on a checkpoint.
 */
final class IndexDelta {
    private static final int MAGIC = 0x4C4B4958; // "LKIX"

    final long[] keys;
    final long[] fileGroups;

    IndexDelta(long[] keys, long[] fileGroups) {
        this.keys = keys;
        this.fileGroups = fileGroups;
    }

    void write(Storage storage, String path) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(8 + keys.length * 16 + 8);
        DataOutputStream out = new DataOutputStream(bos);
        out.writeInt(MAGIC);
        out.writeInt(keys.length);
        for (int i = 0; i < keys.length; i++) {
            out.writeLong(keys[i]);
            out.writeLong(fileGroups[i]);
        }
        CRC32 crc = new CRC32();
        crc.update(bos.toByteArray());
        out.writeLong(crc.getValue());
        storage.put(path, bos.toByteArray());
    }

    static IndexDelta read(Storage storage, String path) throws IOException {
        byte[] b = storage.read(path);
        CRC32 crc = new CRC32();
        crc.update(b, 0, b.length - 8);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(b));
        if (in.readInt() != MAGIC) throw new CorruptTableException("bad index delta " + path);
        int n = in.readInt();
        long[] k = new long[n], f = new long[n];
        for (int i = 0; i < n; i++) {
            k[i] = in.readLong();
            f[i] = in.readLong();
        }
        if (in.readLong() != crc.getValue()) throw new CorruptTableException("index delta checksum " + path);
        return new IndexDelta(k, f);
    }
}
