package org.hurtorius.music.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Util;
import org.hurtorius.music.client.importer.AudioDecoder;
import org.hurtorius.music.client.importer.FileDialogs;
import org.hurtorius.music.client.importer.NoFileWindowException;
import org.hurtorius.music.song.Song;
import org.hurtorius.music.song.midi.MidiSongs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * The screen's file work: asking for a file to import with the system's own dialog, starting the
 * import that fits it, importing files dropped on the window one after another, and writing songs
 * out as MIDI files. Where the system has no file window (a Flatpak sandbox has none), an export goes
 * to the game's {@code music/exports} folder and an import is done by dropping the file on the
 * window. Dialogs and writing run off the game thread; results and failures come back to the player
 * through {@link Feedback}. Client thread.
 */
final class SongFiles {
    private static final Logger LOG = LoggerFactory.getLogger("Music");
    private static final List<String> MIDI_EXTENSIONS = List.of("mid", "midi", "kar");

    private SongFiles() {}

    /** Asks for a MIDI file and starts importing it. */
    static void chooseMidi() {
        whenChosen(FileDialogs.openMidi(), SongFiles::importMidi, SongFiles::noWindowToImport);
    }

    /** Asks for a recording and gets it ready to be turned into notes. */
    static void chooseAudio() {
        whenChosen(FileDialogs.openAudio(), SongFiles::importAudio, SongFiles::noWindowToImport);
    }

    /** Imports a MIDI file: the same path the Import MIDI button takes after its dialog. True when the import started. */
    static boolean importMidi(Path file) {
        if (!Files.isRegularFile(file)) {
            Feedback.error(missing(file));
            return false;
        }
        ImportJob.midi(file);
        PianoScreen.refreshOpen();
        return true;
    }

    /** Gets a recording ready to transcribe: the same path the Import audio button takes after its dialog. True when it is. */
    static boolean importAudio(Path file) {
        if (!Files.isRegularFile(file)) {
            Feedback.error(missing(file));
            return false;
        }
        ImportJob.audio(file);
        PianoScreen.refreshOpen();
        return true;
    }

    /**
     * A file that is not there: its name first and then the folder it was in, so a long path cut
     * short on the status line loses the end of the folder rather than the name.
     */
    static Component missing(Path file) {
        Path folder = file.getParent();
        return Component.translatable("music.screen.import.missing", fileName(file), folder == null ? "" : folder.toString());
    }

    /** True for a file name Music imports: a MIDI file or a recording. */
    static boolean importable(Path file) {
        return isMidi(file) || AudioDecoder.hasAudioExtension(file);
    }

    private static boolean isMidi(Path file) {
        String name = file.getFileName() == null ? "" : file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot >= 0 && MIDI_EXTENSIONS.contains(name.substring(dot + 1));
    }

    /**
     * What was dropped, ready to import in order: files as they are, a folder as the files directly
     * in it (by name); everything Music cannot import is left out.
     */
    static List<Path> importableOf(List<Path> dropped) {
        List<Path> files = new ArrayList<>();
        for (Path path : dropped) {
            if (Files.isDirectory(path)) {
                try (Stream<Path> inside = Files.list(path)) {
                    inside.filter(Files::isRegularFile).filter(SongFiles::importable)
                        .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT))).forEach(files::add);
                } catch (IOException | UncheckedIOException e) {
                    Throwable cause = e instanceof UncheckedIOException u ? u.getCause() : e;
                    Feedback.error(Component.translatable("music.screen.import.folder_failed", fileName(path), Feedback.describe(cause)));
                }
            } else if (importable(path)) {
                files.add(path);
            }
        }
        return files;
    }

    /**
     * Imports whatever was dropped on the window: MIDI files and recordings, the first now and the
     * others each after the one before is done, every one named on its own. Anything else is left
     * out, and the player is told.
     */
    static void importDropped(List<Path> dropped) {
        if (dropped.isEmpty()) return;
        List<Path> files = importableOf(dropped);
        int left = (int) dropped.stream().filter(p -> !Files.isDirectory(p) && !importable(p)).count();
        if (files.isEmpty()) {
            if (dropped.size() == 1 && left == 1) Feedback.error(Component.translatable("music.screen.import.unknown_type", fileName(dropped.getFirst())));
            else Feedback.error(Component.translatable("music.screen.import.nothing_dropped"));
            return;
        }
        PianoSession.pendingImports.clear();
        PianoSession.pendingImports.addAll(files);
        importNext();
        if (left > 0) {
            Feedback.info(Component.translatable(left == 1 ? "music.screen.import.left_out.one" : "music.screen.import.left_out", left));
        }
    }

    /**
     * Starts the next file waiting from a drop. One that can no longer be read (moved or deleted
     * while it waited) is reported and passed over, so the files after it are not left waiting.
     */
    static void importNext() {
        startFirst(PianoSession.pendingImports, SongFiles::importFile);
    }

    /** Takes files from the front of {@code waiting} until {@code start} starts one; false when none could start. */
    static boolean startFirst(Queue<Path> waiting, Predicate<Path> start) {
        for (Path next = waiting.poll(); next != null; next = waiting.poll()) {
            if (start.test(next)) return true;
        }
        return false;
    }

    private static boolean importFile(Path file) {
        return isMidi(file) ? importMidi(file) : importAudio(file);
    }

    private static String fileName(Path path) {
        return path.getFileName() == null ? path.toString() : path.getFileName().toString();
    }

    /** Asks where to save {@code song} as a MIDI file and writes it there. */
    static void exportMidi(Song song) {
        whenChosen(FileDialogs.saveMidi(song.name()), path -> write(song, path), noWindow -> exportToGameFolder(song, noWindow));
    }

    private static void write(Song song, Path path) {
        Minecraft mc = Minecraft.getInstance();
        CompletableFuture.runAsync(() -> {
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(path))) {
                MidiSongs.write(song, out);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, Util.ioPool()).whenComplete((done, failure) -> mc.execute(() -> {
            Throwable cause = failure == null ? null : Feedback.cause(failure);
            if (cause instanceof UncheckedIOException io) cause = io.getCause();
            if (failure == null) Feedback.success(Component.translatable("music.screen.export.done", song.name(), path.toString()));
            else if (cause != null) Feedback.error(Component.translatable("music.screen.export.failed", Feedback.describe(cause)));
        }));
    }

    /** The game's own folder for songs going out or coming in by hand ({@code music/exports}, {@code music/import}). */
    private static Path gameFolder(String name) {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("music").resolve(name);
    }

    /** No file window to ask where: the song goes to {@code music/exports} under a free name, and that folder opens. */
    private static void exportToGameFolder(Song song, NoFileWindowException noWindow) {
        Minecraft mc = Minecraft.getInstance();
        Path folder = gameFolder("exports");
        CompletableFuture.supplyAsync(() -> {
            try {
                return TakeKeeper.write(song, folder);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, Util.ioPool()).whenComplete((file, failure) -> mc.execute(() -> {
            Throwable cause = failure == null ? null : Feedback.cause(failure);
            if (cause instanceof UncheckedIOException io) cause = io.getCause();
            if (failure == null) {
                Feedback.success(withInstallHint(Component.translatable("music.screen.export.no_window", song.name(), file.toString()), noWindow));
                Util.getPlatform().openPath(folder);
            } else if (cause != null) {
                Feedback.error(Component.translatable("music.screen.export.failed", Feedback.describe(cause)));
            }
        }));
    }

    /** No file window to choose with: the player drops the file on the window instead, perhaps from {@code music/import}, which opens. */
    private static void noWindowToImport(NoFileWindowException noWindow) {
        Path folder = gameFolder("import");
        try {
            Files.createDirectories(folder);
        } catch (IOException e) {
            LOG.warn("Could not make the folder {} for files to drop", folder, e);
            Feedback.error(withInstallHint(Component.translatable("music.screen.import.no_window"), noWindow));
            return;
        }
        Feedback.error(withInstallHint(Component.translatable("music.screen.import.no_window.folder", folder.toString()), noWindow));
        Util.getPlatform().openPath(folder);
    }

    /** Off a sandbox, a dialog program is something the player can install; inside one it is not. */
    private static Component withInstallHint(MutableComponent text, NoFileWindowException noWindow) {
        return noWindow.sandboxed() ? text : text.append(" ").append(Component.translatable("music.screen.dialog.install"));
    }

    private static void whenChosen(CompletableFuture<Optional<Path>> dialog, Consumer<Path> then, Consumer<NoFileWindowException> noWindow) {
        dialog.whenComplete((chosen, failure) -> {
            if (failure != null) {
                Throwable cause = Feedback.cause(failure);
                if (cause instanceof NoFileWindowException none) noWindow.accept(none);
                else if (cause instanceof IllegalStateException) Feedback.error(Component.translatable("music.screen.dialog.busy"));
                else if (cause != null) Feedback.error(Component.translatable("music.screen.dialog.failed", Feedback.describe(cause)));
                return;
            }
            chosen.ifPresent(then);
        });
    }
}
