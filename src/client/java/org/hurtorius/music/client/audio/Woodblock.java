package org.hurtorius.music.client.audio;

/**
 * The metronome's click: a short woodblock-like knock made from two damped resonances and a tiny burst
 * of noise for the stick. The accent is pitched a fourth higher and played louder. Deterministic.
 */
public final class Woodblock {
    public static final int SAMPLE_RATE = 48000;
    private static final double SECONDS = 0.09;

    private Woodblock() {}

    public static PcmSample sample(boolean accent) {
        return PcmSample.of(accent ? "metronome accent" : "metronome tick", SAMPLE_RATE, synthesize(accent));
    }

    /** Mono 16-bit samples at {@link #SAMPLE_RATE}. */
    public static short[] synthesize(boolean accent) {
        int n = (int) (SAMPLE_RATE * SECONDS);
        double f1 = accent ? 1760.0 : 1320.0;
        double f2 = f1 * 2.76;
        double peak = accent ? 0.85 : 0.55;
        float[] wave = new float[n];
        long noise = 0x9E3779B97F4A7C15L;
        double max = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) SAMPLE_RATE;
            double body = Math.sin(2 * Math.PI * f1 * t) * Math.exp(-t / 0.018)
                + 0.45 * Math.sin(2 * Math.PI * f2 * t) * Math.exp(-t / 0.007);
            noise ^= noise << 13;
            noise ^= noise >>> 7;
            noise ^= noise << 17;
            double stick = ((noise >>> 11) / (double) (1L << 53) * 2 - 1) * Math.exp(-t / 0.0015) * 0.6;
            double attack = Math.min(1.0, i / (SAMPLE_RATE * 0.0004));
            double tail = Math.min(1.0, (n - 1 - i) / (SAMPLE_RATE * 0.005));
            wave[i] = (float) ((body + stick) * attack * tail);
            max = Math.max(max, Math.abs(wave[i]));
        }
        short[] out = new short[n];
        double scale = peak * 32767 / max;
        for (int i = 0; i < n; i++) out[i] = (short) Math.round(wave[i] * scale);
        return out;
    }
}
