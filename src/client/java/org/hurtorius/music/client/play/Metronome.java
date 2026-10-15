package org.hurtorius.music.client.play;

import org.hurtorius.music.client.ClientPrefs;
import org.hurtorius.music.client.audio.PianoAudio;

import org.jspecify.annotations.Nullable;

import java.util.concurrent.locks.LockSupport;

/**
 * A metronome only the performer hears. Beats are timed on their own thread against
 * {@link System#nanoTime()} (within about a millisecond), independent of frame rate and game ticks.
 * It runs while enabled in {@link ClientPrefs} and the player is at a piano.
 */
public final class Metronome {
    private static volatile boolean running;
    private static volatile long beatZero;
    private static volatile int beat = -1;
    private static volatile int generation;
    private static volatile @Nullable Grid grid;

    /**
     * Where the clicks fall: the time of the last click, the interval after it, that click's beat
     * within the bar (0 = accent) and the beats per bar.
     */
    public record Grid(long clickNanos, long interval, int beatInBar, int beatsPerBar) {}
    private static Thread thread;

    private Metronome() {}

    /** Client thread, every tick: start or stop following the preferences and the performer. */
    static void tick() {
        boolean want = ClientPrefs.get().metronome && Performer.isActive();
        if (want && !running) start();
        else if (!want && running) stop();
    }

    /** Index of the last beat played within the bar (0 = accent), or -1 when silent. */
    public static int currentBeat() {
        return running ? beat : -1;
    }

    /** The current click grid, or null while the metronome is silent. */
    public static @Nullable Grid grid() {
        return running ? grid : null;
    }

    /** Starts counting from the next beat now (e.g. after changing the tempo). */
    public static void restart() {
        if (running) beatZero = System.nanoTime() + 100_000_000L;
    }

    private static synchronized void start() {
        running = true;
        beatZero = System.nanoTime() + 100_000_000L;
        int gen = ++generation;
        thread = new Thread(() -> run(gen), "music-metronome");
        thread.setDaemon(true);
        thread.start();
    }

    private static synchronized void stop() {
        running = false;
        generation++;
        beat = -1;
        grid = null;
        if (thread != null) LockSupport.unpark(thread);
        thread = null;
    }

    static void reset() {
        stop();
    }

    private static void run(int gen) {
        long n = 0;
        long zero = beatZero;
        long next = zero;
        while (running && gen == generation) {
            if (zero != beatZero) {
                zero = beatZero;
                next = zero;
                n = 0;
            }
            long wait = next - System.nanoTime();
            if (wait > 2_000_000L) {
                LockSupport.parkNanos(Math.min(wait - 1_000_000L, 50_000_000L));
                continue;
            }
            while (System.nanoTime() < next) Thread.onSpinWait();
            if (!running || gen != generation) break;
            ClientPrefs prefs = ClientPrefs.get();
            int beats = Math.max(1, prefs.metronomeBeats);
            int b = (int) (n % beats);
            beat = b;
            PianoAudio.click(b == 0, prefs.metronomeVolume);
            n++;
            // The interval is read after each beat, so tempo changes apply from the next beat on.
            long interval = 60_000_000_000L / Math.max(20, prefs.metronomeBpm);
            grid = new Grid(next, interval, b, beats);
            next += interval;
        }
    }
}
