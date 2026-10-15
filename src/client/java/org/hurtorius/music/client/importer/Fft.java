package org.hurtorius.music.client.importer;

/**
 * Magnitude spectrum of a real signal block of power-of-two length, computed as one complex FFT of
 * half the length (even/odd samples packed as real/imaginary) followed by the usual split step.
 * Tables are built once per size; an instance is not thread-safe.
 */
final class Fft {
    private final int half;
    private final int[] reverse;
    private final double[] cos, sin;
    private final double[] splitCos, splitSin;
    private final double[] re, im;

    Fft(int n) {
        if (n < 4 || Integer.bitCount(n) != 1) throw new IllegalArgumentException("FFT size must be a power of two: " + n);
        this.half = n / 2;
        int bits = Integer.numberOfTrailingZeros(half);
        reverse = new int[half];
        for (int i = 0; i < half; i++) reverse[i] = Integer.reverse(i) >>> (32 - bits);
        cos = new double[half / 2];
        sin = new double[half / 2];
        for (int i = 0; i < half / 2; i++) {
            cos[i] = Math.cos(2 * Math.PI * i / half);
            sin[i] = -Math.sin(2 * Math.PI * i / half);
        }
        splitCos = new double[half];
        splitSin = new double[half];
        for (int k = 0; k < half; k++) {
            splitCos[k] = Math.cos(2 * Math.PI * k / n);
            splitSin[k] = -Math.sin(2 * Math.PI * k / n);
        }
        re = new double[half];
        im = new double[half];
    }

    /**
     * Writes |X[k]| for k = 0..n/2 into {@code magnitude} (length at least n/2 + 1).
     *
     * @param input exactly {@code n} real samples (already windowed)
     */
    void magnitudes(float[] input, float[] magnitude) {
        for (int i = 0; i < half; i++) {
            int j = reverse[i];
            re[j] = input[2 * i];
            im[j] = input[2 * i + 1];
        }
        for (int size = 2; size <= half; size <<= 1) {
            int step = half / size;
            int h = size / 2;
            for (int start = 0; start < half; start += size) {
                for (int k = 0; k < h; k++) {
                    double wr = cos[k * step], wi = sin[k * step];
                    int a = start + k, b = a + h;
                    double tr = re[b] * wr - im[b] * wi;
                    double ti = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - tr;
                    im[b] = im[a] - ti;
                    re[a] += tr;
                    im[a] += ti;
                }
            }
        }
        // Split: X[k] = (Z[k] + conj(Z[half-k]))/2 + W^k (Z[k] - conj(Z[half-k]))/(2i)
        magnitude[0] = (float) Math.abs(re[0] + im[0]);
        magnitude[half] = (float) Math.abs(re[0] - im[0]);
        for (int k = 1; k < half; k++) {
            double ar = re[k], ai = im[k];
            double br = re[half - k], bi = -im[half - k];
            double er = (ar + br) * 0.5, ei = (ai + bi) * 0.5;
            double or = (ai - bi) * 0.5, oi = -(ar - br) * 0.5;
            double xr = er + splitCos[k] * or - splitSin[k] * oi;
            double xi = ei + splitCos[k] * oi + splitSin[k] * or;
            magnitude[k] = (float) Math.sqrt(xr * xr + xi * xi);
        }
    }
}
