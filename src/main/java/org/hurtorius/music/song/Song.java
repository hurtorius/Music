package org.hurtorius.music.song;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A piece of music as the piano understands it: notes on the 88 keys, pedal changes, and the
 * tempo/meter needed to draw sheet music and run a metronome. Times are milliseconds from the
 * start. Immutable; build with {@link Builder}.
 *
 * <p>Keys are 0..87 (A0..C8, MIDI 21..108). Velocities are 1..127. Hands are
 * {@link #HAND_UNKNOWN}, {@link #HAND_LEFT} or {@link #HAND_RIGHT}.</p>
 */
public final class Song {
    public static final int HAND_UNKNOWN = 0, HAND_LEFT = 1, HAND_RIGHT = 2;
    public static final int PEDAL_SUSTAIN = 0, PEDAL_SOSTENUTO = 1, PEDAL_SOFT = 2;
    public static final int MAX_NOTES = 250_000;
    public static final int MAX_PEDALS = 100_000;
    public static final long MAX_DURATION_MS = 4L * 60 * 60 * 1000;
    public static final int MAX_NAME = 96;

    private final String name;
    private final long durationMs;
    // notes, sorted by start then key
    private final int[] start, length;
    private final byte[] key, velocity, hand;
    // pedal changes, sorted by time
    private final int[] pedalTime;
    private final byte[] pedalType, pedalValue;
    // tempo: microseconds per quarter note from a time on; meter changes
    private final int[] tempoTime, tempoMicros;
    private final int[] meterTime;
    private final byte[] meterNumerator, meterDenominator;

    private Song(Builder b) {
        this.name = b.name;
        long[][] sorted = b.notes.toArray(new long[0][]);
        Arrays.sort(sorted, (x, y) -> x[0] != y[0] ? Long.compare(x[0], y[0]) : Long.compare(x[2], y[2]));
        // The same key struck twice at the same moment is one note: keep the longer, louder one.
        List<long[]> unique = new ArrayList<>(sorted.length);
        for (long[] e : sorted) {
            long[] last = unique.isEmpty() ? null : unique.get(unique.size() - 1);
            if (last != null && last[0] == e[0] && last[2] == e[2]) {
                last[1] = Math.max(last[1], e[1]);
                last[3] = Math.max(last[3], e[3]);
            } else {
                unique.add(e.clone());
            }
        }
        long[][] notes = unique.toArray(new long[0][]);
        int n = notes.length;
        start = new int[n];
        length = new int[n];
        key = new byte[n];
        velocity = new byte[n];
        hand = new byte[n];
        long end = 0;
        for (int i = 0; i < n; i++) {
            long[] e = notes[i];
            start[i] = (int) e[0];
            length[i] = (int) e[1];
            key[i] = (byte) e[2];
            velocity[i] = (byte) e[3];
            hand[i] = (byte) e[4];
            end = Math.max(end, e[0] + e[1]);
        }
        int p = b.pedals.size();
        long[][] pedals = b.pedals.toArray(new long[0][]);
        Arrays.sort(pedals, (x, y) -> Long.compare(x[0], y[0]));
        pedalTime = new int[p];
        pedalType = new byte[p];
        pedalValue = new byte[p];
        for (int i = 0; i < p; i++) {
            pedalTime[i] = (int) pedals[i][0];
            pedalType[i] = (byte) pedals[i][1];
            pedalValue[i] = (byte) pedals[i][2];
            end = Math.max(end, pedals[i][0]);
        }
        List<long[]> tempos = new ArrayList<>(b.tempos);
        tempos.sort((x, y) -> Long.compare(x[0], y[0]));
        if (tempos.isEmpty() || tempos.get(0)[0] > 0) tempos.add(0, new long[]{0, 500_000});
        tempoTime = new int[tempos.size()];
        tempoMicros = new int[tempos.size()];
        for (int i = 0; i < tempos.size(); i++) {
            tempoTime[i] = (int) tempos.get(i)[0];
            tempoMicros[i] = (int) tempos.get(i)[1];
        }
        List<long[]> meters = new ArrayList<>(b.meters);
        meters.sort((x, y) -> Long.compare(x[0], y[0]));
        if (meters.isEmpty() || meters.get(0)[0] > 0) meters.add(0, new long[]{0, 4, 4});
        meterTime = new int[meters.size()];
        meterNumerator = new byte[meters.size()];
        meterDenominator = new byte[meters.size()];
        for (int i = 0; i < meters.size(); i++) {
            meterTime[i] = (int) meters.get(i)[0];
            meterNumerator[i] = (byte) meters.get(i)[1];
            meterDenominator[i] = (byte) meters.get(i)[2];
        }
        this.durationMs = Math.max(end, b.minDurationMs);
    }

    public String name() { return name; }
    public long durationMs() { return durationMs; }
    public int noteCount() { return start.length; }
    public int noteStart(int i) { return start[i]; }
    public int noteLength(int i) { return length[i]; }
    public int noteEnd(int i) { return start[i] + length[i]; }
    /** 0..87 */
    public int noteKey(int i) { return key[i]; }
    public int noteMidi(int i) { return key[i] + 21; }
    public int noteVelocity(int i) { return velocity[i]; }
    public int noteHand(int i) { return hand[i]; }
    public int pedalCount() { return pedalTime.length; }
    public int pedalTime(int i) { return pedalTime[i]; }
    /** {@link #PEDAL_SUSTAIN}, {@link #PEDAL_SOSTENUTO} or {@link #PEDAL_SOFT} */
    public int pedalType(int i) { return pedalType[i]; }
    /** 0..127; 64 and above is down. */
    public int pedalValue(int i) { return pedalValue[i] & 0x7f; }
    public int tempoCount() { return tempoTime.length; }
    public int tempoTime(int i) { return tempoTime[i]; }
    public int tempoMicrosPerQuarter(int i) { return tempoMicros[i]; }
    public int meterCount() { return meterTime.length; }
    public int meterTime(int i) { return meterTime[i]; }
    public int meterNumerator(int i) { return meterNumerator[i]; }
    public int meterDenominator(int i) { return meterDenominator[i]; }

    /** First note index whose start is >= ms (binary search). */
    public int firstNoteAtOrAfter(long ms) {
        int lo = 0, hi = start.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (start[mid] < ms) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    /** First pedal change index whose time is >= ms. */
    public int firstPedalAtOrAfter(long ms) {
        int lo = 0, hi = pedalTime.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (pedalTime[mid] < ms) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    /** Tempo (microseconds per quarter note) in effect at ms. */
    public int tempoAt(long ms) {
        int t = tempoMicros[0];
        for (int i = 0; i < tempoTime.length && tempoTime[i] <= ms; i++) t = tempoMicros[i];
        return t;
    }

    /** Index of the meter in effect at ms. */
    public int meterIndexAt(long ms) {
        int m = 0;
        for (int i = 0; i < meterTime.length && meterTime[i] <= ms; i++) m = i;
        return m;
    }

    /** Milliseconds of each bar line start, from the tempo and meter maps, up to the song's end. */
    public long[] barLines() {
        List<Long> bars = new ArrayList<>();
        double t = 0;
        while (t <= durationMs && bars.size() < 100_000) {
            bars.add((long) t);
            int m = meterIndexAt((long) t);
            double beatsPerBar = meterNumerator[m] * (4.0 / Math.max(1, meterDenominator[m]));
            double quarterMs = tempoAt((long) t) / 1000.0;
            t += Math.max(50.0, beatsPerBar * quarterMs);
        }
        long[] out = new long[bars.size()];
        for (int i = 0; i < out.length; i++) out[i] = bars.get(i);
        return out;
    }

    public Builder toBuilder() {
        Builder b = new Builder(name);
        for (int i = 0; i < noteCount(); i++) b.note(start[i], length[i], key[i], velocity[i], hand[i]);
        for (int i = 0; i < pedalCount(); i++) b.pedal(pedalTime[i], pedalType[i], pedalValue(i));
        for (int i = 0; i < tempoTime.length; i++) b.tempo(tempoTime[i], tempoMicros[i]);
        for (int i = 0; i < meterTime.length; i++) b.meter(meterTime[i], meterNumerator[i], meterDenominator[i]);
        b.minDuration(durationMs);
        return b;
    }

    public static final class Builder {
        private String name;
        private final List<long[]> notes = new ArrayList<>();
        private final List<long[]> pedals = new ArrayList<>();
        private final List<long[]> tempos = new ArrayList<>();
        private final List<long[]> meters = new ArrayList<>();
        private long minDurationMs;

        public Builder(String name) {
            name(name);
        }

        public Builder name(String name) {
            String n = name == null ? "" : name.strip().replaceAll("[\\p{Cntrl}]", "");
            this.name = n.isEmpty() ? "Untitled" : n.length() > MAX_NAME ? n.substring(0, MAX_NAME) : n;
            return this;
        }

        /** Adds a note; out-of-range keys are dropped, other values clamped. Returns false if dropped. */
        public boolean note(long startMs, long lengthMs, int key, int velocity, int hand) {
            if (key < 0 || key > 87 || startMs < 0 || startMs > MAX_DURATION_MS || notes.size() >= MAX_NOTES) return false;
            long len = Math.max(1, Math.min(lengthMs, MAX_DURATION_MS - startMs));
            notes.add(new long[]{startMs, len, key, Math.max(1, Math.min(127, velocity)), hand == HAND_LEFT || hand == HAND_RIGHT ? hand : HAND_UNKNOWN});
            return true;
        }

        public boolean noteMidi(long startMs, long lengthMs, int midi, int velocity, int hand) {
            return note(startMs, lengthMs, midi - 21, velocity, hand);
        }

        public boolean pedal(long timeMs, int type, int value) {
            if (timeMs < 0 || timeMs > MAX_DURATION_MS || type < 0 || type > 2 || pedals.size() >= MAX_PEDALS) return false;
            pedals.add(new long[]{timeMs, type, Math.max(0, Math.min(127, value))});
            return true;
        }

        public Builder tempo(long timeMs, int microsPerQuarter) {
            if (timeMs >= 0 && timeMs <= MAX_DURATION_MS && microsPerQuarter >= 60_000 && microsPerQuarter <= 4_000_000 && tempos.size() < 10_000) {
                tempos.add(new long[]{timeMs, microsPerQuarter});
            }
            return this;
        }

        public Builder meter(long timeMs, int numerator, int denominator) {
            if (timeMs >= 0 && timeMs <= MAX_DURATION_MS && numerator >= 1 && numerator <= 32
                && (denominator == 1 || denominator == 2 || denominator == 4 || denominator == 8 || denominator == 16 || denominator == 32)
                && meters.size() < 10_000) {
                meters.add(new long[]{timeMs, numerator, denominator});
            }
            return this;
        }

        public Builder minDuration(long ms) {
            minDurationMs = Math.max(0, Math.min(ms, MAX_DURATION_MS));
            return this;
        }

        public int noteCount() {
            return notes.size();
        }

        public Song build() {
            return new Song(this);
        }
    }
}
