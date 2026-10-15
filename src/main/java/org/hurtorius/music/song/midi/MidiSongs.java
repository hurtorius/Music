package org.hurtorius.music.song.midi;

import org.hurtorius.music.song.Song;

import javax.sound.midi.Sequence;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

/**
 * Standard MIDI Files to and from {@link Song}s.
 *
 * <p>Reading accepts formats 0 and 1 (format 2 is merged as if it were 1), PPQ and SMPTE timing,
 * running status, tempo and time-signature changes, CC 64/66/67 pedals, and .kar karaoke files.
 * Channel 10 drums are dropped, everything else plays on the one piano, keys outside A0..C8 move
 * by octaves into range, and every note gets a hand. Problems come back as {@link IOException}s
 * whose messages can be shown to the player as they are.</p>
 *
 * <p>Writing produces a format 1 file at 480 ticks per quarter note: a conductor track with the
 * song's name, tempo and meter, then a "Right hand" and a "Left hand" track (and "Piano" for notes
 * with no hand), with the pedals as controllers. Reading the result back gives the same notes,
 * hands, pedals, tempo and meter to within a millisecond, as long as the song plays each key once at
 * a time (reading applies the one-piano rules: overlapping strikes of a key are re-struck, strikes
 * within 12 ms merge) and ends at most five seconds after its last sound.</p>
 */
public final class MidiSongs {
    /** Largest MIDI file read: far above any real piano piece. */
    public static final int MAX_FILE_BYTES = 16 * 1024 * 1024;
    /** Most note, pedal, tempo and meter events read from one file. */
    public static final int MAX_EVENTS = 2_000_000;

    private MidiSongs() {}

    /**
     * Reads a MIDI file. The stream is read to its end (at most {@link #MAX_FILE_BYTES} + 1 bytes)
     * and not closed.
     *
     * @param name the song's name; when blank, the file's own title is used if it has one
     */
    public static Song read(InputStream in, String name) throws IOException {
        Objects.requireNonNull(in, "in");
        byte[] bytes = in.readNBytes(MAX_FILE_BYTES + 1);
        if (bytes.length == 0) throw new IOException("The file is empty");
        if (bytes.length > MAX_FILE_BYTES) throw new IOException("The MIDI file is larger than " + (MAX_FILE_BYTES >> 20) + " MB");
        return MidiToSong.convert(SmfParser.parse(bytes), name);
    }

    /**
     * Converts a sequence (read by Java Sound or built in memory) the same way as a file.
     *
     * @param name the song's name; when blank, the sequence's first track name is used if it has one
     */
    public static Song read(Sequence sequence, String name) throws IOException {
        return MidiToSong.convert(MidiData.of(sequence), name);
    }

    /** Writes the song as a format 1 MIDI file. The stream is flushed, not closed. */
    public static void write(Song song, OutputStream out) throws IOException {
        Objects.requireNonNull(song, "song");
        Objects.requireNonNull(out, "out");
        SmfWriter.write(song, out);
    }
}
