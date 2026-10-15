package org.hurtorius.music.net;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.hurtorius.music.song.SongLibrary;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * What a server's owner sets for the songs players add, in {@code config/music-server.json}: who may
 * add songs, how large the world's song library may grow, and how much and how many one player may
 * add (operators and the host of a singleplayer world count against no player limit). Read when the
 * server starts. A dedicated server without the file writes it with the defaults, so its owner finds
 * it; a value that cannot be used is left at its default and one out of range is brought into it, and
 * a file that cannot be read at all is left as it is (the defaults apply), each said in the log.
 *
 * @param uploads     who may add songs
 * @param libraryMb   how large the world's library may grow, in MiB, at most what a library holds
 * @param playerMb    how much one player may add, in MiB
 * @param playerSongs how many songs one player may add
 */
public record SongLimits(Uploads uploads, int libraryMb, int playerMb, int playerSongs) {
    private static final Logger LOG = LoggerFactory.getLogger("Music");
    public static final String FILE = "music-server.json";
    /** The most a library holds, in MiB: a world's library never grows past it, whatever the file says. */
    public static final int MAX_LIBRARY_MB = (int) (SongLibrary.DEFAULT_QUOTA >> 20);
    public static final int MAX_PLAYER_SONGS = SongLibrary.MAX_SONGS;
    public static final SongLimits DEFAULT = new SongLimits(Uploads.EVERYONE, MAX_LIBRARY_MB, 32, 256);

    /** Who may add songs to a world's library. */
    public enum Uploads {
        /** Every player. */
        EVERYONE,
        /** Operators and the host of a singleplayer world. */
        OPERATORS,
        /** Nobody: the library stays as it is. */
        NOBODY;

        String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** How large the world's library may grow, in bytes. */
    public long libraryBytes() {
        return (long) libraryMb << 20;
    }

    /** How much one player may add, in bytes. */
    public long playerBytes() {
        return (long) playerMb << 20;
    }

    /** How much a world's library may hold, in bytes, when the library itself holds at most {@code capacity}: the less of the two. */
    public long libraryBytes(long capacity) {
        return Math.min(capacity, libraryBytes());
    }

    /**
     * Why a player may not add songs at all, as the translation key of what they are told, or null
     * when they may; {@code trusted} for an operator or the host of a singleplayer world.
     */
    public @Nullable String whoMayAdd(boolean trusted) {
        return switch (uploads) {
            case EVERYONE -> null;
            case OPERATORS -> trusted ? null : "music.message.uploads_operators";
            case NOBODY -> "music.message.uploads_off";
        };
    }

    /**
     * Why a song of {@code size} bytes has no room, as the translation key of what the player adding
     * it is told, or null when it fits: the world's library holds {@code libraryUsed} bytes of at most
     * {@code capacity}, and the player added {@code playerUsed} bytes in {@code playerSongs} songs of
     * it. A {@code trusted} player (an operator or the host) counts against no player limit.
     */
    public @Nullable String room(boolean trusted, long libraryUsed, long capacity, long playerUsed, int playerSongs, long size) {
        if (libraryUsed + size > libraryBytes(capacity)) return "music.message.library_full";
        if (trusted) return null;
        if (playerUsed + size > playerBytes()) return "music.message.your_space_full";
        if (playerSongs >= this.playerSongs) return "music.message.your_songs_full";
        return null;
    }

    /**
     * The limits {@code config/music-server.json} in {@code configDir} sets: the defaults when there is
     * no file (written there first when {@code writeDefaults}) or it cannot be read.
     */
    public static SongLimits load(Path configDir, boolean writeDefaults) {
        Path file = configDir.resolve(FILE);
        if (!Files.exists(file)) {
            if (writeDefaults) {
                try {
                    Files.createDirectories(configDir);
                    Files.writeString(file, DEFAULT.toJson(), StandardCharsets.UTF_8);
                    LOG.info("Wrote the default song limits to {}", file);
                } catch (IOException e) {
                    LOG.error("Could not write the default song limits to {}", file, e);
                }
            }
            return DEFAULT;
        }
        try {
            SongLimits limits = parse(Files.readString(file, StandardCharsets.UTF_8), problem -> LOG.warn("{}: {}", file, problem));
            LOG.info("Song limits from {}: {}", file, limits.describe());
            return limits;
        } catch (IOException | JsonParseException | IllegalStateException e) {
            LOG.error("Could not read the song limits in {}; using the defaults ({})", file, DEFAULT.describe(), e);
            return DEFAULT;
        }
    }

    /**
     * The limits the JSON {@code text} sets, each value it lacks or that cannot be used at its default
     * and each out of range brought into it, every such value told to {@code problems}.
     *
     * @throws JsonParseException when the text is not a JSON object
     */
    public static SongLimits parse(String text, Consumer<String> problems) {
        JsonElement root = JsonParser.parseString(text);
        if (!root.isJsonObject()) throw new JsonParseException("The song limits must be a JSON object");
        JsonObject json = root.getAsJsonObject();
        Uploads uploads = DEFAULT.uploads;
        JsonElement who = json.get("uploads");
        if (who != null) {
            uploads = null;
            if (who instanceof JsonPrimitive p && p.isString()) {
                for (Uploads u : Uploads.values()) if (u.id().equals(p.getAsString().trim().toLowerCase(Locale.ROOT))) uploads = u;
            }
            if (uploads == null) {
                problems.accept("\"uploads\" is " + who + ", not \"everyone\", \"operators\" or \"nobody\"; it stays \"" + DEFAULT.uploads.id() + "\"");
                uploads = DEFAULT.uploads;
            }
        }
        int library = number(json, "library_mb", 1, MAX_LIBRARY_MB, DEFAULT.libraryMb, problems);
        int player = number(json, "player_mb", 1, MAX_LIBRARY_MB, DEFAULT.playerMb, problems);
        int songs = number(json, "player_songs", 1, MAX_PLAYER_SONGS, DEFAULT.playerSongs, problems);
        return new SongLimits(uploads, library, player, songs);
    }

    private static int number(JsonObject json, String key, int min, int max, int fallback, Consumer<String> problems) {
        JsonElement value = json.get(key);
        if (value == null) return fallback;
        if (!(value instanceof JsonPrimitive p) || !p.isNumber() || p.getAsDouble() != Math.rint(p.getAsDouble())) {
            problems.accept("\"" + key + "\" is " + value + ", not a whole number; it stays " + fallback);
            return fallback;
        }
        double n = p.getAsDouble();
        if (n < min || n > max) {
            int kept = (int) Math.max(min, Math.min(max, n));
            problems.accept("\"" + key + "\" is " + p + ", outside " + min + " to " + max + "; it is " + kept);
            return kept;
        }
        return (int) n;
    }

    /** These limits as the file holds them. */
    public String toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("uploads", uploads.id());
        json.addProperty("library_mb", libraryMb);
        json.addProperty("player_mb", playerMb);
        json.addProperty("player_songs", playerSongs);
        return new GsonBuilder().setPrettyPrinting().create().toJson(json) + "\n";
    }

    private String describe() {
        return String.format(Locale.ROOT, "uploads %s, library %d MiB, each player %d MiB and %d songs", uploads.id(), libraryMb, playerMb, playerSongs);
    }
}
