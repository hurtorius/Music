package org.hurtorius.music.client.importer;

/**
 * Estimates how far a recording's tuning is from A4 = 440 Hz. Spectral peaks between 80 Hz and
 * 1.6 kHz (where piano partials are still close to harmonic) are located to a fraction of a bin by
 * parabolic interpolation, and their deviations from the equal-tempered grid are averaged on a
 * circle (so +49 and -49 cents agree), weighted by peak strength.
 */
final class TuningEstimator {
    private static final int SIZE = 4096;
    private static final int PEAKS_PER_FRAME = 6;
    private final Fft fft = new Fft(SIZE);
    private final float[] window = new float[SIZE];
    private final float[] block = new float[SIZE];
    private final float[] magnitude = new float[SIZE / 2 + 1];
    private double sumCos, sumSin;

    TuningEstimator() {
        for (int i = 0; i < SIZE; i++) window[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / SIZE));
    }

    /** Adds the peaks of up to {@code maxFrames} frames spread evenly over a signal at {@link LogSpectrum#RATE}. */
    void add(float[] signal, int maxFrames) {
        int available = Math.max(1, (signal.length - SIZE) / (SIZE / 2) + 1);
        int frames = Math.min(maxFrames, available);
        for (int f = 0; f < frames; f++) {
            int start = (int) ((long) f * Math.max(0, signal.length - SIZE) / Math.max(1, frames - 1));
            if (frames == 1) start = 0;
            for (int i = 0; i < SIZE; i++) {
                int at = start + i;
                block[i] = at < signal.length ? signal[at] * window[i] : 0f;
            }
            fft.magnitudes(block, magnitude);
            addPeaks();
        }
    }

    private void addPeaks() {
        double binHz = LogSpectrum.RATE / (double) SIZE;
        int lo = (int) (80 / binHz), hi = (int) (1600 / binHz);
        float max = 0;
        for (int k = lo; k <= hi; k++) max = Math.max(max, magnitude[k]);
        if (max <= 1e-9f) return;
        // Strongest local maxima, found by repeated selection (few peaks, small range).
        int[] chosen = new int[PEAKS_PER_FRAME];
        int count = 0;
        for (int p = 0; p < PEAKS_PER_FRAME; p++) {
            int best = -1;
            float bestValue = max * 0.05f;
            for (int k = lo + 1; k < hi; k++) {
                float m = magnitude[k];
                if (m > bestValue && m >= magnitude[k - 1] && m > magnitude[k + 1] && !taken(chosen, count, k)) {
                    best = k;
                    bestValue = m;
                }
            }
            if (best < 0) break;
            chosen[count++] = best;
        }
        for (int i = 0; i < count; i++) {
            int k = chosen[i];
            double a = Math.log(magnitude[k - 1] + 1e-12), b = Math.log(magnitude[k] + 1e-12), c = Math.log(magnitude[k + 1] + 1e-12);
            double denominator = a - 2 * b + c;
            double offset = denominator < 0 ? 0.5 * (a - c) / denominator : 0;
            double hz = (k + Math.max(-0.5, Math.min(0.5, offset))) * binHz;
            double cents = 1200 * Math.log(hz / 440.0) / Math.log(2);
            double angle = 2 * Math.PI * cents / 100.0;
            double weight = magnitude[k];
            sumCos += weight * Math.cos(angle);
            sumSin += weight * Math.sin(angle);
        }
    }

    private static boolean taken(int[] chosen, int count, int k) {
        for (int i = 0; i < count; i++) if (Math.abs(chosen[i] - k) <= 2) return true;
        return false;
    }

    /** The accumulated statistics, for {@link #merge} into another estimator. */
    double[] state() {
        return new double[]{sumCos, sumSin};
    }

    /** Adds statistics gathered by another estimator. */
    void merge(double[] state) {
        sumCos += state[0];
        sumSin += state[1];
    }

    /** The estimate in cents, -50..50; 0 if no peaks were seen. */
    double cents() {
        if (sumCos == 0 && sumSin == 0) return 0;
        return Math.atan2(sumSin, sumCos) * 100.0 / (2 * Math.PI);
    }
}
