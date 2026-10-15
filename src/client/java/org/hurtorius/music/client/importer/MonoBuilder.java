package org.hurtorius.music.client.importer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Collects a decoder's output as mono samples at no more than a target rate. Decoders push one
 * downmixed sample per frame; this class resamples on the fly when the source rate is higher than
 * allowed, enforces {@link AudioDecoder#MAX_SECONDS}, replaces non-finite samples with silence and
 * checks for cancellation as the samples arrive. When the decoder knows the length up front the
 * output is written into one exactly sized array, so the result needs no final copy.
 */
final class MonoBuilder {
    private static final int BLOCK = 1 << 20;
    /** Largest single array allocated up front from a header's length (192 MB: 30 minutes at 22 kHz). */
    private static final long MAX_PREALLOCATION = 48L << 20;

    private final int sourceRate;
    private final int outputRate;
    private final long maxSourceFrames;
    private final Progress progress;
    private final Resampler resampler;
    private final List<float[]> full = new ArrayList<>();
    private float[] current;
    private int fill;
    private long sourceFrames;

    /**
     * @param expectedSourceFrames frames the decoder expects at the source rate, or 0 if unknown;
     *                             only used to size storage and to reject over-long files early, so
     *                             decoders pass a header's length only when the file bears it out
     */
    MonoBuilder(int sourceRate, int maxRate, long expectedSourceFrames, Progress progress) throws AudioImportException {
        if (sourceRate < 1000 || sourceRate > 768_000) {
            throw new AudioImportException("Unsupported sample rate: " + sourceRate + " Hz.");
        }
        this.sourceRate = sourceRate;
        this.outputRate = Math.min(sourceRate, Math.max(1000, maxRate));
        this.maxSourceFrames = (long) AudioDecoder.MAX_SECONDS * sourceRate;
        this.progress = progress;
        if (expectedSourceFrames > maxSourceFrames) throw tooLong(expectedSourceFrames, true);
        this.resampler = outputRate == sourceRate ? null : new Resampler(sourceRate, outputRate, this::store);
        long expectedOutput = expectedSourceFrames > 0
            ? Resampler.outputLength(expectedSourceFrames, sourceRate, outputRate)
            : BLOCK;
        this.current = new float[(int) Math.max(1024, Math.min(expectedOutput, MAX_PREALLOCATION))];
    }

    /** Frames received so far, at the source rate. */
    long sourceFrames() {
        return sourceFrames;
    }

    /** Adds one (already downmixed) frame. */
    void add(float sample) throws AudioImportException {
        if (++sourceFrames > maxSourceFrames) throw tooLong(sourceFrames, false);
        if ((sourceFrames & 0x3FFF) == 0) progress.check();
        if (resampler == null) store(sample);
        else resampler.accept(sample);
    }

    private void store(float sample) {
        if (fill == current.length) {
            full.add(current);
            current = new float[BLOCK];
            fill = 0;
        }
        current[fill++] = Float.isFinite(sample) ? sample : 0f;
    }

    /** Flushes the resampler and returns the collected audio. */
    DecodedAudio finish() throws AudioImportException {
        if (resampler != null) resampler.finish();
        progress.check();
        long size = fill;
        for (float[] block : full) size += block.length;
        if (size == 0) throw new AudioImportException("The file contains no audio.");
        if (full.isEmpty() && fill == current.length) return new DecodedAudio(current, outputRate);
        float[] out = new float[(int) size];
        int at = 0;
        for (float[] block : full) {
            System.arraycopy(block, 0, out, at, block.length);
            at += block.length;
        }
        System.arraycopy(current, 0, out, at, fill);
        full.clear();
        current = null;
        return new DecodedAudio(out, outputRate);
    }

    private AudioImportException tooLong(long frames, boolean exact) {
        String length = exact
            ? String.format(Locale.ROOT, "%.1f minutes long", frames / (double) sourceRate / 60.0)
            : "longer than " + AudioDecoder.MAX_SECONDS / 60 + " minutes";
        return new AudioImportException("This audio is " + length + "; at most " + AudioDecoder.MAX_SECONDS / 60
            + " minutes can be imported. Cut it into shorter parts first.");
    }
}
