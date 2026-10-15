package org.hurtorius.music.client.importer;

/**
 * Streaming band-limited sample rate converter: a Kaiser-windowed sinc kernel with its cut-off just
 * below the lower of the two Nyquist frequencies, read from a table with linear interpolation.
 * Input samples are pushed one at a time; each output sample is passed to the sink as soon as all
 * of its input taps have arrived, so memory stays constant however long the stream is.
 */
final class Resampler {
    /** Receives output samples in order. */
    interface Sink {
        void accept(float sample);
    }

    private static final int ZERO_CROSSINGS = 12;
    private static final int TABLE_RESOLUTION = 512;
    private static final double KAISER_BETA = 8.0;
    private static final double CUTOFF_FRACTION = 0.94;

    private final Sink sink;
    private final double step;
    private final double halfWidth;
    private final float[] kernel;
    private final float[] ring;
    private final int mask;
    private long received;
    private long emitted;

    Resampler(int inputRate, int outputRate, Sink sink) {
        if (inputRate < 1 || outputRate < 1) throw new IllegalArgumentException("Rates must be positive");
        this.sink = sink;
        this.step = inputRate / (double) outputRate;
        double cutoff = 0.5 * Math.min(1.0, 1.0 / step) * CUTOFF_FRACTION;
        this.halfWidth = ZERO_CROSSINGS / (2.0 * cutoff);
        int entries = (int) Math.ceil(halfWidth * TABLE_RESOLUTION) + 2;
        kernel = new float[entries];
        double norm = besselI0(KAISER_BETA);
        for (int i = 0; i < entries; i++) {
            double x = i / (double) TABLE_RESOLUTION;
            double r = x / halfWidth;
            double window = r >= 1 ? 0 : besselI0(KAISER_BETA * Math.sqrt(1 - r * r)) / norm;
            double arg = Math.PI * 2 * cutoff * x;
            double sinc = i == 0 ? 1 : Math.sin(arg) / arg;
            kernel[i] = (float) (2 * cutoff * sinc * window);
        }
        int size = Integer.highestOneBit((int) Math.ceil(2 * halfWidth) + 4) << 1;
        ring = new float[size];
        mask = size - 1;
    }

    /** Resamples a whole signal. */
    static float[] resample(float[] input, int inputRate, int outputRate) {
        if (inputRate == outputRate) return input.clone();
        long length = outputLength(input.length, inputRate, outputRate);
        if (length > Integer.MAX_VALUE - 8) throw new IllegalArgumentException("Signal too long to resample");
        float[] out = new float[(int) length];
        int[] count = {0};
        Resampler r = new Resampler(inputRate, outputRate, v -> {
            if (count[0] < out.length) out[count[0]++] = v;
        });
        for (float v : input) r.accept(v);
        r.finish();
        return out;
    }

    /** Number of output samples a signal of {@code inputLength} samples becomes. */
    static long outputLength(long inputLength, int inputRate, int outputRate) {
        if (inputLength <= 0) return 0;
        double step = inputRate / (double) outputRate;
        return (long) Math.floor((inputLength - 1) / step) + 1;
    }

    void accept(float sample) {
        ring[(int) (received & mask)] = sample;
        received++;
        while (true) {
            double t = emitted * step;
            if (Math.floor(t + halfWidth) >= received) return;
            sink.accept(compute(t));
            emitted++;
        }
    }

    /** Emits the remaining output samples, treating the signal as silent after its end. */
    void finish() {
        long total = received == 0 ? 0 : (long) Math.floor((received - 1) / step) + 1;
        while (emitted < total) {
            sink.accept(compute(emitted * step));
            emitted++;
        }
    }

    private float compute(double t) {
        long first = Math.max(0, (long) Math.ceil(t - halfWidth));
        long last = Math.min(received - 1, (long) Math.floor(t + halfWidth));
        double sum = 0;
        for (long i = first; i <= last; i++) {
            double x = Math.abs(i - t) * TABLE_RESOLUTION;
            int k = (int) x;
            float h = kernel[k] + (kernel[k + 1] - kernel[k]) * (float) (x - k);
            sum += ring[(int) (i & mask)] * h;
        }
        return (float) sum;
    }

    private static double besselI0(double x) {
        double sum = 1, term = 1, half = x / 2;
        for (int k = 1; k < 64; k++) {
            term *= (half / k) * (half / k);
            sum += term;
            if (term < sum * 1e-12) break;
        }
        return sum;
    }
}
