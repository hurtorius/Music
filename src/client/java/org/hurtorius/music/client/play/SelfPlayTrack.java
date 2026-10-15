package org.hurtorius.music.client.play;

import org.hurtorius.music.piano.Pedal;
import org.hurtorius.music.song.Song;
import org.jspecify.annotations.Nullable;

import java.util.Comparator;
import java.util.PriorityQueue;

/**
 * One self-playing piano as this client sounds it. It follows the server's timeline
 * {@link #PRESENTATION_DELAY_MS} behind, starts over only when the timeline really moves, and hands
 * notes and pedals to its {@link Output} a little ahead of time so they start sample-accurately while
 * the keys move in step. It holds no game state, so its timing can be checked on its own;
 * {@link SelfPlayer} feeds it each piano's timeline. Times are {@link System#nanoTime()} instants.
 * Client thread.
 */
public final class SelfPlayTrack {
    /**
     * Every client sounds the timeline this many song milliseconds behind the server. It is a fixed
     * stretch of the song, so a change of speed does not move the point being heard.
     */
    public static final long PRESENTATION_DELAY_MS = 250;
    /** How far ahead notes are handed out. */
    public static final long LOOKAHEAD_MS = 400;
    /** Start over when the running estimate drifts this far from the timeline. */
    static final long DRIFT_MS = 80;
    /** Notes due more than this long ago (after a client hitch) are skipped rather than blurted out. */
    static final long LATE_MS = 60;
    /** Most notes handed out per call; denser passages are thinned. */
    static final int MAX_NOTES_PER_TICK = 256;
    /**
     * Releases due further ahead than this wait in the track, in song time: the audio engine refuses
     * events far in the future, and a pause would not move them there.
     */
    public static final long OFF_AHEAD_MS = 2_000;
    /** After a pause, how long the timeline copy from before it is distrusted while the corrected one is on its way. */
    static final long RESYNC_MS = 2_000;

    /** Where a track's notes go: the piano's keys and its sound. */
    public interface Output {
        void noteOn(int key, int velocity, long at);

        void noteOff(int key, long at);

        void pedal(Pedal pedal, boolean down, long at);

        /**
         * Lets go of everything, including what was handed out for later (which never sounds). With
         * {@code ringOut} the dampers fall and the strings die away, as when a pianist stops (the song
         * ended, was paused or stopped, the next one begins); without it the sound stops at once (the
         * song jumps elsewhere, or is out of earshot).
         */
        void silence(long now, boolean ringOut);
    }

    private final Output out;
    private @Nullable String songId;
    private @Nullable Song song;
    private int transpose;
    private float speed;
    /** Revision of the timeline copy last followed. */
    private int revision;
    private long anchorNanos;
    private long anchorPositionMs;
    private int noteCursor;
    private int pedalCursor;
    /** Releases of notes already handed out, kept until they are due soon: {end in song ms, key}. */
    private final PriorityQueue<long[]> pendingOffs = new PriorityQueue<>(Comparator.comparingLong(p -> p[0]));
    private long lastStamp = Long.MIN_VALUE;
    private long staleStamp = Long.MIN_VALUE;
    private long resyncUntil;

    public SelfPlayTrack(Output out) {
        this.out = out;
    }

    /**
     * Follows the piano's timeline and hands out what is due soon.
     *
     * <p>Every copy of the timeline reaches this client late: it says where the song was when the
     * server sent it, and counts from when it arrived, after the trip and the wait for the next frame.
     * So while the timeline has not moved (the same revision), a copy behind the track only took
     * longer on the way than the one the track started from (or the server's wall clock was set
     * back), and never pulls the song back, which would repeat what was just heard; a copy more than
     * {@link #DRIFT_MS} ahead came quicker and moves the song forward to it. A timeline the server
     * moved (a new revision: a seek, a song starting over) is followed either way.</p>
     *
     * @param revision      the timeline's revision, which changes whenever the server moves it
     * @param timelineMs    the song position the server's timeline has now, as this client knows it
     * @param timelineStamp when the copy of the timeline in use arrived; a newer copy has a different stamp
     */
    public void follow(String id, Song song, int transpose, float speed, int revision, long timelineMs, long timelineStamp, long now) {
        long position = timelineMs - PRESENTATION_DELAY_MS;
        // The copy from before a pause still counts the paused time; the track already took it out.
        boolean stale = timelineStamp == staleStamp && now < resyncUntil;
        long ahead = position - positionAt(now);
        boolean moved = revision != this.revision ? Math.abs(ahead) > DRIFT_MS : ahead > DRIFT_MS;
        boolean jumped = this.song == null || !id.equals(songId) || this.transpose != transpose || this.speed != speed
            || !stale && moved;
        // A new song, or this one starting over after its end, follows what rang on; a seek or a change of key or speed cuts it.
        boolean ringOut = this.song != null && (!id.equals(songId) || positionAt(now) >= this.song.durationMs());
        if (jumped) start(id, song, transpose, speed, position, now, ringOut);
        this.revision = revision;
        lastStamp = timelineStamp;
        schedule(now);
    }

    /**
     * The game was paused for {@code pausedNanos}. The pause did not happen for the song (the server
     * takes the same pause out of its timeline): the track continues where it stopped, and hands out
     * again the notes that came due while paused (the audio engine dropped them).
     */
    public void resume(long pausedNanos, long now) {
        anchorNanos += pausedNanos;
        Song song = this.song;
        if (song == null) return;
        long position = positionAt(now);
        noteCursor = song.firstNoteAtOrAfter(position);
        pedalCursor = song.firstPedalAtOrAfter(position);
        staleStamp = lastStamp;
        resyncUntil = now + RESYNC_MS * 1_000_000L;
    }

    /** Stops sounding the song; {@code ringOut} lets the strings die away (see {@link Output#silence}). */
    public void silence(long now, boolean ringOut) {
        out.silence(now, ringOut);
        pendingOffs.clear();
        song = null;
    }

    public boolean sounding() {
        return song != null;
    }

    public @Nullable String songId() {
        return songId;
    }

    public @Nullable Song song() {
        return song;
    }

    /** Song position this track sounds at {@code nanos}. */
    public long positionAt(long nanos) {
        return anchorPositionMs + (long) ((nanos - anchorNanos) / 1_000_000.0 * speed);
    }

    private long nanosAt(long positionMs) {
        return anchorNanos + (long) ((positionMs - anchorPositionMs) / (double) speed * 1_000_000.0);
    }

    private void start(String id, Song song, int transpose, float speed, long position, long now, boolean ringOut) {
        if (this.song != null) silence(now, ringOut);
        this.songId = id;
        this.song = song;
        this.transpose = transpose;
        this.speed = speed;
        anchorNanos = now;
        anchorPositionMs = position;
        noteCursor = song.firstNoteAtOrAfter(position);
        pedalCursor = song.firstPedalAtOrAfter(position);
        // Pedals already down at this point of the song stay down.
        boolean[] down = new boolean[3];
        for (int i = 0; i < pedalCursor; i++) down[song.pedalType(i)] = song.pedalValue(i) >= 64;
        for (int type = 0; type < 3; type++) {
            if (down[type]) out.pedal(Pianos.pedalOf(type), true, now);
        }
    }

    private void schedule(long now) {
        Song song = this.song;
        long position = positionAt(now);
        long until = position + (long) (LOOKAHEAD_MS * speed);
        long offUntil = position + (long) (OFF_AHEAD_MS * speed);
        long late = position - (long) (LATE_MS * speed);
        // Releases first: a note that ends where the next one on its key starts lets go before the strike.
        while (!pendingOffs.isEmpty() && pendingOffs.peek()[0] <= offUntil) {
            long[] p = pendingOffs.poll();
            out.noteOff((int) p[1], Math.max(now, nanosAt(p[0])));
        }
        int handed = 0;
        while (noteCursor < song.noteCount() && song.noteStart(noteCursor) <= until && handed < MAX_NOTES_PER_TICK) {
            int i = noteCursor++;
            if (song.noteStart(i) < late) continue;
            int key = song.noteKey(i) + transpose;
            if (key < 0 || key > 87) continue;
            long on = Math.max(now, nanosAt(song.noteStart(i)));
            out.noteOn(key, song.noteVelocity(i), on);
            if (song.noteEnd(i) <= offUntil) out.noteOff(key, Math.max(on + 5_000_000L, nanosAt(song.noteEnd(i))));
            else pendingOffs.add(new long[]{song.noteEnd(i), key});
            handed++;
        }
        // A passage denser than the cap leaves notes behind; they are past due next time and skipped.
        while (pedalCursor < song.pedalCount() && song.pedalTime(pedalCursor) <= until) {
            int i = pedalCursor++;
            long at = Math.max(now, nanosAt(song.pedalTime(i)));
            out.pedal(Pianos.pedalOf(song.pedalType(i)), song.pedalValue(i) >= 64, at);
        }
    }
}
