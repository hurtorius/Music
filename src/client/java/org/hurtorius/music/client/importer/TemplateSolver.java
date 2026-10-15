package org.hurtorius.music.client.importer;

import java.util.Arrays;

/**
 * Explains a spectrum as a non-negative mix of fixed templates: finds {@code h >= 0} minimising the
 * Kullback-Leibler divergence between {@code x} and {@code sum_k h[k] * template[k]}, with an L1
 * penalty that pushes unneeded templates to zero, by multiplicative updates (NMF with the
 * dictionary held fixed). Because a key's template carries its whole harmonic series, energy at an
 * octave or twelfth of a sounding note is attributed to that note instead of producing a second
 * one.
 *
 * <p>Only the {@link #CANDIDATES} templates that correlate best with the spectrum (plus any still
 * active from the previous frame) take part, which keeps a frame cheap without changing the result
 * in practice: a key whose template does not even correlate with the frame would end up at zero.
 * Template entries below a small fraction of their peak are dropped. Not thread-safe.</p>
 */
final class TemplateSolver {
    private static final float EPSILON = 1e-9f;
    private static final float KEEP_FRACTION = 0.003f;
    private static final int CANDIDATES = 24;
    private final int[][] bins;
    private final float[][] values;
    private final float[] model;
    private final float[] ratio;
    private final float[] score;
    private final float[] sorted;
    private final int[] active;

    TemplateSolver(float[][] templates) {
        int count = templates.length;
        int size = templates[0].length;
        bins = new int[count][];
        values = new float[count][];
        for (int k = 0; k < count; k++) {
            float max = 0;
            for (float v : templates[k]) max = Math.max(max, v);
            int n = 0;
            for (float v : templates[k]) if (v > max * KEEP_FRACTION) n++;
            bins[k] = new int[n];
            values[k] = new float[n];
            double sum = 0;
            int i = 0;
            for (int b = 0; b < size; b++) {
                if (templates[k][b] > max * KEEP_FRACTION) {
                    bins[k][i] = b;
                    values[k][i] = templates[k][b];
                    sum += templates[k][b];
                    i++;
                }
            }
            for (int j = 0; j < n; j++) values[k][j] /= (float) sum;
        }
        model = new float[size];
        ratio = new float[size];
        score = new float[count];
        sorted = new float[count];
        active = new int[count];
    }

    /**
     * Updates {@code h} in place. Pass the previous frame's result to warm-start (candidates that
     * were silent get a small seed so they can grow), or zeros to start afresh.
     *
     * @param sparsity L1 penalty (0 = none); 0.02 shrinks every activation by 2% per iteration
     */
    void solve(float[] x, float[] h, int iterations, float sparsity) {
        float total = 0;
        for (float v : x) total += v;
        int count = h.length;
        if (total <= EPSILON) {
            Arrays.fill(h, 0f);
            return;
        }
        float seed = total / count * 0.05f;
        for (int k = 0; k < count; k++) {
            int[] b = bins[k];
            float[] w = values[k];
            float s = 0;
            for (int i = 0; i < b.length; i++) s += w[i] * x[b[i]];
            score[k] = s;
        }
        System.arraycopy(score, 0, sorted, 0, count);
        Arrays.sort(sorted);
        float threshold = sorted[Math.max(0, count - CANDIDATES)];
        int n = 0;
        for (int k = 0; k < count; k++) {
            if (score[k] >= threshold || h[k] > seed) {
                if (h[k] < seed) h[k] = seed;
                active[n++] = k;
            } else {
                h[k] = 0;
            }
        }
        float shrink = 1f / (1f + sparsity);
        for (int iteration = 0; iteration < iterations; iteration++) {
            Arrays.fill(model, EPSILON);
            for (int j = 0; j < n; j++) {
                int k = active[j];
                float a = h[k];
                int[] b = bins[k];
                float[] w = values[k];
                for (int i = 0; i < b.length; i++) model[b[i]] += a * w[i];
            }
            for (int b = 0; b < ratio.length; b++) ratio[b] = x[b] / model[b];
            for (int j = 0; j < n; j++) {
                int k = active[j];
                int[] b = bins[k];
                float[] w = values[k];
                float s = 0;
                for (int i = 0; i < b.length; i++) s += w[i] * ratio[b[i]];
                h[k] *= s * shrink;
            }
        }
    }
}
