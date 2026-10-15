package org.hurtorius.music.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.hurtorius.music.client.ClientPrefs;
import org.hurtorius.music.client.play.Pianos;
import org.hurtorius.music.client.play.SelfPlayer;
import org.hurtorius.music.client.render.DeskSheet;
import org.hurtorius.music.client.render.SheetPainter;
import org.hurtorius.music.client.song.ClientSongs;
import org.hurtorius.music.piano.GrandPianoBlockEntity;
import org.hurtorius.music.piano.Playback;
import org.hurtorius.music.piano.SheetMode;
import org.hurtorius.music.registry.MusicContent;
import org.hurtorius.music.song.Song;
import org.jspecify.annotations.Nullable;

/**
 * The strip above the keyboard that shows the music: a take the player listens back to, the take
 * being recorded, the song this piano is playing by itself, or what the player just played. With
 * nothing to show it draws nothing at all; a song or a take lies on a translucent band, and what the
 * player just played on none (they are watching the piano's own keys and their hands under it), so
 * those stay in view behind it. {@link ClientPrefs#sheetView} picks the view:
 * "notation" is sheet music painted by the desk's {@link SheetPainter} a page at a time with a
 * moving playhead; "roll" is a piano roll whose notes fall onto the very keys below that play them
 * (what the player plays rises away from the keys instead, and always as a roll: it has no pages);
 * "off" leaves the strip out. Drawing happens in device pixels; the notation page is a texture
 * exactly the strip's pixel size, so it is as sharp as the screen allows.
 */
final class SheetStrip extends AbstractWidget {
    /** Song time the roll shows above the keys. */
    static final long ROLL_SPAN_MS = 3_000;
    /** The notation page is repainted at most this often (played notes fade as the music passes). */
    static final long REPAINT_NANOS = 100_000_000L;
    /** A song still downloading is asked for at most this often. */
    static final long LOOKUP_NANOS = 250_000_000L;
    private static final Identifier TEXTURE = MusicContent.id("dynamic/piano_screen_sheet");
    private static final int ROLL_BACKGROUND = 0x80141414, ROLL_GRID = 0x26FFFFFF, ROLL_BAR = 0x40FFFFFF, NOW_LINE = 0x90FFFFFF;
    private static final int RIGHT = 0xFF5B9BFF, LEFT = 0xFFFF8F57, EITHER = 0xFFD8D8D8, NOTE_EDGE = 0xFF0C0C0C;
    private static final int HINT = 0xFFA8A8A8, HINT_BACKGROUND = 0x60000000;
    private static final Song NO_NOTES = new Song.Builder("take").build();

    /** How a view moves: a song falls onto the keys; what is being played rises away from them. */
    private enum Kind { SONG, TAKE, FREE_PLAY }

    /** What the strip shows: a song at a position, the take being recorded, or free playing. */
    private record View(Song song, long position, int transpose, Kind kind) {}

    private final BlockPos piano;
    private @Nullable DynamicTexture texture;
    private @Nullable SheetPainter painter;
    private int textureWidth, textureHeight;
    private @Nullable Song pagedSong;
    private long[] pages = new long[0];
    private int paintedPage = -1;
    private long paintedPosition = -1, paintedAt;
    private @Nullable Song longestOf;
    private long longest;
    private @Nullable Song barsOf;
    private long[] barLines = new long[0];
    private @Nullable String songId;
    private @Nullable Song song;
    private long lookedUpAt;
    private long shownPosition = -1;
    private boolean loading;

    SheetStrip(BlockPos piano) {
        super(0, 0, 0, 0, Component.translatable("music.screen.sheet"));
        this.piano = piano;
        this.active = false;
    }

    /** Song position (or take time) shown at the last frame, -1 when nothing was shown. */
    long shownPosition() {
        return shownPosition;
    }

    private static int guiScale() {
        return Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
    }

    private @Nullable View view() {
        loading = false;
        Song listened = TakePreview.song(piano);
        if (listened != null) return new View(listened, TakePreview.position(), 0, Kind.SONG);
        PlayedNotes played = PlayedNotes.INSTANCE;
        if (played.recording()) {
            Song preview = played.preview();
            return new View(preview == null ? NO_NOTES : preview, played.now(), 0, Kind.TAKE);
        }
        // A song playing comes first; free playing over a song standing paused shows what is played.
        View song = songView();
        if (song != null && isPlaying()) return song;
        if (played.showing()) return new View(NO_NOTES, played.now(), 0, Kind.FREE_PLAY);
        return song;
    }

    private boolean isPlaying() {
        GrandPianoBlockEntity be = Pianos.at(piano);
        return be != null && be.playback().playing();
    }

    /** The song the piano plays by itself (or stands paused in), or null; notes {@link #loading} while it downloads. */
    private @Nullable View songView() {
        GrandPianoBlockEntity be = Pianos.at(piano);
        if (be == null) return null;
        Playback playback = be.playback();
        String id = playback.currentSong();
        if (id == null || !playback.playing() && playback.positionMs() <= 0) return null;
        Song current = lookup(id);
        if (current == null) {
            loading = true;
            return null;
        }
        // Where the sound is (and the desk sheet), not where the server is.
        long position = SelfPlayer.presentedPosition(piano, id, be.clientPosition());
        return new View(current, position, be.settings().transpose(), Kind.SONG);
    }

    /** The song with this id, asking the cache (which may start a download) at most every {@value #LOOKUP_NANOS} ns. */
    private @Nullable Song lookup(String id) {
        long now = System.nanoTime();
        if (!id.equals(songId)) {
            songId = id;
            song = null;
            lookedUpAt = now - LOOKUP_NANOS;
        }
        if (song == null && now - lookedUpAt >= LOOKUP_NANOS) {
            lookedUpAt = now;
            song = ClientSongs.get(id);
        }
        return song;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (getWidth() <= 0 || getHeight() <= 0 || "off".equals(ClientPrefs.get().sheetView)) {
            shownPosition = -1;
            return;
        }
        View view = view();
        if (view == null) {
            // Nothing to show: the piano behind stays clear, unless a song is on its way.
            shownPosition = -1;
            if (loading) {
                g.fill(getX(), getY(), getRight(), getBottom(), HINT_BACKGROUND);
                hint(g, Component.translatable("music.screen.sheet.loading"));
            }
            return;
        }
        shownPosition = view.kind() == Kind.FREE_PLAY ? -1 : view.position();
        int scale = guiScale();
        boolean notation = "notation".equals(ClientPrefs.get().sheetView) && view.kind() != Kind.FREE_PLAY;
        if (notation && getWidth() * scale >= 64 && getHeight() * scale >= 48 && view.song().noteCount() > 0) drawNotation(g, view, scale);
        else drawRoll(g, view, scale);
    }

    private void hint(GuiGraphics g, Component text) {
        Font font = Minecraft.getInstance().font;
        String line = StatusLine.fit(font, text.getString(), getWidth() - 8);
        g.drawCenteredString(font, line, getX() + getWidth() / 2, getY() + (getHeight() - font.lineHeight) / 2 + 1, HINT);
    }

    // ------------------------------------------------------------------ roll

    private void drawRoll(GuiGraphics g, View view, int scale) {
        int x0 = getX() * scale, y0 = getY() * scale, w = getWidth() * scale, h = getHeight() * scale, bottom = y0 + h;
        KeyboardLayout keys = new KeyboardLayout(x0, y0, w, h);
        double rate = h / (double) ROLL_SPAN_MS;
        boolean free = view.kind() == Kind.FREE_PLAY;
        float shown = free ? PlayedNotes.INSTANCE.guidesShown() : 1f;
        g.enableScissor(getX(), getY(), getRight(), getBottom());
        g.pose().pushMatrix();
        g.pose().scale(1f / scale, 1f / scale);
        // Free playing rises over the piano's keys and the player's hands with no band to darken
        // them: the notes have edges of their own, and the guides fade in and out with them.
        if (!free) g.fill(x0, y0, x0 + w, bottom, ROLL_BACKGROUND);
        for (int key = 0; key < KeyboardLayout.KEYS; key++) {
            if (KeyboardLayout.isC(key)) g.fill(keys.left(key), y0, keys.left(key) + Math.max(1, scale / 2), bottom, faded(ROLL_GRID, shown));
        }
        Song song = view.song();
        long position = view.position();
        if (view.kind() != Kind.SONG) drawPlayed(g, keys, bottom, rate, scale);
        else {
            for (long bar : bars(song)) {
                if (bar < position || bar > position + ROLL_SPAN_MS) continue;
                int y = (int) Math.round(bottom - (bar - position) * rate);
                g.fill(x0, y, x0 + w, y + Math.max(1, scale / 2), ROLL_BAR);
            }
            long from = Math.max(0, position - longest(song));
            for (int i = song.firstNoteAtOrAfter(from); i < song.noteCount() && song.noteStart(i) <= position + ROLL_SPAN_MS; i++) {
                if (song.noteEnd(i) < position) continue;
                int key = song.noteKey(i) + view.transpose();
                if (key < 0 || key >= KeyboardLayout.KEYS) continue;
                double top = bottom - (song.noteEnd(i) - position) * rate, low = bottom - (song.noteStart(i) - position) * rate;
                note(g, keys, key, top, low, song.noteHand(i), song.noteStart(i) <= position, y0, bottom, scale);
            }
        }
        g.fill(x0, bottom - scale, x0 + w, bottom, faded(NOW_LINE, shown));
        g.pose().popMatrix();
        g.disableScissor();
    }

    /** {@code colour} with its alpha multiplied by {@code shown}. */
    private static int faded(int colour, float shown) {
        if (shown >= 1f) return colour;
        int alpha = Math.round((colour >>> 24) * Math.max(0f, shown));
        return alpha << 24 | colour & 0xFFFFFF;
    }

    /** What is being played rises away from the keys: its newest moment is at the bottom edge. */
    private static void drawPlayed(GuiGraphics g, KeyboardLayout keys, int bottom, double rate, int scale) {
        PlayedNotes played = PlayedNotes.INSTANCE;
        long now = played.now();
        for (int i = played.size() - 1; i >= 0; i--) {
            long end = played.end(i) < 0 ? now : played.end(i);
            if (now - end > ROLL_SPAN_MS) continue;
            double low = bottom - (now - end) * rate, top = bottom - (now - played.start(i)) * rate;
            int key = played.key(i);
            note(g, keys, key, top, low, key < 39 ? Song.HAND_LEFT : Song.HAND_RIGHT, played.end(i) < 0, keys.y(), bottom, scale);
        }
    }

    private static void note(GuiGraphics g, KeyboardLayout keys, int key, double top, double low, int hand, boolean sounding,
                             int clipTop, int clipBottom, int scale) {
        int y0 = (int) Math.max(clipTop, Math.round(top));
        int y1 = (int) Math.min(clipBottom, Math.round(low));
        if (y1 - y0 < 1) y1 = y0 + 1;
        if (y0 >= clipBottom || y1 <= clipTop) return;
        int inset = KeyboardLayout.isBlack(key) ? 0 : Math.max(1, scale / 2);
        int x0 = keys.left(key) + inset, x1 = keys.right(key) - inset - (KeyboardLayout.isBlack(key) ? 0 : scale);
        if (x1 <= x0) x1 = x0 + 1;
        int colour = hand == Song.HAND_LEFT ? LEFT : hand == Song.HAND_RIGHT ? RIGHT : EITHER;
        if (sounding) colour = brighten(colour);
        g.fill(x0, y0, x1, y1, NOTE_EDGE);
        int edge = Math.max(1, scale / 2);
        if (x1 - x0 > 2 * edge && y1 - y0 > 2 * edge) g.fill(x0 + edge, y0 + edge, x1 - edge, y1 - edge, colour);
    }

    private static int brighten(int colour) {
        int r = Math.min(255, ((colour >> 16) & 0xFF) + 60), gr = Math.min(255, ((colour >> 8) & 0xFF) + 60), b = Math.min(255, (colour & 0xFF) + 60);
        return 0xFF000000 | r << 16 | gr << 8 | b;
    }

    private long[] bars(Song s) {
        if (s != barsOf) {
            barsOf = s;
            barLines = s.barLines();
        }
        return barLines;
    }

    /** The longest note of a song, so the roll can find notes that started before the window but still sound. */
    private long longest(Song s) {
        if (s != longestOf) {
            longestOf = s;
            long max = 0;
            for (int i = 0; i < s.noteCount(); i++) max = Math.max(max, s.noteLength(i));
            longest = max;
        }
        return longest;
    }

    // ------------------------------------------------------------------ notation

    private void drawNotation(GuiGraphics g, View view, int scale) {
        int w = getWidth() * scale, h = getHeight() * scale;
        DynamicTexture tex = ensureTexture(w, h);
        SheetPainter p = painter;
        Song s = view.song();
        long position = view.position();
        if (s != pagedSong) {
            pagedSong = s;
            pages = DeskSheet.pages(s);
            paintedPage = -1;
        }
        int page = DeskSheet.pageAt(pages, position);
        long now = System.nanoTime();
        if (page != paintedPage || position != paintedPosition && now - paintedAt >= REPAINT_NANOS) {
            p.paint(s, SheetMode.NOTATION, pages[page], pages[page + 1], position, SheetPainter.HAND_COLOURS | SheetPainter.DIM_PLAYED);
            p.copyTo(tex.getPixels());
            tex.upload();
            paintedPage = page;
            paintedPosition = position;
            paintedAt = now;
        }
        g.blit(TEXTURE, getX(), getY(), getRight(), getBottom(), 0f, 1f, 0f, 1f);
        float x = position < pages[page + 1] ? p.xAt(position) : w;
        int px = getX() * scale + Math.round(x);
        g.pose().pushMatrix();
        g.pose().scale(1f / scale, 1f / scale);
        g.fill(px - Math.max(1, scale / 2), getY() * scale, px + Math.max(1, scale / 2), getBottom() * scale, SheetPainter.PLAYHEAD);
        g.pose().popMatrix();
    }

    private DynamicTexture ensureTexture(int w, int h) {
        if (texture != null && painter != null && textureWidth == w && textureHeight == h) return texture;
        release();
        DynamicTexture created = new DynamicTexture(() -> "Music piano screen sheet", w, h, false);
        Minecraft.getInstance().getTextureManager().register(TEXTURE, created);
        texture = created;
        painter = new SheetPainter(w, h);
        textureWidth = w;
        textureHeight = h;
        paintedPage = -1;
        return created;
    }

    /** Gives the page texture back; it is made again when next needed. */
    void release() {
        if (texture != null) Minecraft.getInstance().getTextureManager().release(TEXTURE);
        texture = null;
        painter = null;
        paintedPage = -1;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, getMessage());
    }
}
