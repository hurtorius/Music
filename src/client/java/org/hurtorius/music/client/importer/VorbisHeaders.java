package org.hurtorius.music.client.importer;

import com.jcraft.jogg.Packet;
import com.jcraft.jogg.Page;
import com.jcraft.jogg.StreamState;
import com.jcraft.jogg.SyncState;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads an Ogg Vorbis file's headers the way Minecraft's {@code JOrbisAudioStream} does, before it
 * does. JOrbis trusts the codebooks in the setup header: one of dimension 0 makes it loop forever
 * (while unpacking the codebook, or later while decoding with it), on a thread that never sees the
 * player's Cancel. Such files are refused here, with the bounds libvorbis itself applies. The walk
 * reads every codebook bit for bit as JOrbis will, so both see the same codebooks.
 */
final class VorbisHeaders {
    /** Most samples one Ogg page of Vorbis can decode to: 255 packets of at most 4096 new samples. */
    static final long MAX_PAGE_SAMPLES = 255L * 4096;
    private static final int READ = 8192;
    /** Pages looked at after the headers for the first one that ends an audio packet. */
    private static final int AUDIO_PAGES = 16;

    private final SyncState sync = new SyncState();
    private final StreamState stream = new StreamState();
    private final Page page = new Page();
    private final InputStream in;

    private VorbisHeaders(InputStream in) {
        this.in = in;
    }

    /**
     * Checks the setup header's codebooks and finds where the audio starts.
     *
     * @return the granule position of the first page after the headers that ends an audio packet (where
     *         a recording made from the middle of a broadcast starts counting), or 0 when there is none or
     *         the headers cannot be read at all (JOrbis then says so itself)
     * @throws AudioImportException when a codebook would make JOrbis hang or read the header differently
     */
    static long check(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return new VorbisHeaders(in).read();
        }
    }

    private long read() throws IOException {
        if (!nextPage()) return 0;
        stream.init(page.serialno());
        Packet packet = new Packet();
        // Like JOrbis: the identification packet comes from the first page, the comment and setup from any.
        if (stream.pagein(page) < 0 || stream.packetout(packet) != 1) return 0;
        for (int i = 0; i < 2; i++) {
            if (!nextPacket(packet)) return 0;
        }
        if (isSetup(packet)) codebooks(new Bits(packet.packet_base, packet.packet, packet.bytes));
        long granule = page.granulepos();
        for (int pages = 0; granule <= 0 && pages < AUDIO_PAGES && nextPage(); pages++) granule = page.granulepos();
        return Math.max(0, granule);
    }

    private boolean nextPacket(Packet packet) throws IOException {
        while (true) {
            int result = stream.packetout(packet);
            if (result == 1) return true;
            if (result < 0 || !nextPage() || stream.pagein(page) < 0) return false;
        }
    }

    private boolean nextPage() throws IOException {
        while (true) {
            int result = sync.pageout(page);
            if (result == 1) return true;
            if (result < 0) return false;
            int at = sync.buffer(READ);
            int n = in.read(sync.data, at, READ);
            if (n < 0) return false;
            sync.wrote(n);
        }
    }

    private static boolean isSetup(Packet p) {
        if (p.bytes < 7 || p.packet_base[p.packet] != 5) return false;
        for (int i = 0; i < 6; i++) if (p.packet_base[p.packet + 1 + i] != "vorbis".charAt(i)) return false;
        return true;
    }

    /** Walks the codebooks as JOrbis's {@code StaticCodeBook.unpack} reads them. */
    private static void codebooks(Bits b) throws AudioImportException {
        b.skip(7 * 8);
        long count = b.read(8) + 1;
        for (int book = 1; book <= count; book++) {
            if (b.read(24) != 0x564342) throw damaged(book, "it does not start with the codebook pattern");
            long dimensions = b.read(16), entries = b.read(24);
            if (dimensions == 0) throw damaged(book, "it has no dimensions");
            if (ilog(dimensions) + ilog(entries) > 24) throw damaged(book, "it is impossibly large");
            if (b.read(1) == 0) {
                boolean sparse = b.read(1) == 1;
                for (long e = 0; e < entries; e++) {
                    if (!sparse || b.read(1) == 1) b.skip(5);
                }
            } else {
                b.skip(5);
                for (long e = 0; e < entries; ) {
                    long run = b.read(ilog(entries - e));
                    if (run > entries - e) throw damaged(book, "its code lengths run past its entries");
                    e += run;
                }
            }
            long lookup = b.read(4);
            if (lookup > 2) throw damaged(book, "its lookup type is unknown");
            if (lookup == 0) continue;
            b.skip(64);
            long valueBits = b.read(4) + 1;
            b.skip(1);
            long values = lookup == 1 ? lookup1Values(entries, dimensions, book) : entries * dimensions;
            b.skip(values * valueBits);
        }
    }

    /** Values per dimension of a lookup type 1 codebook: the largest v with v^dimensions <= entries. */
    private static long lookup1Values(long entries, long dimensions, int book) throws AudioImportException {
        long v = (long) Math.floor(Math.pow(entries, 1.0 / dimensions));
        while (v > 0 && power(v, dimensions) > entries) v--;
        while (power(v + 1, dimensions) <= entries) v++;
        // JOrbis looks for v in int arithmetic, which (v + 1)^dimensions must fit to find the same v.
        if (power(v + 1, dimensions) > Integer.MAX_VALUE) throw damaged(book, "its value table is impossibly large");
        return v;
    }

    /** base^exponent, or Long.MAX_VALUE once it is beyond Integer.MAX_VALUE. */
    private static long power(long base, long exponent) {
        if (base <= 1) return base;
        long result = 1;
        for (long i = 0; i < exponent; i++) {
            result *= base;
            if (result > Integer.MAX_VALUE) return Long.MAX_VALUE;
        }
        return result;
    }

    private static int ilog(long value) {
        return 64 - Long.numberOfLeadingZeros(value);
    }

    private static AudioImportException damaged(int book, String why) {
        return new AudioImportException("This OGG file's Vorbis headers are damaged (codebook " + book + ": " + why + ").");
    }

    /** Vorbis packs bits least significant first; reading past the packet's end means the header is cut short. */
    private static final class Bits {
        private final byte[] data;
        private final long end;
        private long at;

        Bits(byte[] data, int from, int length) {
            this.data = data;
            this.at = (long) from * 8;
            this.end = (long) (from + length) * 8;
        }

        long read(int count) throws AudioImportException {
            need(count);
            long value = 0;
            for (int i = 0; i < count; i++, at++) value |= (long) ((data[(int) (at >>> 3)] >> (at & 7)) & 1) << i;
            return value;
        }

        void skip(long count) throws AudioImportException {
            need(count);
            at += count;
        }

        private void need(long count) throws AudioImportException {
            if (count > end - at) throw new AudioImportException("This OGG file's Vorbis headers are damaged (the setup header is cut short).");
        }
    }
}
