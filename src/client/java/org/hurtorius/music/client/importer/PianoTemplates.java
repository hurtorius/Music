package org.hurtorius.music.client.importer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.sounds.JOrbisAudioStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Spectral templates of every piano key, learned at run time from the mod's own grand piano samples
 * (read through the class loader). Each key is rendered the way the audio engine plays it (nearest
 * sample, pitch-shifted with its tuning correction) from all five velocity layers and analysed with
 * the same {@link LogSpectrum} as imported audio. Per key, in this order:
 * <ul>
 *   <li>{@link #frame}: the attack (the first 30 ms after the hammer strikes) and the sustained
 *       sound (30-300 ms);</li>
 *   <li>{@link #onset}: the spectral increase ({@link OnsetSpectrum}) at a strike from silence, and
 *       at a strike of the same key while its previous strike is still dying away.</li>
 * </ul>
 * Templates are normalised to unit sum, layers weighted equally. Built once per game session, on
 * first use, in parallel (a few seconds).
 */
final class PianoTemplates {
    private static final Logger LOG = LoggerFactory.getLogger("Music");
    static final int KEYS = 88;
    private static final String ROOT = "/assets/music/instruments/grand_piano/";
    private static final int LAYERS = 5;
    private static final double SOURCE_SECONDS = 1.0;
    private static final double LEAD_SECONDS = 0.45;
    private static final double RELEASE_SECONDS = 0.08;
    private static final double RESTRIKE_SECONDS = 0.11;
    /** Templates per key, in this order: frame = attack, sustain; onset = fresh strike, repeated strike. */
    static final int PER_KEY = 2;
    private static PianoTemplates instance;

    final float[][] frame;
    final float[][] onset;
    /** What {@link TuningEstimator} reports for these samples; imported audio is measured relative to it. */
    final double tuningCents;

    private PianoTemplates(float[][] frame, float[][] onset, double tuningCents) {
        this.frame = frame;
        this.onset = onset;
        this.tuningCents = tuningCents;
    }

    /** The templates, building them on the first call (a few seconds; reported through {@code progress}). */
    static synchronized PianoTemplates get(Progress progress) throws AudioImportException {
        if (instance == null) {
            long start = System.nanoTime();
            instance = build(progress);
            LOG.info("Learned piano templates from the bundled samples in {} ms", (System.nanoTime() - start) / 1_000_000);
        }
        progress.at(1);
        return instance;
    }

    private record SampleSet(int key, int lo, int hi, int tuneCents, List<String> files) {}

    private static PianoTemplates build(Progress progress) throws AudioImportException {
        List<SampleSet> sets = readInstrument();
        int[] setOfKey = new int[KEYS];
        for (int key = 0; key < KEYS; key++) setOfKey[key] = nearest(sets, key + LogSpectrum.LOWEST_MIDI);
        float[][][] decoded = new float[sets.size()][LAYERS][];
        List<int[]> wanted = new ArrayList<>();
        for (int key = 0; key < KEYS; key++) {
            for (int l = 0; l < LAYERS; l++) {
                if (decoded[setOfKey[key]][l] == null) {
                    decoded[setOfKey[key]][l] = new float[0];
                    wanted.add(new int[]{setOfKey[key], l});
                }
            }
        }
        AudioImportException[] failure = new AudioImportException[1];
        wanted.parallelStream().forEach(w -> {
            try {
                decoded[w[0]][w[1]] = decodeSample(sets.get(w[0]).files.get(w[1]));
            } catch (AudioImportException e) {
                synchronized (failure) {
                    failure[0] = e;
                }
            }
        });
        if (failure[0] != null) throw failure[0];
        progress.at(0.3);

        float[][] frame = new float[KEYS * PER_KEY][];
        float[][] onset = new float[KEYS * PER_KEY][];
        double[][] tuningParts = new double[KEYS][];
        java.util.stream.IntStream.range(0, KEYS).parallel().forEach(key -> {
            int midi = key + LogSpectrum.LOWEST_MIDI;
            SampleSet set = sets.get(setOfKey[key]);
            double pitch = Math.pow(2, (midi - set.key) / 12.0 + set.tuneCents / 1200.0);
            KeyAnalysis analysis = new KeyAnalysis();
            for (int l = 0; l < LAYERS; l++) analysis.add(decoded[setOfKey[key]][l], pitch);
            for (int i = 0; i < PER_KEY; i++) {
                frame[key * PER_KEY + i] = normalise(analysis.frame[i]);
                onset[key * PER_KEY + i] = normalise(analysis.onset[i]);
            }
            tuningParts[key] = analysis.tuning.state();
        });
        TuningEstimator tuning = new TuningEstimator();
        for (double[] part : tuningParts) tuning.merge(part);
        return new PianoTemplates(frame, onset, tuning.cents());
    }

    /** Accumulates one key's templates over the velocity layers. */
    private static final class KeyAnalysis {
        final float[][] frame = new float[PER_KEY][LogSpectrum.BINS];
        final float[][] onset = new float[PER_KEY][LogSpectrum.BINS];
        final TuningEstimator tuning = new TuningEstimator();
        private final LogSpectrum spectrum = new LogSpectrum(0);
        private final OnsetSpectrum increases = new OnsetSpectrum();
        private final float[] x = new float[LogSpectrum.BINS];
        private final float[] d = new float[LogSpectrum.BINS];

        void add(float[] sample, double pitch) {
            int strike = (int) Math.round(LEAD_SECONDS * LogSpectrum.RATE / LogSpectrum.HOP);
            float[] note = render(sample, pitch, 1.0, -1);
            tuning.add(note, 8);
            float[] attack = new float[LogSpectrum.BINS], sustain = new float[LogSpectrum.BINS], struck = new float[LogSpectrum.BINS];
            increases.reset();
            for (int t = strike - 4; t <= strike + 30; t++) {
                spectrum.compute(note, t, x);
                increases.next(x, d);
                if (t >= strike && t < strike + 3) accumulate(attack, x);
                if (t >= strike + 3) accumulate(sustain, x);
                if (t >= strike - 1 && t <= strike + 1) accumulate(struck, d);
            }
            // The same key struck again while its previous strike is still dying away.
            int again = (int) Math.round((LEAD_SECONDS + RESTRIKE_SECONDS) * LogSpectrum.RATE / LogSpectrum.HOP);
            float[] repeated = render(sample, pitch, RELEASE_SECONDS, RESTRIKE_SECONDS);
            float[] restruck = new float[LogSpectrum.BINS];
            increases.reset();
            for (int t = again - 4; t <= again + 3; t++) {
                spectrum.compute(repeated, t, x);
                increases.next(x, d);
                if (t >= again) accumulate(restruck, d);
            }
            addNormalised(frame[0], attack);
            addNormalised(frame[1], sustain);
            addNormalised(onset[0], struck);
            addNormalised(onset[1], restruck);
        }
    }

    private static void accumulate(float[] sum, float[] v) {
        for (int i = 0; i < sum.length; i++) sum[i] += v[i];
    }

    private static void addNormalised(float[] sum, float[] v) {
        double total = 0;
        for (float f : v) total += f;
        if (total <= 0) return;
        for (int i = 0; i < sum.length; i++) sum[i] += (float) (v[i] / total);
    }

    private static float[] normalise(float[] v) {
        double total = 0;
        for (float f : v) total += f;
        for (int i = 0; i < v.length; i++) v[i] = (float) (v[i] / total);
        return v;
    }

    /** The sample set covering {@code midi}, or the nearest one when none does. */
    private static int nearest(List<SampleSet> sets, int midi) {
        int best = 0;
        for (int i = 0; i < sets.size(); i++) {
            SampleSet s = sets.get(i);
            if (midi >= s.lo && midi <= s.hi) return i;
            if (Math.abs(s.key - midi) < Math.abs(sets.get(best).key - midi)) best = i;
        }
        return best;
    }

    /**
     * The sample at {@code pitch} after {@link #LEAD_SECONDS} of silence, at the analysis rate. With
     * {@code again >= 0} the key is released after {@code held} seconds (fading 60 dB per second, as
     * a damper does) and struck again {@code again} seconds after the first strike.
     */
    private static float[] render(float[] sample, double pitch, double held, double again) {
        int inputRate = (int) Math.round(44100 * pitch);
        int lead = (int) Math.round(LEAD_SECONDS * LogSpectrum.RATE);
        float[] voice = new float[(int) Resampler.outputLength(sample.length, inputRate, LogSpectrum.RATE)];
        int[] at = {0};
        Resampler resampler = new Resampler(inputRate, LogSpectrum.RATE, v -> {
            if (at[0] < voice.length) voice[at[0]++] = v;
        });
        for (float v : sample) resampler.accept(v);
        resampler.finish();
        float[] out = new float[lead + voice.length];
        if (again < 0) {
            System.arraycopy(voice, 0, out, lead, voice.length);
            return out;
        }
        int release = (int) Math.round(held * LogSpectrum.RATE);
        double fade = Math.log(1000) / LogSpectrum.RATE;
        for (int i = 0; i < voice.length; i++) {
            out[lead + i] = (float) (voice[i] * (i < release ? 1 : Math.exp(-(i - release) * fade)));
        }
        int second = lead + (int) Math.round(again * LogSpectrum.RATE);
        for (int i = 0; second + i < out.length; i++) out[second + i] += voice[i];
        return out;
    }

    private static List<SampleSet> readInstrument() throws AudioImportException {
        try (InputStream in = open("grand_piano.json")) {
            JsonObject json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            List<SampleSet> sets = new ArrayList<>();
            for (JsonElement e : json.getAsJsonArray("notes")) {
                JsonObject n = e.getAsJsonObject();
                List<String> files = new ArrayList<>();
                JsonArray samples = n.getAsJsonArray("samples");
                for (JsonElement f : samples) files.add(f.getAsString());
                if (files.size() < LAYERS) throw new AudioImportException("The piano's sample list is incomplete.");
                sets.add(new SampleSet(n.get("key").getAsInt(), n.get("lo").getAsInt(), n.get("hi").getAsInt(),
                    n.get("tuneCents").getAsInt(), files));
            }
            if (sets.isEmpty()) throw new AudioImportException("The piano's sample list is empty.");
            return sets;
        } catch (IOException | RuntimeException e) {
            if (e instanceof AudioImportException a) throw a;
            throw new AudioImportException("The mod's piano description could not be read (" + e.getMessage() + ").", e);
        }
    }

    /** First {@link #SOURCE_SECONDS} of a bundled sample as mono floats at 44.1 kHz. */
    private static float[] decodeSample(String file) throws AudioImportException {
        try (InputStream in = open(file); JOrbisAudioStream ogg = new JOrbisAudioStream(in)) {
            int channels = ogg.getFormat().getChannels();
            int rate = Math.round(ogg.getFormat().getSampleRate());
            if (rate != 44100) throw new AudioImportException("Piano sample " + file + " is not 44.1 kHz.");
            int wanted = (int) (SOURCE_SECONDS * rate);
            float[] out = new float[wanted];
            int[] state = {0, 0};
            float[] sum = {0};
            it.unimi.dsi.fastutil.floats.FloatConsumer downmix = v -> {
                sum[0] += v;
                if (++state[1] == channels) {
                    if (state[0] < wanted) out[state[0]++] = sum[0] / channels;
                    sum[0] = 0;
                    state[1] = 0;
                }
            };
            boolean more = true;
            while (more && state[0] < wanted) more = ogg.readChunk(downmix);
            return state[0] == wanted ? out : java.util.Arrays.copyOf(out, state[0]);
        } catch (IOException | RuntimeException e) {
            if (e instanceof AudioImportException a) throw a;
            throw new AudioImportException(String.format(Locale.ROOT, "The mod's piano sample %s could not be decoded (%s).", file, e.getMessage()), e);
        }
    }

    private static InputStream open(String file) throws AudioImportException {
        InputStream in = PianoTemplates.class.getResourceAsStream(ROOT + file);
        if (in == null) throw new AudioImportException("The mod's piano sample " + file + " is missing from the jar.");
        return in;
    }
}
