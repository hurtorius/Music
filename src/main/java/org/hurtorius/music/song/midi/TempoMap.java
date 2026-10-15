package org.hurtorius.music.song.midi;

import javax.sound.midi.Sequence;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Converts a MIDI file's ticks to time. PPQ files follow their tempo changes (from every track;
 * 120 bpm until the first one); SMPTE files run at a fixed rate and ignore tempo, as the standard
 * says. Sums are kept as exact products of ticks and tempo, so long pieces with many tempo changes
 * do not drift.
 */
final class TempoMap {
    static final int DEFAULT_TEMPO = 500_000;

    private final boolean smpte;
    private final double microsPerTick;
    private final int ppq;
    private final long[] segmentTick;
    /** Microseconds × ppq at each segment start: an exact integer while it stays below 2^53. */
    private final double[] segmentScaledMicros;
    private final int[] segmentTempo;

    private TempoMap(boolean smpte, double microsPerTick, int ppq, long[] segmentTick, double[] segmentScaledMicros, int[] segmentTempo) {
        this.smpte = smpte;
        this.microsPerTick = microsPerTick;
        this.ppq = ppq;
        this.segmentTick = segmentTick;
        this.segmentScaledMicros = segmentScaledMicros;
        this.segmentTempo = segmentTempo;
    }

    static TempoMap of(MidiData data) {
        float type = data.divisionType;
        if (type != Sequence.PPQ) {
            double fps = type == Sequence.SMPTE_30DROP ? 30_000.0 / 1001.0 : type;
            return new TempoMap(true, 1_000_000.0 / (fps * data.resolution), 0, new long[0], new double[0], new int[0]);
        }
        List<long[]> changes = sortedTempos(data);
        List<long[]> segments = new ArrayList<>();
        segments.add(new long[]{0, DEFAULT_TEMPO});
        for (long[] change : changes) {
            long[] last = segments.get(segments.size() - 1);
            if (change[0] == last[0]) last[1] = change[1];
            else if (change[1] != last[1]) segments.add(new long[]{change[0], change[1]});
        }
        int n = segments.size();
        long[] ticks = new long[n];
        double[] scaled = new double[n];
        int[] tempos = new int[n];
        for (int i = 0; i < n; i++) {
            ticks[i] = segments.get(i)[0];
            tempos[i] = (int) segments.get(i)[1];
            if (i > 0) scaled[i] = scaled[i - 1] + (double) (ticks[i] - ticks[i - 1]) * tempos[i - 1];
        }
        return new TempoMap(false, 0, data.resolution, ticks, scaled, tempos);
    }

    /** Tempo changes ordered by tick; at one tick, the one written last (track order, then file order) wins. */
    static List<long[]> sortedTempos(MidiData data) {
        List<long[]> changes = new ArrayList<>(data.tempos);
        changes.sort(Comparator.comparingLong(c -> c[0]));
        return changes;
    }

    /** Microseconds from the start to {@code tick}. */
    double micros(long tick) {
        if (smpte) return tick * microsPerTick;
        int lo = 0, hi = segmentTick.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (segmentTick[mid] <= tick) lo = mid; else hi = mid - 1;
        }
        return (segmentScaledMicros[lo] + (double) (tick - segmentTick[lo]) * segmentTempo[lo]) / ppq;
    }

    /** Milliseconds from the start to {@code tick}, rounded to the nearest. */
    long millis(long tick) {
        return Math.round(micros(tick) / 1000.0);
    }
}
