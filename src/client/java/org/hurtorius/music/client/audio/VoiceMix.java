package org.hurtorius.music.client.audio;

/**
 * How a piano's settings colour a voice, as plain numbers: overall gain, the EFX band-pass shelves
 * (OpenAL Soft's band-pass is a low shelf at 250 Hz and a high shelf at 5 kHz) and the envelopes.
 *
 * <ul>
 *   <li>Tone -1..0 darkens (high shelf down to -24 dB). Tone 0..1 brightens. The grand piano strikes
 *       harder: it plays the sample of a velocity up to {@link #BRIGHT_VELOCITY} higher, at the loudness
 *       of the velocity played ({@link GrandPianoSpec#strikeLayer}), so it gains the hammer's attack and
 *       upper partials and keeps its bass. Five layers give a note a step or two of that at most, so
 *       the part of the tone past the step it took ({@link #tilt}) thins its lows a little, with a
 *       quarter of the shelf below: every step of the slider changes the sound, the hardest layer's notes
 *       too, and notes already sounding follow the slider. A shelf cannot add treble a recording does
 *       not have, so other sounds, which have no harder hammer, get the whole shelf (low shelf down to
 *       -9 dB, with 2 dB of make-up gain).</li>
 *   <li>A closed lid is 4 dB quieter and 8 dB duller up top; it moves over the lid's one-second swing.</li>
 *   <li>The soft pedal (una corda) is 3 dB quieter and 5 dB duller.</li>
 * </ul>
 */
public final class VoiceMix {
    public static final float LID_DB = -4f, LID_HF_DB = -8f;
    public static final float SOFT_DB = -3f, SOFT_HF_DB = -5f;
    public static final float DARK_HF_DB = -24f, BRIGHT_LF_DB = -9f, BRIGHT_MAKEUP_DB = 2f;
    /** Velocity added to choose the grand piano's sample at full brightness. */
    public static final int BRIGHT_VELOCITY = 32;
    /** Share of the bright shelf the grand piano gets for the part of a brighter tone its harder layer does not give. */
    public static final float HAMMER_TILT = 0.25f;
    /** -60 dB in nepers: exp(-6.9078) = 0.001. */
    private static final double MINUS_60_DB = Math.log(1000.0);

    private VoiceMix() {}

    public static float dbToGain(double db) {
        return (float) Math.pow(10.0, db / 20.0);
    }

    /** Overall gain from the bright shelf ({@link #tilt}), the lid (0 open .. 1 closed) and the soft pedal. */
    public static float colourGain(float tilt, float lidClosed, boolean soft) {
        return dbToGain(LID_DB * lidClosed + (soft ? SOFT_DB : 0) + BRIGHT_MAKEUP_DB * tilt);
    }

    /** High-shelf gain (0..1) of the voice's band-pass. */
    public static float highShelf(float tone, float lidClosed, boolean soft) {
        double db = LID_HF_DB * lidClosed + (soft ? SOFT_HF_DB : 0) + (tone < 0 ? -DARK_HF_DB * tone : 0);
        return Math.min(1f, dbToGain(db));
    }

    /** Low-shelf gain (0..1) of the voice's band-pass for the bright shelf {@code tilt} (see {@link #tilt}). */
    public static float lowShelf(float tilt) {
        return Math.min(1f, dbToGain(BRIGHT_LF_DB * tilt));
    }

    /**
     * How much of the bright shelf (low shelf down to {@link #BRIGHT_LF_DB}, {@link #BRIGHT_MAKEUP_DB} of
     * make-up) a voice gets at {@code tone}, 0..1. A sound without harder hammers gets the whole brighter
     * tone; the grand piano ({@code hammered}) {@link #HAMMER_TILT} of {@code beyond}, the part of the tone
     * past the one that struck the layer it plays ({@link GrandPianoSpec#toneFor}).
     */
    public static float tilt(float tone, boolean hammered, float beyond) {
        if (!(tone > 0)) return 0f;
        return hammered ? HAMMER_TILT * Math.max(0f, Math.min(1f, beyond)) : Math.min(1f, tone);
    }

    /** Exponential release: 1 at the start, -60 dB after {@code seconds}. */
    public static float release(long elapsedNanos, float seconds) {
        if (elapsedNanos <= 0) return 1f;
        return (float) Math.exp(-MINUS_60_DB * elapsedNanos / (seconds * 1e9));
    }

    /** A note going on past its recording: 1 when the loop starts, falling {@code dbPerSecond} every second after. */
    public static float tail(long elapsedNanos, float dbPerSecond) {
        if (elapsedNanos <= 0) return 1f;
        return dbToGain(-dbPerSecond * elapsedNanos / 1e9);
    }

    /** Linear fade: 1 at the start, 0 after {@code nanos}. */
    public static float fade(long elapsedNanos, long nanos) {
        if (elapsedNanos <= 0) return 1f;
        return Math.max(0f, 1f - elapsedNanos / (float) nanos);
    }
}
