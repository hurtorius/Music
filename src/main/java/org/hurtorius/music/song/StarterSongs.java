package org.hurtorius.music.song;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.hurtorius.music.song.midi.MidiSongs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The pieces every new world's library starts with: public-domain piano music in the jar, under
 * {@code data/music/starter_songs/} (an {@code index.json} naming each MIDI file, its title and its
 * composer), whose notes come from Mutopia Project editions placed in the Public Domain (the jar's
 * {@code THIRD-PARTY-NOTICES.md} names them). They are read the way a player's imported MIDI file is,
 * once per game.
 *
 * <p>A library credits each piece to its composer, as initials and surname ("F. Chopin"): no player
 * can have such a name (it has a space), so a song credited to one of them is one of these pieces,
 * whatever version of Music wrote it, and only operators manage it. Telling that needs only the
 * index ({@link #isComposer}), never the MIDI files, so a game that only lists a server's songs reads
 * none of them.</p>
 */
public final class StarterSongs {
    private static final Logger LOG = LoggerFactory.getLogger("Music");
    private static final String FOLDER = "/data/music/starter_songs/";
    private static List<Starter> loaded;

    private StarterSongs() {}

    /**
     * One piece.
     *
     * @param id       stable name of the piece; a world remembers which ones it was given by it
     * @param name     the title shown in the library
     * @param composer the song's author in a library; never a valid player name, so the song counts
     *                 against no player's space and only operators can rename or delete it
     * @param song     the notes
     * @param encoded  the song as a world stores it
     */
    public record Starter(String id, String name, String composer, Song song, byte[] encoded) {}

    /** One entry of the index: a piece before its MIDI file is read. */
    private record Entry(String id, String file, String name, String composer) {}

    /** The composers, read from the index on first use; none if it cannot be read (the log says why). */
    private static final class Composers {
        static final Set<String> ALL = read();

        private static Set<String> read() {
            try {
                return index().stream().map(Entry::composer).collect(Collectors.toUnmodifiableSet());
            } catch (IOException e) {
                LOG.error("Could not read the starter songs' index in the Music jar; the starter pieces will be listed among a world's own songs", e);
                return Set.of();
            }
        }
    }

    /** Every piece, read on first use. If the jar's copies cannot be read, none (and the log says why). */
    public static synchronized List<Starter> all() {
        if (loaded == null) {
            try {
                loaded = read();
            } catch (IOException e) {
                LOG.error("Could not read the starter songs in the Music jar; worlds will not be given them", e);
                loaded = List.of();
            }
        }
        return loaded;
    }

    /** Whether {@code author} is a starter piece's composer, so a song credited to it is one of these pieces. */
    public static boolean isComposer(String author) {
        return Composers.ALL.contains(author);
    }

    /** Reads every piece from the jar; fails on the first one that is missing or unreadable. */
    static List<Starter> read() throws IOException {
        List<Starter> starters = new ArrayList<>();
        for (Entry entry : index()) {
            Song song;
            try (InputStream in = open(entry.file())) {
                song = MidiSongs.read(in, entry.name());
            } catch (IOException e) {
                throw new IOException("Starter song " + entry.file() + ": " + e.getMessage(), e);
            }
            starters.add(new Starter(entry.id(), entry.name(), entry.composer(), song, SongCodec.encode(song)));
        }
        return List.copyOf(starters);
    }

    /** The index, without reading the MIDI files it names. */
    private static List<Entry> index() throws IOException {
        JsonElement index;
        try (InputStream in = open("index.json")) {
            index = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new IOException("index.json of the starter songs is not valid JSON", e);
        }
        if (!index.isJsonArray()) throw new IOException("index.json of the starter songs is not a list");
        List<Entry> entries = new ArrayList<>();
        for (JsonElement element : index.getAsJsonArray()) {
            try {
                JsonObject entry = element.getAsJsonObject();
                entries.add(new Entry(entry.get("id").getAsString(), entry.get("file").getAsString(), entry.get("name").getAsString(),
                    entry.get("composer").getAsString()));
            } catch (RuntimeException e) {
                throw new IOException("index.json of the starter songs has an entry without id, file, name and composer: " + element, e);
            }
        }
        return entries;
    }

    private static InputStream open(String file) throws IOException {
        InputStream in = StarterSongs.class.getResourceAsStream(FOLDER + file);
        if (in == null) throw new IOException("Missing " + FOLDER + file + " in the Music jar");
        return in;
    }
}
