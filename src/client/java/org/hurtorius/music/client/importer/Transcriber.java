package org.hurtorius.music.client.importer;

import org.hurtorius.music.song.Song;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;

/**
 * Listens to recorded audio and writes down the piano notes in it, as a {@link Song}. The result
 * is an approximation: it is good on clear solo piano, rougher on other instruments, voices and
 * dense mixes, and it does not recover pedalling.
 *
 * <p>How it works: the audio is resampled to 22.05 kHz and analysed every 10 ms with a
 * constant-Q style spectrum (three bins per semitone, longer windows in the bass). Each frame, and
 * each frame's spectral increase, is explained as a non-negative mix of spectral templates of all
 * 88 keys that are learned at run time from the mod's own piano samples ({@link PianoTemplates});
 * a key's template carries its whole harmonic series, which keeps octaves and other harmonics of a
 * played note from becoming notes of their own. The recording's tuning is measured first so that
 * a piano tuned away from A440 still lines up. Strikes are then picked from the per-key onset
 * evidence by {@link NoteTracker}, their loudness gives the velocity, the spacing of the onsets
 * gives a tempo for the sheet music, and the notes are split between the hands by pitch.</p>
 *
 * <p>All entry points block except {@link #importAudio}, which runs on a background thread. Progress
 * callbacks arrive on the working thread; hop to the client thread before touching game state.</p>
 */
public final class Transcriber {
    private static final Logger LOG = LoggerFactory.getLogger("Music");
    /** Suffix that marks a song as transcribed (approximate). */
    public static final String TRANSCRIBED_SUFFIX = " (transcribed)";
    private static final double VELOCITY_AT_MEDIAN = 76;
    private static final double VELOCITY_PER_DB = 1.5;
    private static final int MIN_NOTE_MS = 30;
    private static final double SILENCE = 1e-5;
    private static ExecutorService worker;

    /**
     * Transcription settings.
     *
     * @param sensitivity        0..1; 0.5 is the tuned default, higher keeps quieter and less certain notes
     * @param trimLeadingSilence start the song at its first note instead of where the recording starts
     * @param splitHands         assign left/right hands by pitch (otherwise hands are unknown)
     */
    public record Options(double sensitivity, boolean trimLeadingSilence, boolean splitHands) {
        public static final Options DEFAULT = new Options(0.5, true, true);

        public Options {
            if (!(sensitivity >= 0 && sensitivity <= 1)) throw new IllegalArgumentException("Sensitivity must be 0..1: " + sensitivity);
        }
    }

    private Transcriber() {}

    /** The library name for a transcription of {@code file}: its name without extension, marked as transcribed. */
    public static String songName(Path file) {
        String name = file.getFileName() == null ? "Audio" : file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        name = name.strip();
        if (name.isEmpty()) name = "Audio";
        int room = Song.MAX_NAME - TRANSCRIBED_SUFFIX.length();
        if (name.length() > room) name = name.substring(0, room).strip();
        return name + TRANSCRIBED_SUFFIX;
    }

    /**
     * Decodes and transcribes a file on a background thread (one import at a time; later calls
     * queue), naming the song with {@link #songName}. The future completes with the song, or fails
     * with a {@link CompletionException} whose cause is an {@link AudioImportException} (its message
     * is ready to show the player) or a {@link CancellationException} after {@code cancelled}
     * returned true.
     *
     * @param progress  0..1 over decoding and transcription, called on the background thread
     * @param cancelled polled regularly on the background thread
     */
    public static CompletableFuture<Song> importAudio(Path file, Options options, DoubleConsumer progress, BooleanSupplier cancelled) {
        return CompletableFuture.supplyAsync(() -> {
            Progress p = Progress.of(progress, cancelled);
            try {
                DecodedAudio audio = AudioDecoder.decode(file, LogSpectrum.RATE, p.slice(0, 0.2));
                return transcribe(audio, songName(file), options, p.slice(0.2, 1));
            } catch (AudioImportException e) {
                LOG.warn("Audio import of {} failed: {}", file, e.getMessage());
                throw new CompletionException(e);
            } catch (CancellationException e) {
                LOG.info("Audio import of {} cancelled", file);
                throw e;
            } catch (RuntimeException e) {
                LOG.error("Audio import of {} failed unexpectedly", file, e);
                throw new CompletionException(new AudioImportException("Transcription failed unexpectedly (" + e + "). See the log for details.", e));
            }
        }, worker());
    }

    /** Transcribes with {@link Options#DEFAULT}. */
    public static Song transcribe(DecodedAudio audio, String name, DoubleConsumer progress, BooleanSupplier cancelled)
        throws AudioImportException {
        return transcribe(audio, name, Options.DEFAULT, progress, cancelled);
    }

    /**
     * Transcribes decoded audio. Blocks for a few seconds per minute of audio (more the first time,
     * while the piano templates are learned); run it off the client thread.
     *
     * @throws AudioImportException  if the audio is silent, contains no recognisable notes, or the piano samples are missing
     * @throws CancellationException if {@code cancelled} returned true
     */
    public static Song transcribe(DecodedAudio audio, String name, Options options, DoubleConsumer progress, BooleanSupplier cancelled)
        throws AudioImportException {
        return transcribe(audio, name, options, Progress.of(progress, cancelled));
    }

    static Song transcribe(DecodedAudio audio, String name, Options options, Progress progress) throws AudioImportException {
        long started = System.nanoTime();
        try {
            PianoTemplates templates = PianoTemplates.get(progress.slice(0, 0.06));
            float[] signal = audio.sampleRate() == LogSpectrum.RATE ? audio.mono()
                : Resampler.resample(audio.mono(), audio.sampleRate(), LogSpectrum.RATE);
            progress.at(0.08);
            float level = robustPeak(signal);
            if (level < SILENCE) throw new AudioImportException("This audio is silent; there is nothing to transcribe.");
            float gain = 1 / level;

            TuningEstimator tuning = new TuningEstimator();
            tuning.add(signal, 600);
            double tuningCents = wrapCents(tuning.cents() - templates.tuningCents);
            progress.at(0.1);

            Activations activations = Activations.analyse(signal, gain, templates, tuningCents, progress.slice(0.1, 0.94));
            List<NoteTracker.Detected> detected = NoteTracker.track(activations, options.sensitivity());
            progress.at(0.97);
            if (detected.isEmpty()) {
                throw new AudioImportException("No piano notes were recognised in this audio. Try a clearer recording, or a higher sensitivity.");
            }
            Song song = toSong(detected, name, options, audio.seconds());
            progress.at(1);
            LOG.info("Transcribed {}: {} notes in {} s of audio, {} bpm, tuning {} cents, took {} ms", song.name(), song.noteCount(),
                String.format(Locale.ROOT, "%.1f", audio.seconds()), Math.round(60_000_000.0 / song.tempoMicrosPerQuarter(0)),
                String.format(Locale.ROOT, "%+.0f", tuningCents), (System.nanoTime() - started) / 1_000_000);
            return song;
        } catch (OutOfMemoryError e) {
            throw new AudioImportException("Not enough memory to transcribe this audio. Give Minecraft more memory or import a shorter file.", e);
        }
    }

    private static Song toSong(List<NoteTracker.Detected> detected, String name, Options options, double seconds) {
        double hop = LogSpectrum.HOP / (double) LogSpectrum.RATE;
        int n = detected.size();
        double[] starts = new double[n];
        double[] levels = new double[n];
        int[] keys = new int[n];
        for (int i = 0; i < n; i++) {
            NoteTracker.Detected d = detected.get(i);
            starts[i] = d.onsetFrame() * hop;
            levels[i] = 20 * Math.log10(Math.max(1e-12, d.level()));
            keys[i] = d.key();
        }
        double median = median(levels);
        int[] velocities = new int[n];
        double[] weights = new double[n];
        for (int i = 0; i < n; i++) {
            velocities[i] = (int) Math.max(1, Math.min(127, Math.round(VELOCITY_AT_MEDIAN + VELOCITY_PER_DB * (levels[i] - median))));
            weights[i] = velocities[i];
        }
        double bpm = TempoEstimator.estimate(starts, weights);
        int[] hands = options.splitHands() ? HandSplitter.split(starts, keys) : new int[n];
        double first = Double.MAX_VALUE;
        for (double s : starts) first = Math.min(first, s);
        double shift = options.trimLeadingSilence() ? first : 0;

        Song.Builder builder = new Song.Builder(name);
        builder.tempo(0, (int) Math.round(60_000_000 / bpm));
        builder.meter(0, 4, 4);
        int dropped = 0;
        for (int i = 0; i < n; i++) {
            NoteTracker.Detected d = detected.get(i);
            long start = Math.round((starts[i] - shift) * 1000);
            long length = Math.max(MIN_NOTE_MS, Math.round((d.endFrame() - d.onsetFrame()) * hop * 1000));
            if (!builder.note(start, length, keys[i], velocities[i], hands[i])) dropped++;
        }
        if (dropped > 0) LOG.warn("Transcription of {} kept {} notes; {} beyond the song limit were dropped", name, Song.MAX_NOTES, dropped);
        builder.minDuration(Math.round((seconds - shift) * 1000));
        return builder.build();
    }

    /** Level below which 99.9% of the samples lie (a peak that ignores the odd click). */
    static float robustPeak(float[] signal) {
        int[] histogram = new int[1400];
        long counted = 0;
        for (float v : signal) {
            float a = Math.abs(v);
            if (!(a > 1e-7f)) continue;
            int bin = (int) Math.round(Math.max(0, Math.min(1399, (20 * Math.log10(a) + 140) * 10)));
            histogram[bin]++;
            counted++;
        }
        if (counted == 0) return 0;
        long keep = (long) Math.ceil(counted * 0.999);
        long sum = 0;
        for (int bin = 0; bin < histogram.length; bin++) {
            sum += histogram[bin];
            if (sum >= keep) return (float) Math.pow(10, (bin / 10.0 - 140) / 20);
        }
        return 1;
    }

    private static double wrapCents(double cents) {
        double c = cents % 100;
        if (c > 50) c -= 100;
        if (c < -50) c += 100;
        return c;
    }

    private static double median(double[] values) {
        double[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        int m = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[m] : (sorted[m - 1] + sorted[m]) / 2;
    }

    private static synchronized ExecutorService worker() {
        if (worker == null) {
            worker = Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "Music audio import");
                thread.setDaemon(true);
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                return thread;
            });
        }
        return worker;
    }
}
