package org.hurtorius.music.client.importer;

/**
 * How much a key's harmonic peaks stand out of the spectrum around them. The log spectrum minus
 * its own average over the surrounding 2 1/3 semitones leaves its fine structure: positive at
 * spectral peaks, negative between them, and close to zero everywhere for noise. Each key's value is
 * that fine structure averaged with the weights of the key's sustained template, so it is high when
 * the frame has peaks where the key's partials are, and near zero for noise, breath, drums and other
 * sounds without pitch. In natural-log units (1 is about 8.7 dB). Not thread-safe.
 */
final class Tonality {
    private static final float FLOOR = 1e-6f;
    private static final int RADIUS = 3;
    private static final float KEEP_FRACTION = 0.01f;
    private final int[][] bins;
    private final float[][] weights;
    private final float[] log = new float[LogSpectrum.BINS];
    private final float[] detail = new float[LogSpectrum.BINS];

    Tonality(PianoTemplates templates) {
        int keys = PianoTemplates.KEYS;
        bins = new int[keys][];
        weights = new float[keys][];
        for (int k = 0; k < keys; k++) {
            float[] w = templates.frame[k * PianoTemplates.PER_KEY + 1];
            float max = 0;
            for (float v : w) max = Math.max(max, v);
            int n = 0;
            for (float v : w) if (v > max * KEEP_FRACTION) n++;
            bins[k] = new int[n];
            weights[k] = new float[n];
            double sum = 0;
            int i = 0;
            for (int b = 0; b < w.length; b++) {
                if (w[b] > max * KEEP_FRACTION) {
                    bins[k][i] = b;
                    weights[k][i++] = w[b];
                    sum += w[b];
                }
            }
            for (int j = 0; j < n; j++) weights[k][j] /= (float) sum;
        }
    }

    /** Writes each key's tonality for spectrum {@code x} into {@code out}. */
    void compute(float[] x, float[] out) {
        int size = x.length;
        for (int b = 0; b < size; b++) log[b] = (float) Math.log(x[b] + FLOOR);
        float window = 0;
        int count = 0;
        for (int b = 0; b < Math.min(size, RADIUS); b++) {
            window += log[b];
            count++;
        }
        for (int b = 0; b < size; b++) {
            int enter = b + RADIUS, leave = b - RADIUS - 1;
            if (enter < size) {
                window += log[enter];
                count++;
            }
            if (leave >= 0) {
                window -= log[leave];
                count--;
            }
            detail[b] = log[b] - window / count;
        }
        for (int k = 0; k < bins.length; k++) {
            int[] b = bins[k];
            float[] w = weights[k];
            float sum = 0;
            for (int i = 0; i < b.length; i++) sum += w[i] * detail[b[i]];
            out[k] = sum;
        }
    }
}
