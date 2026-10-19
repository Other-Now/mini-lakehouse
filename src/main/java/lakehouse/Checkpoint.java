package lakehouse;

import lakehouse.index.LongLongMap;

import java.io.*;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * The full table state at a version (every current slice, plus the record-level index), so opening a table
 * reads one checkpoint and the commits after it instead of replaying the timeline from 0. Checkpoints are an
 * optimisation only: a missing or corrupt one falls back to full replay.
 */
final class Checkpoint {
    private static final int MAGIC = 0x4C4B4350; // "LKCP"

    final Snapshot snapshot;
    final LongLongMap index;   // null when the writing table did not track a hash index

    Checkpoint(Snapshot snapshot, LongLongMap index) {
        this.snapshot = snapshot;
        this.index = index;
    }

    byte[] encode() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeInt(MAGIC);
        out.writeLong(snapshot.version());
        out.writeInt(snapshot.fileCount());
        for (FileSlice s : snapshot.slices()) {
            out.writeLong(s.fileGroup());
            out.writeLong(s.count());
            out.writeLong(s.minKey());
            out.writeLong(s.maxKey());
            out.writeLong(s.version());
            out.writeUTF(s.path());
        }
        out.writeBoolean(index != null);
        if (index != null) {
            out.writeInt(index.size());
            IOException[] err = new IOException[1];
            index.forEach((k, v) -> {
                try {
                    out.writeLong(k);
                    out.writeLong(v);
                } catch (IOException e) {
                    err[0] = e;
                }
            });
            if (err[0] != null) throw err[0];
        }
        CRC32 crc = new CRC32();
        crc.update(bos.toByteArray());
        out.writeLong(crc.getValue());
        return bos.toByteArray();
    }

    static Checkpoint decode(byte[] b, String where) throws IOException {
        if (b.length < 8) throw new CorruptTableException("short checkpoint " + where);
        CRC32 crc = new CRC32();
        crc.update(b, 0, b.length - 8);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(b));
        if (in.readInt() != MAGIC) throw new CorruptTableException("bad checkpoint " + where);
        long version = in.readLong();
        int n = in.readInt();
        Map<Long, FileSlice> slices = new HashMap<>(n * 2);
        for (int i = 0; i < n; i++) {
            long fg = in.readLong(), count = in.readLong(), min = in.readLong(), max = in.readLong(), v = in.readLong();
            slices.put(fg, new FileSlice(fg, in.readUTF(), count, min, max, v));
        }
        LongLongMap index = null;
        if (in.readBoolean()) {
            int m = in.readInt();
            index = new LongLongMap(m);
            for (int i = 0; i < m; i++) index.put(in.readLong(), in.readLong());
        }
        if (in.readLong() != crc.getValue()) throw new CorruptTableException("checkpoint checksum " + where);
        return new Checkpoint(Snapshot.of(version, slices), index);
    }
}
