package org.hurtorius.music.client.importer;

import java.util.Arrays;

/**
 * The spectral increase of each {@link LogSpectrum} frame over the frame {@link #LAG} hops earlier,
 * where the earlier frame is first maximum-filtered across neighbouring bins (as in SuperFlux) so
 * that slight pitch wobble and beating do not count as new energy. Decaying notes contribute
 * nothing; a newly struck note contributes its own spectrum, which is what lets the onsets of
 * individual keys be separated with templates.
 */
final class OnsetSpectrum {
    static final int LAG = 2;
    private final float[][] history = new float[LAG][LogSpectrum.BINS];
    private int next;

    void reset() {
        for (float[] h : history) Arrays.fill(h, 0f);
        next = 0;
    }

    /**
     * Writes the increase of {@code x} into {@code increase} and remembers {@code x}.
     *
     * @return the total increase (a broadband onset strength)
     */
    float next(float[] x, float[] increase) {
        float[] old = history[next];
        float total = 0;
        int last = x.length - 1;
        for (int b = 0; b <= last; b++) {
            float reference = old[b];
            if (b > 0) reference = Math.max(reference, old[b - 1]);
            if (b < last) reference = Math.max(reference, old[b + 1]);
            float d = x[b] - reference;
            d = d > 0 ? d : 0;
            increase[b] = d;
            total += d;
        }
        System.arraycopy(x, 0, old, 0, x.length);
        next = (next + 1) % LAG;
        return total;
    }
}
