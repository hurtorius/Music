package org.hurtorius.music.client.importer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-key evidence over time, one value per {@link LogSpectrum} frame: how strongly each key
 * sounds ({@link #sounding}) and how strongly it was just struck ({@link #onset}). Every frame's
 * spectrum is explained with the {@link PianoTemplates} frame templates, and its spectral increase
 * with the onset templates; the attack-template share ({@link #attack}) and each key's
 * {@link Tonality} are kept as further evidence.
 *
 * <p>The signal is analysed in chunks on a small pool of low-priority worker threads; each chunk
 * starts a few frames early so the warm-started solver has settled when its frames begin. Values
 * are kept as half-precision floats (2 bytes per key and frame).</p>
 */
final class Activations {
    private static final int CHUNK = 1000;
    private static final int WARM_UP = 12;
    private static final int SOUNDING_ITERATIONS = 12;
    private static final int COLD_ITERATIONS = 60;
    private static final int ONSET_ITERATIONS = 40;
    private static final float SPARSITY = 0.02f;
    /** Spectral increase (of a peak-normalised signal) below which no key can have been struck. */
    private static final float QUIET_FLUX = 1e-4f;
    private static ExecutorService pool;

    final int frames;
    private final short[][] sounding;
    private final short[][] onset;
    private final short[][] attack;
    private final short[][] tonality;

    Activations(int frames) {
        this.frames = frames;
        sounding = new short[PianoTemplates.KEYS][frames];
        onset = new short[PianoTemplates.KEYS][frames];
        attack = new short[PianoTemplates.KEYS][frames];
        tonality = new short[PianoTemplates.KEYS][frames];
    }

    float sounding(int key, int frame) {
        return Float.float16ToFloat(sounding[key][frame]);
    }

    float onset(int key, int frame) {
        return Float.float16ToFloat(onset[key][frame]);
    }

    /** How strongly the key's attack (the first 30 ms after a strike) is present: high right after a strike, also on a key still ringing. */
    float attack(int key, int frame) {
        return Float.float16ToFloat(attack[key][frame]);
    }

    void setAttack(int key, int frame, float value) {
        attack[key][frame] = Float.floatToFloat16(value);
    }

    /** {@link Tonality} of the key: whether its harmonic peaks stand out of the spectrum. */
    float tonality(int key, int frame) {
        return Float.float16ToFloat(tonality[key][frame]);
    }

    void setTonality(int key, int frame, float value) {
        tonality[key][frame] = Float.floatToFloat16(value);
    }

    void set(int key, int frame, float soundingValue, float onsetValue) {
        sounding[key][frame] = Float.floatToFloat16(soundingValue);
        onset[key][frame] = Float.floatToFloat16(onsetValue);
    }

    private static synchronized ExecutorService pool() {
        if (pool == null) {
            int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 2));
            AtomicInteger number = new AtomicInteger();
            pool = Executors.newFixedThreadPool(threads, task -> {
                Thread thread = new Thread(task, "Music transcriber #" + number.incrementAndGet());
                thread.setDaemon(true);
                thread.setPriority(Thread.MIN_PRIORITY + 1);
                return thread;
            });
        }
        return pool;
    }

    /**
     * Analyses a signal at {@link LogSpectrum#RATE}.
     *
     * @param gain        factor applied to the signal (to normalise its level without copying it)
     * @param tuningCents tuning of the signal relative to the templates
     */
    static Activations analyse(float[] signal, float gain, PianoTemplates templates, double tuningCents, Progress progress) {
        int frames = LogSpectrum.frames(signal.length);
        Activations a = new Activations(frames);
        AtomicInteger done = new AtomicInteger();
        AtomicBoolean stop = new AtomicBoolean();
        List<Future<?>> parts = new ArrayList<>();
        for (int from = 0; from < frames; from += CHUNK) {
            int start = from, end = Math.min(frames, from + CHUNK);
            parts.add(pool().submit(() -> a.analyseChunk(signal, gain, templates, tuningCents, start, end, done, stop)));
        }
        try {
            for (Future<?> part : parts) {
                while (true) {
                    progress.at(done.get() / (double) frames);
                    try {
                        part.get(100, TimeUnit.MILLISECONDS);
                        break;
                    } catch (TimeoutException e) {
                        // Still running: loop to report progress and check for cancellation again.
                    }
                }
            }
        } catch (CancellationException e) {
            stop.set(true);
            for (Future<?> part : parts) part.cancel(false);
            throw e;
        } catch (InterruptedException e) {
            stop.set(true);
            Thread.currentThread().interrupt();
            throw new CancellationException("Interrupted");
        } catch (ExecutionException e) {
            stop.set(true);
            if (e.getCause() instanceof RuntimeException r) throw r;
            if (e.getCause() instanceof Error err) throw err;
            throw new IllegalStateException(e.getCause());
        }
        progress.at(1);
        return a;
    }

    private void analyseChunk(float[] signal, float gain, PianoTemplates templates, double tuningCents,
                              int from, int to, AtomicInteger done, AtomicBoolean stop) {
        LogSpectrum spectrum = new LogSpectrum(tuningCents);
        OnsetSpectrum increases = new OnsetSpectrum();
        TemplateSolver soundingSolver = new TemplateSolver(templates.frame);
        TemplateSolver onsetSolver = new TemplateSolver(templates.onset);
        Tonality tonal = new Tonality(templates);
        float[] peaks = new float[PianoTemplates.KEYS];
        int per = PianoTemplates.PER_KEY;
        float[] x = new float[LogSpectrum.BINS];
        float[] d = new float[LogSpectrum.BINS];
        float[] h = new float[PianoTemplates.KEYS * per];
        float[] o = new float[PianoTemplates.KEYS * per];
        int first = Math.max(0, from - WARM_UP);
        for (int t = first; t < to; t++) {
            if (stop.get()) return;
            spectrum.compute(signal, t, x);
            for (int b = 0; b < x.length; b++) x[b] *= gain;
            float increase = increases.next(x, d);
            soundingSolver.solve(x, h, t == first ? COLD_ITERATIONS : SOUNDING_ITERATIONS, SPARSITY);
            if (t < from) continue;
            java.util.Arrays.fill(o, 0f);
            if (increase > QUIET_FLUX) onsetSolver.solve(d, o, ONSET_ITERATIONS, SPARSITY);
            tonal.compute(x, peaks);
            for (int k = 0; k < PianoTemplates.KEYS; k++) {
                float s = 0, n = 0;
                for (int i = 0; i < per; i++) {
                    s += h[k * per + i];
                    n += o[k * per + i];
                }
                set(k, t, s, n);
                setAttack(k, t, h[k * per]);
                setTonality(k, t, peaks[k]);
            }
            done.incrementAndGet();
        }
    }
}
