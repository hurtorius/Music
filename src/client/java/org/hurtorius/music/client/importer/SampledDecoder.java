package org.hurtorius.music.client.importer;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.io.InputStream;

/**
 * WAV, AIFF and AU through {@code javax.sound.sampled}: integer PCM of 8, 16, 24 or 32 bits (signed
 * or unsigned, either byte order), 32/64-bit float, and the mu-law/A-law telephone encodings.
 */
final class SampledDecoder {
    private SampledDecoder() {}

    /**
     * @param in       the file, supporting mark/reset (the format readers probe the header)
     * @param kind     format name for messages ("WAV", "AIFF", "AU")
     * @param fileSize the file's size in bytes
     */
    static DecodedAudio decode(InputStream in, String kind, long fileSize, int maxRate, Progress progress) throws IOException {
        AudioInputStream source;
        try {
            source = AudioSystem.getAudioInputStream(in);
        } catch (UnsupportedAudioFileException e) {
            throw new AudioImportException("This " + kind + " file uses a layout or compression that cannot be read ("
                + e.getMessage() + "). Save it as plain PCM " + kind + ", FLAC or OGG instead.", e);
        }
        // The header's length is believed only if the file can hold it: a WAV written to a pipe (or by a
        // recorder that was stopped) keeps 0xFFFFFFFF there, which reads as hours. Then it is read to its end.
        long claimed = source.getFrameLength();
        int frameBytes = source.getFormat().getFrameSize();
        long expected = claimed > 0 && frameBytes > 0 && claimed <= fileSize / frameBytes ? claimed : 0;
        try (AudioInputStream audio = readable(source, kind)) {
            return convert(audio, kind, expected, maxRate, progress);
        }
    }

    /** The stream itself if it is plain PCM or float, otherwise a 16-bit PCM conversion of it. */
    private static AudioInputStream readable(AudioInputStream audio, String kind) throws AudioImportException {
        AudioFormat.Encoding encoding = audio.getFormat().getEncoding();
        if (AudioFormat.Encoding.PCM_SIGNED.equals(encoding) || AudioFormat.Encoding.PCM_UNSIGNED.equals(encoding)
            || AudioFormat.Encoding.PCM_FLOAT.equals(encoding)) {
            return audio;
        }
        if (AudioFormat.Encoding.ULAW.equals(encoding) || AudioFormat.Encoding.ALAW.equals(encoding)) {
            try {
                return AudioSystem.getAudioInputStream(AudioFormat.Encoding.PCM_SIGNED, audio);
            } catch (IllegalArgumentException e) {
                throw new AudioImportException("This " + kind + " file's " + encoding + " audio cannot be converted.", e);
            }
        }
        throw new AudioImportException("This " + kind + " file is compressed as " + encoding
            + ", which cannot be read. Save it as plain PCM " + kind + ", FLAC or OGG instead.");
    }

    private static DecodedAudio convert(AudioInputStream audio, String kind, long expected, int maxRate, Progress progress) throws IOException {
        AudioFormat format = audio.getFormat();
        int channels = format.getChannels();
        int bits = format.getSampleSizeInBits();
        boolean isFloat = AudioFormat.Encoding.PCM_FLOAT.equals(format.getEncoding());
        boolean unsigned = AudioFormat.Encoding.PCM_UNSIGNED.equals(format.getEncoding());
        boolean bigEndian = format.isBigEndian();
        int bytes = (bits + 7) / 8;
        if (channels < 1 || channels > 32) throw new AudioImportException("This " + kind + " file has an unsupported channel count: " + channels + ".");
        if (isFloat ? bits != 32 && bits != 64 : bits != 8 && bits != 16 && bits != 24 && bits != 32) {
            throw new AudioImportException("This " + kind + " file uses " + bits + "-bit " + (isFloat ? "float" : "integer")
                + " samples; 8, 16, 24 and 32-bit integer or 32/64-bit float are supported.");
        }
        int frameSize = format.getFrameSize() > 0 ? format.getFrameSize() : channels * bytes;
        if (frameSize < channels * bytes) throw new AudioImportException("This " + kind + " file has an invalid frame size.");
        float rate = format.getSampleRate();
        if (!(rate >= 1000 && rate <= 768_000)) throw new AudioImportException("This " + kind + " file has an unsupported sample rate: " + rate + " Hz.");

        MonoBuilder out = new MonoBuilder(Math.round(rate), maxRate, expected, progress);
        byte[] buffer = new byte[frameSize * 4096];
        int carry = 0;
        float scale = 1f / channels;
        while (true) {
            int n = audio.read(buffer, carry, buffer.length - carry);
            if (n < 0) break;
            int available = carry + n;
            int frames = available / frameSize;
            for (int f = 0; f < frames; f++) {
                int at = f * frameSize;
                float sum = 0;
                for (int c = 0; c < channels; c++) {
                    sum += sample(buffer, at + c * bytes, bits, isFloat, unsigned, bigEndian);
                }
                out.add(sum * scale);
            }
            carry = available - frames * frameSize;
            System.arraycopy(buffer, frames * frameSize, buffer, 0, carry);
        }
        return out.finish();
    }

    private static float sample(byte[] b, int at, int bits, boolean isFloat, boolean unsigned, boolean bigEndian) {
        if (isFloat) {
            if (bits == 32) return Float.intBitsToFloat((int) integer(b, at, 4, bigEndian));
            return (float) Double.longBitsToDouble(integer(b, at, 8, bigEndian));
        }
        int bytes = bits / 8;
        long raw = integer(b, at, bytes, bigEndian);
        long value = unsigned ? raw - (1L << (bits - 1)) : (raw << (64 - bits)) >> (64 - bits);
        return (float) (value / (double) (1L << (bits - 1)));
    }

    private static long integer(byte[] b, int at, int bytes, boolean bigEndian) {
        long v = 0;
        if (bigEndian) {
            for (int i = 0; i < bytes; i++) v = (v << 8) | (b[at + i] & 0xFF);
        } else {
            for (int i = bytes - 1; i >= 0; i--) v = (v << 8) | (b[at + i] & 0xFF);
        }
        return v;
    }
}
