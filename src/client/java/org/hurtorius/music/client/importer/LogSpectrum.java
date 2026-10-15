package org.hurtorius.music.client.importer;

/**
 * Constant-Q style magnitude spectrum: three bins per semitone from A0 (MIDI 21) up to MIDI 120,
 * at {@link #RATE} Hz with one frame every {@link #HOP} samples (about 10 ms). Bin {@code 3k + 1} is
 * centred on piano key {@code k}, its neighbours a third of a semitone either side.
 *
 * <p>Each register uses its own Hann window so that the frequency resolution grows towards the bass
 * while the treble keeps a short window: 8192 samples (372 ms) below A2, 4096 up to A3, 2048 up to
 * A5 and 1024 (46 ms) above. FFT bins are combined into log bins with triangular weights (or linear
 * interpolation where the log bins are narrower than an FFT bin), and magnitudes are scaled so that a
 * steady sinusoid of amplitude {@code a} reads about {@code a} in every register. An instance keeps
 * scratch buffers and is not thread-safe.</p>
 */
final class LogSpectrum {
    static final int RATE = 22050;
    static final int HOP = 220;
    static final int LOWEST_MIDI = 21;
    static final int BINS_PER_SEMITONE = 3;
    static final int BINS = 300;
    private static final int[] WINDOWS = {8192, 4096, 2048, 1024};
    /** First MIDI pitch handled by each window (the first window starts at the bottom). */
    private static final int[] BAND_START = {0, 45, 57, 81};

    private final Band[] bands = new Band[WINDOWS.length];

    /** @param tuningCents offset of the recording's A4 from 440 Hz, in cents */
    LogSpectrum(double tuningCents) {
        for (int i = 0; i < WINDOWS.length; i++) {
            int fromBin = i == 0 ? 0 : binOf(BAND_START[i]) - 1;
            int toBin = i + 1 < WINDOWS.length ? binOf(BAND_START[i + 1]) - 1 : BINS;
            bands[i] = new Band(WINDOWS[i], fromBin, toBin, tuningCents);
        }
    }

    /** Index of the bin centred on a MIDI pitch. */
    static int binOf(int midi) {
        return (midi - LOWEST_MIDI) * BINS_PER_SEMITONE + 1;
    }

    /** Centre pitch of a bin, in fractional MIDI numbers. */
    static double pitchOf(int bin) {
        return LOWEST_MIDI + (bin - 1) / (double) BINS_PER_SEMITONE;
    }

    static double frequency(double midi, double tuningCents) {
        return 440.0 * Math.pow(2, (midi - 69) / 12.0 + tuningCents / 1200.0);
    }

    /** Number of frames covering a signal of {@code samples} samples. */
    static int frames(int samples) {
        return samples / HOP + 1;
    }

    /** Spectrum of the frame centred on sample {@code frame * HOP}; samples outside the signal are silence. */
    void compute(float[] signal, int frame, float[] out) {
        int centre = frame * HOP;
        for (Band band : bands) band.compute(signal, centre, out);
    }

    private static final class Band {
        private final int size;
        private final int fromBin, toBin;
        private final Fft fft;
        private final float[] window;
        private final float[] block;
        private final float[] magnitude;
        private final int[][] taps;
        private final float[][] weights;

        Band(int size, int fromBin, int toBin, double tuningCents) {
            this.size = size;
            this.fromBin = fromBin;
            this.toBin = toBin;
            fft = new Fft(size);
            window = new float[size];
            double scale = 4.0 / size;
            for (int i = 0; i < size; i++) window[i] = (float) (scale * (0.5 - 0.5 * Math.cos(2 * Math.PI * i / size)));
            block = new float[size];
            magnitude = new float[size / 2 + 1];
            taps = new int[toBin - fromBin][];
            weights = new float[toBin - fromBin][];
            double binHz = RATE / (double) size;
            for (int b = fromBin; b < toBin; b++) {
                double centre = frequency(pitchOf(b), tuningCents);
                double lower = frequency(pitchOf(b) - 1.0 / BINS_PER_SEMITONE, tuningCents);
                double upper = frequency(pitchOf(b) + 1.0 / BINS_PER_SEMITONE, tuningCents);
                int first = (int) Math.ceil(lower / binHz), last = (int) Math.floor(upper / binHz);
                last = Math.min(last, size / 2);
                if (last - first + 1 >= 3) {
                    int[] t = new int[last - first + 1];
                    float[] w = new float[t.length];
                    double sum = 0;
                    for (int k = first; k <= last; k++) {
                        double f = k * binHz;
                        double v = f <= centre ? (f - lower) / (centre - lower) : (upper - f) / (upper - centre);
                        t[k - first] = k;
                        w[k - first] = (float) Math.max(0, v);
                        sum += Math.max(0, v);
                    }
                    for (int i = 0; i < w.length; i++) w[i] /= (float) sum;
                    taps[b - fromBin] = t;
                    weights[b - fromBin] = w;
                } else {
                    double x = Math.min(centre / binHz, size / 2.0 - 1);
                    int k = (int) Math.floor(x);
                    float frac = (float) (x - k);
                    taps[b - fromBin] = new int[]{k, k + 1};
                    weights[b - fromBin] = new float[]{1 - frac, frac};
                }
            }
        }

        void compute(float[] signal, int centre, float[] out) {
            int start = centre - size / 2;
            for (int i = 0; i < size; i++) {
                int at = start + i;
                block[i] = at >= 0 && at < signal.length ? signal[at] * window[i] : 0f;
            }
            fft.magnitudes(block, magnitude);
            for (int b = fromBin; b < toBin; b++) {
                int[] t = taps[b - fromBin];
                float[] w = weights[b - fromBin];
                float sum = 0;
                for (int i = 0; i < t.length; i++) sum += w[i] * magnitude[t[i]];
                out[b] = sum;
            }
        }
    }
}
