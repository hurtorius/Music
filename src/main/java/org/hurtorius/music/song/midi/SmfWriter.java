package org.hurtorius.music.song.midi;

import org.hurtorius.music.song.Song;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Writes a {@link Song} as a format 1 Standard MIDI File: a conductor track (song name, time
 * signatures, tempo changes) and one named track per hand on channel 1 with an acoustic grand
 * program, the pedals as CC 64/66/67 in the first hand track only (they hold for the whole channel;
 * a copy in every track would make a reader count a pedal that comes up and goes down on one
 * instant once per track). Resolution is 480 ticks per quarter note;
 * songs with tempos slower than 62.5 bpm get 960, 1920 or 3840 so that every event still lands
 * within a millisecond of where it was.
 */
final class SmfWriter {
    static final int PPQ = 480;
    static final String RIGHT_TRACK = "Right hand", LEFT_TRACK = "Left hand", PIANO_TRACK = "Piano";
    private static final int MAX_MICROS_PER_TICK = 2000;
    private static final int RELEASE_VELOCITY = 64;
    private static final int[] PEDAL_CONTROLLER = {64, 66, 67}; // indexed by Song.PEDAL_*

    private SmfWriter() {}

    static void write(Song song, OutputStream out) throws IOException {
        int ppq = resolutionFor(song);
        TickMap ticks = new TickMap(song, ppq);
        long endTick = ticks.tick(song.durationMs());

        List<byte[]> tracks = new ArrayList<>();
        tracks.add(conductor(song, ticks, endTick));
        int[] hands = {Song.HAND_RIGHT, Song.HAND_LEFT, Song.HAND_UNKNOWN};
        String[] names = {RIGHT_TRACK, LEFT_TRACK, PIANO_TRACK};
        for (int h = 0; h < hands.length; h++) {
            if (hasHand(song, hands[h])) tracks.add(handTrack(song, ticks, endTick, hands[h], names[h], tracks.size() == 1));
        }
        if (tracks.size() == 1 && song.pedalCount() > 0) tracks.add(handTrack(song, ticks, endTick, -1, PIANO_TRACK, true));

        DataOutputStream data = new DataOutputStream(out);
        data.writeBytes("MThd");
        data.writeInt(6);
        data.writeShort(1);
        data.writeShort(tracks.size());
        data.writeShort(ppq);
        for (byte[] track : tracks) {
            data.writeBytes("MTrk");
            data.writeInt(track.length);
            data.write(track);
        }
        data.flush();
    }

    /** 480, doubled while a tick of the slowest tempo would be longer than 2 ms. */
    static int resolutionFor(Song song) {
        int slowest = 0;
        for (int i = 0; i < song.tempoCount(); i++) slowest = Math.max(slowest, song.tempoMicrosPerQuarter(i));
        int ppq = PPQ;
        while (slowest / (double) ppq > MAX_MICROS_PER_TICK && ppq < PPQ * 8) ppq *= 2;
        return ppq;
    }

    private static boolean hasHand(Song song, int hand) {
        for (int i = 0; i < song.noteCount(); i++) if (song.noteHand(i) == hand) return true;
        return false;
    }

    private static byte[] conductor(Song song, TickMap ticks, long endTick) {
        Track track = new Track();
        track.meta(0, MidiData.META_TRACK_NAME, song.name().getBytes(StandardCharsets.UTF_8));
        // Merge meter and tempo changes by tick, a meter first when both share one. Changes after
        // the song's end are left out: they would only stretch the file.
        int m = 0, t = 0;
        while (true) {
            long meterTick = m < song.meterCount() ? ticks.tick(song.meterTime(m)) : Long.MAX_VALUE;
            long tempoTick = t < ticks.size() ? ticks.segmentTick[t] : Long.MAX_VALUE;
            if (Math.min(meterTick, tempoTick) > endTick) break;
            if (meterTick <= tempoTick) {
                int numerator = song.meterNumerator(m), denominator = song.meterDenominator(m);
                track.meta(meterTick, MidiData.META_TIME_SIGNATURE,
                    new byte[]{(byte) numerator, (byte) Integer.numberOfTrailingZeros(denominator), 24, 8});
                m++;
            } else {
                int micros = ticks.segmentTempo[t];
                track.meta(tempoTick, MidiData.META_TEMPO, new byte[]{(byte) (micros >> 16), (byte) (micros >> 8), (byte) micros});
                t++;
            }
        }
        track.end(endTick);
        return track.bytes();
    }

    /** One hand's notes (hand -1: none) with the program, and all pedal changes when {@code withPedals}. */
    private static byte[] handTrack(Song song, TickMap ticks, long endTick, int hand, String name, boolean withPedals) {
        Track track = new Track();
        track.meta(0, MidiData.META_TRACK_NAME, name.getBytes(StandardCharsets.UTF_8));
        track.channel(0, 0xC0, 0, 0);
        // Events sort by tick, then releases before pedals before strikes, then by creation.
        List<long[]> events = new ArrayList<>();
        for (int i = 0; i < song.noteCount(); i++) {
            if (song.noteHand(i) != hand) continue;
            long on = ticks.tick(song.noteStart(i));
            long off = Math.max(on + 1, ticks.tick(song.noteEnd(i)));
            events.add(new long[]{on, 2, events.size(), 0x90, song.noteMidi(i), song.noteVelocity(i)});
            events.add(new long[]{off, 0, events.size(), 0x80, song.noteMidi(i), RELEASE_VELOCITY});
        }
        for (int i = 0; withPedals && i < song.pedalCount(); i++) {
            events.add(new long[]{ticks.tick(song.pedalTime(i)), 1, events.size(), 0xB0, PEDAL_CONTROLLER[song.pedalType(i)], song.pedalValue(i)});
        }
        long[][] sorted = events.toArray(new long[0][]);
        Arrays.sort(sorted, (a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0]) : a[1] != b[1] ? Long.compare(a[1], b[1]) : Long.compare(a[2], b[2]));
        for (long[] e : sorted) track.channel(e[0], (int) e[3], (int) e[4], (int) e[5]);
        track.end(endTick);
        return track.bytes();
    }

    /**
     * Milliseconds to ticks with the song's tempo map. Tempo changes are placed on whole ticks
     * first; every later time is measured from where its tempo change actually landed, so rounding
     * never accumulates.
     */
    private static final class TickMap {
        final int ppq;
        final long[] segmentTick;
        final double[] segmentMicros;
        final int[] segmentTempo;
        private int size;

        TickMap(Song song, int ppq) {
            this.ppq = ppq;
            int n = song.tempoCount();
            segmentTick = new long[n];
            segmentMicros = new double[n];
            segmentTempo = new int[n];
            segmentTempo[0] = song.tempoMicrosPerQuarter(0);
            size = 1;
            for (int i = 1; i < n; i++) {
                long tick = tick(song.tempoTime(i));
                int last = size - 1;
                if (tick == segmentTick[last]) {
                    segmentTempo[last] = song.tempoMicrosPerQuarter(i);
                    continue;
                }
                segmentTick[size] = tick;
                segmentMicros[size] = segmentMicros[last] + (double) (tick - segmentTick[last]) * segmentTempo[last] / ppq;
                segmentTempo[size] = song.tempoMicrosPerQuarter(i);
                size++;
            }
        }

        int size() {
            return size;
        }

        long tick(long ms) {
            double micros = ms * 1000.0;
            int lo = 0, hi = size - 1;
            while (lo < hi) {
                int mid = (lo + hi + 1) >>> 1;
                if (segmentMicros[mid] <= micros) lo = mid; else hi = mid - 1;
            }
            return segmentTick[lo] + Math.round((micros - segmentMicros[lo]) * ppq / segmentTempo[lo]);
        }
    }

    /** One track's bytes: delta times, running status, metas and the end-of-track event. */
    private static final class Track {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private long lastTick;
        private int runningStatus;

        void channel(long tick, int status, int data1, int data2) {
            delta(tick);
            if (status != runningStatus) out.write(status);
            runningStatus = status;
            out.write(data1 & 0x7F);
            int kind = status & 0xF0;
            if (kind != 0xC0 && kind != 0xD0) out.write(data2 & 0x7F);
        }

        void meta(long tick, int type, byte[] data) {
            delta(tick);
            out.write(0xFF);
            out.write(type);
            varLength(data.length);
            out.writeBytes(data);
            runningStatus = 0;
        }

        void end(long tick) {
            meta(Math.max(tick, lastTick), MidiData.META_END_OF_TRACK, new byte[0]);
        }

        byte[] bytes() {
            return out.toByteArray();
        }

        private void delta(long tick) {
            long at = Math.max(tick, lastTick);
            varLength(at - lastTick);
            lastTick = at;
        }

        private void varLength(long value) {
            if (value < 0 || value > 0x0FFFFFFF) throw new IllegalArgumentException("MIDI delta time out of range: " + value);
            int shift = 21;
            while (shift > 0 && (value >> shift) == 0) shift -= 7;
            for (; shift > 0; shift -= 7) out.write((int) (value >> shift & 0x7F) | 0x80);
            out.write((int) (value & 0x7F));
        }
    }
}
