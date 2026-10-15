package org.hurtorius.music.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import org.hurtorius.music.client.play.Recorder;
import org.hurtorius.music.song.Song;
import org.hurtorius.music.song.midi.MidiSongs;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Set;

/**
 * Keeps a take the player has not saved when they leave the world. A recording still running is
 * stopped where it is; it, or a finished take still waiting to be saved, is written as a MIDI file to
 * {@code music/takes} in the game folder, and a toast says where once the world is gone (leaving a
 * world clears every toast). Leaving the world otherwise forgets the session, and the world the take
 * was meant for is gone with it. Client thread.
 */
public final class TakeKeeper {
    private static final Logger LOG = LoggerFactory.getLogger("Music");
    private static final long TOAST_MILLIS = 10_000L;
    private static final int MAX_FILE_NAME = 64;
    /** Names Windows will not give to a file, whatever the extension. */
    private static final Set<String> RESERVED = Set.of("con", "prn", "aux", "nul",
        "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
        "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

    /** Made on first use, so Minecraft's toast classes stay out of plain unit tests. */
    private static SystemToast.@Nullable SystemToastId toast;
    private static @Nullable Component pendingTitle, pendingMessage;

    private TakeKeeper() {}

    /** Writes out the unsaved take, if there is one, and forgets it. */
    static void keep(Minecraft mc) {
        Song take = null;
        if (Recorder.isRecording()) {
            String name = PianoSession.takeName.isBlank() ? PlayTab.defaultTakeName() : PianoSession.takeName.strip();
            take = Recorder.stop(name);
        }
        if (take == null && PianoSession.take != null) take = PlayTab.named(PianoSession.take);
        PianoSession.take = null;
        PianoSession.takeName = "";
        if (take == null) return;
        Path folder = mc.gameDirectory.toPath().resolve("music").resolve("takes");
        try {
            Path file = write(take, folder);
            LOG.info("Kept the unsaved take \"{}\" ({} notes) in {}", take.name(), take.noteCount(), file);
            pendingTitle = Component.translatable("music.take.kept.title");
            pendingMessage = Component.translatable("music.take.kept", file.getFileName().toString());
        } catch (IOException | RuntimeException e) {
            LOG.error("Could not keep the unsaved take \"{}\" in {}", take.name(), folder, e);
            pendingTitle = Component.translatable("music.take.lost.title");
            pendingMessage = Component.translatable("music.take.lost", String.valueOf(e.getMessage()));
        }
    }

    /** Shows the toast about the kept take once the world it was made in is gone. Client thread, every tick. */
    static void showPending(Minecraft mc) {
        if (pendingTitle == null || pendingMessage == null || mc.level != null) return;
        if (toast == null) toast = new SystemToast.SystemToastId(TOAST_MILLIS);
        mc.getToastManager().addToast(SystemToast.multiline(mc, toast, pendingTitle, pendingMessage));
        pendingTitle = null;
        pendingMessage = null;
    }

    /** True while the toast about a kept take is on screen. */
    public static boolean toastShowing(Minecraft mc) {
        return toast != null && mc.getToastManager().getToast(SystemToast.class, toast) != null;
    }

    /** Writes {@code take} into {@code folder} under a free name made from its own. */
    static Path write(Song take, Path folder) throws IOException {
        Files.createDirectories(folder);
        String base = fileName(take.name());
        for (int n = 1; ; n++) {
            Path file = folder.resolve(n == 1 ? base + ".mid" : base + " (" + n + ").mid");
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
                MidiSongs.write(take, out);
                return file;
            } catch (FileAlreadyExistsException taken) {
                if (n >= 999) throw taken;
            }
        }
    }

    /** A file name every common file system accepts, made from a song name. */
    static String fileName(@Nullable String songName) {
        StringBuilder b = new StringBuilder();
        String name = songName == null ? "" : songName.strip();
        for (int i = 0; i < name.length() && b.length() < MAX_FILE_NAME; ) {
            int c = name.codePointAt(i);
            i += Character.charCount(c);
            boolean safe = Character.isLetterOrDigit(c) || c == ' ' || c == '-' || c == '_' || c == '(' || c == ')' || c == '.' || c == ',' || c == '\'';
            b.appendCodePoint(safe ? c : '_');
        }
        String result = b.toString().strip();
        while (result.endsWith(".")) result = result.substring(0, result.length() - 1).strip();
        if (result.isEmpty()) return "Take";
        String stem = result.contains(".") ? result.substring(0, result.indexOf('.')) : result;
        return RESERVED.contains(stem.toLowerCase(Locale.ROOT)) ? result + " take" : result;
    }
}
