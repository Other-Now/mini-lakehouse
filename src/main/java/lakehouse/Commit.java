package lakehouse;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * One timeline entry: the actions of a single transaction. Text, one action per line, CRC32 last so a reader
 * can tell a torn file from a valid one.
 *
 * <pre>
 * LKC1
 * txn 3f2a...           op UPSERT        read 41
 * add &lt;fg&gt; &lt;count&gt; &lt;min&gt; &lt;max&gt; &lt;path&gt;   (new slice of an existing or a new file group)
 * remove &lt;fg&gt;                             (file group dropped, by compaction)
 * crc &lt;hex&gt;
 * </pre>
 */
public record Commit(String txn, Op op, long readVersion, long timestampMillis, int inserted, String indexPath,
                     List<FileSlice> adds, List<Long> removes) {

    public enum Op { CREATE, UPSERT, COMPACT }

    static Commit create() {
        return new Commit("create", Op.CREATE, -1, System.currentTimeMillis(), 0, null, List.of(), List.of());
    }

    /** File groups this commit rewrote or dropped. Two commits conflict at file-group level iff these intersect. */
    public Set<Long> writeSet() {
        Set<Long> s = new HashSet<>();
        for (FileSlice a : adds) s.add(a.fileGroup());
        s.addAll(removes);
        return s;
    }

    public byte[] encode() {
        StringBuilder sb = new StringBuilder(256);
        sb.append("LKC1\n");
        sb.append("txn ").append(txn).append('\n');
        sb.append("op ").append(op).append('\n');
        sb.append("read ").append(readVersion).append('\n');
        sb.append("ts ").append(timestampMillis).append('\n');
        sb.append("inserted ").append(inserted).append('\n');
        sb.append("index ").append(indexPath == null ? "-" : indexPath).append('\n');
        for (FileSlice a : adds) {
            sb.append("add ").append(Long.toHexString(a.fileGroup())).append(' ').append(a.count()).append(' ')
                    .append(a.minKey()).append(' ').append(a.maxKey()).append(' ').append(a.path()).append('\n');
        }
        for (long r : removes) sb.append("remove ").append(Long.toHexString(r)).append('\n');
        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        CRC32 crc = new CRC32();
        crc.update(body);
        return (sb + "crc " + Long.toHexString(crc.getValue()) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    public static Commit decode(byte[] bytes, String where) throws CorruptTableException {
        String s = new String(bytes, StandardCharsets.UTF_8);
        int crcAt = s.lastIndexOf("crc ");
        if (!s.startsWith("LKC1\n") || crcAt < 0 || !s.endsWith("\n")) throw new CorruptTableException("torn commit " + where);
        CRC32 crc = new CRC32();
        crc.update(s.substring(0, crcAt).getBytes(StandardCharsets.UTF_8));
        if (!Long.toHexString(crc.getValue()).equals(s.substring(crcAt + 4, s.length() - 1))) {
            throw new CorruptTableException("checksum mismatch " + where);
        }
        String txn = null, index = null;
        Op op = null;
        long read = 0, ts = 0;
        int inserted = 0;
        List<FileSlice> adds = new ArrayList<>();
        List<Long> removes = new ArrayList<>();
        for (String line : s.substring(5, crcAt).split("\n")) {
            String[] f = line.split(" ", 6);
            switch (f[0]) {
                case "txn" -> txn = f[1];
                case "op" -> op = Op.valueOf(f[1]);
                case "read" -> read = Long.parseLong(f[1]);
                case "ts" -> ts = Long.parseLong(f[1]);
                case "inserted" -> inserted = Integer.parseInt(f[1]);
                case "index" -> index = f[1].equals("-") ? null : f[1];
                case "add" -> adds.add(new FileSlice(Long.parseUnsignedLong(f[1], 16), f[5], Long.parseLong(f[2]),
                        Long.parseLong(f[3]), Long.parseLong(f[4]), -1));
                case "remove" -> removes.add(Long.parseUnsignedLong(f[1], 16));
                default -> throw new CorruptTableException("bad line '" + line + "' in " + where);
            }
        }
        return new Commit(txn, op, read, ts, inserted, index, List.copyOf(adds), List.copyOf(removes));
    }
}
