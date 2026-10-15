package org.hurtorius.music.client.importer;

import fr.delthas.javamp3.Sound;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CancellationException;

/**
 * MP3 (MPEG-1 audio, layers I-III) through the bundled {@code fr.delthas:javamp3} decoder. That
 * decoder only understands MPEG-1 and does not skip tags, so this class first finds the real audio:
 * it skips ID3v2 tags and junk before the first frame, stops before ID3v1/APE tags at the end, and
 * rejects MPEG-2/2.5 and free-format streams with a precise message instead of decoding noise. A
 * Xing/Info/VBRI frame is dropped and, when it carries LAME's encoder delay and padding, the output
 * is trimmed gaplessly so that sound starts exactly where it started before encoding.
 */
final class Mp3Decoder {
    private static final Logger LOG = LoggerFactory.getLogger("Music");
    /** Layer III synthesis delay of a standard decoder, which LAME's delay field does not include. */
    private static final int DECODER_DELAY = 529;
    private static final int SCAN_LIMIT = 1 << 20;
    /** Smallest MPEG-1 layer III frame: 32 kbit/s at 48 kHz. */
    private static final int MIN_FRAME_BYTES = 144 * 32000 / 48000;
    /** kbit/s for bitrate index 1..14: MPEG-1 layers I, II, III, then MPEG-2/2.5 layer I and layers II/III. */
    private static final int[][] BITRATES = {
        {32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448},
        {32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384},
        {32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320},
        {32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256},
        {8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160},
    };
    private static final int[] MPEG1_RATES = {44100, 48000, 32000};

    private Mp3Decoder() {}

    /**
     * One frame header.
     *
     * @param version 3 = MPEG-1, 2 = MPEG-2, 0 = MPEG-2.5 (the header's own encoding)
     * @param layer   1..3
     */
    private record Header(int version, int layer, int bitrateIndex, int rateIndex, boolean padding, int mode, boolean crc) {
        static Header parse(byte[] b, int at) {
            if ((b[at] & 0xFF) != 0xFF || (b[at + 1] & 0xE0) != 0xE0) return null;
            int version = (b[at + 1] >> 3) & 3;
            int layerBits = (b[at + 1] >> 1) & 3;
            int bitrate = (b[at + 2] >> 4) & 15;
            int rate = (b[at + 2] >> 2) & 3;
            if (version == 1 || layerBits == 0 || bitrate == 15 || rate == 3) return null;
            return new Header(version, 4 - layerBits, bitrate, rate, ((b[at + 2] >> 1) & 1) == 1,
                (b[at + 3] >> 6) & 3, (b[at + 1] & 1) == 0);
        }

        int sampleRate() {
            int base = MPEG1_RATES[rateIndex];
            return version == 3 ? base : version == 2 ? base / 2 : base / 4;
        }

        /** Frame length in bytes, 0 for free format. */
        int length() {
            if (bitrateIndex == 0) return 0;
            int table = version == 3 ? layer - 1 : layer == 1 ? 3 : 4;
            int bitsPerSecond = BITRATES[table][bitrateIndex - 1] * 1000;
            int pad = padding ? 1 : 0;
            if (layer == 1) return (12 * bitsPerSecond / sampleRate() + pad) * 4;
            int factor = layer == 3 && version != 3 ? 72 : 144;
            return factor * bitsPerSecond / sampleRate() + pad;
        }

        boolean sameStream(Header other) {
            return other != null && other.version == version && other.layer == layer && other.rateIndex == rateIndex;
        }

        String describe() {
            String name = version == 3 ? "MPEG-1" : version == 2 ? "MPEG-2" : "MPEG-2.5";
            return name + " layer " + "I".repeat(layer) + " at " + sampleRate() + " Hz";
        }
    }

    static DecodedAudio decode(InputStream in, Path file, long fileSize, int maxRate, Progress progress) throws IOException {
        long audioEnd = audioEnd(file, fileSize);
        long start = skipId3v2(in);
        byte[] head = in.readNBytes((int) Math.min(SCAN_LIMIT, Math.max(0, audioEnd - start)));
        int first = -1;
        Header header = null;
        for (int i = 0; i + 4 <= head.length; i++) {
            Header h = Header.parse(head, i);
            if (h == null || !confirmed(head, i, h)) continue;
            if (h.version != 3) throw unsupportedVersion(h);
            first = i;
            header = h;
            break;
        }
        if (header == null) {
            for (int i = 0; i + 4 <= head.length; i++) {
                Header h = Header.parse(head, i);
                if (h != null && h.bitrateIndex == 0) {
                    throw new AudioImportException("This MP3 uses a free-format bitrate, which cannot be decoded. Re-encode it at a standard bitrate.");
                }
            }
            throw new AudioImportException("No MP3 audio frames were found in this file.");
        }

        Gapless gapless = Gapless.read(head, first, header);
        long remaining = Math.max(0, audioEnd - start - head.length);
        InputStream mpeg = new SequenceInputStream(new ByteArrayInputStream(head, first, head.length - first),
            new BoundedInputStream(in, remaining));
        Sound sound;
        try {
            sound = new Sound(mpeg);
        } catch (IOException | RuntimeException e) {
            throw new AudioImportException("This MP3's first frame is damaged (" + e.getMessage() + ").", e);
        }
        int channels = sound.isStereo() ? 2 : 1;
        int rate = sound.getSamplingFrequency();
        // The tag's frame count is believed only if the audio data is large enough for that many frames.
        long mostFrames = Math.max(0, audioEnd - start - first) / MIN_FRAME_BYTES + 1;
        long expected = gapless.expectedSamples <= mostFrames * 1152 ? gapless.expectedSamples : 0;
        MonoBuilder out = new MonoBuilder(rate, maxRate, expected, progress);
        Trimmer trimmer = new Trimmer(out, gapless.skip, gapless.trim);
        int frameBytes = 2 * channels;
        // Small reads: javamp3 decodes frames until a read is filled, and a read cut short by the end of
        // a truncated file loses everything decoded inside it, so ask for no more than one frame.
        byte[] buffer = new byte[384 * frameBytes];
        int carry = 0;
        try (sound) {
            while (true) {
                int n = sound.read(buffer, carry, buffer.length - carry);
                if (n < 0) break;
                int available = carry + n;
                int count = available / frameBytes;
                for (int f = 0; f < count; f++) {
                    int at = f * frameBytes;
                    int left = (short) ((buffer[at] & 0xFF) | (buffer[at + 1] << 8));
                    if (channels == 2) {
                        int right = (short) ((buffer[at + 2] & 0xFF) | (buffer[at + 3] << 8));
                        trimmer.add((left + right) / 65536f);
                    } else {
                        trimmer.add(left / 32768f);
                    }
                }
                carry = available - count * frameBytes;
                System.arraycopy(buffer, count * frameBytes, buffer, 0, carry);
            }
        } catch (EOFException e) {
            // The last frame is cut short: keep everything before it, as every player does.
            LOG.warn("MP3 {} ends inside a frame at {} s; keeping the audio before it", file.getFileName(),
                String.format(Locale.ROOT, "%.2f", out.sourceFrames() / (double) rate));
        } catch (CancellationException | AudioImportException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new AudioImportException(String.format(Locale.ROOT, "This MP3 is damaged at about %.1f s (%s).",
                out.sourceFrames() / (double) rate, e.getMessage()), e);
        }
        return out.finish();
    }

    /** True if the next frame (or the end of the scanned bytes) follows this one where its length says. */
    private static boolean confirmed(byte[] b, int at, Header h) {
        int length = h.length();
        if (length <= 4) return false;
        int next = at + length;
        if (next + 4 > b.length) return next >= b.length - 3 && next <= b.length;
        return h.sameStream(Header.parse(b, next));
    }

    private static AudioImportException unsupportedVersion(Header h) {
        return new AudioImportException("This MP3 is " + h.describe() + ". Only MPEG-1 MP3s (32, 44.1 or 48 kHz) can be decoded; "
            + "re-encode it at 44.1 kHz, or import it as WAV, FLAC or OGG.");
    }

    /** Skips ID3v2 tags at the start; returns the number of bytes skipped. */
    private static long skipId3v2(InputStream in) throws IOException {
        long skipped = 0;
        while (true) {
            in.mark(10);
            byte[] h = in.readNBytes(10);
            if (h.length < 10 || h[0] != 'I' || h[1] != 'D' || h[2] != '3'
                || ((h[6] | h[7] | h[8] | h[9]) & 0x80) != 0) {
                in.reset();
                return skipped;
            }
            long size = ((h[6] & 0x7F) << 21) | ((h[7] & 0x7F) << 14) | ((h[8] & 0x7F) << 7) | (h[9] & 0x7F);
            if ((h[5] & 0x10) != 0) size += 10;
            long done = 0;
            while (done < size) {
                long n = in.skip(size - done);
                if (n <= 0) {
                    if (in.read() < 0) throw new AudioImportException("This MP3 ends inside its ID3 tag; there is no audio.");
                    n = 1;
                }
                done += n;
            }
            skipped += 10 + size;
        }
    }

    /** End of the audio data: the file length minus any ID3v1 and APEv2 tags at the end. */
    private static long audioEnd(Path file, long size) throws IOException {
        long end = size;
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            if (end >= 128) {
                byte[] tag = new byte[3];
                raf.seek(end - 128);
                raf.readFully(tag);
                if (tag[0] == 'T' && tag[1] == 'A' && tag[2] == 'G') end -= 128;
            }
            if (end >= 32) {
                byte[] footer = new byte[32];
                raf.seek(end - 32);
                raf.readFully(footer);
                if (new String(footer, 0, 8, StandardCharsets.ISO_8859_1).equals("APETAGEX")) {
                    long tagSize = (footer[12] & 0xFFL) | (footer[13] & 0xFFL) << 8 | (footer[14] & 0xFFL) << 16 | (footer[15] & 0xFFL) << 24;
                    long total = tagSize + ((footer[23] & 0x80) != 0 ? 32 : 0);
                    if (total <= end) end -= total;
                }
            }
        }
        return end;
    }

    /**
     * What the Xing/Info/VBRI frame says.
     *
     * @param skip            decoded samples to drop at the start (the tag frame itself, plus delays)
     * @param trim            decoded samples to drop at the end (encoder padding)
     * @param expectedSamples samples left after trimming, or 0 if the frame count is unknown
     */
    private record Gapless(long skip, long trim, long expectedSamples) {
        private static final Gapless NONE = new Gapless(0, 0, 0);

        static Gapless read(byte[] b, int at, Header h) {
            if (h.layer != 3) return NONE;
            int tag = at + 4 + (h.crc ? 2 : 0) + (h.mode == 3 ? 17 : 32);
            if (at + 36 + 4 <= b.length && new String(b, at + 36, 4, StandardCharsets.ISO_8859_1).equals("VBRI")) {
                return new Gapless(1152, 0, 0);
            }
            if (tag + 8 > b.length) return NONE;
            String id = new String(b, tag, 4, StandardCharsets.ISO_8859_1);
            if (!id.equals("Xing") && !id.equals("Info")) return NONE;
            int flags = readInt(b, tag + 4);
            int p = tag + 8;
            long frames = 0;
            if ((flags & 1) != 0 && p + 4 <= b.length) frames = readInt(b, p) & 0xFFFFFFFFL;
            if ((flags & 1) != 0) p += 4;
            if ((flags & 2) != 0) p += 4;
            if ((flags & 4) != 0) p += 100;
            if ((flags & 8) != 0) p += 4;
            long skip = 1152, trim = 0;
            if (p + 24 <= b.length) {
                String encoder = new String(b, p, 4, StandardCharsets.ISO_8859_1);
                if (encoder.equals("LAME") || encoder.equals("Lavf") || encoder.equals("Lavc")) {
                    int delay = ((b[p + 21] & 0xFF) << 4) | ((b[p + 22] & 0xF0) >> 4);
                    int padding = ((b[p + 22] & 0x0F) << 8) | (b[p + 23] & 0xFF);
                    skip += delay + DECODER_DELAY;
                    trim = Math.max(0, padding - DECODER_DELAY);
                }
            }
            long expected = frames > 0 ? Math.max(0, (frames + 1) * 1152 - skip - trim) : 0;
            return new Gapless(skip, trim, expected);
        }

        private static int readInt(byte[] b, int at) {
            return ((b[at] & 0xFF) << 24) | ((b[at + 1] & 0xFF) << 16) | ((b[at + 2] & 0xFF) << 8) | (b[at + 3] & 0xFF);
        }
    }

    /** Drops the first {@code skip} samples and holds back the last {@code trim}, which are never passed on. */
    private static final class Trimmer {
        private final MonoBuilder out;
        private final float[] held;
        private long toSkip;
        private int count;
        private int head;

        Trimmer(MonoBuilder out, long skip, long trim) {
            this.out = out;
            this.toSkip = skip;
            this.held = new float[(int) trim];
        }

        void add(float sample) throws AudioImportException {
            if (toSkip > 0) {
                toSkip--;
            } else if (held.length == 0) {
                out.add(sample);
            } else if (count == held.length) {
                out.add(held[head]);
                held[head] = sample;
                head = (head + 1) % held.length;
            } else {
                held[(head + count) % held.length] = sample;
                count++;
            }
        }
    }

    /**
     * Reads at most {@code limit} bytes. A bulk read at the end throws {@link EOFException} instead of
     * returning -1: javamp3 reads a frame's main data with a loop that adds up {@code read} results
     * and would otherwise count the -1 backwards, so a file cut inside a frame now ends decoding
     * cleanly. Single-byte reads (used to look for the next frame) still return -1 at the end.
     */
    private static final class BoundedInputStream extends InputStream {
        private final InputStream in;
        private long left;

        BoundedInputStream(InputStream in, long limit) {
            this.in = in;
            this.left = limit;
        }

        @Override
        public int read() throws IOException {
            if (left <= 0) return -1;
            int b = in.read();
            if (b >= 0) left--;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            if (left <= 0) throw new EOFException("MP3 data ends inside a frame");
            int n = in.read(b, off, (int) Math.min(len, left));
            if (n < 0) throw new EOFException("MP3 data ends inside a frame");
            left -= n;
            return n;
        }
    }
}
