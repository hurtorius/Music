package org.hurtorius.music.client.play;

import org.hurtorius.music.piano.Pedal;
import org.hurtorius.music.song.Song;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Records what the local performer plays (the notes as they sound, after transposition) into a
 * {@link Song}. Leading silence is trimmed to a quarter second. When the metronome was running,
 * the song starts on a bar line of the clicks the performer heard and carries that tempo and
 * meter, so the sheet's bars match the playing. Notes and pedals still held when recording stops end then.
 * Client thread.
 */
public final class Recorder {
    private static final long LEAD_IN_NANOS = 250_000_000L;
    private static boolean recording;
    private static long startNanos;
    private static long firstEventNanos;
    private static Metronome.@Nullable Grid grid;
    private static final long[] downAt = new long[88];
    private static final int[] downVelocity = new int[88];
    private static final List<long[]> notes = new ArrayList<>();
    private static final List<long[]> pedals = new ArrayList<>();
    /** Pedals the performer already held down when the take started, by song pedal type. */
    private static final boolean[] pedalsAtStart = new boolean[3];

    private Recorder() {}

    public static void start() {
        notes.clear();
        pedals.clear();
        Arrays.fill(downAt, -1);
        for (Pedal pedal : Pedal.values()) pedalsAtStart[Pianos.songPedalType(pedal)] = Performer.isPedalDown(pedal);
        startNanos = System.nanoTime();
        firstEventNanos = -1;
        grid = null;
        recording = true;
    }

    public static boolean isRecording() {
        return recording;
    }

    public static long elapsedMs() {
        return recording ? (System.nanoTime() - startNanos) / 1_000_000L : 0;
    }

    public static int noteCount() {
        return notes.size();
    }

    /** Throws away the take. */
    public static void cancel() {
        recording = false;
        notes.clear();
        pedals.clear();
    }

    /** Ends the take; returns null when nothing was played. */
    public static @Nullable Song stop(String name) {
        if (!recording) return null;
        recording = false;
        long end = System.nanoTime();
        for (int k = 0; k < 88; k++) if (downAt[k] >= 0) close(k, end);
        if (notes.isEmpty()) {
            pedals.clear();
            return null;
        }
        long zero = firstEventNanos - LEAD_IN_NANOS;
        Song.Builder b = new Song.Builder(name);
        if (grid != null) {
            // Start the song on the last bar line at or before the lead-in, on the metronome's own clicks.
            long bar = grid.interval() * grid.beatsPerBar();
            long barStart = grid.clickNanos() - grid.beatInBar() * grid.interval();
            while (barStart > zero) barStart -= bar;
            while (barStart + bar <= zero) barStart += bar;
            zero = barStart;
            b.tempo(0, (int) Math.max(60_000, Math.min(4_000_000, grid.interval() / 1000)));
            b.meter(0, grid.beatsPerBar(), 4);
        }
        for (long[] n : notes) {
            b.note((n[0] - zero) / 1_000_000L, (n[1] - n[0]) / 1_000_000L, (int) n[2], (int) n[3], n[2] < 39 ? Song.HAND_LEFT : Song.HAND_RIGHT);
        }
        // A pedal held when recording began is down from the start of the take.
        boolean[] down = pedalsAtStart.clone();
        for (int type = 0; type < 3; type++) {
            if (down[type]) b.pedal(0, type, 127);
        }
        for (long[] p : pedals) {
            b.pedal(Math.max(0, (p[0] - zero) / 1_000_000L), (int) p[1], (int) p[2]);
            down[(int) p[1]] = p[2] >= 64;
        }
        // A pedal still held when the take stops comes up there, as the notes do: the take ends the
        // same way however it was stopped, and an exported file does not end with the sustain stuck down.
        for (int type = 0; type < 3; type++) {
            if (down[type]) b.pedal((end - zero) / 1_000_000L, type, 0);
        }
        b.minDuration((end - zero) / 1_000_000L);
        notes.clear();
        pedals.clear();
        return b.build();
    }

    static void noteOn(int key, int velocity, long nanos) {
        if (!recording) return;
        firstEvent(nanos);
        if (downAt[key] >= 0) close(key, nanos);
        downAt[key] = nanos;
        downVelocity[key] = velocity;
    }

    static void noteOff(int key, long nanos) {
        if (!recording || downAt[key] < 0) return;
        close(key, nanos);
    }

    static void pedal(int type, int value, long nanos) {
        if (!recording) return;
        firstEvent(nanos);
        pedals.add(new long[]{nanos, type, value});
    }

    /** The grid is taken at the first event, so later tempo changes cannot move the take's bars. */
    private static void firstEvent(long nanos) {
        if (firstEventNanos >= 0) return;
        firstEventNanos = nanos;
        grid = Metronome.grid();
    }

    private static void close(int key, long nanos) {
        notes.add(new long[]{downAt[key], Math.max(nanos, downAt[key] + 1_000_000L), key, downVelocity[key]});
        downAt[key] = -1;
    }
}
