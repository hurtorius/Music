package org.hurtorius.music.client.ui;

import org.hurtorius.music.client.play.Performer;
import org.hurtorius.music.client.play.Recorder;
import org.hurtorius.music.piano.Pedal;
import org.hurtorius.music.song.Song;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * What the performer played lately, so the sheet strip can show it rising away from the keys. It
 * hears the performer as the {@link Recorder} does (the notes as they sound, after transposition).
 * While a take is recorded it keeps the whole take, timed with {@link Recorder#elapsedMs()} (the
 * recorder still makes the real song); otherwise it keeps what free playing left in the strip, timed
 * on its own clock, and forgets each note once it has risen out of sight. Switching between the two
 * starts afresh. Client thread.
 */
final class PlayedNotes implements Performer.Listener {
    static final PlayedNotes INSTANCE = new PlayedNotes(() -> System.nanoTime() / 1_000_000L);
    /** Free playing keeps at most this many notes; a take keeps all of its own. */
    static final int MAX_FREE_NOTES = 512;
    /** Free playing's guides in the strip fade in this fast after the first note, and out this long before the last note has risen away. */
    static final long FADE_IN_MS = 150, FADE_OUT_MS = 500;
    /** Rebuilding the preview song at most this often keeps a long take cheap to show. */
    private static final long PREVIEW_MS = 200;

    private final LongSupplier clock;
    /** Each note as {start, end or -1 while held, key, velocity}, in the order they started. */
    private final List<long[]> notes = new ArrayList<>();
    private final long[][] open = new long[88][];
    private boolean recording;
    private long lastElapsed;
    private @Nullable Song preview;
    private long previewAt = Long.MIN_VALUE;
    private int previewNotes = -1;

    /** @param clock milliseconds on a monotonic clock, for free playing */
    PlayedNotes(LongSupplier clock) {
        this.clock = clock;
    }

    /** Follows the recorder starting and stopping, and lets risen notes go. Client thread, every tick. */
    void tick() {
        sync();
    }

    private void sync() {
        boolean nowRecording = Recorder.isRecording();
        long elapsed = Recorder.elapsedMs();
        if (nowRecording != recording || nowRecording && elapsed < lastElapsed) {
            notes.clear();
            for (int key = 0; key < open.length; key++) open[key] = null;
            preview = null;
            previewNotes = -1;
            recording = nowRecording;
        }
        lastElapsed = elapsed;
        if (!recording) forgetRisen(now());
    }

    /** Free playing: a note gone out of the strip is forgotten (held notes stay). */
    private void forgetRisen(long now) {
        for (Iterator<long[]> it = notes.iterator(); it.hasNext(); ) {
            long[] n = it.next();
            if (n[1] >= 0 && now - n[1] > SheetStrip.ROLL_SPAN_MS) it.remove();
        }
    }

    /** True while a take is being recorded. */
    boolean recording() {
        sync();
        return recording;
    }

    /** True when free playing left notes the strip still shows (or keys still held). */
    boolean showing() {
        sync();
        return !recording && !notes.isEmpty();
    }

    /** The clock the notes are timed on: milliseconds recorded so far, or the free-playing clock. */
    long now() {
        return recording ? Recorder.elapsedMs() : clock.getAsLong();
    }

    /** Number of notes kept; they are numbered in the order they started. */
    int size() {
        sync();
        return notes.size();
    }

    /** Start of note {@code i} on {@link #now()}'s clock. */
    long start(int i) {
        return notes.get(i)[0];
    }

    /** End of note {@code i}, or -1 while its key is still held. */
    long end(int i) {
        return notes.get(i)[1];
    }

    /** Sounding key 0..87 of note {@code i}. */
    int key(int i) {
        return (int) notes.get(i)[2];
    }

    /**
     * How much of free playing's guides in the strip show (the line at each C and the line along the
     * keys), 0..1: they fade in as the first note is struck and out before the last one has risen out
     * of the strip, so they are there only while there are notes to show.
     */
    float guidesShown() {
        sync();
        if (notes.isEmpty()) return 0f;
        long now = now(), last = -1;
        for (long[] n : notes) last = Math.max(last, n[1] < 0 ? now : n[1]);
        float in = (now - notes.getFirst()[0]) / (float) FADE_IN_MS;
        float out = (last + SheetStrip.ROLL_SPAN_MS - now) / (float) FADE_OUT_MS;
        return Math.max(0f, Math.min(1f, Math.min(in, out)));
    }

    /** The take so far as a song (rebuilt at most every {@value #PREVIEW_MS} ms), or null when not recording or before the first note. */
    @Nullable Song preview() {
        sync();
        if (!recording || notes.isEmpty()) return null;
        long now = Recorder.elapsedMs();
        if (preview == null || notes.size() != previewNotes && now - previewAt >= PREVIEW_MS || now - previewAt >= PREVIEW_MS * 5) {
            Song.Builder b = new Song.Builder("take");
            for (long[] n : notes) {
                long end = n[1] < 0 ? now : n[1];
                b.note(n[0], Math.max(1, end - n[0]), (int) n[2], (int) n[3], n[2] < 39 ? Song.HAND_LEFT : Song.HAND_RIGHT);
            }
            b.minDuration(now);
            preview = b.build();
            previewAt = now;
            previewNotes = notes.size();
        }
        return preview;
    }

    @Override
    public void noteOn(int soundingKey, int velocity) {
        sync();
        if (soundingKey < 0 || soundingKey > 87) return;
        long now = now();
        if (open[soundingKey] != null) open[soundingKey][1] = now;
        long[] note = {now, -1, soundingKey, velocity};
        open[soundingKey] = note;
        notes.add(note);
        if (!recording && notes.size() > MAX_FREE_NOTES) dropOldestFinished();
    }

    private void dropOldestFinished() {
        for (Iterator<long[]> it = notes.iterator(); it.hasNext(); ) {
            if (it.next()[1] >= 0) {
                it.remove();
                return;
            }
        }
    }

    @Override
    public void noteOff(int soundingKey) {
        sync();
        if (soundingKey < 0 || soundingKey > 87 || open[soundingKey] == null) return;
        open[soundingKey][1] = now();
        open[soundingKey] = null;
    }

    @Override
    public void pedal(Pedal pedal, boolean down) {
        // The strip shows notes only; pedals are in the recorded song itself.
    }
}
