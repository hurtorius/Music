package org.hurtorius.music.client.audio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One sample played across the whole keyboard (a note block instrument or a custom sound event).
 *
 * <p>OpenAL's resampler does not filter what it skips, so a sample played much faster than it was
 * recorded folds its upper partials back down as metallic, out-of-tune tones. Alongside the original
 * this keeps band-limited copies that are each one octave higher (half as long at the same rate); a
 * note uses the copy that keeps its playback rate at or below {@link #MAX_PITCH}, where nothing folds
 * back below 14 kHz.</p>
 *
 * <p>Note block sounds are recorded at very different levels; {@link #levelled} brings each one's attack
 * to the grand piano's, so switching instruments does not jump in loudness. A loud sound is turned down by
 * its {@link #trim}; a quiet one is raised in its samples, as far as its peak allows, never by its trim:
 * a trim above 1 would push loud notes past OpenAL's gain limit, where velocity and volume stop making
 * them louder.</p>
 */
public final class PitchedSound {
    /** Highest playback rate used: half an octave up, so a copy is never sped up enough to alias. */
    public static final double MAX_PITCH = Math.sqrt(2.0);
    /** The grand piano's attack at a middle velocity (its v2 layer, the first 100 ms): what note block sounds are trimmed to. */
    static final double REFERENCE_ATTACK_DBFS = -17.0;
    /** Trims beyond this would make a sound that is meant to be soft or loud into something else. */
    static final double MAX_TRIM_DB = 8.0;
    /** A quiet sound is raised in its samples until its peak reaches this (-1 dBFS), leaving the octave copies' filter room. */
    static final double PEAK_CEILING = 0.891;
    /** The attack is measured from the first sample above this share of the peak, for this long. */
    private static final double ONSET_SHARE = 0.1, ATTACK_SECONDS = 0.1;
    /** Highest key the piano plays (C8). */
    private static final int HIGHEST_MIDI = 108;
    /** Highest supported concert pitch, so the copies cover every tuning. */
    private static final double MAX_TUNING_RATIO = 466.0 / 440.0;
    /** Half-band anti-alias filter: Blackman-windowed sinc, cutoff at a quarter of the sample rate. */
    private static final float[] HALF_BAND = halfBand(63);

    public final String name;
    /** Gain every note of this sound is played with on top of its velocity: 1 or less. */
    public final float trim;
    private final List<PcmSample> octaves;

    private PitchedSound(String name, float trim, List<PcmSample> octaves) {
        this.name = name;
        this.trim = trim;
        this.octaves = Collections.unmodifiableList(octaves);
    }

    /** The sample, at its own level, plus enough octave-up copies to reach C8 from {@code lowestRootMidi}. */
    public static PitchedSound build(PcmSample base, int lowestRootMidi) {
        return build(base, lowestRootMidi, 1f);
    }

    /**
     * A note block sound levelled to the grand piano ({@link #levelTrim}), plus its octave copies: turned
     * down by its trim when it is louder, raised in its samples when it is quieter, up to where its peak
     * reaches {@link #PEAK_CEILING} (a sound that peaks higher than its attack suggests stays a little
     * quieter). {@code base} is let go of when a raised copy replaces it.
     */
    public static PitchedSound levelled(PcmSample base, int lowestRootMidi) {
        float trim = levelTrim(base);
        if (trim <= 1f) return build(base, lowestRootMidi, trim);
        int peak = peak(base);
        double raise = Math.min(trim, PEAK_CEILING * 32767.0 / peak);
        if (raise <= 1.0) return build(base, lowestRootMidi, 1f);
        short[] louder = new short[base.frames()];
        for (int i = 0; i < louder.length; i++) louder[i] = (short) Math.round(base.get(i) * raise);
        PcmSample raised = PcmSample.of(base.name, base.sampleRate, louder);
        base.release();
        return build(raised, lowestRootMidi, 1f);
    }

    /** The sample, played with gain {@code trim} (at most 1), plus enough octave-up copies to reach C8 from {@code lowestRootMidi}. */
    public static PitchedSound build(PcmSample base, int lowestRootMidi, float trim) {
        if (!(trim > 0f && trim <= 1f)) throw new IllegalArgumentException("a trim must be above 0 and at most 1, not " + trim);
        double highest = Math.pow(2.0, (HIGHEST_MIDI - lowestRootMidi) / 12.0) * MAX_TUNING_RATIO;
        List<PcmSample> list = new ArrayList<>();
        list.add(base);
        PcmSample current = base;
        while (highest > MAX_PITCH && current.frames() >= 16) {
            current = octaveUp(current, base.name + " +" + list.size() + " oct");
            list.add(current);
            highest /= 2;
        }
        return new PitchedSound(base.name, trim, list);
    }

    /**
     * The gain that brings a sound's attack (the first 100 ms from where it starts) to the grand piano's
     * at a middle velocity, within {@link #MAX_TRIM_DB}; 1 for a silent sound.
     */
    public static float levelTrim(PcmSample base) {
        int peak = peak(base);
        if (peak == 0) return 1f;
        int onset = 0;
        while (Math.abs(base.get(onset)) <= ONSET_SHARE * peak) onset++;
        int end = Math.min(base.frames(), onset + (int) (ATTACK_SECONDS * base.sampleRate));
        double sum = 0;
        for (int i = onset; i < end; i++) {
            double v = base.get(i) / 32768.0;
            sum += v * v;
        }
        double attackDb = 10 * Math.log10(sum / (end - onset));
        return VoiceMix.dbToGain(Math.max(-MAX_TRIM_DB, Math.min(MAX_TRIM_DB, REFERENCE_ATTACK_DBFS - attackDb)));
    }

    private static int peak(PcmSample base) {
        int peak = 0;
        for (int i = 0; i < base.frames(); i++) peak = Math.max(peak, Math.abs(base.get(i)));
        return peak;
    }

    /** A note's sample and playback rate. */
    public record Choice(PcmSample sample, double pitch) {}

    /** Picks the copy and rate that sound {@code midi} when the original sounds {@code rootMidi}. */
    public Choice choose(int midi, int rootMidi, float tuning) {
        double pitch = Math.pow(2.0, (midi - rootMidi) / 12.0) * tuning / 440.0;
        int octave = 0;
        while (pitch > MAX_PITCH && octave + 1 < octaves.size()) {
            pitch /= 2;
            octave++;
        }
        return new Choice(octaves.get(octave), pitch);
    }

    /** The original and its octave-up copies, lowest first. */
    public List<PcmSample> samples() {
        return octaves;
    }

    /**
     * One octave up at the same sample rate: low-pass at a quarter of the rate, then keep every other
     * sample. Played at the same rate it sounds twice as high and lasts half as long.
     */
    public static PcmSample octaveUp(PcmSample in, String name) {
        int n = in.frames();
        int half = HALF_BAND.length / 2;
        short[] out = new short[(n + 1) / 2];
        for (int o = 0; o < out.length; o++) {
            int centre = o * 2;
            float acc = 0;
            for (int t = 0; t < HALF_BAND.length; t++) {
                int i = centre + t - half;
                if (i >= 0 && i < n) acc += HALF_BAND[t] * in.get(i);
            }
            out[o] = (short) Math.max(-32768, Math.min(32767, Math.round(acc)));
        }
        return PcmSample.of(name, in.sampleRate, out);
    }

    private static float[] halfBand(int taps) {
        float[] h = new float[taps];
        int mid = taps / 2;
        double sum = 0;
        for (int i = 0; i < taps; i++) {
            int k = i - mid;
            double sinc = k == 0 ? 0.5 : Math.sin(Math.PI * k / 2.0) / (Math.PI * k);
            double window = 0.42 - 0.5 * Math.cos(2 * Math.PI * i / (taps - 1)) + 0.08 * Math.cos(4 * Math.PI * i / (taps - 1));
            h[i] = (float) (sinc * window);
            sum += h[i];
        }
        for (int i = 0; i < taps; i++) h[i] /= (float) sum;
        return h;
    }
}
