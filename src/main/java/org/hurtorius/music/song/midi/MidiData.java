package org.hurtorius.music.song.midi;

import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * A MIDI file or {@link Sequence} reduced to what the piano needs, whatever it was read from:
 * note and pedal-related channel messages with absolute ticks (all tracks concatenated in track
 * order, each track in file order), tempo and time-signature changes, track names and where each
 * track ends.
 */
final class MidiData {
    static final int META_TEXT = 0x01, META_TRACK_NAME = 0x03, META_END_OF_TRACK = 0x2F;
    static final int META_TEMPO = 0x51, META_TIME_SIGNATURE = 0x58;
    private static final int MAX_TRACKS = 65_535;
    private static final int MAX_TEXT = 256;

    /** Standard MIDI File format 0, 1 or 2; a {@link Sequence} counts as format 1. */
    final int format;
    /** {@link Sequence#PPQ} or one of the {@code Sequence.SMPTE_*} frame rates. */
    final float divisionType;
    /** Ticks per quarter note (PPQ) or ticks per frame (SMPTE). */
    final int resolution;

    long[] tick = new long[256];
    /** status | data1 << 8 | data2 << 16 */
    int[] message = new int[256];
    int[] track = new int[256];
    int size;

    /** {tick, microseconds per quarter note}, in file order. */
    final List<long[]> tempos = new ArrayList<>();
    /** {tick, numerator, denominator}, in file order. */
    final List<long[]> meters = new ArrayList<>();
    /** Per track: its first track name meta event, or null. */
    final List<String> trackNames = new ArrayList<>();
    /** Per track: the tick of its end (end-of-track event or last event). */
    final List<Long> trackEnds = new ArrayList<>();
    /** Title from a karaoke (.kar) "@T" text event, or null. */
    String karaokeTitle;

    MidiData(int format, float divisionType, int resolution) {
        this.format = format;
        this.divisionType = divisionType;
        this.resolution = resolution;
    }

    /** Copies what matters out of a sequence built in memory or read by Java Sound. */
    static MidiData of(Sequence sequence) throws IOException {
        if (sequence == null) throw new IOException("No MIDI sequence");
        float type = sequence.getDivisionType();
        if (type != Sequence.PPQ && type != Sequence.SMPTE_24 && type != Sequence.SMPTE_25
            && type != Sequence.SMPTE_30DROP && type != Sequence.SMPTE_30) {
            throw new IOException("Unknown MIDI timing " + type);
        }
        if (sequence.getResolution() <= 0) throw new IOException("The MIDI sequence has no timing resolution");
        MidiData data = new MidiData(1, type, sequence.getResolution());
        for (Track source : sequence.getTracks()) {
            int track = data.beginTrack();
            long end = 0;
            for (int i = 0; i < source.size(); i++) {
                MidiEvent event = source.get(i);
                long tick = event.getTick();
                if (tick < 0) continue;
                if (tick > SmfParser.MAX_TICK) throw new IOException("The MIDI timing is out of range");
                end = Math.max(end, tick);
                MidiMessage message = event.getMessage();
                if (message instanceof ShortMessage s) {
                    if (s.getStatus() < 0xF0) data.channel(track, tick, s.getStatus(), s.getData1(), s.getData2());
                } else if (message instanceof MetaMessage meta) {
                    byte[] bytes = meta.getData();
                    data.meta(track, tick, meta.getType(), bytes, 0, bytes.length);
                }
            }
            data.endTrack(track, end);
        }
        return data;
    }

    int trackCount() {
        return trackNames.size();
    }

    int beginTrack() throws IOException {
        if (trackNames.size() >= MAX_TRACKS) throw new IOException("The MIDI file has more than " + MAX_TRACKS + " tracks");
        trackNames.add(null);
        trackEnds.add(0L);
        return trackNames.size() - 1;
    }

    void endTrack(int track, long endTick) {
        trackEnds.set(track, Math.max(trackEnds.get(track), endTick));
    }

    /** Keeps the channel messages the piano reacts to: notes, pedals and all-notes-off style controllers. */
    void channel(int trackIndex, long at, int status, int data1, int data2) throws IOException {
        int kind = status & 0xF0;
        boolean wanted = kind == 0x80 || kind == 0x90 || kind == 0xB0 && MidiToSong.isRelevantController(data1);
        if (!wanted) return;
        if (size + tempos.size() + meters.size() >= MidiSongs.MAX_EVENTS) throw tooManyEvents();
        if (size == tick.length) {
            int grown = Math.min(MidiSongs.MAX_EVENTS, size * 2);
            tick = Arrays.copyOf(tick, grown);
            message = Arrays.copyOf(message, grown);
            track = Arrays.copyOf(track, grown);
        }
        tick[size] = at;
        message[size] = status | (data1 & 0x7F) << 8 | (data2 & 0x7F) << 16;
        track[size] = trackIndex;
        size++;
    }

    void meta(int trackIndex, long at, int type, byte[] bytes, int offset, int length) throws IOException {
        switch (type) {
            case META_TEMPO -> {
                if (length != 3) return;
                int micros = (bytes[offset] & 0xFF) << 16 | (bytes[offset + 1] & 0xFF) << 8 | bytes[offset + 2] & 0xFF;
                if (micros <= 0) return;
                if (tempos.size() + meters.size() + size >= MidiSongs.MAX_EVENTS) throw tooManyEvents();
                tempos.add(new long[]{at, micros});
            }
            case META_TIME_SIGNATURE -> {
                if (length < 2) return;
                int numerator = bytes[offset] & 0xFF, power = bytes[offset + 1] & 0xFF;
                if (numerator < 1 || power > 5) return;
                if (tempos.size() + meters.size() + size >= MidiSongs.MAX_EVENTS) throw tooManyEvents();
                meters.add(new long[]{at, numerator, 1 << power});
            }
            case META_TRACK_NAME -> {
                if (trackNames.get(trackIndex) == null) trackNames.set(trackIndex, text(bytes, offset, length));
            }
            case META_TEXT -> {
                if (karaokeTitle == null && length > 2 && bytes[offset] == '@' && (bytes[offset + 1] == 'T' || bytes[offset + 1] == 't')) {
                    karaokeTitle = text(bytes, offset + 2, length - 2);
                }
            }
            default -> {
            }
        }
    }

    private static IOException tooManyEvents() {
        return new IOException(String.format(Locale.ROOT, "The MIDI file has more than %,d events", MidiSongs.MAX_EVENTS));
    }

    /** MIDI text has no declared encoding: UTF-8 when it is valid UTF-8, otherwise Latin-1. */
    static String text(byte[] bytes, int offset, int length) {
        int n = Math.min(length, MAX_TEXT * 4);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, n)).toString().strip();
        } catch (CharacterCodingException e) {
            return new String(bytes, offset, n, StandardCharsets.ISO_8859_1).strip();
        }
    }
}
