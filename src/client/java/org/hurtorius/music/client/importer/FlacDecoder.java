package org.hurtorius.music.client.importer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;

/**
 * A clean-room FLAC decoder written from the format specification (RFC 9639): STREAMINFO, frame
 * headers with CRC-8, CONSTANT / VERBATIM / FIXED / LPC subframes, Rice and Rice2 partitions
 * including escaped (raw) partitions, wasted bits, independent / left-side / side-right / mid-side
 * channels, and the frame CRC-16. Sample sizes from 4 to 24 bits. The decoded samples are checked
 * against the STREAMINFO MD5 signature when the encoder stored one.
 */
final class FlacDecoder {
    private static final Logger LOG = LoggerFactory.getLogger("Music");
    private static final int[] FIXED_RATES = {0, 88200, 176400, 192000, 8000, 16000, 22050, 24000, 32000, 44100, 48000, 96000};
    private static final int[] SAMPLE_SIZES = {0, 8, 12, -1, 16, 20, 24, 32};

    private final Bits in;
    private int sampleRate, channels, bitsPerSample;
    private long totalSamples;
    private byte[] md5;
    private int maxBlockSize;
    private int[][] block = new int[0][];
    private long decoded;

    private FlacDecoder(InputStream in) {
        this.in = new Bits(in);
    }

    /** Decodes a FLAC stream positioned at its "fLaC" marker (or an ID3v2 tag in front of it). */
    static DecodedAudio decode(InputStream in, int maxRate, Progress progress) throws IOException {
        FlacDecoder d = new FlacDecoder(in);
        try {
            d.readMetadata();
            return d.readFrames(maxRate, progress);
        } catch (EOFException e) {
            throw new AudioImportException(String.format(Locale.ROOT, "This FLAC file is cut short: it ends at %.1f s%s.",
                d.seconds(), d.totalSamples > 0 ? String.format(Locale.ROOT, " of %.1f s", d.totalSamples / (double) d.sampleRate) : ""), e);
        }
    }

    private double seconds() {
        return sampleRate > 0 ? decoded / (double) sampleRate : 0;
    }

    private AudioImportException damaged(String what) {
        return new AudioImportException(String.format(Locale.ROOT, "This FLAC file is damaged at %.1f s (%s).", seconds(), what));
    }

    private void readMetadata() throws IOException {
        int b0 = in.peekByte(0), b1 = in.peekByte(1), b2 = in.peekByte(2);
        if (b0 == 'I' && b1 == 'D' && b2 == '3') {
            in.readUInt(24);
            in.readUInt(16);
            int flags = in.readUInt(8);
            int size = 0;
            for (int i = 0; i < 4; i++) size = (size << 7) | (in.readUInt(8) & 0x7F);
            in.skipBytes(size + ((flags & 0x10) != 0 ? 10 : 0));
        }
        if (in.readUInt(8) != 'f' || in.readUInt(8) != 'L' || in.readUInt(8) != 'a' || in.readUInt(8) != 'C') {
            throw new AudioImportException("This is not a FLAC file (no fLaC marker).");
        }
        boolean last = false;
        boolean first = true;
        while (!last) {
            last = in.readUInt(1) == 1;
            int type = in.readUInt(7);
            int length = in.readUInt(24);
            if (first && type != 0) throw new AudioImportException("This FLAC file is damaged: STREAMINFO is missing.");
            if (type == 127) throw new AudioImportException("This FLAC file is damaged: invalid metadata block.");
            if (type == 0) {
                if (length != 34) throw new AudioImportException("This FLAC file is damaged: STREAMINFO has the wrong size.");
                in.readUInt(16);
                maxBlockSize = in.readUInt(16);
                in.readUInt(24);
                in.readUInt(24);
                sampleRate = in.readUInt(20);
                channels = in.readUInt(3) + 1;
                bitsPerSample = in.readUInt(5) + 1;
                totalSamples = ((long) in.readUInt(4) << 32) | (in.readUInt(32) & 0xFFFFFFFFL);
                md5 = new byte[16];
                for (int i = 0; i < 16; i++) md5[i] = (byte) in.readUInt(8);
                boolean empty = true;
                for (byte b : md5) empty &= b == 0;
                if (empty) md5 = null;
            } else {
                in.skipBytes(length);
            }
            first = false;
        }
        if (sampleRate == 0) throw new AudioImportException("This FLAC file has no sample rate.");
        if (bitsPerSample < 4 || bitsPerSample > 24) {
            throw new AudioImportException("This FLAC file uses " + bitsPerSample + "-bit samples; 4 to 24 bits are supported. Convert it to 24-bit FLAC or WAV.");
        }
    }

    private DecodedAudio readFrames(int maxRate, Progress progress) throws IOException {
        MonoBuilder out = new MonoBuilder(sampleRate, maxRate, totalSamples, progress);
        MessageDigest digest = md5 == null ? null : md5();
        byte[] md5Bytes = new byte[0];
        int bytesPerSample = (bitsPerSample + 7) / 8;
        double scale = 1.0 / (channels * (double) (1 << (bitsPerSample - 1)));
        while (totalSamples == 0 || decoded < totalSamples) {
            if (!in.hasFrame()) {
                if (in.atEnd() || in.trailingTag()) break;
                throw damaged("data between frames is not a frame");
            }
            int blockSize = frame();
            int[][] s = block;
            for (int i = 0; i < blockSize; i++) {
                long sum = 0;
                for (int c = 0; c < channels; c++) sum += s[c][i];
                out.add((float) (sum * scale));
            }
            if (digest != null) {
                int need = blockSize * channels * bytesPerSample;
                if (md5Bytes.length < need) md5Bytes = new byte[need];
                int at = 0;
                for (int i = 0; i < blockSize; i++) {
                    for (int c = 0; c < channels; c++) {
                        int v = s[c][i];
                        for (int k = 0; k < bytesPerSample; k++) md5Bytes[at++] = (byte) (v >> (8 * k));
                    }
                }
                digest.update(md5Bytes, 0, need);
            }
            decoded += blockSize;
        }
        if (totalSamples > 0 && decoded < totalSamples) throw new EOFException("FLAC stream ended early");
        if (digest != null) {
            if (!MessageDigest.isEqual(digest.digest(), md5)) {
                throw new AudioImportException("This FLAC file is damaged: its audio does not match the checksum stored in it.");
            }
        } else {
            LOG.debug("FLAC stream has no MD5 signature; skipped the check");
        }
        return out.finish();
    }

    private static MessageDigest md5() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is required by every Java runtime", e);
        }
    }

    /** Decodes one frame into {@link #block}; returns its block size. */
    private int frame() throws IOException {
        in.startCrc();
        in.readUInt(15);
        in.readUInt(1);
        int sizeCode = in.readUInt(4);
        int rateCode = in.readUInt(4);
        int assignment = in.readUInt(4);
        int sizeBitsCode = in.readUInt(3);
        if (in.readUInt(1) != 0) throw damaged("reserved header bit set");
        int lead = in.readUInt(8);
        int extra = lead < 0x80 ? 0 : lead >= 0xC0 && lead < 0xFF ? Integer.numberOfLeadingZeros(~lead << 24) - 1 : -1;
        if (extra < 0) throw damaged("bad frame number");
        for (int i = 0; i < extra; i++) {
            if ((in.readUInt(8) & 0xC0) != 0x80) throw damaged("bad frame number");
        }
        int blockSize = switch (sizeCode) {
            case 0 -> throw damaged("reserved block size");
            case 1 -> 192;
            case 2, 3, 4, 5 -> 576 << (sizeCode - 2);
            case 6 -> in.readUInt(8) + 1;
            case 7 -> in.readUInt(16) + 1;
            default -> 256 << (sizeCode - 8);
        };
        int rate = switch (rateCode) {
            case 0 -> sampleRate;
            case 12 -> in.readUInt(8) * 1000;
            case 13 -> in.readUInt(16);
            case 14 -> in.readUInt(16) * 10;
            case 15 -> throw damaged("invalid sample rate code");
            default -> FIXED_RATES[rateCode];
        };
        int bits = sizeBitsCode == 0 ? bitsPerSample : SAMPLE_SIZES[sizeBitsCode];
        if (bits < 0) throw damaged("reserved sample size");
        int expectedCrc8 = in.crc8();
        if (in.readUInt(8) != expectedCrc8) throw damaged("frame header checksum mismatch");
        if (rate != sampleRate) throw damaged("sample rate changes mid-stream");
        if (bits != bitsPerSample) throw damaged("sample size changes mid-stream");
        int frameChannels = assignment < 8 ? assignment + 1 : assignment <= 10 ? 2 : -1;
        if (frameChannels < 0) throw damaged("reserved channel assignment");
        if (frameChannels != channels) throw damaged("channel count changes mid-stream");
        if (maxBlockSize >= 16 && blockSize > maxBlockSize) throw damaged("block larger than STREAMINFO allows");

        if (block.length != channels || block[0].length < blockSize) block = new int[channels][Math.max(blockSize, maxBlockSize)];
        for (int c = 0; c < channels; c++) {
            boolean side = (assignment == 8 && c == 1) || (assignment == 9 && c == 0) || (assignment == 10 && c == 1);
            subframe(block[c], blockSize, bits + (side ? 1 : 0));
        }
        in.alignToByte();
        int expectedCrc16 = in.crc16();
        if (in.readUInt(16) != expectedCrc16) throw damaged("frame checksum mismatch");

        int[] a = block[0], b = channels > 1 ? block[1] : null;
        switch (assignment) {
            case 8 -> {
                for (int i = 0; i < blockSize; i++) b[i] = a[i] - b[i];
            }
            case 9 -> {
                for (int i = 0; i < blockSize; i++) a[i] += b[i];
            }
            case 10 -> {
                for (int i = 0; i < blockSize; i++) {
                    int side = b[i];
                    int mid = (a[i] << 1) | (side & 1);
                    a[i] = (mid + side) >> 1;
                    b[i] = (mid - side) >> 1;
                }
            }
            default -> {
            }
        }
        return blockSize;
    }

    private void subframe(int[] out, int blockSize, int bits) throws IOException {
        if (in.readUInt(1) != 0) throw damaged("subframe padding bit set");
        int type = in.readUInt(6);
        int wasted = 0;
        if (in.readUInt(1) == 1) {
            wasted = in.readUnary() + 1;
            if (wasted >= bits) throw damaged("too many wasted bits");
            bits -= wasted;
        }
        if (type == 0) {
            Arrays.fill(out, 0, blockSize, in.readSigned(bits));
        } else if (type == 1) {
            for (int i = 0; i < blockSize; i++) out[i] = in.readSigned(bits);
        } else if (type >= 8 && type <= 12) {
            fixed(out, blockSize, bits, type - 8);
        } else if (type >= 32) {
            lpc(out, blockSize, bits, (type & 31) + 1);
        } else {
            throw damaged("reserved subframe type " + type);
        }
        if (wasted > 0) {
            for (int i = 0; i < blockSize; i++) out[i] <<= wasted;
        }
    }

    private void fixed(int[] out, int blockSize, int bits, int order) throws IOException {
        if (order > blockSize) throw damaged("predictor order larger than the block");
        for (int i = 0; i < order; i++) out[i] = in.readSigned(bits);
        residual(out, blockSize, order);
        switch (order) {
            case 1 -> {
                for (int i = 1; i < blockSize; i++) out[i] += out[i - 1];
            }
            case 2 -> {
                for (int i = 2; i < blockSize; i++) out[i] += 2 * out[i - 1] - out[i - 2];
            }
            case 3 -> {
                for (int i = 3; i < blockSize; i++) out[i] += 3 * out[i - 1] - 3 * out[i - 2] + out[i - 3];
            }
            case 4 -> {
                for (int i = 4; i < blockSize; i++) out[i] += 4 * out[i - 1] - 6 * out[i - 2] + 4 * out[i - 3] - out[i - 4];
            }
            default -> {
            }
        }
    }

    private void lpc(int[] out, int blockSize, int bits, int order) throws IOException {
        if (order > blockSize) throw damaged("predictor order larger than the block");
        for (int i = 0; i < order; i++) out[i] = in.readSigned(bits);
        int precision = in.readUInt(4) + 1;
        if (precision == 16) throw damaged("invalid coefficient precision");
        int shift = in.readSigned(5);
        if (shift < 0) throw damaged("negative prediction shift");
        int[] coefficients = new int[order];
        for (int j = 0; j < order; j++) coefficients[j] = in.readSigned(precision);
        residual(out, blockSize, order);
        for (int i = order; i < blockSize; i++) {
            long sum = 0;
            for (int j = 0; j < order; j++) sum += (long) coefficients[j] * out[i - 1 - j];
            out[i] += (int) (sum >> shift);
        }
    }

    private void residual(int[] out, int blockSize, int order) throws IOException {
        int method = in.readUInt(2);
        if (method > 1) throw damaged("reserved residual coding");
        int parameterBits = method == 0 ? 4 : 5;
        int escape = method == 0 ? 15 : 31;
        int partitionOrder = in.readUInt(4);
        int partitions = 1 << partitionOrder;
        int perPartition = blockSize >> partitionOrder;
        if (perPartition << partitionOrder != blockSize || perPartition < order) throw damaged("invalid residual partitions");
        int i = order;
        for (int p = 0; p < partitions; p++) {
            int count = p == 0 ? perPartition - order : perPartition;
            int k = in.readUInt(parameterBits);
            if (k == escape) {
                int raw = in.readUInt(5);
                for (int j = 0; j < count; j++) out[i++] = raw == 0 ? 0 : in.readSigned(raw);
            } else {
                for (int j = 0; j < count; j++) {
                    long v = ((long) in.readUnary() << k) | (k == 0 ? 0 : in.readUInt(k));
                    out[i++] = (int) ((v >>> 1) ^ -(v & 1));
                }
            }
        }
    }

    /**
     * Bit reader over a buffered stream. Bytes are loaded only when a read needs them, so after every
     * read fewer than 8 loaded bits are unconsumed; the running CRC-8 and CRC-16 therefore cover
     * exactly the bytes read since {@link #startCrc()} once the reader is byte-aligned.
     */
    private static final class Bits {
        private static final int[] CRC8 = new int[256];
        private static final int[] CRC16 = new int[256];

        static {
            for (int i = 0; i < 256; i++) {
                int c8 = i;
                for (int k = 0; k < 8; k++) c8 = (c8 & 0x80) != 0 ? ((c8 << 1) ^ 0x07) & 0xFF : (c8 << 1) & 0xFF;
                CRC8[i] = c8;
                int c16 = i << 8;
                for (int k = 0; k < 8; k++) c16 = (c16 & 0x8000) != 0 ? ((c16 << 1) ^ 0x8005) & 0xFFFF : (c16 << 1) & 0xFFFF;
                CRC16[i] = c16;
            }
        }

        private final InputStream in;
        private final byte[] buffer = new byte[1 << 16];
        private int pos, limit;
        private boolean eof;
        private long cache;
        private int bits;
        private int crc8, crc16;

        Bits(InputStream in) {
            this.in = in;
        }

        private boolean fill(int needed) throws IOException {
            if (limit - pos >= needed) return true;
            if (eof) return false;
            System.arraycopy(buffer, pos, buffer, 0, limit - pos);
            limit -= pos;
            pos = 0;
            while (limit < needed && !eof) {
                int n = in.read(buffer, limit, buffer.length - limit);
                if (n < 0) eof = true;
                else limit += n;
            }
            return limit - pos >= needed;
        }

        private int nextByte() throws IOException {
            if (pos == limit && !fill(1)) throw new EOFException("End of FLAC data");
            int b = buffer[pos++] & 0xFF;
            crc8 = CRC8[crc8 ^ b];
            crc16 = ((crc16 << 8) & 0xFFFF) ^ CRC16[(crc16 >> 8) ^ b];
            return b;
        }

        int peekByte(int offset) throws IOException {
            return fill(offset + 1) ? buffer[pos + offset] & 0xFF : -1;
        }

        boolean atEnd() throws IOException {
            return bits == 0 && !fill(1);
        }

        /** True if a frame sync code starts at the current (byte-aligned) position. */
        boolean hasFrame() throws IOException {
            return bits == 0 && peekByte(0) == 0xFF && (peekByte(1) & 0xFE) == 0xF8;
        }

        /** True if only an ID3v1 tag (or zero padding) is left. */
        boolean trailingTag() throws IOException {
            if (bits != 0) return false;
            if (peekByte(0) == 'T' && peekByte(1) == 'A' && peekByte(2) == 'G' && !fill(129)) return true;
            for (int i = 0; fill(i + 1); i++) {
                if (buffer[pos + i] != 0) return false;
                if (i > 65_000) return false;
            }
            return true;
        }

        void startCrc() {
            crc8 = 0;
            crc16 = 0;
        }

        int crc8() {
            return crc8;
        }

        int crc16() {
            return crc16;
        }

        int readUInt(int n) throws IOException {
            if (n == 0) return 0;
            while (bits < n) {
                cache = (cache << 8) | nextByte();
                bits += 8;
            }
            bits -= n;
            return (int) ((cache >>> bits) & ((1L << n) - 1));
        }

        int readSigned(int n) throws IOException {
            int v = readUInt(n);
            return (v << (32 - n)) >> (32 - n);
        }

        /** Counts zero bits up to and including the next one bit. */
        int readUnary() throws IOException {
            int zeros = 0;
            while (true) {
                if (bits == 0) {
                    cache = nextByte();
                    bits = 8;
                }
                int valid = (int) (cache & ((1 << bits) - 1));
                if (valid == 0) {
                    zeros += bits;
                    bits = 0;
                    if (zeros > (1 << 30)) throw new IOException("Unary code too long");
                    continue;
                }
                int top = 31 - Integer.numberOfLeadingZeros(valid);
                zeros += bits - 1 - top;
                bits = top;
                return zeros;
            }
        }

        void alignToByte() {
            bits = 0;
        }

        void skipBytes(long n) throws IOException {
            for (long i = 0; i < n; i++) readUInt(8);
        }
    }
}
