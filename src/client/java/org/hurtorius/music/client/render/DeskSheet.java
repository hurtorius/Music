package org.hurtorius.music.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import org.hurtorius.music.client.play.SelfPlayer;
import org.hurtorius.music.client.song.ClientSongs;
import org.hurtorius.music.piano.GrandPianoBlockEntity;
import org.hurtorius.music.piano.PianoLayout;
import org.hurtorius.music.piano.Playback;
import org.hurtorius.music.piano.PianoSettings;
import org.hurtorius.music.piano.SheetMode;
import org.hurtorius.music.registry.MusicContent;
import org.hurtorius.music.song.Song;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * The digital sheet on one piano's music desk.
 *
 * <p>While the piano plays a song by itself (or holds one paused part way), the desk is up, the lid
 * is open, the sheet setting is not off and the viewer is close enough to read it, the song is shown
 * a page at a time on a sheet of paper resting on the desk: whole bars per page, turning when the
 * playback reaches the next page. The sheet follows the song as this client sounds it
 * ({@link SelfPlayer#presentedPosition}), a little behind the server, so the playhead, the faded
 * notes and the page turns match what is heard and the keys that move. The page is painted by a
 * {@link SheetPainter} into a mipmapped {@link SheetTexture} when it would look different (a note on
 * it ends, so it fades, or in the roll also starts), at most ten times a second and, page turns
 * apart, by one sheet per frame across all pianos so several sheets never repaint in the same
 * frame; the playhead is drawn as its own thin quad every frame so it moves smoothly. Like a map in
 * an item frame it is drawn unshaded and lit as a display, so it reads the same by day and night;
 * under a shader pack it takes the piano's own light instead, since packs make full light glow and
 * the glow washes the notes out.</p>
 *
 * <p>Textures come from a small pool of slots and go back to it as soon as the sheet has not been
 * shown for a few seconds or the piano unloads. Render thread only.</p>
 */
public final class DeskSheet implements SubmitNodeCollector.CustomGeometryRenderer {
    private static final Logger LOG = LoggerFactory.getLogger("Music");
    /** Painted area of the texture. Below it: 8 rows of paper (so smaller mip levels never mix the
     * playhead into the page), then 8 rows of solid playhead colour. */
    public static final int WIDTH = 512, HEIGHT = 188;
    private static final int GUARD_ROWS = 8, TEXTURE_HEIGHT = HEIGHT + 2 * GUARD_ROWS;
    public static final int MAX_SHEETS = 16;
    private static final long REPAINT_NANOS = 100_000_000L;
    /** Repaints of different sheets (page turns apart) are at least this far apart, about one frame. */
    private static final long REPAINT_SPACING_NANOS = 4_000_000L;
    /** Notes that started this long before a page are not looked at for its next change. */
    static final long LOOKBACK_MS = 60_000;
    /** While a song is still downloading, the song cache is asked at most this often. */
    private static final long LOOKUP_NANOS = 250_000_000L;
    private static final long IDLE_RELEASE_NANOS = 5_000_000_000L;
    /**
     * Farthest the sheet is shown from, in blocks: 24 for a life-size desk, further for a larger one,
     * which looks as large from that much further away.
     */
    public static final double SHOW_DISTANCE = 24 * PianoLayout.SCALE;
    /** A page holds whole bars: at most this long and with at most this many chord onsets, but always one bar. */
    public static final long PAGE_MAX_MS = 6_000;
    public static final int PAGE_MAX_ONSETS = 28;
    private static final int CHORD_MS = 25;

    // Placement on the music desk, in the desk group's own model units. The desk frame's front face
    // (the kit's music_desk_* elements) leans back: z = FRONT_Z + (y - FRONT_Y) * SLOPE. The paper
    // rests on the bottom rail, spans the frame and rises above it without reaching the open lid.
    public static final float FRONT_Y = 0.06237f, FRONT_Z = -0.29344f, SLOPE = 0.65492f / 3.08117f;
    public static final float LEFT = 5.25f, RIGHT = -5.25f, BOTTOM = 0.32f, TOP = 4.05f;
    public static final float LIFT = 0.06f, PLAYHEAD_LIFT = 0.075f, PLAYHEAD_HALF_WIDTH = 0.045f;
    /** Unit normal of the desk face, toward the performer. */
    private static final float NORMAL_Y = SLOPE / (float) Math.sqrt(1 + SLOPE * SLOPE), NORMAL_Z = -1 / (float) Math.sqrt(1 + SLOPE * SLOPE);

    private static final boolean[] SLOTS = new boolean[MAX_SHEETS];
    private static long lastRepaint;
    private static boolean warnedFull;

    private int slot = -1;
    private @Nullable Identifier id;
    private @Nullable SheetTexture texture;
    private @Nullable RenderType renderType;
    private @Nullable SheetPainter painter;

    private @Nullable String songId;
    private @Nullable Song song;
    private long lookedUpAt;
    private long[] pages = new long[0];
    private int page = -1;
    private SheetMode paintedMode = SheetMode.OFF;
    private long paintedPosition = -1, paintedAt, nextChange;
    private long shownPosition = -1;
    private long lastShown;
    private float playheadU;
    private int light = LightTexture.FULL_BRIGHT;
    private Matrix4f desk = new Matrix4f();

    private final Matrix4f position = new Matrix4f();
    private final Vector3f corner = new Vector3f();

    DeskSheet() {
    }

    /**
     * Decides whether the sheet shows this frame and brings its texture up to date.
     *
     * @param desk       the music desk's model transform this frame
     * @param distance   from the camera to the piano, in blocks
     * @param pianoLight the packed light the piano is drawn with
     * @return true when {@link #render} should be submitted this frame
     */
    boolean update(GrandPianoBlockEntity piano, float lidClosed, float deskFolded, Matrix4f desk, double distance, int pianoLight, long now) {
        this.desk = desk;
        this.light = ShaderPacks.mayBeInUse() ? pianoLight : LightTexture.FULL_BRIGHT;
        PianoSettings settings = piano.settings();
        if (settings.sheet() == SheetMode.OFF || !settings.deskUp() || !settings.lidOpen() || lidClosed > 0 || deskFolded > 0 || distance > SHOW_DISTANCE) {
            return false;
        }
        Playback playback = piano.playback();
        String current = playback.currentSong();
        if (current == null || !playback.playing() && playback.positionMs() <= 0) return false;
        if (!current.equals(songId)) {
            songId = current;
            song = null;
            lookedUpAt = now - LOOKUP_NANOS;
        }
        if (song == null) {
            if (now - lookedUpAt < LOOKUP_NANOS) return false;
            lookedUpAt = now;
            song = ClientSongs.get(current);
            if (song == null) return false;
            pages = pages(song);
            page = -1;
        }
        if (!ensureTexture()) return false;
        long position = SelfPlayer.presentedPosition(piano.getBlockPos(), current, piano.clientPosition());
        int at = pageAt(pages, position);
        boolean turned = at != page || settings.sheet() != paintedMode;
        boolean changed = position >= nextChange || position < paintedPosition;
        if (turned || changed && now - paintedAt >= REPAINT_NANOS && now - lastRepaint >= REPAINT_SPACING_NANOS) {
            painter.paint(song, settings.sheet(), pages[at], pages[at + 1], position, SheetPainter.HAND_COLOURS | SheetPainter.DIM_PLAYED);
            painter.copyTo(texture.image());
            texture.upload();
            page = at;
            paintedMode = settings.sheet();
            paintedPosition = position;
            paintedAt = lastRepaint = now;
            nextChange = nextChange(song, paintedMode, pages[at], pages[at + 1], position);
        }
        float x = position < pages[at + 1] ? painter.xAt(position) : WIDTH;
        playheadU = Math.max(0, Math.min(1, x / WIDTH));
        lastShown = now;
        shownPosition = position;
        return true;
    }

    /** The song position shown by the last {@link #update} that showed the sheet; -1 before that. */
    long shownPosition() {
        return shownPosition;
    }

    /** Render type that draws this sheet; valid after {@link #update} returned true. */
    RenderType renderType() {
        return renderType;
    }

    @Override
    public void render(PoseStack.Pose pose, VertexConsumer out) {
        position.set(pose.pose()).mul(desk);
        float paperV = HEIGHT / (float) TEXTURE_HEIGHT;
        vertex(out, LEFT, TOP, LIFT, 0, 0);
        vertex(out, LEFT, BOTTOM, LIFT, 0, paperV);
        vertex(out, RIGHT, BOTTOM, LIFT, 1, paperV);
        vertex(out, RIGHT, TOP, LIFT, 1, 0);
        // The playhead samples the middle of the solid block under the page; its texture
        // coordinates do not change across the quad, so it always reads the full-size level.
        float x = LEFT + (RIGHT - LEFT) * playheadU;
        float u = 0.5f, v = (HEIGHT + GUARD_ROWS * 1.5f) / TEXTURE_HEIGHT;
        vertex(out, x + PLAYHEAD_HALF_WIDTH, TOP, PLAYHEAD_LIFT, u, v);
        vertex(out, x + PLAYHEAD_HALF_WIDTH, BOTTOM, PLAYHEAD_LIFT, u, v);
        vertex(out, x - PLAYHEAD_HALF_WIDTH, BOTTOM, PLAYHEAD_LIFT, u, v);
        vertex(out, x - PLAYHEAD_HALF_WIDTH, TOP, PLAYHEAD_LIFT, u, v);
    }

    private void vertex(VertexConsumer out, float x, float y, float lift, float u, float v) {
        float z = FRONT_Z + (y - FRONT_Y) * SLOPE;
        position.transformPosition(corner.set(x, y + NORMAL_Y * lift, z + NORMAL_Z * lift));
        out.addVertex(corner.x, corner.y, corner.z).setColor(0xFFFFFFFF).setUv(u, v).setLight(light);
    }

    /** Frees the texture if the sheet has not been shown for a while. */
    void releaseIfIdle(long now) {
        if (texture != null && now - lastShown > IDLE_RELEASE_NANOS) release();
    }

    /** Gives the texture back to the pool. */
    void release() {
        if (texture != null) {
            Minecraft.getInstance().getTextureManager().release(id);
            texture = null;
            renderType = null;
            SLOTS[slot] = false;
            slot = -1;
        }
        painter = null;
        songId = null;
        song = null;
        page = -1;
        shownPosition = -1;
    }

    private boolean ensureTexture() {
        if (texture != null) return true;
        int free = -1;
        for (int i = 0; i < MAX_SHEETS && free < 0; i++) if (!SLOTS[i]) free = i;
        if (free < 0) {
            if (!warnedFull) LOG.warn("More than {} pianos are showing sheet music nearby; the rest show none", MAX_SHEETS);
            warnedFull = true;
            return false;
        }
        int number = free;
        SheetTexture created = new SheetTexture("Music desk sheet " + number, WIDTH, TEXTURE_HEIGHT);
        for (int y = HEIGHT; y < TEXTURE_HEIGHT; y++) {
            int colour = y < HEIGHT + GUARD_ROWS ? SheetPainter.PAPER : SheetPainter.PLAYHEAD;
            for (int x = 0; x < WIDTH; x++) created.image().setPixel(x, y, colour);
        }
        Identifier textureId = MusicContent.id("dynamic/desk_sheet_" + number);
        Minecraft.getInstance().getTextureManager().register(textureId, created);
        SLOTS[number] = true;
        slot = number;
        id = textureId;
        texture = created;
        renderType = RenderTypes.text(textureId);
        if (painter == null) painter = new SheetPainter(WIDTH, HEIGHT);
        page = -1;
        return true;
    }

    // ------------------------------------------------------------------ pages

    /**
     * Page boundaries of a song: {@code pages[i]..pages[i + 1]} is page i. Pages start on bar lines
     * and hold whole bars; the last boundary is the end of the last bar.
     */
    public static long[] pages(Song song) {
        long[] bars = song.barLines();
        long lastBarLength = bars.length > 1 ? bars[bars.length - 1] - bars[bars.length - 2] : Math.max(1, song.durationMs());
        long end = Math.max(song.durationMs(), bars[bars.length - 1] + lastBarLength);
        List<Long> starts = new ArrayList<>();
        starts.add(bars[0]);
        long pageStart = bars[0];
        int pageOnsets = 0, pageBars = 0;
        int note = 0, n = song.noteCount();
        for (int b = 0; b < bars.length; b++) {
            long barEnd = b + 1 < bars.length ? bars[b + 1] : end;
            int onsets = 0;
            long lastOnset = -CHORD_MS - 1L;
            while (note < n && song.noteStart(note) < barEnd) {
                if (song.noteStart(note) - lastOnset > CHORD_MS) {
                    onsets++;
                    lastOnset = song.noteStart(note);
                }
                note++;
            }
            if (pageBars > 0 && (barEnd - pageStart > PAGE_MAX_MS || pageOnsets + onsets > PAGE_MAX_ONSETS)) {
                starts.add(bars[b]);
                pageStart = bars[b];
                pageOnsets = 0;
                pageBars = 0;
            }
            pageOnsets += onsets;
            pageBars++;
        }
        long[] out = new long[starts.size() + 1];
        for (int i = 0; i < starts.size(); i++) out[i] = starts.get(i);
        out[starts.size()] = end;
        return out;
    }

    /**
     * The first song time after {@code position} at which the page {@code from..to} looks different:
     * a note on it ends (played notes fade), or in the roll also one starts (sounding notes light up).
     * {@link Long#MAX_VALUE} when nothing on it changes any more.
     */
    public static long nextChange(Song song, SheetMode mode, long from, long to, long position) {
        long next = Long.MAX_VALUE;
        for (int i = song.firstNoteAtOrAfter(from - LOOKBACK_MS), n = song.noteCount(); i < n && song.noteStart(i) < to; i++) {
            long start = song.noteStart(i), end = song.noteEnd(i);
            if (end <= from) continue;
            if (end > position) next = Math.min(next, end);
            if (mode == SheetMode.ROLL && start > position) next = Math.min(next, start);
        }
        return next;
    }

    /** The page showing {@code position}: the last one starting at or before it. */
    public static int pageAt(long[] pages, long position) {
        int lo = 0, hi = pages.length - 2;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (pages[mid] <= position) lo = mid; else hi = mid - 1;
        }
        return Math.max(0, lo);
    }
}
