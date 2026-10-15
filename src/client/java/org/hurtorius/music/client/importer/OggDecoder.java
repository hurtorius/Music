package org.hurtorius.music.client.importer;

import net.minecraft.client.sounds.JOrbisAudioStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Ogg Vorbis through Minecraft's own {@link JOrbisAudioStream}, after {@link VorbisHeaders} has
 * checked the headers JOrbis would otherwise trust. Other codecs in an Ogg container (Opus, FLAC,
 * Speex, Theora video) are named precisely instead of failing with a decoder error.
 */
final class OggDecoder {
    private static final Logger LOG = LoggerFactory.getLogger("Music");
    private OggDecoder() {}

    /**
     * @param in   the file from its first byte, supporting mark/reset
     * @param file the same file, read separately to check its headers and find the stream length for sizing
     */
    static DecodedAudio decode(InputStream in, Path file, int maxRate, Progress progress) throws IOException {
        checkCodec(in);
        long firstGranule = VorbisHeaders.check(file);
        JOrbisAudioStream stream;
        try {
            stream = new JOrbisAudioStream(in);
        } catch (IOException | RuntimeException e) {
            throw new AudioImportException("This OGG file's Vorbis headers are damaged (" + e.getMessage() + ").", e);
        }
        try (stream) {
            int channels = stream.getFormat().getChannels();
            int rate = Math.round(stream.getFormat().getSampleRate());
            if (channels < 1 || channels > 255) throw new AudioImportException("This OGG file has an unsupported channel count: " + channels + ".");
            MonoBuilder out = new MonoBuilder(rate, maxRate, length(firstGranule, lastGranule(file)), progress);
            Downmix downmix = new Downmix(out, channels);
            try {
                while (stream.readChunk(downmix::accept)) {
                    if (downmix.failure != null) throw downmix.failure;
                }
            } catch (AudioImportException e) {
                throw e;
            } catch (IOException | RuntimeException e) {
                if (e instanceof java.util.concurrent.CancellationException c) throw c;
                throw new AudioImportException(String.format(java.util.Locale.ROOT,
                    "This OGG file is damaged or has several chained streams; decoding stopped at %.1f s (%s).",
                    out.sourceFrames() / (double) rate, e.getMessage()), e);
            }
            if (downmix.failure != null) throw downmix.failure;
            return out.finish();
        }
    }

    /** Interleaved floats to mono frames; remembers a limit failure because the callback cannot throw it. */
    private static final class Downmix {
        private final MonoBuilder out;
        private final int channels;
        private final float scale;
        private float sum;
        private int channel;
        AudioImportException failure;

        Downmix(MonoBuilder out, int channels) {
            this.out = out;
            this.channels = channels;
            this.scale = 1f / channels;
        }

        void accept(float sample) {
            if (failure != null) return;
            sum += sample;
            if (++channel == channels) {
                try {
                    out.add(sum * scale);
                } catch (AudioImportException e) {
                    failure = e;
                }
                sum = 0;
                channel = 0;
            }
        }
    }

    /** Rejects non-Vorbis codecs by the first packet's signature. */
    private static void checkCodec(InputStream in) throws IOException {
        in.mark(512);
        byte[] head = in.readNBytes(300);
        in.reset();
        if (head.length < 28 || head[0] != 'O' || head[1] != 'g' || head[2] != 'g' || head[3] != 'S') {
            throw new AudioImportException("This OGG file is damaged: it does not start with an Ogg page.");
        }
        int segments = head[26] & 0xFF;
        int payload = 27 + segments;
        if (payload + 8 > head.length) return;
        String signature = new String(head, payload, 8, StandardCharsets.ISO_8859_1);
        if (signature.startsWith("\u0001vorbis")) return;
        String codec = signature.startsWith("OpusHead") ? "Opus"
            : signature.startsWith("\u007fFLAC") ? "FLAC"
            : signature.startsWith("Speex") ? "Speex"
            : signature.startsWith("\u0080theora") ? "Theora video"
            : null;
        if (codec != null) {
            throw new AudioImportException("This OGG file contains " + codec + ", not Vorbis. Convert it to OGG Vorbis, FLAC, WAV or MP3.");
        }
        throw new AudioImportException("This OGG file does not contain Vorbis audio.");
    }

    /**
     * The stream's length in samples from the granule positions of its first and last audio pages. A
     * recording made from the middle of a broadcast keeps the broadcast's running count, so its
     * positions start far above 0; then only the span between those pages counts, which leaves out the
     * first page's own samples (decoding enforces the limit on the samples themselves anyway).
     */
    private static long length(long firstGranule, long lastGranule) {
        if (firstGranule <= VorbisHeaders.MAX_PAGE_SAMPLES) return lastGranule;
        return Math.max(0, lastGranule - firstGranule);
    }

    /** Granule position of the last page (where the stream ends, in samples), or 0 if it cannot be found. */
    private static long lastGranule(Path file) {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long length = raf.length();
            int window = (int) Math.min(length, 65_536 + 27);
            byte[] tail = new byte[window];
            raf.seek(length - window);
            raf.readFully(tail);
            for (int i = window - 27; i >= 0; i--) {
                if (tail[i] == 'O' && tail[i + 1] == 'g' && tail[i + 2] == 'g' && tail[i + 3] == 'S' && tail[i + 4] == 0) {
                    long granule = 0;
                    for (int b = 13; b >= 6; b--) granule = (granule << 8) | (tail[i + b] & 0xFF);
                    return Math.max(0, granule);
                }
            }
        } catch (IOException e) {
            // Only a sizing hint: decoding reads the whole stream regardless and reports any failure.
            LOG.debug("Could not read the end of {} to size its buffer", file, e);
            return 0;
        }
        return 0;
    }
}
