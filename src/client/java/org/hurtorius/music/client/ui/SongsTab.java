package org.hurtorius.music.client.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.hurtorius.music.client.play.Pianos;
import org.hurtorius.music.client.play.SelfPlayer;
import org.hurtorius.music.client.play.Uploader;
import org.hurtorius.music.client.song.ClientSongs;
import org.hurtorius.music.net.MusicPayloads;
import org.hurtorius.music.piano.GrandPianoBlockEntity;
import org.hurtorius.music.piano.LoopMode;
import org.hurtorius.music.piano.Playback;
import org.hurtorius.music.song.Song;
import org.hurtorius.music.song.SongInfo;
import org.hurtorius.music.song.SongOrder;
import org.hurtorius.music.song.StarterSongs;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The piano as a music box: the world's song library (with a search box that narrows it to the
 * songs whose name or player has what was typed), this piano's playlist (its selected song moved up
 * or down with ▲ and ▼, the song playing going on wherever it ends up) and its transport
 * (previous, play/pause, stop, next, loop, speed and a seek bar). Songs come in from MIDI files and
 * recordings (turned into notes, approximately) and go out as MIDI files; the player who added a
 * song, or an operator, can rename or delete it. The library lists a song with its length and who
 * added it, and a starter piece with its composer after its title and its length; the playlist lists
 * titles with their lengths, or without them where a title would otherwise be cut. While an import
 * runs, its steps take the place of the lists, saying how many dropped files wait after it.
 */
final class SongsTab extends ScreenTab {
    private static final int CURRENT = 0xFFFFE070, PLAIN = 0xFFFFFFFF;
    /** The library's rows in two groups that give up their credits together: the world's own songs, and the starter pieces. */
    private static final int OWN_SONGS = 0, STARTER_PIECES = 1;
    /** The seek bar shows a title cut short only where this much of it fits, about five letters; less reads as nothing. */
    private static final int SEEK_TITLE_MIN = 30;

    private final Tooltip playTooltip = Tooltip.create(Component.translatable("music.screen.play"));
    private final Tooltip pauseTooltip = Tooltip.create(Component.translatable("music.screen.pause"));
    private final Tooltip notYours = Tooltip.create(Component.translatable("music.message.not_your_song"));
    private final Tooltip startersToOperators = Tooltip.create(Component.translatable("music.message.starter_song"));

    private Button previous, playPause, stop, next;
    private CycleButton<LoopMode> loop;
    private ValueSlider speed, seek;
    private @Nullable TextList<String> library;
    private @Nullable TextList<Integer> playlist;
    private @Nullable SearchBox search;
    private @Nullable StringWidget playlistTitle;
    private @Nullable Button playNow, queue, export, rename, delete, remove, moveUp, moveDown, clear;
    private int shownLibrary = -1;
    private List<String> shownPlaylist = List.of();
    private int shownIndex = -2;
    /** The move asked of the server and not yet back from it, or null. */
    private @Nullable PendingMove pendingMove;

    /**
     * What a move asked of the server will give: its playlist and the index of the song playing in it
     * (a song moved past another of the same song changes only that), and the row the moved song then
     * stands in, which is selected when the piano's playback comes back so.
     */
    private record PendingMove(List<String> playlist, int index, int row) {}

    SongsTab(PianoScreen screen) {
        super(Component.translatable("music.screen.tab.songs"), screen);
    }

    @Override
    protected boolean scrollable() {
        return false;
    }

    @Override
    protected void addRows() {
        Playback playback = playback();
        float square = -geometry.rowHeight();
        previous = button(Component.literal("⏮"), Component.translatable("music.screen.previous"), b -> screen.control(MusicPayloads.PlaybackControl.PREVIOUS));
        playPause = button(Component.literal("▶"), b -> playOrPause());
        stop = button(Component.literal("⏹"), Component.translatable("music.screen.stop"), b -> screen.control(MusicPayloads.PlaybackControl.STOP));
        next = button(Component.literal("⏭"), Component.translatable("music.screen.next"), b -> screen.control(MusicPayloads.PlaybackControl.NEXT));
        loop = CycleButton.<LoopMode>builder(m -> Component.translatable(m.translationKey()), playback.loop())
            .withValues(LoopMode.values())
            .withTooltip(m -> Tooltip.create(Component.translatable("music.screen.loop.tooltip")))
            .create(0, 0, 100, 20, Component.translatable("music.screen.loop"),
                (b, mode) -> screen.send(new MusicPayloads.PlaybackControl(screen.piano(), MusicPayloads.PlaybackControl.LOOP, "", mode.ordinal(), 1f)));
        speed = slider(Playback.MIN_SPEED, Playback.MAX_SPEED, 0.05, playback.speed(),
            v -> Component.translatable("music.screen.speed", String.format(Locale.ROOT, "%.2f", v)), v -> {
            }, v -> screen.send(new MusicPayloads.PlaybackControl(screen.piano(), MusicPayloads.PlaybackControl.SPEED, "", 0, (float) v)));
        seek = slider(0, 1, 0.0005, 0, v -> seekLabel(v), v -> {
        }, this::seekTo);
        seek.setTooltip(Tooltip.create(Component.translatable("music.screen.seek.tooltip")));
        // Loop and speed get room for their longest labels; the seek bar takes the rest. It shares their
        // row on a wide panel, and on a short one where a row of its own would leave the lists a single
        // row, if it still has room there for its times; otherwise it has the row under them.
        int loopLabel = 0;
        for (LoopMode mode : LoopMode.values()) {
            Component label = CommonComponents.optionNameValue(Component.translatable("music.screen.loop"), Component.translatable(mode.translationKey()));
            loopLabel = Math.max(loopLabel, minecraft.font.width(label));
        }
        float loopWidth = -(loopLabel + 10);
        float speedWidth = -(minecraft.font.width(Component.translatable("music.screen.speed", "0.25")) + 10);
        float[] oneRow = cells(square, square, square, square, 1, loopWidth, speedWidth);
        int twoRows = 2 * geometry.rowHeight() + geometry.spacing();
        int seekInRow = ScreenLayout.split(geometry.content().width(), geometry.spacing(), oneRow)[4];
        int used;
        if (geometry.content().width() >= 380 || TextList.rowsShown(listRoom(twoRows)) < 2 && seekInRow >= seekTimesWidth()) {
            row(oneRow, previous, playPause, stop, next, seek, loop, speed);
            used = geometry.rowHeight();
        } else {
            row(cells(square, square, square, square, 1, 1), previous, playPause, stop, next, loop, speed);
            row(cells(1), seek);
            used = twoRows;
        }
        ImportJob job = PianoSession.job;
        if (job != null) importRows(job);
        else listRows(listRoom(used));
        refresh();
    }

    // ------------------------------------------------------------------ library and playlist

    /**
     * The height left for the library and the playlist under transport rows {@code transport} high:
     * what the panel has after the search box's row and the two rows of buttons under the lists.
     */
    private int listRoom(int transport) {
        int row = geometry.rowHeight(), gap = geometry.spacing();
        return geometry.content().height() - transport - 3 * row - 4 * gap;
    }

    /**
     * Lays out the library and the playlist in {@code room} (from {@link #listRoom}) with the rows
     * around them: as many whole rows as fit, and never less than one, so the buttons under them keep
     * clear of the status line.
     */
    private void listRows(int room) {
        int listHeight = TextList.wholeRows(Math.max(TextList.ROW_HEIGHT + 4, room));
        library = new TextList<String>(minecraft, id -> PianoSession.selectedSong = id, id -> playNow(id))
            .empty(Component.translatable("music.screen.library.empty"));
        playlist = new TextList<Integer>(minecraft, i -> {
        }, i -> screen.send(new MusicPayloads.PlaybackControl(screen.piano(), MusicPayloads.PlaybackControl.SELECT, "", i, 1f)))
            .empty(Component.translatable("music.screen.playlist.empty"))
            .namesFirst();
        // The search box takes the place of the library's title; its hint says how many songs there are.
        SearchBox finder = new SearchBox(minecraft.font, Component.translatable("music.screen.library.search.name"));
        finder.setMaxLength(Song.MAX_NAME);
        finder.setValue(PianoSession.librarySearch);
        finder.setResponder(v -> {
            if (v.equals(PianoSession.librarySearch)) return;
            PianoSession.librarySearch = v;
            fillLibrary();
        });
        search = finder;
        playlistTitle = text(Component.empty());
        row(cells(3, 2), finder, playlistTitle);
        row(listHeight, cells(3, 2), library, playlist);

        playNow = button(Component.translatable("music.screen.play_now"), b -> {
            String id = selected();
            if (id != null) playNow(id);
        });
        queue = button(Component.translatable("music.screen.queue"), Component.translatable("music.screen.queue.tooltip"), b -> {
            String id = selected();
            if (id != null) screen.send(new MusicPayloads.PlaybackControl(screen.piano(), MusicPayloads.PlaybackControl.QUEUE, id, 0, 1f));
        });
        export = button(Component.translatable("music.screen.export"), Component.translatable("music.screen.export.library.tooltip"), b -> exportSelected());
        remove = button(Component.translatable("music.screen.remove"), Component.translatable("music.screen.remove.tooltip"), b -> {
            Integer index = playlist.selectedValue();
            if (index != null) screen.send(new MusicPayloads.PlaybackControl(screen.piano(), MusicPayloads.PlaybackControl.REMOVE, "", index, 1f));
        });
        moveUp = button(Component.literal("▲"), Component.translatable("music.screen.move_up"), b -> moveSelected(-1));
        moveDown = button(Component.literal("▼"), Component.translatable("music.screen.move_down"), b -> moveSelected(1));
        float square = -geometry.rowHeight();
        row(aligned() ? underTheLists(3, 3) : followedBy(natural(playNow, queue, export, remove), square, square), playNow, queue, export, remove, moveUp, moveDown);

        clear = button(Component.translatable("music.screen.clear"), Component.translatable("music.screen.clear.tooltip"),
            b -> screen.control(MusicPayloads.PlaybackControl.CLEAR));
        String renaming = screen.renaming();
        SongInfo renamed = renaming == null ? null : ClientSongs.info(renaming);
        if (renamed != null) {
            EditBox box = new EditBox(minecraft.font, 100, 20, Component.translatable("music.screen.rename"));
            box.setMaxLength(Song.MAX_NAME);
            box.setValue(screen.renameText() == null ? renamed.name() : screen.renameText());
            box.setResponder(screen::setRenameText);
            box.moveCursorToEnd(false);
            screen.focusAfterBuild(box);
            Button apply = button(Component.translatable("music.screen.rename.apply"), b -> {
                String text = box.getValue().strip();
                if (!text.isEmpty() && !text.equals(renamed.name())) {
                    screen.send(new MusicPayloads.LibraryAction(MusicPayloads.LibraryAction.RENAME, renamed.id(), text));
                }
                screen.setRenaming(null);
            });
            Button cancel = button(Component.translatable("gui.cancel"), b -> screen.setRenaming(null));
            row(cells(1.6f, 0.7f, 0.7f, 2), box, apply, cancel, clear);
        } else {
            if (renaming != null) screen.setRenaming(null);
            Button importMidi = button(Component.translatable("music.screen.import_midi"), Component.translatable("music.screen.import_midi.tooltip"),
                b -> SongFiles.chooseMidi());
            Button importAudio = button(Component.translatable("music.screen.import_audio"), Component.translatable("music.screen.import_audio.tooltip"),
                b -> SongFiles.chooseAudio());
            rename = button(Component.translatable("music.screen.rename"), b -> {
                String id = selected();
                if (id != null) screen.setRenaming(id);
            });
            delete = button(Component.translatable("music.screen.delete"), b -> deleteSelected());
            row(aligned() ? cells(0.75f, 0.75f, 0.75f, 0.75f, 2) : natural(importMidi, importAudio, rename, delete, clear),
                importMidi, importAudio, rename, delete, clear);
        }
        fillLibrary();
        fillPlaylist();
    }

    /** Wide panels line the buttons up under the two lists; narrow ones size them to their labels. */
    private boolean aligned() {
        return geometry.content().width() >= 480;
    }

    /**
     * Fixed widths for a row of {@code underLibrary} buttons in equal shares under the library and
     * {@code underPlaylist} under the playlist, its last two square (the playlist's ▲ and ▼), each
     * group ending where its list does.
     */
    private float[] underTheLists(int underLibrary, int underPlaylist) {
        int gap = geometry.spacing(), square = geometry.rowHeight();
        int[] lists = ScreenLayout.split(geometry.content().width(), gap, 3, 2);
        int[] left = ScreenLayout.split(lists[0], gap, even(underLibrary));
        float[] right = even(underPlaylist);
        right[underPlaylist - 1] = right[underPlaylist - 2] = -square;
        int[] rightWidths = ScreenLayout.split(lists[1], gap, right);
        float[] cells = new float[underLibrary + underPlaylist];
        for (int i = 0; i < underLibrary; i++) cells[i] = -left[i];
        for (int i = 0; i < underPlaylist; i++) cells[underLibrary + i] = -rightWidths[i];
        return cells;
    }

    /** {@code shares} for the first widgets of a row, then fixed widths for those after them. */
    private static float[] followedBy(float[] shares, float... fixed) {
        float[] cells = new float[shares.length + fixed.length];
        System.arraycopy(shares, 0, cells, 0, shares.length);
        System.arraycopy(fixed, 0, cells, shares.length, fixed.length);
        return cells;
    }

    /**
     * Asks the server to move the selected playlist song {@code step} rows up (negative) or down; the
     * song stays selected in its new row once the server's playlist comes back that way. Past another
     * row of the same song, neither of them playing, the playlist would read just the same: only the
     * selection moves on, and nothing is sent.
     */
    private void moveSelected(int step) {
        TextList<Integer> list = playlist;
        Integer from = list == null ? null : list.selectedValue();
        if (from == null) return;
        Playback playback = playback();
        int to = from + step;
        if (to < 0 || to >= playback.playlist().size()) return;
        Playback moved = playback.moved(from, to);
        if (moved == playback) {
            list.reveal(to);
            return;
        }
        pendingMove = new PendingMove(moved.playlist(), moved.index(), to);
        screen.send(MusicPayloads.PlaybackControl.move(screen.piano(), from, to));
    }

    private @Nullable String selected() {
        return library == null ? null : library.selectedValue();
    }

    private void playNow(String id) {
        screen.send(new MusicPayloads.PlaybackControl(screen.piano(), MusicPayloads.PlaybackControl.PLAY_SONG, id, 0, 1f));
    }

    private void exportSelected() {
        String id = selected();
        if (id == null) return;
        SongInfo info = ClientSongs.info(id);
        String name = info == null ? id : info.name();
        Feedback.info(Component.translatable("music.screen.export.fetching", name));
        ClientSongs.fetch(id).whenComplete((song, failure) -> minecraft.execute(() -> {
            if (failure != null) {
                Throwable cause = Feedback.cause(failure);
                if (cause != null) Feedback.error(Component.translatable("music.screen.export.failed", Feedback.describe(cause)));
                return;
            }
            SongFiles.exportMidi(song);
        }));
    }

    private void deleteSelected() {
        String id = selected();
        SongInfo info = id == null ? null : ClientSongs.info(id);
        if (info == null) return;
        screen.confirm(Component.translatable("music.screen.delete.title", info.name()), Component.translatable("music.screen.delete.message"),
            () -> screen.send(new MusicPayloads.LibraryAction(MusicPayloads.LibraryAction.DELETE, info.id(), "")));
    }

    /** True when a song's name or the player who added it has {@code query} in it (any case); an empty query matches all. */
    static boolean matches(String name, String author, String query) {
        String q = query.strip().toLowerCase(Locale.ROOT);
        return q.isEmpty() || name.toLowerCase(Locale.ROOT).contains(q) || author.toLowerCase(Locale.ROOT).contains(q);
    }

    private void fillLibrary() {
        if (library == null) return;
        String wanted = PianoSession.selectWhenListed;
        SongInfo arrived = wanted == null ? null : ClientSongs.info(wanted);
        if (arrived != null && !matches(arrived.name(), arrived.author(), PianoSession.librarySearch)) {
            // A song just added is shown even when the search would hide it.
            PianoSession.librarySearch = "";
            if (search != null) search.setValue("");
        }
        List<SongInfo> all = new ArrayList<>(ClientSongs.library());
        all.sort(SongOrder.LIBRARY);
        String query = PianoSession.librarySearch;
        List<TextList.Row<String>> rows = new ArrayList<>(all.size());
        for (SongInfo s : all) {
            if (!matches(s.name(), s.author(), query)) continue;
            // A starter piece's composer follows its title; a song's player follows its length. Where
            // a row does not fit with them, they give way in every row of its group.
            String length = Formats.duration(s.durationMs());
            if (StarterSongs.isComposer(s.author())) {
                rows.add(new TextList.Row<>(s.id(), s.name(), s.author(), length, length, STARTER_PIECES, PLAIN));
            } else {
                String detail = s.author().isEmpty() ? length : length + TextList.SEPARATOR + s.author();
                rows.add(new TextList.Row<>(s.id(), s.name(), "", detail, length, OWN_SONGS, PLAIN));
            }
        }
        library.setRows(rows, PianoSession.selectedSong);
        if (arrived != null) {
            PianoSession.selectWhenListed = null;
            PianoSession.selectedSong = wanted;
            library.select(wanted);
        }
        // A song the search hides stays chosen for when the search is cleared; one gone from the world does not.
        String chosen = PianoSession.selectedSong;
        if (library.selectedValue() == null && (chosen == null || ClientSongs.info(chosen) == null)) PianoSession.selectedSong = null;
        shownLibrary = ClientSongs.libraryRevision();
        library.empty(query.isBlank() ? Component.translatable("music.screen.library.empty")
            : Component.translatable("music.screen.library.no_match", query.strip()));
        if (search != null) {
            Component hint = all.size() == 1 ? Component.translatable("music.screen.library.search.one")
                : Component.translatable("music.screen.library.search", all.size());
            search.setHint(hint.copy().withStyle(EditBox.SEARCH_HINT_STYLE));
            search.count(query.isBlank() ? null : Component.translatable("music.screen.library.shown", rows.size(), all.size()));
        }
    }

    /**
     * The library's search box: what was typed, and, at the box's far end in grey, how many of the
     * songs it leaves, apart from the text (and left out when the text reaches that far).
     */
    private static final class SearchBox extends EditBox {
        private static final int COUNT_COLOUR = 0xFF808080;
        private final Font font;
        private @Nullable Component count;

        SearchBox(Font font, Component name) {
            super(font, 100, 20, name);
            this.font = font;
        }

        void count(@Nullable Component text) {
            count = text;
        }

        @Override
        public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            super.renderWidget(g, mouseX, mouseY, partialTick);
            Component shown = count;
            if (shown == null) return;
            int countX = getX() + getWidth() - 4 - font.width(shown);
            // The typed text, and the cursor after it, end here.
            int textEnd = getX() + 4 + font.width(getValue()) + font.width("_");
            if (countX - textEnd < font.width(" ")) return;
            g.drawString(font, shown, countX, getY() + (getHeight() - 8) / 2, COUNT_COLOUR);
        }
    }

    private void fillPlaylist() {
        if (playlist == null) return;
        Playback playback = playback();
        Integer chosen = playlist.selectedValue();
        PendingMove move = pendingMove;
        boolean followed = false;
        if (move != null) {
            // A move that reorders the playlist is answered by the new playlist (the next song may have
            // come on meanwhile); one past the same song, which changes only the index, by the index.
            boolean reorders = !move.playlist().equals(shownPlaylist);
            boolean listChanged = !playback.playlist().equals(shownPlaylist);
            if (reorders ? listChanged : listChanged || playback.index() != shownIndex) {
                // The move came back (the moved song keeps the selection), or something else changed the playlist first.
                followed = move.playlist().equals(playback.playlist()) && (reorders || move.index() == playback.index());
                if (followed) chosen = move.row();
                pendingMove = null;
            }
        }
        List<TextList.Row<Integer>> rows = new ArrayList<>();
        for (int i = 0; i < playback.playlist().size(); i++) {
            String id = playback.playlist().get(i);
            SongInfo info = ClientSongs.info(id);
            String title = info == null ? Component.translatable("music.screen.playlist.unknown").getString() : info.name();
            boolean current = i == playback.index();
            String label = (current ? "▶ " : "") + (i + 1) + ". " + title;
            // The library beside it has the lengths: wherever they would cut a title, the playlist goes without them.
            rows.add(new TextList.Row<>(i, label, "", info == null ? "" : Formats.duration(info.durationMs()), "", 0, current ? CURRENT : PLAIN));
        }
        playlist.setRows(rows, chosen != null && chosen < rows.size() ? chosen : null);
        // A song moved past the list's edge scrolls back into view.
        if (followed) playlist.reveal(chosen);
        shownPlaylist = playback.playlist();
        shownIndex = playback.index();
        if (playlistTitle != null) playlistTitle.setMessage(Component.translatable("music.screen.playlist", rows.size()));
    }

    // ------------------------------------------------------------------ import

    private void importRows(ImportJob job) {
        String file = job.fileName();
        int waiting = PianoSession.pendingImports.size();
        MutableComponent heading = Component.translatable(job.kind == ImportJob.Kind.AUDIO ? "music.screen.import.audio_title" : "music.screen.import.midi_title", file);
        if (waiting > 0) {
            heading.append(" · ").append(waiting == 1 ? Component.translatable("music.screen.import.waiting.one")
                : Component.translatable("music.screen.import.waiting", waiting));
        }
        row(TEXT_ROW, cells(1), text(heading));
        // With dropped files waiting, Cancel becomes Skip (on to the next one) beside Cancel all.
        Button cancel = button(Component.translatable(waiting > 0 ? "music.screen.import.skip" : "gui.cancel"), b -> job.skip());
        Button cancelAll = waiting > 0 ? button(Component.translatable("music.screen.import.cancel_all"), b -> job.cancelAll()) : null;
        switch (job.stage()) {
            case SETUP -> {
                ValueSlider sensitivity = slider(0, 1, 0.05, job.sensitivity,
                    v -> Component.translatable("music.screen.import.sensitivity", Math.round(v * 100)), v -> job.sensitivity = v, v -> job.sensitivity = v);
                sensitivity.setTooltip(Tooltip.create(Component.translatable("music.screen.import.sensitivity.tooltip")));
                Button start = button(Component.translatable("music.screen.import.start"), b -> job.startTranscription());
                withCancel(cells(2, 1), cancel, cancelAll, sensitivity, start);
                row(TEXT_ROW, cells(1), text(Component.translatable("music.screen.import.approximate")));
            }
            case WORKING -> {
                ProgressBar bar = job.kind == ImportJob.Kind.AUDIO
                    ? new ProgressBar(job::progress, () -> Component.translatable("music.screen.import.transcribing", Math.round(job.progress() * 100)))
                    : new ProgressBar(() -> 0.5, () -> Component.translatable("music.screen.import.reading"));
                withCancel(cells(3), cancel, cancelAll, bar);
                if (job.kind == ImportJob.Kind.AUDIO) row(TEXT_ROW, cells(1), text(Component.translatable("music.screen.import.approximate")));
            }
            case NAMING -> {
                EditBox box = new EditBox(minecraft.font, 100, 20, Component.translatable("music.screen.import.name"));
                box.setMaxLength(Song.MAX_NAME);
                box.setValue(job.name);
                box.setResponder(v -> job.name = v);
                box.moveCursorToEnd(false);
                if (PianoSession.tab == PianoSession.SONGS) screen.focusAfterBuild(box);
                Button add = button(Component.translatable("music.screen.import.add"), Component.translatable("music.screen.import.add.tooltip"), b -> job.add(box.getValue()));
                withCancel(cells(2, 1), cancel, cancelAll, box, add);
                Song song = job.song();
                Component facts = song == null ? Component.empty() : song.noteCount() == 1
                    ? Component.translatable("music.screen.import.facts.one", Formats.duration(song.durationMs()))
                    : Component.translatable("music.screen.import.facts", song.noteCount(), Formats.duration(song.durationMs()));
                row(TEXT_ROW, cells(1), text(job.kind == ImportJob.Kind.AUDIO
                    ? Component.translatable("music.screen.import.facts_approximate", facts) : facts));
            }
            case UPLOADING -> {
                ProgressBar bar = new ProgressBar(Uploader::progress, () -> Component.translatable("music.screen.import.uploading", job.name, Math.round(Uploader.progress() * 100)));
                row(cells(1), bar);
            }
        }
    }

    /** A row of {@code widgets} in {@code shares}, ending with the cancel button (and Cancel all, when files wait), each a share of 1. */
    private void withCancel(float[] shares, Button cancel, @Nullable Button cancelAll, AbstractWidget... widgets) {
        int extra = cancelAll == null ? 1 : 2;
        float[] cells = new float[shares.length + extra];
        AbstractWidget[] all = new AbstractWidget[widgets.length + extra];
        System.arraycopy(shares, 0, cells, 0, shares.length);
        System.arraycopy(widgets, 0, all, 0, widgets.length);
        cells[shares.length] = 1;
        all[widgets.length] = cancel;
        if (cancelAll != null) {
            cells[shares.length + 1] = 1;
            all[widgets.length + 1] = cancelAll;
        }
        row(cells, all);
    }

    // ------------------------------------------------------------------ transport

    private Playback playback() {
        GrandPianoBlockEntity be = Pianos.at(screen.piano());
        return be == null ? Playback.EMPTY : be.playback();
    }

    private void playOrPause() {
        Playback playback = playback();
        if (playback.playing()) screen.control(MusicPayloads.PlaybackControl.PAUSE);
        else if (playback.index() >= 0) screen.control(MusicPayloads.PlaybackControl.PLAY);
        else if (selected() != null) playNow(selected());
        else Feedback.info(Component.translatable("music.message.nothing_to_play"));
    }

    /** Length of the song the piano is on, or 0. */
    private long duration() {
        String id = playback().currentSong();
        if (id == null) return 0;
        SongInfo info = ClientSongs.info(id);
        if (info != null) return info.durationMs();
        Song song = SelfPlayer.currentSong(screen.piano());
        return song == null ? 0 : song.durationMs();
    }

    /** Where the song is now, as this client hears it. */
    private long position() {
        GrandPianoBlockEntity be = Pianos.at(screen.piano());
        if (be == null) return 0;
        return SelfPlayer.isSounding(screen.piano()) ? SelfPlayer.position(screen.piano(), be.clientPosition()) : be.clientPosition();
    }

    /**
     * The seek bar's words: where the song is, its length and its title. Where they do not all fit the
     * bar, the times stay whole and the title is cut to the room left, or left out where little of it
     * would show (the playlist has it, after its ▶): the bar never scrolls its times out of view.
     */
    private Component seekLabel(double fraction) {
        long duration = duration();
        if (playback().currentSong() == null || duration <= 0) return Component.translatable("music.screen.seek.none");
        long at = seek != null && seek.busy() ? Math.round(fraction * duration) : Math.min(duration, position());
        String id = playback().currentSong();
        SongInfo info = ClientSongs.info(id);
        String now = Formats.duration(at), length = Formats.duration(duration), title = info == null ? "" : info.name();
        Component full = Component.translatable("music.screen.seek", now, length, title);
        if (seek == null) return full;
        Font font = minecraft.font;
        // Vanilla keeps a slider's words 2 in from each side.
        int room = seek.getWidth() - 4;
        if (font.width(full) <= room) return full;
        int titleRoom = room - font.width(Component.translatable("music.screen.seek", now, length, ""));
        if (titleRoom < SEEK_TITLE_MIN) return Component.translatable("music.screen.seek.times", now, length);
        return Component.translatable("music.screen.seek", now, length, StatusLine.fit(font, title, titleRoom));
    }

    /** How wide a seek bar must be for its times to read whole, for songs up to an hour long. */
    private int seekTimesWidth() {
        return minecraft.font.width(Component.translatable("music.screen.seek.times", "00:00", "00:00")) + 4;
    }

    private void seekTo(double fraction) {
        long duration = duration();
        if (duration <= 0) return;
        screen.send(new MusicPayloads.PlaybackControl(screen.piano(), MusicPayloads.PlaybackControl.SEEK, "", Math.round(fraction * duration), 1f));
    }

    @Override
    protected void refresh() {
        Playback playback = playback();
        boolean edit = screen.canEdit();
        boolean any = !playback.playlist().isEmpty();
        previous.active = edit && any;
        next.active = edit && any;
        stop.active = edit && playback.index() >= 0 && (playback.playing() || playback.positionMs() > 0);
        playPause.active = edit && (any || selected() != null);
        playPause.setMessage(Component.literal(playback.playing() ? "⏸" : "▶"));
        playPause.setTooltip(playback.playing() ? pauseTooltip : playTooltip);
        loop.active = edit;
        if (loop.getValue() != playback.loop()) loop.setValue(playback.loop());
        speed.active = edit;
        speed.sync(playback.speed());
        seek.active = edit && playback.currentSong() != null && duration() > 0;
        if (library != null && shownLibrary != ClientSongs.libraryRevision()) fillLibrary();
        if (library != null && PianoSession.selectWhenListed != null && ClientSongs.info(PianoSession.selectWhenListed) != null) fillLibrary();
        if (playlist != null && (!shownPlaylist.equals(playback.playlist()) || shownIndex != playback.index())) fillPlaylist();
        String id = selected();
        SongInfo info = id == null ? null : ClientSongs.info(id);
        if (playNow != null) playNow.active = edit && info != null;
        if (queue != null) queue.active = edit && info != null && playback.playlist().size() < Playback.MAX_PLAYLIST;
        if (export != null) export.active = info != null;
        boolean manage = info != null && screen.mayManage(info);
        Tooltip refusal = info == null || manage ? null : StarterSongs.isComposer(info.author()) ? startersToOperators : notYours;
        if (rename != null) {
            rename.active = manage;
            rename.setTooltip(refusal);
        }
        if (delete != null) {
            delete.active = manage;
            delete.setTooltip(refusal);
        }
        Integer picked = playlist == null ? null : playlist.selectedValue();
        if (remove != null) remove.active = edit && picked != null;
        if (moveUp != null) moveUp.active = edit && picked != null && picked > 0;
        if (moveDown != null) moveDown.active = edit && picked != null && picked < playback.playlist().size() - 1;
        if (clear != null) clear.active = edit && any;
    }

    @Override
    void frame() {
        long duration = duration();
        if (!seek.busy()) seek.sync(duration <= 0 ? 0 : Math.min(1, position() / (double) duration));
        seek.relabel();
    }
}
