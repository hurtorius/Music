package org.hurtorius.music.song.midi;

import org.hurtorius.music.song.Song;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Turns {@link MidiData} into a {@link Song} for one piano.
 *
 * <ul>
 *   <li>Notes pair per track, channel and key, first in first out, so overlapping repeats of a key
 *   pair the way they were written; note-on with velocity 0 is a note-off; notes still down at the
 *   end of their track end there; all-notes-off and all-sound-off (CC 120, 123-127) release the
 *   channel's keys.</li>
 *   <li>Channel 10 is percussion in General MIDI and is left out.</li>
 *   <li>Every other channel plays on the one piano. Keys outside A0..C8 move by octaves into range.
 *   Strikes of one key within {@value #DUPLICATE_MS} ms are one strike; a key struck again while it
 *   is still down comes up at the new strike and stays down until the later release, as a real key
 *   would.</li>
 *   <li>Sustain, sostenuto and soft come from CC 64/66/67 (64 and above is down). The piano has one
 *   of each pedal, so a pedal is down while any channel holds it; reset-all-controllers (CC 121)
 *   lifts a channel's pedals. Pedals still down at the end are lifted there.</li>
 *   <li>Silence after the last sound is kept up to the file's end, at most {@value #TRAILING_MS} ms.</li>
 * </ul>
 */
final class MidiToSong {
    static final int DUPLICATE_MS = 12;
    static final int TRAILING_MS = 5_000;
    private static final int CC_SUSTAIN = 64, CC_SOSTENUTO = 66, CC_SOFT = 67;
    private static final int CC_ALL_SOUND_OFF = 120, CC_RESET_CONTROLLERS = 121, CC_ALL_NOTES_OFF = 123;
    private static final int DRUM_CHANNEL = 9;
    private static final int LOWEST = 21, HIGHEST = 108;
    private static final Pattern GENERIC_NAME = Pattern.compile(
        "(?i)(untitled.*|unnamed.*|tempo( track)?|conductor( track)?|(midi )?track ?\\d*|sequence ?\\d*|seq-?\\d*|new (song|sequence|project).*|[-_. ]*)");

    private MidiToSong() {}

    /** Controllers worth keeping when reading: the pedals and the messages that release keys or pedals. */
    static boolean isRelevantController(int controller) {
        return controller == CC_SUSTAIN || controller == CC_SOSTENUTO || controller == CC_SOFT || controller >= CC_ALL_SOUND_OFF;
    }

    static Song convert(MidiData data, String name) throws IOException {
        TempoMap time = TempoMap.of(data);
        Notes raw = new Notes();
        Pedals pedals = new Pedals();
        int drums = pairNotes(data, raw, pedals);

        if (raw.size == 0) {
            throw new IOException(drums > 0
                ? "This MIDI file only has drums (channel 10), which a piano does not play"
                : "This MIDI file has no notes");
        }

        // Ticks to milliseconds, keys into the piano's range.
        long[] order = new long[raw.size];
        int[] startMs = new int[raw.size], endMs = new int[raw.size], midi = new int[raw.size];
        for (int i = 0; i < raw.size; i++) {
            long start = time.millis(raw.startTick[i]);
            long end = time.millis(raw.endTick[i]);
            if (end > Song.MAX_DURATION_MS) throw tooLong();
            startMs[i] = (int) start;
            endMs[i] = (int) Math.max(end, start + 1);
            int m = raw.midi[i];
            while (m < LOWEST) m += 12;
            while (m > HIGHEST) m -= 12;
            midi[i] = m;
            order[i] = start << 32 | (long) m << 24 | i;
        }
        Arrays.sort(order);

        // One piano: merge duplicate strikes, and let a re-struck key come up at the new strike.
        int n = 0;
        int[] keptStart = new int[raw.size], keptEnd = new int[raw.size], keptMidi = new int[raw.size];
        int[] keptVelocity = new int[raw.size], keptPart = new int[raw.size];
        int[] lastOnKey = new int[128];
        Arrays.fill(lastOnKey, -1);
        for (long entry : order) {
            int i = (int) (entry & 0xFFFFFF);
            int m = midi[i], start = startMs[i], end = endMs[i];
            int previous = lastOnKey[m];
            if (previous >= 0 && start - keptStart[previous] <= DUPLICATE_MS) {
                keptEnd[previous] = Math.max(keptEnd[previous], end);
                keptVelocity[previous] = Math.max(keptVelocity[previous], raw.velocity[i]);
                continue;
            }
            if (previous >= 0 && start < keptEnd[previous]) {
                end = Math.max(end, keptEnd[previous]);
                keptEnd[previous] = start;
            }
            keptStart[n] = start;
            keptEnd[n] = end;
            keptMidi[n] = m;
            keptVelocity[n] = raw.velocity[i];
            keptPart[n] = raw.part[i];
            lastOnKey[m] = n;
            n++;
        }
        if (n > Song.MAX_NOTES) {
            throw new IOException(String.format(Locale.ROOT, "This MIDI file has %,d notes; a song can hold at most %,d", n, Song.MAX_NOTES));
        }

        byte[] hands = HandAssigner.assign(keptStart, keptEnd, keptMidi, keptPart, n,
            part -> data.trackNames.get(part >>> 4));

        // Where the song ends: the last sound, plus the file's own trailing silence up to a limit.
        long soundEnd = 0;
        for (int i = 0; i < n; i++) soundEnd = Math.max(soundEnd, keptEnd[i]);
        long[] pedalMs = new long[pedals.size];
        for (int i = 0; i < pedals.size; i++) {
            pedalMs[i] = time.millis(pedals.tick[i]);
            if (pedalMs[i] > Song.MAX_DURATION_MS) throw tooLong();
            soundEnd = Math.max(soundEnd, pedalMs[i]);
        }
        long fileEnd = 0;
        for (long end : data.trackEnds) fileEnd = Math.max(fileEnd, end);
        long songEnd = Math.max(soundEnd, Math.min(time.millis(fileEnd), soundEnd + TRAILING_MS));
        if (songEnd > Song.MAX_DURATION_MS) throw tooLong();

        Song.Builder song = new Song.Builder(name == null || name.isBlank() ? metadataName(data) : name);
        addTempoAndMeter(data, time, song, songEnd);
        for (int i = 0; i < n; i++) {
            song.note(keptStart[i], keptEnd[i] - keptStart[i], keptMidi[i] - LOWEST, keptVelocity[i], hands[i]);
        }
        int pedalChanges = pedals.size + 3;
        if (pedalChanges > Song.MAX_PEDALS) {
            throw new IOException(String.format(Locale.ROOT, "This MIDI file has more than %,d pedal changes", Song.MAX_PEDALS - 3));
        }
        for (int i = 0; i < pedals.size; i++) song.pedal(pedalMs[i], pedals.type[i], pedals.value[i]);
        for (int type = 0; type < 3; type++) {
            if (pedals.down(type)) song.pedal(songEnd, type, 0);
        }
        song.minDuration(songEnd);
        return song.build();
    }

    private static IOException tooLong() {
        return new IOException("This MIDI file is longer than " + Song.MAX_DURATION_MS / 3_600_000 + " hours");
    }

    /**
     * Walks every channel message in time order (ties keep track order, then file order), pairing
     * notes and following the pedals. Returns how many drum notes were skipped.
     */
    private static int pairNotes(MidiData data, Notes notes, Pedals pedals) {
        long[] order = new long[data.size];
        for (int i = 0; i < data.size; i++) order[i] = data.tick[i] << 21 | i;
        Arrays.sort(order);

        // Strikes waiting for their release, per channel, keyed by track and key. A key leaves its map
        // once nothing waits on it, and a channel release starts the channel on a new map (a map's
        // table never shrinks), so each release only visits what its channel struck since the last.
        List<Map<Integer, ArrayDeque<long[]>>> open = new ArrayList<>(16);
        for (int c = 0; c < 16; c++) open.add(new HashMap<>());
        boolean[][] channelPedal = new boolean[16][3];
        int drums = 0;
        for (long entry : order) {
            int i = (int) (entry & 0x1FFFFF);
            long tick = data.tick[i];
            int message = data.message[i];
            int status = message & 0xFF, channel = status & 0x0F, kind = status & 0xF0;
            int data1 = message >>> 8 & 0x7F, data2 = message >>> 16 & 0x7F;
            if (channel == DRUM_CHANNEL) {
                if (kind == 0x90 && data2 > 0) drums++;
                continue;
            }
            int track = data.track[i];
            if (kind == 0x90 && data2 > 0) {
                open.get(channel).computeIfAbsent(slot(track, data1), k -> new ArrayDeque<>(2)).addLast(new long[]{tick, data2});
            } else if (kind == 0x80 || kind == 0x90) {
                Map<Integer, ArrayDeque<long[]>> channelOpen = open.get(channel);
                int slot = slot(track, data1);
                ArrayDeque<long[]> waiting = channelOpen.get(slot);
                if (waiting != null) {
                    long[] on = waiting.removeFirst();
                    notes.add(on[0], tick, data1, (int) on[1], track << 4 | channel);
                    if (waiting.isEmpty()) channelOpen.remove(slot);
                }
            } else if (kind == 0xB0) {
                switch (data1) {
                    case CC_SUSTAIN -> pedals.change(channelPedal, channel, Song.PEDAL_SUSTAIN, data2, tick);
                    case CC_SOSTENUTO -> pedals.change(channelPedal, channel, Song.PEDAL_SOSTENUTO, data2, tick);
                    case CC_SOFT -> pedals.change(channelPedal, channel, Song.PEDAL_SOFT, data2, tick);
                    case CC_RESET_CONTROLLERS -> {
                        for (int type = 0; type < 3; type++) pedals.change(channelPedal, channel, type, 0, tick);
                    }
                    default -> {
                        if (data1 == CC_ALL_SOUND_OFF || data1 >= CC_ALL_NOTES_OFF) releaseChannel(open, channel, tick, notes);
                    }
                }
            }
        }
        // Notes still down end with their track.
        for (int channel = 0; channel < 16; channel++) {
            for (Map.Entry<Integer, ArrayDeque<long[]>> e : open.get(channel).entrySet()) {
                int track = e.getKey() >>> 7, midi = e.getKey() & 0x7F;
                long end = data.trackEnds.get(track);
                for (long[] on : e.getValue()) notes.add(on[0], Math.max(end, on[0]), midi, (int) on[1], track << 4 | channel);
            }
        }
        return drums;
    }

    private static int slot(int track, int key) {
        return track << 7 | key;
    }

    private static void releaseChannel(List<Map<Integer, ArrayDeque<long[]>>> open, int channel, long tick, Notes notes) {
        if (open.get(channel).isEmpty()) return;
        for (Map.Entry<Integer, ArrayDeque<long[]>> e : open.get(channel).entrySet()) {
            int track = e.getKey() >>> 7, midi = e.getKey() & 0x7F;
            for (long[] on : e.getValue()) notes.add(on[0], tick, midi, (int) on[1], track << 4 | channel);
        }
        open.set(channel, new HashMap<>());
    }

    /** The song's tempo and meter maps up to its end; changes after the last sound mean nothing. */
    private static void addTempoAndMeter(MidiData data, TempoMap time, Song.Builder song, long songEnd) {
        int lastTempo = -1;
        for (long[] change : TempoMap.sortedTempos(data)) {
            int micros = (int) Math.max(60_000, Math.min(4_000_000, change[1]));
            long ms = time.millis(change[0]);
            if (ms > songEnd) break;
            if (micros == lastTempo) continue;
            song.tempo(ms, micros);
            lastTempo = micros;
        }
        List<long[]> meters = new ArrayList<>(data.meters);
        meters.sort(Comparator.comparingLong(m -> m[0]));
        long lastMeter = -1;
        for (long[] meter : meters) {
            long code = meter[1] << 8 | meter[2];
            long ms = time.millis(meter[0]);
            if (ms > songEnd) break;
            if (code == lastMeter) continue;
            song.meter(ms, (int) meter[1], (int) meter[2]);
            lastMeter = code;
        }
    }

    /** The sequence name (first track's name), else a karaoke title, else null. Generic names are skipped. */
    private static String metadataName(MidiData data) {
        String first = data.trackNames.isEmpty() ? null : data.trackNames.get(0);
        if (first != null && !GENERIC_NAME.matcher(first).matches()) return first;
        if (data.karaokeTitle != null && !GENERIC_NAME.matcher(data.karaokeTitle).matches()) return data.karaokeTitle;
        return null;
    }

    /** Paired notes in ticks, before timing and piano rules. */
    private static final class Notes {
        long[] startTick = new long[256], endTick = new long[256];
        int[] midi = new int[256], velocity = new int[256], part = new int[256];
        int size;

        void add(long start, long end, int key, int vel, int partId) {
            if (size == midi.length) {
                int grown = size * 2;
                startTick = Arrays.copyOf(startTick, grown);
                endTick = Arrays.copyOf(endTick, grown);
                midi = Arrays.copyOf(midi, grown);
                velocity = Arrays.copyOf(velocity, grown);
                part = Arrays.copyOf(part, grown);
            }
            startTick[size] = start;
            endTick[size] = end;
            midi[size] = key;
            velocity[size] = vel;
            part[size] = partId;
            size++;
        }
    }

    /** The piano's pedal changes in ticks: one entry each time a pedal goes down or up. */
    private static final class Pedals {
        long[] tick = new long[64];
        int[] type = new int[64], value = new int[64];
        int size;
        private final int[] downChannels = new int[3];

        boolean down(int pedal) {
            return downChannels[pedal] > 0;
        }

        void change(boolean[][] channelPedal, int channel, int pedal, int newValue, long at) {
            boolean nowDown = newValue >= 64;
            if (channelPedal[channel][pedal] == nowDown) return;
            boolean wasDown = down(pedal);
            channelPedal[channel][pedal] = nowDown;
            downChannels[pedal] += nowDown ? 1 : -1;
            if (down(pedal) == wasDown) return;
            if (size == tick.length) {
                tick = Arrays.copyOf(tick, size * 2);
                type = Arrays.copyOf(type, size * 2);
                value = Arrays.copyOf(value, size * 2);
            }
            tick[size] = at;
            type[size] = pedal;
            value[size] = newValue;
            size++;
        }
    }
}
