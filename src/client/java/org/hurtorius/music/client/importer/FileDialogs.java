package org.hurtorius.music.client.importer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import org.hurtorius.music.client.ClientPrefs;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The operating system's own open and save dialogs (tinyfd, which Minecraft bundles), shown from a
 * daemon thread so the game keeps running while one is open. Call from the client thread; the
 * returned future also completes on the client thread, with the chosen file or empty when the
 * player cancelled. The folder of the last chosen file is remembered in
 * {@link ClientPrefs#lastFolder}. One dialog at a time: asking while one is open fails the future.
 * Where the system has no dialog program the future fails with a {@link NoFileWindowException}.
 */
public final class FileDialogs {
    private static final Logger LOG = LoggerFactory.getLogger("Music");
    /** Files offered when importing MIDI. */
    public static final List<String> MIDI_PATTERNS = List.of("*.mid", "*.midi", "*.kar");
    /** Files offered when importing a recording to transcribe. */
    public static final List<String> AUDIO_PATTERNS = List.of("*.mp3", "*.wav", "*.ogg", "*.flac", "*.aif", "*.aiff", "*.au");
    private static final List<String> SAVE_PATTERNS = List.of("*.mid");
    private static final int MAX_FILE_NAME = 100;
    /**
     * Characters that keep a meaning inside the double-quoted arguments of the shell command tinyfd
     * (3.13.3, which only refuses quotes) runs for its dialogs on macOS and Linux: command substitution,
     * variables and escapes. Titles come from translations a server's resource pack can replace, file
     * names from songs other players named, folders from wherever the player went.
     */
    private static final String SHELL_SPECIAL = "\"'`$\\";

    private static final ExecutorService THREAD = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "music-file-dialog");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicBoolean OPEN = new AtomicBoolean();
    /** Whether tinyfd has a graphical dialog to show; null until first asked. Dialog thread only. */
    private static Boolean graphical;

    private FileDialogs() {}

    /** Asks for a MIDI file to import (.mid, .midi, .kar). */
    public static CompletableFuture<Optional<Path>> openMidi() {
        return show(false, I18n.get("music.dialog.open_midi"), I18n.get("music.dialog.filter_midi"), MIDI_PATTERNS, null);
    }

    /** Asks for a recording to transcribe (.mp3, .wav, .ogg, .flac, .aif, .aiff, .au). */
    public static CompletableFuture<Optional<Path>> openAudio() {
        return show(false, I18n.get("music.dialog.open_audio"), I18n.get("music.dialog.filter_audio"), AUDIO_PATTERNS, null);
    }

    /** Asks where to export a MIDI file, suggesting {@code suggestedName}.mid. The result always ends in .mid or .midi. */
    public static CompletableFuture<Optional<Path>> saveMidi(String suggestedName) {
        return show(true, I18n.get("music.dialog.save_midi"), I18n.get("music.dialog.filter_midi"), SAVE_PATTERNS, fileName(suggestedName) + ".mid");
    }

    /** True while a dialog is showing. */
    public static boolean isOpen() {
        return OPEN.get();
    }

    private static CompletableFuture<Optional<Path>> show(boolean save, String title, String filterName, List<String> patterns, String fileName) {
        CompletableFuture<Optional<Path>> result = new CompletableFuture<>();
        if (!OPEN.compareAndSet(false, true)) {
            result.completeExceptionally(new IllegalStateException("A file dialog is already open"));
            return result;
        }
        Minecraft minecraft = Minecraft.getInstance();
        boolean windows = windows();
        String lastFolder = ClientPrefs.get().lastFolder;
        List<String> filters = platformPatterns(patterns);
        String shownTitle = dialogText(title, windows), shownFilter = dialogText(filterName, windows);
        try {
            THREAD.execute(() -> {
                Optional<Path> chosen = Optional.empty();
                Throwable failure = null;
                try {
                    // Looking at the last folder can wait for a network share that is offline: never on the render thread.
                    chosen = runDialog(save, shownTitle, filters, shownFilter, startPath(lastFolder, fileName, windows));
                } catch (Throwable t) {
                    failure = t;
                } finally {
                    OPEN.set(false);
                }
                Optional<Path> picked = chosen;
                Throwable error = failure;
                minecraft.execute(() -> deliver(result, picked, error));
            });
        } catch (RuntimeException e) {
            OPEN.set(false);
            result.completeExceptionally(e);
        }
        return result;
    }

    private static void deliver(CompletableFuture<Optional<Path>> result, Optional<Path> chosen, Throwable failure) {
        if (failure != null) {
            LOG.error("The file dialog failed", failure);
            result.completeExceptionally(failure);
            return;
        }
        chosen.map(Path::getParent).ifPresent(folder -> {
            ClientPrefs prefs = ClientPrefs.get();
            prefs.lastFolder = folder.toString();
            prefs.save();
        });
        result.complete(chosen);
    }

    private static Optional<Path> runDialog(boolean save, String title, List<String> patterns, String filterName, String start) throws IOException {
        requireGraphicalDialogs();
        String chosen;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = stack.mallocPointer(patterns.size());
            for (String pattern : patterns) filters.put(stack.UTF8(pattern));
            filters.flip();
            chosen = save
                ? TinyFileDialogs.tinyfd_saveFileDialog(title, start, filters, filterName)
                : TinyFileDialogs.tinyfd_openFileDialog(title, start, filters, filterName, false);
        }
        if (chosen == null || chosen.isBlank()) return Optional.empty();
        Path path = Path.of(chosen).toAbsolutePath().normalize();
        if (save) {
            String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
            if (!name.endsWith(".mid") && !name.endsWith(".midi")) path = path.resolveSibling(path.getFileName() + ".mid");
        }
        return Optional.of(path);
    }

    /**
     * Without a dialog program (some Linux systems lack zenity and kdialog, and a Flatpak sandbox has
     * neither) tinyfd falls back to reading the console, which would wait forever. Its query mode says
     * which backend it would use without showing anything; the native call is used because the result
     * is a flag, not a string.
     */
    private static void requireGraphicalDialogs() throws NoFileWindowException {
        if (graphical == null) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                long answer = TinyFileDialogs.ntinyfd_openFileDialog(MemoryUtil.memAddress(stack.UTF8("tinyfd_query")), 0L, 0, 0L, 0L, 0);
                graphical = answer != 0L;
                LOG.info("File dialogs use: {}", TinyFileDialogs.tinyfd_getGlobalChar("tinyfd_response"));
            }
        }
        if (!graphical) throw new NoFileWindowException(sandboxed());
    }

    /**
     * The folder to start in, with a trailing separator (and the file name for saving). It touches the
     * file system, so the dialog thread calls it. {@code fileName} comes from {@link #fileName} or is null.
     */
    static String startPath(String lastFolder, String fileName, boolean windows) {
        Path folder = null;
        if (lastFolder != null && !lastFolder.isBlank()) {
            try {
                Path last = Path.of(lastFolder);
                if (Files.isDirectory(last)) folder = last;
            } catch (RuntimeException e) {
                LOG.debug("Ignoring unusable last folder {}", lastFolder);
            }
        }
        if (folder == null) {
            Path home = Path.of(System.getProperty("user.home", "."));
            folder = Files.isDirectory(home.resolve("Music")) ? home.resolve("Music") : home;
        }
        String start = folder.toAbsolutePath() + File.separator + (fileName == null ? "" : fileName);
        // On macOS and Linux this goes into a shell command: start without a folder the shell would read.
        if (!windows && shellUnsafe(start)) return fileName;
        return start;
    }

    /** True inside a Flatpak sandbox (Flatpak sets FLATPAK_ID and puts its info file at the root). */
    private static boolean sandboxed() {
        return System.getenv("FLATPAK_ID") != null || Files.exists(Path.of("/.flatpak-info"));
    }

    /** Linux dialogs match patterns case-sensitively: offer SONG.MID as well as song.mid there. */
    private static List<String> platformPatterns(List<String> patterns) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (windows() || os.startsWith("mac")) return patterns;
        List<String> all = new ArrayList<>(patterns);
        for (String p : patterns) all.add(p.toUpperCase(Locale.ROOT));
        return all;
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    /** A dialog's title or filter name as shown: off Windows, characters the shell would read become spaces. */
    static String dialogText(String text, boolean windows) {
        if (windows) return text;
        StringBuilder shown = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) shown.append(shellUnsafe(text.charAt(i)) ? ' ' : text.charAt(i));
        return shown.toString();
    }

    private static boolean shellUnsafe(String text) {
        for (int i = 0; i < text.length(); i++) if (shellUnsafe(text.charAt(i))) return true;
        return false;
    }

    private static boolean shellUnsafe(char c) {
        return SHELL_SPECIAL.indexOf(c) >= 0 || Character.isISOControl(c);
    }

    /** A file name made from a song name: no path separators, reserved, control or shell characters. */
    static String fileName(String name) {
        String cleaned = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}'`$]", "_").strip();
        while (cleaned.endsWith(".")) cleaned = cleaned.substring(0, cleaned.length() - 1).strip();
        if (cleaned.length() > MAX_FILE_NAME) cleaned = cleaned.substring(0, MAX_FILE_NAME).strip();
        return cleaned.isEmpty() ? "song" : cleaned;
    }
}
