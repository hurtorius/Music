package org.hurtorius.music.song.midi;

import javax.sound.midi.Sequence;
import java.io.IOException;

/**
 * Reads Standard MIDI File bytes into {@link MidiData}. Handles formats 0, 1 and 2, PPQ and SMPTE
 * timing, running status (also across meta and sysex events, which some writers rely on), files
 * wrapped in RIFF/RMID or preceded by a few hundred bytes of junk, unknown chunks, and a last track
 * cut short (everything before the cut is kept). Structural damage inside a track is an error.
 */
final class SmfParser {
    /** Largest absolute tick accepted; far beyond any sensible song at any tempo. */
    static final long MAX_TICK = 1L << 40;
    private static final int MTHD = 0x4D546864, MTRK = 0x4D54726B;
    private static final int HEADER_SEARCH = 4096;

    private SmfParser() {}

    static MidiData parse(byte[] bytes) throws IOException {
        int start = findHeader(bytes);
        if (start < 0) throw new IOException("This is not a MIDI file");
        if (start + 14 > bytes.length) throw new IOException("The MIDI file ends inside its header");
        long headerLength = u32(bytes, start + 4);
        if (headerLength < 6) throw new IOException("The MIDI file header is damaged");
        int format = u16(bytes, start + 8);
        int division = u16(bytes, start + 12);
        if (format > 2) throw new IOException("Unknown MIDI file format " + format);

        float divisionType;
        int resolution;
        if ((division & 0x8000) == 0) {
            divisionType = Sequence.PPQ;
            resolution = division;
            if (resolution == 0) throw new IOException("The MIDI file has no timing (0 ticks per quarter note)");
        } else {
            int fps = -(byte) (division >> 8);
            divisionType = switch (fps) {
                case 24 -> Sequence.SMPTE_24;
                case 25 -> Sequence.SMPTE_25;
                case 29 -> Sequence.SMPTE_30DROP;
                case 30 -> Sequence.SMPTE_30;
                default -> throw new IOException("Unknown SMPTE frame rate " + fps + " in the MIDI file");
            };
            resolution = division & 0xFF;
            if (resolution == 0) throw new IOException("The MIDI file has no timing (0 ticks per frame)");
        }

        MidiData data = new MidiData(format, divisionType, resolution);
        long pos = start + 8 + headerLength;
        while (pos + 8 <= bytes.length) {
            int id = (int) u32(bytes, (int) pos);
            long length = u32(bytes, (int) pos + 4);
            long body = pos + 8;
            if (id == MTRK) readTrack(bytes, (int) body, (int) Math.min(bytes.length, body + length), data);
            pos = body + length;
        }
        if (data.trackCount() == 0) throw new IOException("The MIDI file has no tracks");
        return data;
    }

    /** Offset of "MThd" near the start (plain file, RIFF/RMID wrapper or a short junk prefix), or -1. */
    private static int findHeader(byte[] bytes) {
        int limit = Math.min(bytes.length - 4, HEADER_SEARCH);
        for (int i = 0; i <= limit; i++) {
            if (bytes[i] == 'M' && bytes[i + 1] == 'T' && bytes[i + 2] == 'h' && bytes[i + 3] == 'd') return i;
        }
        return -1;
    }

    private static void readTrack(byte[] bytes, int from, int to, MidiData data) throws IOException {
        int track = data.beginTrack();
        Cursor in = new Cursor(bytes, from, to);
        long tick = 0;
        int running = 0;
        try {
            while (in.more()) {
                tick += in.varLength(track);
                if (tick > MAX_TICK) throw new IOException("The MIDI file's timing is out of range");
                int first = in.peek();
                if (first == 0xFF) {
                    in.skip(1);
                    int type = in.next();
                    int length = in.length(track);
                    data.meta(track, tick, type, bytes, in.pos, length);
                    in.skip(length);
                    if (type == MidiData.META_END_OF_TRACK) break;
                } else if (first == 0xF0 || first == 0xF7) {
                    in.skip(1);
                    in.skip(in.length(track));
                } else if (first > 0xF0) {
                    throw damaged(track, in.pos);
                } else {
                    int status;
                    if (first >= 0x80) {
                        status = first;
                        in.skip(1);
                    } else if (running != 0) {
                        status = running;
                    } else {
                        throw damaged(track, in.pos);
                    }
                    running = status;
                    int data1 = in.next();
                    int kind = status & 0xF0;
                    int data2 = kind == 0xC0 || kind == 0xD0 ? 0 : in.next();
                    if (data1 > 0x7F || data2 > 0x7F) throw damaged(track, in.pos);
                    data.channel(track, tick, status, data1, data2);
                }
            }
        } catch (Truncated cut) {
            // The file ends inside this track: keep what was read.
        }
        data.endTrack(track, tick);
    }

    private static IOException damaged(int track, int offset) {
        return new IOException("Track " + (track + 1) + " of the MIDI file is damaged (byte " + offset + ")");
    }

    private static long u32(byte[] b, int i) {
        return (b[i] & 0xFFL) << 24 | (b[i + 1] & 0xFF) << 16 | (b[i + 2] & 0xFF) << 8 | b[i + 3] & 0xFF;
    }

    private static int u16(byte[] b, int i) {
        return (b[i] & 0xFF) << 8 | b[i + 1] & 0xFF;
    }

    /** Thrown when a track's data stops in the middle of an event; never escapes this class. */
    private static final class Truncated extends Exception {
        private static final Truncated INSTANCE = new Truncated();

        private Truncated() {
            super(null, null, false, false);
        }
    }

    private static final class Cursor {
        private final byte[] bytes;
        private final int end;
        int pos;

        Cursor(byte[] bytes, int pos, int end) {
            this.bytes = bytes;
            this.pos = pos;
            this.end = end;
        }

        boolean more() {
            return pos < end;
        }

        int peek() throws Truncated {
            if (pos >= end) throw Truncated.INSTANCE;
            return bytes[pos] & 0xFF;
        }

        int next() throws Truncated {
            if (pos >= end) throw Truncated.INSTANCE;
            return bytes[pos++] & 0xFF;
        }

        void skip(int n) throws Truncated {
            if (n > end - pos) {
                pos = end;
                throw Truncated.INSTANCE;
            }
            pos += n;
        }

        /** A variable-length quantity: at most four bytes, 28 bits. */
        long varLength(int track) throws Truncated, IOException {
            long value = 0;
            for (int i = 0; i < 4; i++) {
                int b = next();
                value = value << 7 | b & 0x7F;
                if ((b & 0x80) == 0) return value;
            }
            throw damaged(track, pos);
        }

        /** A meta or sysex length; one that runs past the end of the data means the file was cut. */
        int length(int track) throws Truncated, IOException {
            long n = varLength(track);
            if (n > end - pos) {
                pos = end;
                throw Truncated.INSTANCE;
            }
            return (int) n;
        }
    }
}
