package org.hurtorius.music.client.ui;

import org.hurtorius.music.song.Song;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * What the piano screen remembers between openings until the player leaves the world: the open
 * tab, whether the panel is hidden, an unsaved recording, the song picked in the library and what
 * the library is searched for, an import that keeps running while the screen is closed (with the
 * files dropped after it waiting their turn), and the errors it showed, which go to chat when the
 * player leaves it. Client thread only.
 */
final class PianoSession {
    static final int PLAY = 0, SONGS = 1, SOUND = 2, PIANO = 3, DEVICES = 4;

    static int tab = PLAY;
    static boolean panelHidden;
    /** The last recording, kept until it is saved or discarded. */
    static @Nullable Song take;
    static String takeName = "";
    static boolean takeUploading;
    /** Library song selected in the Songs tab. */
    static @Nullable String selectedSong;
    /** A song waiting to be selected once the library lists it (just imported). */
    static @Nullable String selectWhenListed;
    /** What the library list is narrowed to (in a song's name or who added it); empty for every song. */
    static String librarySearch = "";
    static @Nullable ImportJob job;
    /** Files dropped on the window that wait for the import before them to be done. */
    static final ArrayDeque<Path> pendingImports = new ArrayDeque<>();
    /**
     * Errors the open screen showed, in chat's colours and dated when they happened (with chat's
     * newest message then), waiting to go to chat when the player leaves the screen; oldest first.
     */
    static final List<Feedback.ChatLater> chatLater = new ArrayList<>();

    private PianoSession() {}

    /** Forgets everything; the world is being left. An import still running is cancelled. */
    static void reset() {
        tab = PLAY;
        panelHidden = false;
        take = null;
        takeName = "";
        takeUploading = false;
        selectedSong = null;
        selectWhenListed = null;
        librarySearch = "";
        pendingImports.clear();
        chatLater.clear();
        if (job != null) job.cancel();
        job = null;
    }
}
