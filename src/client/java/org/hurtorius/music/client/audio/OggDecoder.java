package org.hurtorius.music.client.audio;

import net.minecraft.client.sounds.JOrbisAudioStream;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * Decodes Ogg Vorbis into mono {@link PcmSample}s with Minecraft's own decoder ({@link JOrbisAudioStream}).
 * Multi-channel files are averaged to mono, because OpenAL only positions mono sources in the world.
 */
public final class OggDecoder {
    private OggDecoder() {}

    /**
     * Reads the whole stream (or its first {@code maxSeconds}) into a sample that keeps its PCM, and closes it.
     *
     * @throws IOException when the data is not Ogg Vorbis, is damaged, or holds no audio
     */
    public static PcmSample decode(String name, InputStream in, double maxSeconds) throws IOException {
        Mixdown mix = read(name, in, maxSeconds, Long.MAX_VALUE);
        return PcmSample.of(name, mix.rate, mix.data, mix.frames);
    }

    /**
     * Reads a grand piano sample, which lets go of its PCM once uploaded. A looped one is read up to its
     * loop's end (the file goes on past it, so the encoder treats that end as the middle of the stream).
     *
     * @throws IOException when the data is not Ogg Vorbis, is damaged, holds no audio or ends before its loop does
     */
    static PcmSample decodeForBank(String name, InputStream in, PcmSample.@Nullable Loop loop) throws IOException {
        Mixdown mix = read(name, in, Double.POSITIVE_INFINITY, loop == null ? Long.MAX_VALUE : loop.end());
        if (loop != null && mix.frames < loop.end()) throw new IOException(name + ": ends at frame " + mix.frames + ", before its loop ends at " + loop.end());
        return PcmSample.forBank(name, mix.rate, mix.data, mix.frames, loop);
    }

    private static Mixdown read(String name, InputStream in, double maxSeconds, long maxFrames) throws IOException {
        try (JOrbisAudioStream ogg = new JOrbisAudioStream(in)) {
            int channels = ogg.getFormat().getChannels();
            int rate = Math.round(ogg.getFormat().getSampleRate());
            if (channels < 1 || rate <= 0) throw new IOException(name + ": unsupported format " + ogg.getFormat());
            long limit = Math.min(maxFrames, Double.isFinite(maxSeconds) ? (long) (maxSeconds * rate) : Long.MAX_VALUE);
            Mixdown mix = new Mixdown(channels, rate, limit);
            while (!mix.full() && ogg.readChunk(mix::accept)) {
                // the consumer collects every decoded sample
            }
            if (mix.frames == 0) throw new IOException(name + ": no audio");
            return mix;
        }
    }

    /** Averages interleaved channels into 16-bit mono, growing as needed. */
    private static final class Mixdown {
        private final int channels;
        private final int rate;
        private final long limit;
        private short[] data = new short[1 << 16];
        private int frames;
        private int channel;
        private float sum;

        Mixdown(int channels, int rate, long limit) {
            this.channels = channels;
            this.rate = rate;
            this.limit = limit;
        }

        boolean full() {
            return frames >= limit;
        }

        void accept(float sample) {
            sum += sample;
            if (++channel < channels) return;
            float v = sum / channels;
            channel = 0;
            sum = 0;
            if (frames >= limit) return;
            if (frames == data.length) data = Arrays.copyOf(data, data.length * 2);
            data[frames++] = (short) Math.max(-32768, Math.min(32767, Math.round(v * 32767f)));
        }
    }
}
