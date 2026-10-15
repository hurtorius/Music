package org.hurtorius.music.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.hurtorius.music.client.importer.Transcriber;
import org.hurtorius.music.client.play.Uploader;
import org.hurtorius.music.song.Song;
import org.hurtorius.music.song.midi.MidiSongs;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * One song on its way into the world's library: a MIDI file is read, or a recording is turned into
 * notes; then the player names it and it is uploaded. The work runs in the background and goes on
 * while the screen is closed; every change of stage happens on the client thread and is announced
 * through {@link Feedback} and the open piano screen. Only the job in {@link PianoSession#job} is
 * live: a cancelled one ignores whatever its background work still delivers. Files dropped together
 * wait in {@link PianoSession#pendingImports}; each starts when the one before it is done.
 */
final class ImportJob {
    enum Kind { MIDI, AUDIO }

    enum Stage {
        /** Audio only: the player sets the sensitivity and starts. */
        SETUP,
        /** Reading or transcribing. */
        WORKING,
        /** The song is ready; the player names it and adds it. */
        NAMING,
        /** Sending it to the world. */
        UPLOADING
    }

    final Path file;
    final Kind kind;
    private Stage stage;
    /** Transcription sensitivity 0..1 chosen in {@link Stage#SETUP}. */
    double sensitivity = Transcriber.Options.DEFAULT.sensitivity();
    /** 0..1 progress of a transcription; written by its worker thread. */
    private volatile double progress;
    private volatile boolean cancelled;
    private @Nullable Song song;
    /** The name the player is typing in {@link Stage#NAMING}. */
    String name = "";

    private ImportJob(Path file, Kind kind, Stage stage) {
        this.file = file;
        this.kind = kind;
        this.stage = stage;
    }

    /** Starts reading a MIDI file and makes it the session's import. */
    static ImportJob midi(Path file) {
        ImportJob job = replace(new ImportJob(file, Kind.MIDI, Stage.WORKING));
        job.readMidi();
        return job;
    }

    /** Makes a recording the session's import, waiting in {@link Stage#SETUP} for the player to start it. */
    static ImportJob audio(Path file) {
        return replace(new ImportJob(file, Kind.AUDIO, Stage.SETUP));
    }

    private static ImportJob replace(ImportJob job) {
        ImportJob old = PianoSession.job;
        if (old != null) old.cancel();
        PianoSession.job = job;
        return job;
    }

    Stage stage() {
        return stage;
    }

    double progress() {
        return progress;
    }

    @Nullable Song song() {
        return song;
    }

    /** The file's name, for messages. */
    String fileName() {
        Path name = file.getFileName();
        return name == null ? file.toString() : name.toString();
    }

    private boolean live() {
        return PianoSession.job == this && !cancelled;
    }

    /** Stops the job: background work is abandoned and the session forgets it. Files waiting stay waiting. */
    void cancel() {
        cancelled = true;
        if (PianoSession.job == this) PianoSession.job = null;
        PianoScreen.refreshOpen();
    }

    /** The player gave this file up: it stops, and the next file waiting starts. */
    void skip() {
        boolean wasLive = live();
        cancel();
        if (wasLive) SongFiles.importNext();
    }

    /** The player gave up this file and every file waiting after it. */
    void cancelAll() {
        PianoSession.pendingImports.clear();
        cancel();
    }

    /** This file is done with, one way or another: the next file waiting starts. */
    private void done() {
        PianoSession.job = null;
        SongFiles.importNext();
        PianoScreen.refreshOpen();
    }

    // ------------------------------------------------------------------ reading

    private void readMidi() {
        Minecraft mc = Minecraft.getInstance();
        CompletableFuture.supplyAsync(() -> {
            try (InputStream in = Files.newInputStream(file)) {
                return MidiSongs.read(in, "");
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, Util.ioPool()).whenComplete((read, failure) -> mc.execute(() -> finishReading(read, failure)));
    }

    /** Starts turning the recording into notes with the chosen sensitivity. */
    void startTranscription() {
        if (stage != Stage.SETUP || !live()) return;
        stage = Stage.WORKING;
        progress = 0;
        Minecraft mc = Minecraft.getInstance();
        Transcriber.Options options = new Transcriber.Options(Math.max(0, Math.min(1, sensitivity)), true, true);
        Transcriber.importAudio(file, options, p -> progress = p, () -> cancelled)
            .whenComplete((read, failure) -> mc.execute(() -> finishReading(read, failure)));
        PianoScreen.refreshOpen();
    }

    private void finishReading(@Nullable Song read, @Nullable Throwable failure) {
        if (!live()) return;
        if (failure != null) {
            Throwable cause = Feedback.cause(failure);
            if (cause != null) Feedback.error(Component.translatable("music.screen.import.failed", fileName(), Feedback.describe(cause)));
            done();
            return;
        }
        if (read == null || read.noteCount() == 0) {
            Feedback.error(Component.translatable("music.screen.import.no_notes", fileName()));
            done();
            return;
        }
        song = read;
        name = kind == Kind.MIDI && "Untitled".equals(read.name()) ? baseName() : read.name();
        stage = Stage.NAMING;
        // On the Songs tab the naming row, with the song's facts, says this already.
        if (!PianoScreen.showingTab(PianoSession.SONGS)) Feedback.info(Component.translatable("music.screen.import.ready", fileName()));
        PianoScreen.refreshOpen();
    }

    private String baseName() {
        String n = fileName();
        int dot = n.lastIndexOf('.');
        return (dot > 0 ? n.substring(0, dot) : n).strip();
    }

    // ------------------------------------------------------------------ adding

    /** Uploads the song under {@code chosenName} (the song's own name when blank). */
    void add(String chosenName) {
        Song ready = song;
        if (stage != Stage.NAMING || ready == null || !live()) return;
        String finalName = chosenName == null || chosenName.isBlank() ? ready.name() : chosenName.strip();
        Song named = finalName.equals(ready.name()) ? ready : ready.toBuilder().name(finalName).build();
        name = named.name();
        stage = Stage.UPLOADING;
        Minecraft mc = Minecraft.getInstance();
        Uploader.upload(named).whenComplete((id, failure) -> mc.execute(() -> finishUpload(named, id, failure)));
        PianoScreen.refreshOpen();
    }

    private void finishUpload(Song named, @Nullable String id, @Nullable Throwable failure) {
        if (PianoSession.job != this) return;
        if (failure != null || id == null) {
            Throwable cause = failure == null ? null : Feedback.cause(failure);
            stage = Stage.NAMING;
            Feedback.error(Component.translatable("music.screen.upload.failed", named.name(), cause == null ? "?" : Feedback.describe(cause)));
            PianoScreen.refreshOpen();
            return;
        }
        PianoSession.selectedSong = id;
        PianoSession.selectWhenListed = id;
        Feedback.success(Component.translatable("music.screen.upload.done", named.name()));
        done();
    }
}
