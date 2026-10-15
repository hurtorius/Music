package org.hurtorius.music.client.importer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * Turns {@link Activations} into notes.
 *
 * <p>Every peak of a key's onset activation is a candidate strike. A candidate is described by
 * a few level ratios in dB, all relative to the music around it: how strong the strike is against
 * the loudest strikes of the last and next few seconds and against the other keys struck at that
 * moment; how strongly the key then sounds, overall and next to the other sounding keys; how much
 * its sound and its attack rose; how loud it already was; whether a stronger strike an octave,
 * twelfth or two octaves away could explain it; its register; and how clearly its harmonic peaks
 * stand out of the spectrum ({@link Tonality}). Candidates without harmonic peaks are dropped
 * outright ({@link #TONAL_GATE}); for the rest a logistic model of these features gives the
 * probability that the candidate is a real strike. The model was fitted on randomised synthetic
 * piano textures (clean, in a room, quiet with hiss, and with noise bursts) rendered from the
 * bundled samples, separate from the test pieces. Peaks of one key closer than
 * {@link #MERGE_FRAMES} are one strike.</p>
 *
 * <p>A note ends when its key's sounding activation falls well below the level it reached, when
 * it starts dropping at a damper's rate, or when the key is struck again.</p>
 */
final class NoteTracker {
    /**
     * A detected note in analysis frames.
     *
     * @param onsetFrame fractional frame of the strike, already corrected for the analysis delay
     * @param level      onset activation at the strike (for velocity)
     */
    record Detected(int key, double onsetFrame, int endFrame, float level) {}

    /** A candidate strike and its features (see {@link #FEATURES}). */
    record Candidate(int key, int frame, float level, double[] features) {}

    static final String[] FEATURES = {"level", "frame", "sound", "soundFrame", "soundRise", "attackRise",
        "attackFrame", "ringing", "harmonic", "subharmonic", "register", "tonality"};
    /** Logistic weights for {@link #FEATURES}, then the bias. */
    static final double[] WEIGHTS = {0.17986, -0.06949, 0.03070, 0.23930, -0.09100, 0.03397, 0.02155, -0.06399, -0.81217, 0.02571, 0.30396, 0.38816, 4.80812};
    /** Probability above which a candidate is accepted at the default sensitivity. */
    static final double THRESHOLD = 0.45;
    /**
     * Least {@link Tonality} a candidate needs at all: below it the key's partials do not stand out
     * of the spectrum, which is what noise, drums and knocks look like (real notes rarely fall below).
     */
    static final double TONAL_GATE = 0.2;

    private static final int PEAK_RADIUS = 3;
    private static final int SUSTAIN_FROM = 3, SUSTAIN_TO = 12;
    private static final int MERGE_FRAMES = 7;
    /** The onset activation peaks about this many frames after the hammer strikes. */
    private static final double ONSET_DELAY_FRAMES = 1.5;
    /** Half-width of the window over which the loudness reference is taken (about 4 s). */
    private static final int REFERENCE_RADIUS = 400;
    private static final double CANDIDATE_FLOOR_DB = -60;
    private static final int[] HARMONIC_INTERVALS = {12, 19, 24};
    private static final double END_BELOW_PEAK_DB = -30;
    private static final double END_FLOOR_DB = -55;
    /** A drop of this much over {@link #DAMPER_FRAMES} frames means the damper came down. */
    private static final double DAMPER_DROP_DB = -6;
    private static final int DAMPER_FRAMES = 8;
    private static final int MIN_FRAMES = 4;

    private NoteTracker() {}

    /**
     * Detects notes.
     *
     * @param sensitivity 0..1, 0.5 = default; higher accepts less certain strikes (more notes, more mistakes)
     */
    static List<Detected> track(Activations a, double sensitivity) {
        double s = Math.max(0, Math.min(1, sensitivity));
        double threshold = 1 / (1 + Math.exp(-(Math.log(THRESHOLD / (1 - THRESHOLD)) - (s - 0.5) * 4)));
        List<Candidate> accepted = new ArrayList<>();
        int tonality = FEATURES.length - 1;
        candidates(a, c -> {
            if (c.features[tonality] > TONAL_GATE && probability(c.features) > threshold) accepted.add(c);
        });
        return notes(a, accepted);
    }

    static double probability(double[] features) {
        double z = WEIGHTS[WEIGHTS.length - 1];
        for (int i = 0; i < features.length; i++) z += WEIGHTS[i] * features[i];
        return 1 / (1 + Math.exp(-z));
    }

    /** Passes every onset peak with its features to {@code sink}, in key order. */
    static void candidates(Activations a, Consumer<Candidate> sink) {
        int frames = a.frames;
        int keys = PianoTemplates.KEYS;
        float[] onsetFrameMax = new float[frames];
        float[] soundingFrameMax = new float[frames];
        float[] attackFrameMax = new float[frames];
        for (int t = 0; t < frames; t++) {
            float o = 0, so = 0, at = 0;
            for (int k = 0; k < keys; k++) {
                o = Math.max(o, a.onset(k, t));
                so = Math.max(so, a.sounding(k, t));
                at = Math.max(at, a.attack(k, t));
            }
            onsetFrameMax[t] = o;
            soundingFrameMax[t] = so;
            attackFrameMax[t] = at;
        }
        float[] onsetReference = slidingMax(onsetFrameMax, REFERENCE_RADIUS);
        float[] soundingReference = slidingMax(soundingFrameMax, REFERENCE_RADIUS);
        float[] onsetLocal = slidingMax(onsetFrameMax, 2);
        float[] attackLocal = slidingMax(attackFrameMax, PEAK_RADIUS);
        float floor = db(CANDIDATE_FLOOR_DB);

        float[] sustained = new float[keys];
        for (int k = 0; k < keys; k++) {
            for (int t = 1; t < frames - 1; t++) {
                float v = a.onset(k, t);
                if (v <= 0 || v < onsetReference[t] * floor || !isPeak(a, k, t)) continue;
                float sustainedMax = 0;
                for (int j = 0; j < keys; j++) {
                    sustained[j] = meanSounding(a, j, t + SUSTAIN_FROM, t + SUSTAIN_TO);
                    sustainedMax = Math.max(sustainedMax, sustained[j]);
                }
                float before = meanSounding(a, k, t - 8, t - 3);
                float attackAfter = 0;
                for (int i = Math.max(0, t - PEAK_RADIUS); i <= Math.min(frames - 1, t + PEAK_RADIUS); i++) {
                    attackAfter = Math.max(attackAfter, a.attack(k, i));
                }
                float attackBefore = meanAttack(a, k, t - 10, t - 5);
                double[] f = {
                    clippedDb(v, onsetReference[t], -80, 0),
                    clippedDb(v, onsetLocal[t], -60, 0),
                    clippedDb(sustained[k], soundingReference[t], -80, 0),
                    clippedDb(sustained[k], sustainedMax, -60, 0),
                    clippedDb(sustained[k], before, -20, 40),
                    clippedDb(attackAfter, attackBefore, -20, 60),
                    clippedDb(attackAfter, attackLocal[t], -60, 0),
                    clippedDb(before, soundingReference[t], -80, 0),
                    strongerAt(a, k, t, v, -1) ? 1 : 0,
                    strongerAt(a, k, t, v, 1) ? 1 : 0,
                    k / (double) (keys - 1),
                    Math.max(-2, Math.min(4, meanTonality(a, k, t + 2, t + 9))),
                };
                sink.accept(new Candidate(k, t, v, f));
            }
        }
    }

    /** True if a key a harmonic interval below ({@code direction} -1) or above (+1) has a stronger strike now. */
    private static boolean strongerAt(Activations a, int key, int t, float level, int direction) {
        for (int interval : HARMONIC_INTERVALS) {
            int other = key + direction * interval;
            if (other < 0 || other >= PianoTemplates.KEYS) continue;
            for (int i = Math.max(0, t - 2); i <= Math.min(a.frames - 1, t + 2); i++) {
                if (a.onset(other, i) > level) return true;
            }
        }
        return false;
    }

    /** Accepted strikes to notes: merges double peaks, then finds where each note ends. */
    private static List<Detected> notes(Activations a, List<Candidate> accepted) {
        int frames = a.frames;
        float[] soundingFrameMax = new float[frames];
        for (int t = 0; t < frames; t++) {
            float m = 0;
            for (int k = 0; k < PianoTemplates.KEYS; k++) m = Math.max(m, a.sounding(k, t));
            soundingFrameMax[t] = m;
        }
        float[] soundingReference = slidingMax(soundingFrameMax, REFERENCE_RADIUS);
        accepted.sort(Comparator.comparingInt(Candidate::frame));
        List<Candidate> strikes = new ArrayList<>();
        int[] last = new int[PianoTemplates.KEYS];
        Arrays.fill(last, -1);
        for (Candidate c : accepted) {
            int previous = last[c.key];
            if (previous >= 0 && c.frame - strikes.get(previous).frame <= MERGE_FRAMES) {
                if (c.level > strikes.get(previous).level) strikes.set(previous, c);
                continue;
            }
            last[c.key] = strikes.size();
            strikes.add(c);
        }
        List<Detected> notes = new ArrayList<>(strikes.size());
        for (int i = 0; i < strikes.size(); i++) {
            Candidate c = strikes.get(i);
            int next = frames;
            for (int j = i + 1; j < strikes.size(); j++) {
                if (strikes.get(j).key == c.key) {
                    next = strikes.get(j).frame;
                    break;
                }
            }
            int end = end(a, c.key, c.frame, next, soundingReference);
            notes.add(new Detected(c.key, Math.max(0, c.frame - ONSET_DELAY_FRAMES), end, c.level));
        }
        return notes;
    }

    /** First frame after the note at which it has decayed away, been damped, or is struck again. */
    private static int end(Activations a, int key, int start, int next, float[] reference) {
        int frames = a.frames;
        int limit = Math.min(next, frames);
        float peak = 0;
        for (int t = start; t < Math.min(limit, start + SUSTAIN_TO); t++) peak = Math.max(peak, a.sounding(key, t));
        int end = start + MIN_FRAMES;
        while (end < limit) {
            float s = a.sounding(key, end);
            if (s < peak * db(END_BELOW_PEAK_DB) || s < reference[end] * db(END_FLOOR_DB)) break;
            if (end + DAMPER_FRAMES < limit && end > start + SUSTAIN_FROM
                && a.sounding(key, end + DAMPER_FRAMES) < s * db(DAMPER_DROP_DB)) {
                break;
            }
            end++;
        }
        return Math.max(Math.min(end, Math.max(limit, start + MIN_FRAMES)), start + MIN_FRAMES);
    }

    private static boolean isPeak(Activations a, int key, int t) {
        float v = a.onset(key, t);
        for (int i = Math.max(0, t - PEAK_RADIUS); i <= Math.min(a.frames - 1, t + PEAK_RADIUS); i++) {
            float o = a.onset(key, i);
            if (o > v || (o == v && i < t)) return false;
        }
        return true;
    }

    private static float meanSounding(Activations a, int key, int from, int to) {
        from = Math.max(0, from);
        to = Math.min(a.frames, to);
        if (to <= from) return from < a.frames ? a.sounding(key, from) : 0;
        double sum = 0;
        for (int i = from; i < to; i++) sum += a.sounding(key, i);
        return (float) (sum / (to - from));
    }

    private static float meanTonality(Activations a, int key, int from, int to) {
        from = Math.max(0, from);
        to = Math.min(a.frames, to);
        if (to <= from) return 0;
        double sum = 0;
        for (int i = from; i < to; i++) sum += a.tonality(key, i);
        return (float) (sum / (to - from));
    }

    private static float meanAttack(Activations a, int key, int from, int to) {
        from = Math.max(0, from);
        to = Math.min(a.frames, to);
        if (to <= from) return 0;
        double sum = 0;
        for (int i = from; i < to; i++) sum += a.attack(key, i);
        return (float) (sum / (to - from));
    }

    /** Maximum of {@code v} over {@code [t - radius, t + radius]} for every t (monotonic deque, linear time). */
    static float[] slidingMax(float[] v, int radius) {
        int n = v.length;
        float[] out = new float[n];
        int[] deque = new int[n];
        int head = 0, tail = 0;
        int added = 0;
        for (int t = 0; t < n; t++) {
            int want = Math.min(n - 1, t + radius);
            while (added <= want) {
                while (tail > head && v[deque[tail - 1]] <= v[added]) tail--;
                deque[tail++] = added++;
            }
            while (deque[head] < t - radius) head++;
            out[t] = v[deque[head]];
        }
        return out;
    }

    private static double clippedDb(double a, double b, double lo, double hi) {
        double v = 20 * Math.log10(Math.max(a, 1e-30) / Math.max(b, 1e-30));
        return Math.max(lo, Math.min(hi, v));
    }

    static float db(double db) {
        return (float) Math.pow(10, db / 20);
    }
}
