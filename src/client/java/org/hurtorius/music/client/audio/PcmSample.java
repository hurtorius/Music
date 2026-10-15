package org.hurtorius.music.client.audio;

import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.lang.ref.Cleaner;
import java.nio.ShortBuffer;

/**
 * Decoded mono 16-bit PCM in native memory, for OpenAL. A sample that does not keep its PCM (the grand
 * piano's, which its {@link GrandPianoBank} can decode again from the bytes it keeps) is let go of once
 * OpenAL holds its own copy, as Minecraft does with its own sounds, so the piano is not held in memory
 * twice, and is filled again in place when a new audio device needs it ({@link #refillFrom}); other
 * samples keep theirs, to be uploaded again after the audio device changes. The OpenAL buffer name and
 * the context generation it belongs to are owned by {@link AudioEngine} and only touched under its
 * OpenAL lock.
 *
 * <p>A sample may end in a {@link Loop}: OpenAL repeats it once playback gets there, and the engine fades
 * the note at the loop's rate meanwhile, so a held note dies away as the string does instead of
 * stopping where the recording was cut.</p>
 */
public final class PcmSample {
    /**
     * Frames before a loop's end that are blended into the frames leading up to its start, so the
     * wrap joins two neighbouring frames of the decoded sound even where the codec's noise differs.
     */
    public static final int SEAM_FRAMES = 512;
    private static final Cleaner CLEANER = Cleaner.create();

    /**
     * Where a sample repeats, and how fast the note goes on fading while it does.
     *
     * @param start            the loop's first frame
     * @param end              the frame after its last, where the sample ends
     * @param decayDbPerSecond how fast the note keeps fading once the loop starts
     */
    public record Loop(int start, int end, float decayDbPerSecond) {
        public Loop {
            if (start < SEAM_FRAMES) throw new IllegalArgumentException("a loop must start at least " + SEAM_FRAMES + " frames in, not at " + start);
            if (end - start < 2 * SEAM_FRAMES) throw new IllegalArgumentException("a loop must be at least " + 2 * SEAM_FRAMES + " frames long, not " + (end - start));
            if (!(decayDbPerSecond > 0) || !Float.isFinite(decayDbPerSecond)) {
                throw new IllegalArgumentException("a loop's decay must be a positive number of dB per second, not " + decayDbPerSecond);
            }
        }
    }

    public final String name;
    public final int sampleRate;
    private final int frames;
    private final @Nullable Loop loop;
    private final boolean keep;
    private final Pcm pcm;
    /** OpenAL buffer name in context {@link #generation}; 0 when not uploaded. */
    int buffer;
    int generation;

    private PcmSample(String name, int sampleRate, short[] data, int frames, @Nullable Loop loop, boolean keep) {
        if (sampleRate <= 0) throw new IllegalArgumentException("sample rate must be positive: " + sampleRate);
        if (frames <= 0) throw new IllegalArgumentException(name + " has no audio");
        if (loop != null && loop.end() != frames) throw new IllegalArgumentException(name + ": its loop ends at " + loop.end() + ", the sound at " + frames);
        this.name = name;
        this.sampleRate = sampleRate;
        this.frames = frames;
        this.loop = loop;
        this.keep = keep;
        ShortBuffer copy = MemoryUtil.memAllocShort(frames);
        copy.put(data, 0, frames).flip();
        if (loop != null) closeLoop(copy, loop);
        this.pcm = new Pcm(copy);
        // Frees whatever PCM the sample holds once it is no longer reachable (refilled and never let go of again, say).
        CLEANER.register(this, pcm);
    }

    /** Copies {@code frames} samples of {@code data} into native memory, kept for the life of the sample. */
    public static PcmSample of(String name, int sampleRate, short[] data, int frames) {
        return new PcmSample(name, sampleRate, data, frames, null, true);
    }

    public static PcmSample of(String name, int sampleRate, short[] data) {
        return of(name, sampleRate, data, data.length);
    }

    /** A grand piano sample: its PCM goes once uploaded, and its loop (if any) ends where the sample does. */
    public static PcmSample forBank(String name, int sampleRate, short[] data, int frames, @Nullable Loop loop) {
        return new PcmSample(name, sampleRate, data, frames, loop, false);
    }

    /** Blends the frames before the loop's end into those before its start: at the wrap the last frame played is the one before the start. */
    private static void closeLoop(ShortBuffer pcm, Loop loop) {
        for (int i = 0; i < SEAM_FRAMES; i++) {
            float w = (i + 1) / (float) SEAM_FRAMES;
            int at = loop.end() - SEAM_FRAMES + i, from = loop.start() - SEAM_FRAMES + i;
            pcm.put(at, (short) Math.round((1 - w) * pcm.get(at) + w * pcm.get(from)));
        }
    }

    public int frames() {
        return frames;
    }

    public long bytes() {
        return frames * 2L;
    }

    /** The loop the sample ends in, or null when it simply ends. */
    public @Nullable Loop loop() {
        return loop;
    }

    /** True when the PCM stays after it is uploaded; false for samples that are decoded again when needed. */
    public boolean keepsPcm() {
        return keep;
    }

    /** True until the PCM has been let go of. */
    public boolean hasPcm() {
        return pcm.present();
    }

    /** One sample. Throws {@link IllegalStateException} once the PCM has been let go of. */
    public short get(int frame) {
        return pcm.get(frame, name);
    }

    /** A view of the PCM for {@code alBufferData}, which only reads it; null once it has been let go of. Audio thread. */
    @Nullable ShortBuffer uploadView() {
        return pcm.view();
    }

    /** Frees the native PCM now (OpenAL has its own copy, or the sample is no longer used). Any thread; again is harmless. */
    public void release() {
        pcm.run();
    }

    /**
     * Takes the PCM of {@code decoded}, this sample's file decoded again, after this sample let go of its
     * own: a new audio context needs it uploaded once more. {@code decoded} is left without PCM. False,
     * with {@code decoded}'s PCM freed, when this sample has its PCM already. Any thread.
     *
     * @throws IllegalArgumentException when {@code decoded} is not the same sound (name, rate or length differ)
     */
    boolean refillFrom(PcmSample decoded) {
        if (!decoded.name.equals(name) || decoded.sampleRate != sampleRate || decoded.frames != frames) {
            decoded.release();
            throw new IllegalArgumentException(name + " decoded again as " + decoded.name + ", " + decoded.frames + " frames at " + decoded.sampleRate
                + " Hz, not " + frames + " at " + sampleRate);
        }
        ShortBuffer data = decoded.pcm.take();
        if (data == null) return false;
        if (pcm.put(data)) return true;
        MemoryUtil.memFree(data);
        return false;
    }

    @Override
    public String toString() {
        return name;
    }

    /** The native memory, freed once: by {@link #release()}, or by the cleaner when the sample is no longer reachable. */
    private static final class Pcm implements Runnable {
        private @Nullable ShortBuffer data;

        Pcm(ShortBuffer data) {
            this.data = data;
        }

        synchronized boolean present() {
            return data != null;
        }

        synchronized short get(int frame, String name) {
            ShortBuffer d = data;
            if (d == null) throw new IllegalStateException(name + ": its PCM was let go of after it went to OpenAL");
            return d.get(frame);
        }

        synchronized @Nullable ShortBuffer view() {
            return data == null ? null : data.duplicate();
        }

        /** Hands the memory over (it is no longer this one's to free), or null when there is none. */
        synchronized @Nullable ShortBuffer take() {
            ShortBuffer d = data;
            data = null;
            return d;
        }

        /** Holds {@code d} from now on, unless memory is held already (false: the caller still owns {@code d}). */
        synchronized boolean put(ShortBuffer d) {
            if (data != null) return false;
            data = d;
            return true;
        }

        @Override
        public synchronized void run() {
            if (data == null) return;
            MemoryUtil.memFree(data);
            data = null;
        }
    }
}
