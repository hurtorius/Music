package org.hurtorius.music.song;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * The ".song" file format, also used on the wire. A deflate stream of:
 * <pre>
 * "MSNG" version:u8 name:utf duration:varlong
 * tempos:varint  (deltaTime:varint micros:varint)*
 * meters:varint  (deltaTime:varint numerator:u8 denominator:u8)*
 * notes:varint   (deltaStart:varint length:varint key:u8 velocity:u8 hand:u8)*
 * pedals:varint  (deltaTime:varint type:u8 value:u8)*
 * </pre>
 * A song's id is the lowercase sha-256 hex of its encoded bytes.
 */
public final class SongCodec {
    public static final int MAX_ENCODED_BYTES = 4 * 1024 * 1024;
    private static final int MAX_DECODED_BYTES = 32 * 1024 * 1024;
    private static final byte[] MAGIC = {'M', 'S', 'N', 'G'};
    private static final int VERSION = 1;

    private SongCodec() {}

    public static byte[] encode(Song song) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(bytes, deflater))) {
            out.write(MAGIC);
            out.writeByte(VERSION);
            byte[] name = song.name().getBytes(StandardCharsets.UTF_8);
            writeVar(out, name.length);
            out.write(name);
            writeVarLong(out, song.durationMs());
            writeVar(out, song.tempoCount());
            int last = 0;
            for (int i = 0; i < song.tempoCount(); i++) {
                writeVar(out, song.tempoTime(i) - last);
                last = song.tempoTime(i);
                writeVar(out, song.tempoMicrosPerQuarter(i));
            }
            writeVar(out, song.meterCount());
            last = 0;
            for (int i = 0; i < song.meterCount(); i++) {
                writeVar(out, song.meterTime(i) - last);
                last = song.meterTime(i);
                out.writeByte(song.meterNumerator(i));
                out.writeByte(song.meterDenominator(i));
            }
            writeVar(out, song.noteCount());
            last = 0;
            for (int i = 0; i < song.noteCount(); i++) {
                writeVar(out, song.noteStart(i) - last);
                last = song.noteStart(i);
                writeVar(out, song.noteLength(i));
                out.writeByte(song.noteKey(i));
                out.writeByte(song.noteVelocity(i));
                out.writeByte(song.noteHand(i));
            }
            writeVar(out, song.pedalCount());
            last = 0;
            for (int i = 0; i < song.pedalCount(); i++) {
                writeVar(out, song.pedalTime(i) - last);
                last = song.pedalTime(i);
                out.writeByte(song.pedalType(i));
                out.writeByte(song.pedalValue(i));
            }
        } catch (IOException e) {
            throw new IllegalStateException("In-memory song encoding failed", e);
        } finally {
            deflater.end();
        }
        return bytes.toByteArray();
    }

    /** Decodes and validates. Throws {@link IOException} with a readable message on any problem. */
    public static Song decode(byte[] data) throws IOException {
        if (data == null || data.length == 0) throw new IOException("Empty song");
        if (data.length > MAX_ENCODED_BYTES) throw new IOException("Song is larger than " + (MAX_ENCODED_BYTES >> 20) + " MB");
        try (DataInputStream in = new DataInputStream(new LimitedStream(new BufferedInputStream(new InflaterInputStream(new ByteArrayInputStream(data)), 64 * 1024), MAX_DECODED_BYTES))) {
            byte[] magic = new byte[4];
            in.readFully(magic);
            if (magic[0] != MAGIC[0] || magic[1] != MAGIC[1] || magic[2] != MAGIC[2] || magic[3] != MAGIC[3]) throw new IOException("Not a song file");
            int version = in.readUnsignedByte();
            if (version != VERSION) throw new IOException("Unsupported song version " + version);
            int nameLength = readVar(in, Song.MAX_NAME * 4);
            byte[] name = new byte[nameLength];
            in.readFully(name);
            Song.Builder b = new Song.Builder(new String(name, StandardCharsets.UTF_8));
            long duration = readVarLong(in);
            if (duration < 0 || duration > Song.MAX_DURATION_MS) throw new IOException("Song is too long");
            b.minDuration(duration);
            long t = 0;
            int tempos = readVar(in, 10_000);
            for (int i = 0; i < tempos; i++) {
                t += readVar(in, Integer.MAX_VALUE);
                b.tempo(t, readVar(in, Integer.MAX_VALUE));
            }
            t = 0;
            int meters = readVar(in, 10_000);
            for (int i = 0; i < meters; i++) {
                t += readVar(in, Integer.MAX_VALUE);
                b.meter(t, in.readUnsignedByte(), in.readUnsignedByte());
            }
            t = 0;
            int notes = readVar(in, Song.MAX_NOTES);
            for (int i = 0; i < notes; i++) {
                t += readVar(in, Integer.MAX_VALUE);
                if (t > Song.MAX_DURATION_MS) throw new IOException("Note after the maximum length");
                int length = readVar(in, Integer.MAX_VALUE);
                int key = in.readUnsignedByte(), velocity = in.readUnsignedByte(), hand = in.readUnsignedByte();
                if (key > 87 || velocity < 1 || velocity > 127 || hand > 2) throw new IOException("Invalid note " + i);
                b.note(t, length, key, velocity, hand);
            }
            t = 0;
            int pedals = readVar(in, Song.MAX_PEDALS);
            for (int i = 0; i < pedals; i++) {
                t += readVar(in, Integer.MAX_VALUE);
                int type = in.readUnsignedByte(), value = in.readUnsignedByte();
                if (type > 2 || value > 127) throw new IOException("Invalid pedal change " + i);
                b.pedal(t, type, value);
            }
            if (in.read() != -1) throw new IOException("Trailing data in song");
            Song song = b.build();
            checkDensity(song);
            return song;
        } catch (EOFException e) {
            throw new IOException("Song data ends early", e);
        } catch (java.util.zip.ZipException e) {
            throw new IOException("Song data is corrupt", e);
        }
    }

    /** Most note starts allowed inside any {@link #DENSITY_WINDOW_MS} window; more is not music a piano can play. */
    public static final int MAX_ONSETS_PER_WINDOW = 512;
    public static final int DENSITY_WINDOW_MS = 50;

    /** Rejects songs so dense that playing them would only stall every listener. */
    static void checkDensity(Song song) throws IOException {
        int first = 0;
        for (int i = 0; i < song.noteCount(); i++) {
            while (song.noteStart(i) - song.noteStart(first) >= DENSITY_WINDOW_MS) first++;
            if (i - first + 1 > MAX_ONSETS_PER_WINDOW) throw new IOException("Song is too dense to play (over " + MAX_ONSETS_PER_WINDOW + " notes in " + DENSITY_WINDOW_MS + " ms)");
        }
    }

    public static String id(byte[] encoded) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean isId(String id) {
        return id != null && id.length() == 64 && id.chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'));
    }

    private static void writeVar(DataOutputStream out, int value) throws IOException {
        if (value < 0) throw new IOException("Negative value " + value);
        while ((value & ~0x7f) != 0) {
            out.writeByte((value & 0x7f) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    private static void writeVarLong(DataOutputStream out, long value) throws IOException {
        if (value < 0) throw new IOException("Negative value " + value);
        while ((value & ~0x7fL) != 0) {
            out.writeByte((int) ((value & 0x7f) | 0x80));
            value >>>= 7;
        }
        out.writeByte((int) value);
    }

    private static int readVar(DataInputStream in, int max) throws IOException {
        int value = 0, shift = 0;
        while (true) {
            int b = in.readUnsignedByte();
            value |= (b & 0x7f) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
            if (shift > 28) throw new IOException("Number too large");
        }
        if (value < 0 || value > max) throw new IOException("Value out of range");
        return value;
    }

    private static long readVarLong(DataInputStream in) throws IOException {
        long value = 0;
        int shift = 0;
        while (true) {
            int b = in.readUnsignedByte();
            value |= (long) (b & 0x7f) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
            if (shift > 56) throw new IOException("Number too large");
        }
        return value;
    }

    /** Guards against decompression bombs. */
    private static final class LimitedStream extends InputStream {
        private final InputStream in;
        private long remaining;

        LimitedStream(InputStream in, long limit) {
            this.in = in;
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) throw new IOException("Song expands beyond the size limit");
            int b = in.read();
            if (b >= 0) remaining--;
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            if (remaining <= 0) throw new IOException("Song expands beyond the size limit");
            int n = in.read(buf, off, (int) Math.min(len, remaining));
            if (n > 0) remaining -= n;
            return n;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
