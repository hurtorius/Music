package org.hurtorius.music.client.importer;

/**
 * Estimates the beat of a transcription from its note onsets: the autocorrelation of an onset
 * envelope (each strike weighted by its loudness, lightly smoothed), weighted by a broad preference
 * for moderate tempi (log-normal around 110 bpm, one octave wide) so that the choice between a
 * tempo and its double or half falls on the musically likelier one.
 */
final class TempoEstimator {
    static final double DEFAULT_BPM = 120;
    private static final double MIN_BPM = 40, MAX_BPM = 220;
    private static final double PREFERRED_BPM = 110;
    private static final int MIN_NOTES = 8;
    /** Envelope resolution: 10 ms. */
    private static final double STEP_SECONDS = 0.01;

    private TempoEstimator() {}

    /**
     * @param onsets  note start times in seconds
     * @param weights loudness weight of each note (for example its velocity)
     * @return beats per minute, or {@link #DEFAULT_BPM} if there are too few notes to tell
     */
    static double estimate(double[] onsets, double[] weights) {
        if (onsets.length < MIN_NOTES) return DEFAULT_BPM;
        double end = 0;
        for (double t : onsets) end = Math.max(end, t);
        int length = (int) Math.ceil(end / STEP_SECONDS) + 8;
        if (length > 400_000) length = 400_000;
        double[] envelope = new double[length];
        for (int i = 0; i < onsets.length; i++) {
            int at = (int) Math.round(onsets[i] / STEP_SECONDS);
            for (int d = -2; d <= 2; d++) {
                int j = at + d;
                if (j >= 0 && j < length) envelope[j] += weights[i] * Math.exp(-0.5 * d * d);
            }
        }
        double mean = 0;
        for (double v : envelope) mean += v;
        mean /= length;
        for (int i = 0; i < length; i++) envelope[i] -= mean;
        int minLag = (int) Math.floor(60 / MAX_BPM / STEP_SECONDS);
        int maxLag = Math.min(length - 1, (int) Math.ceil(60 / MIN_BPM / STEP_SECONDS));
        if (maxLag <= minLag + 2) return DEFAULT_BPM;
        double[] score = new double[maxLag + 2];
        int best = -1;
        for (int lag = minLag; lag <= maxLag + 1 && lag < length; lag++) {
            double sum = 0;
            for (int i = 0; i + lag < length; i++) sum += envelope[i] * envelope[i + lag];
            double bpm = 60 / (lag * STEP_SECONDS);
            double octaves = Math.log(bpm / PREFERRED_BPM) / Math.log(2);
            score[lag] = sum / (length - lag) * Math.exp(-0.5 * octaves * octaves);
            if (lag <= maxLag && (best < 0 || score[lag] > score[best])) best = lag;
        }
        if (best < 0 || score[best] <= 0) return DEFAULT_BPM;
        double lag = best;
        if (best > minLag && best < maxLag) {
            double a = score[best - 1], b = score[best], c = score[best + 1];
            double denominator = a - 2 * b + c;
            if (denominator < 0) lag += Math.max(-0.5, Math.min(0.5, 0.5 * (a - c) / denominator));
        }
        return 60 / (lag * STEP_SECONDS);
    }
}
