package org.hurtorius.music.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.function.Consumer;

/**
 * This player's own preferences ({@code config/music.json}). Nothing here affects what other players
 * hear or see. Fields are public for the screen to edit; call {@link #save()} after changing them.
 * Each value is read on its own, so one the game cannot use (a hand edit gone wrong) keeps its
 * default and costs none of the others; a file that is not JSON at all is kept beside the new one as
 * {@code music.json.broken} before anything is saved over it, and saving replaces the file whole, so a
 * crash in the middle of it never leaves half a file.
 */
public final class ClientPrefs {
    private static final Logger LOG = LoggerFactory.getLogger("Music");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static ClientPrefs instance;

    /** MIDI input device name, or "" for none. */
    public String midiInput = "";
    /**
     * True once the player chose "None" for the MIDI keyboard: a lone keyboard is then no longer
     * connected by itself when they sit at a piano.
     */
    public boolean midiInputOff = false;
    /** MIDI output device name that receives what is played at the piano, or "" for none. */
    public String midiOutput = "";
    /** Lowest octave of the computer keyboard's lower row, as a MIDI note (C3 = 48); C0 = 12 reaches A0. */
    public int keyboardBase = 48;
    /** Velocity for computer keyboard and mouse notes when not using a velocity-sensitive device. */
    public int keyVelocity = 90;
    /** "soft", "normal", "hard" or "fixed": how MIDI device velocities are shaped. */
    public String velocityCurve = "normal";
    /** Camera while seated: "player", "keys", "side" or "top". */
    public String camera = "player";
    /** Show both hands over the keys in first person while seated. */
    public boolean firstPersonHands = true;
    /** What the piano screen shows above its keys: "roll" (falling notes), "notation" (sheet music) or "off". */
    public String sheetView = "roll";
    /** Hold sustain with the space bar while it is down (true) or toggle it on each press (false). */
    public boolean holdSustain = true;
    public boolean metronome = false;
    public int metronomeBpm = 100;
    public int metronomeBeats = 4;
    public float metronomeVolume = 0.6f;
    /** Extra delay for other players' live notes so their rhythm survives network jitter. */
    public int remoteDelayMs = 90;
    /** Most voices this client plays at once across all pianos. */
    public int voiceLimit = 64;
    /** How loud other people's pianos are for this player only (0..1): every piano they did not place, unless they sit at it. */
    public float otherPianosVolume = 1f;
    /** Keep Minecraft's own music away while the player plays or hears a piano, and for half a minute after. */
    public boolean hushGameMusic = true;
    /** Folder the file picker opens in. */
    public String lastFolder = "";

    private ClientPrefs() {}

    public static ClientPrefs get() {
        if (instance == null) instance = load();
        return instance;
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("music.json");
    }

    private static ClientPrefs load() {
        Path file = file();
        if (Files.isRegularFile(file)) {
            try {
                ClientPrefs prefs = parse(Files.readString(file, StandardCharsets.UTF_8), problem -> LOG.warn("{}: {}", file, problem));
                if (prefs != null) return prefs;
            } catch (IOException | JsonParseException e) {
                Path kept = file.resolveSibling(file.getFileName() + ".broken");
                LOG.warn("{} cannot be read; it is kept as {} and the preferences start from their defaults", file, kept.getFileName(), e);
                try {
                    Files.copy(file, kept, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException copyFailure) {
                    LOG.error("Could not keep a copy of {} as {}", file, kept, copyFailure);
                }
            }
        }
        return new ClientPrefs();
    }

    /** Preferences read from the JSON of {@code config/music.json}, every value brought into range; null for an empty text. */
    static @Nullable ClientPrefs parse(String json) {
        return parse(json, problem -> LOG.warn("config/music.json: {}", problem));
    }

    /**
     * Preferences read from the JSON of {@code config/music.json}, one value at a time: a value that
     * does not fit its preference keeps the default and is told to {@code problems}, a name no
     * preference has is left out, and every value is brought into range. Null for an empty text.
     *
     * @throws JsonParseException when the text is not a JSON object
     */
    static @Nullable ClientPrefs parse(String json, Consumer<String> problems) {
        JsonElement root = JsonParser.parseString(json);
        if (root.isJsonNull()) return null;
        if (!root.isJsonObject()) throw new JsonParseException("The preferences are not a JSON object");
        ClientPrefs prefs = new ClientPrefs();
        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject().entrySet()) {
            Field field = preference(entry.getKey());
            if (field == null) continue;
            try {
                Object value = GSON.fromJson(entry.getValue(), field.getGenericType());
                if (value == null && field.getType().isPrimitive()) throw new JsonParseException("no value");
                field.set(prefs, value);
            } catch (JsonParseException | IllegalArgumentException | IllegalAccessException e) {
                problems.accept("\"" + entry.getKey() + "\" is " + entry.getValue() + ", which it cannot be; it stays " + defaultOf(field));
            }
        }
        return prefs.sanitized();
    }

    /** The preference called {@code name} (a public field saved to the file), or null. */
    private static @Nullable Field preference(String name) {
        try {
            Field field = ClientPrefs.class.getField(name);
            int modifiers = field.getModifiers();
            return Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers) ? null : field;
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    private static String defaultOf(Field field) {
        try {
            return String.valueOf(field.get(new ClientPrefs()));
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("A preference is not readable: " + field.getName(), e);
        }
    }

    /** These preferences, brought into range, as the JSON saved to {@code config/music.json}. */
    String toJson() {
        return GSON.toJson(sanitized());
    }

    private ClientPrefs sanitized() {
        if (midiInput == null) midiInput = "";
        if (midiOutput == null) midiOutput = "";
        keyboardBase = Math.max(12, Math.min(84, keyboardBase));
        keyVelocity = Math.max(1, Math.min(127, keyVelocity));
        if (velocityCurve == null) velocityCurve = "normal";
        if (camera == null) camera = "player";
        if (!"notation".equals(sheetView) && !"off".equals(sheetView)) sheetView = "roll";
        metronomeBpm = Math.max(20, Math.min(300, metronomeBpm));
        metronomeBeats = Math.max(1, Math.min(12, metronomeBeats));
        // Gson reads a bare NaN from a hand-edited file but refuses to write one.
        metronomeVolume = Float.isNaN(metronomeVolume) ? 0.6f : Math.max(0f, Math.min(1f, metronomeVolume));
        remoteDelayMs = Math.max(0, Math.min(500, remoteDelayMs));
        voiceLimit = Math.max(16, Math.min(128, voiceLimit));
        otherPianosVolume = Float.isNaN(otherPianosVolume) ? 1f : Math.max(0f, Math.min(1f, otherPianosVolume));
        if (lastFolder == null) lastFolder = "";
        return this;
    }

    /** Writes the preferences to their file, replacing it whole (through a temporary file next to it). */
    public void save() {
        Path file = file();
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, toJson(), StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not save {}", file, e);
        }
    }
}
