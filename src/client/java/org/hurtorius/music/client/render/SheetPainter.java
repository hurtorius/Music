package org.hurtorius.music.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import org.hurtorius.music.piano.SheetMode;
import org.hurtorius.music.song.Song;
import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;

/**
 * Paints a stretch of a {@link Song} as sheet music or as a piano roll into an ARGB pixel buffer.
 *
 * <p>{@link SheetMode#NOTATION} draws a grand staff in proportional notation: treble and bass staves
 * with clefs, noteheads at their staff positions (black keys spelled as sharps), ledger lines,
 * sharp and natural signs that follow the bar-by-bar rule of written music, ottava marks for notes
 * beyond the drawn range, a thin line showing how long each note sounds, bar lines and bar numbers.
 * Every bar gets a little room at its start and end so accidentals and noteheads never sit on a bar
 * line. {@link SheetMode#ROLL} draws a piano roll with a keyboard strip on the left.</p>
 *
 * <p>The painter remembers the layout of the last {@link #paint} so callers can place things (a
 * playhead, a cursor) with {@link #xAt(long)}, {@link #stepY(int)} and {@link #keyY(int)}. Not
 * thread safe; keep one per view. Pure Java apart from {@link #copyTo(NativeImage)}.</p>
 */
public final class SheetPainter {
    /** Colour notes by hand (right blue, left red). */
    public static final int HAND_COLOURS = 1;
    /** Draw the playhead line at the given position. */
    public static final int PLAYHEAD_LINE = 2;
    /** Fade notes that have finished before the playhead. */
    public static final int DIM_PLAYED = 4;

    public static final int PAPER = 0xFFF5F1E6;
    public static final int INK = 0xFF17171B;
    public static final int STAFF_INK = 0xFF4A4A52;
    public static final int RIGHT_HAND = 0xFF1C4E9E;
    public static final int LEFT_HAND = 0xFFA4381C;
    public static final int PLAYHEAD = 0xFFD23A2E;
    private static final int ROLL_BLACK_ROW = 0xFFE7E1D2;
    private static final int ROLL_GRID = 0xFFC9C1AE;
    private static final int ROLL_NOTE = 0xFF3A3A40;

    /** Diatonic steps (C0 = 0) of the staff lines: bass G2..A3 and treble E4..F5. */
    public static final int BASS_BOTTOM = 18, BASS_TOP = 26, MIDDLE_C = 28, TREBLE_BOTTOM = 30, TREBLE_TOP = 38;
    /** Notes are written an octave (or two) higher or lower to stay within these steps. */
    public static final int LOWEST_DRAWN_STEP = 11, HIGHEST_DRAWN_STEP = 47;
    /** Notes starting this close together form one chord. */
    private static final int CHORD_MS = 25;
    private static final int[] LETTER_OF_PITCH_CLASS = {0, 0, 1, 1, 2, 3, 3, 4, 4, 5, 5, 6};
    private static final boolean[] SHARP_PITCH_CLASS = {false, true, false, true, false, false, true, false, true, false, true, false};

    private final int width, height;
    private final int[] pixels;
    private final float[] mask;
    private int maskX0, maskY0, maskX1, maskY1;

    // Layout of the last paint.
    private SheetMode mode = SheetMode.NOTATION;
    private long fromMs, toMs = 1;
    private float x0, x1, k, padStart, padEnd;
    private long[] windowBars = new long[0];
    private float top, halfSpace = 4;
    private int highStep = HIGHEST_DRAWN_STEP, lowStep = LOWEST_DRAWN_STEP;
    private int lowKey, highKey = 87;
    private float rowHeight = 1;

    // Paper, staves and clefs of the last notation layout; they only change with the step range.
    private int[] staves;
    private int stavesLow = -1, stavesHigh = -1;

    // Facts about the last song, computed once per song.
    private Song cachedSong;
    private long[] bars = new long[0];
    private int songLowStep, songHighStep, songLowKey, songHighKey;

    public SheetPainter(int width, int height) {
        if (width < 64 || height < 48) throw new IllegalArgumentException("Sheet too small: " + width + "x" + height);
        this.width = width;
        this.height = height;
        this.pixels = new int[width * height];
        this.mask = new float[width * height];
        Arrays.fill(pixels, PAPER);
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** The painted image, ARGB, row by row. Live: changes on the next paint. */
    public int[] pixels() {
        return pixels;
    }

    public int pixel(int x, int y) {
        return pixels[y * width + x];
    }

    // ------------------------------------------------------------------ pitch spelling

    /** Diatonic staff step of a MIDI note, spelled with sharps: C0 = 0, C4 = 28, A4 = 33, F#5 = 38. */
    public static int staffStep(int midi) {
        return (Math.floorDiv(midi, 12) - 1) * 7 + LETTER_OF_PITCH_CLASS[Math.floorMod(midi, 12)];
    }

    /** True for the black keys, which this painter spells as sharps. */
    public static boolean isSharp(int midi) {
        return SHARP_PITCH_CLASS[Math.floorMod(midi, 12)];
    }

    // ------------------------------------------------------------------ layout queries

    /** Pixel column, in the last painted window, of a notehead's centre starting at {@code ms} (notation) or of {@code ms} (roll). */
    public float xAt(long ms) {
        if (mode == SheetMode.ROLL) return x0 + (ms - fromMs) * k;
        return timeX(ms) + headRx();
    }

    /** Pixel row of a staff step's centre in the last painted notation. */
    public float stepY(int step) {
        return top + (highStep - step) * halfSpace;
    }

    /** Distance between two staff lines in the last painted notation, in pixels. */
    public float staffSpace() {
        return halfSpace * 2;
    }

    /** Pixel row of a key's centre (0..87) in the last painted piano roll. */
    public float keyY(int key) {
        return 1 + (highKey - key + 0.5f) * rowHeight;
    }

    // ------------------------------------------------------------------ painting

    /**
     * Paints {@code [fromMs, toMs)} of {@code song}.
     *
     * @param playheadMs song position used by {@link #PLAYHEAD_LINE} and {@link #DIM_PLAYED}
     * @param flags      any of {@link #HAND_COLOURS}, {@link #PLAYHEAD_LINE}, {@link #DIM_PLAYED}
     */
    public void paint(Song song, SheetMode mode, long fromMs, long toMs, long playheadMs, int flags) {
        this.mode = mode == SheetMode.ROLL ? SheetMode.ROLL : SheetMode.NOTATION;
        this.fromMs = Math.max(0, fromMs);
        this.toMs = Math.max(this.fromMs + 1, toMs);
        learn(song);
        if (this.mode == SheetMode.ROLL) paintRoll(song, playheadMs, flags);
        else paintNotation(song, playheadMs, flags);
    }

    private void learn(Song song) {
        if (song == cachedSong) return;
        cachedSong = song;
        bars = song.barLines();
        songLowStep = Integer.MAX_VALUE;
        songHighStep = Integer.MIN_VALUE;
        songLowKey = 87;
        songHighKey = 0;
        for (int i = 0; i < song.noteCount(); i++) {
            int step = staffStep(song.noteMidi(i));
            songLowStep = Math.min(songLowStep, step);
            songHighStep = Math.max(songHighStep, step);
            songLowKey = Math.min(songLowKey, song.noteKey(i));
            songHighKey = Math.max(songHighKey, song.noteKey(i));
        }
        if (song.noteCount() == 0) {
            songLowStep = BASS_BOTTOM;
            songHighStep = TREBLE_TOP;
            songLowKey = 27;
            songHighKey = 51;
        }
    }

    // ------------------------------------------------------------------ notation

    private float headRx() {
        return halfSpace * 1.18f;
    }

    private float headRy() {
        return halfSpace * 0.86f;
    }

    /** Left edge of a notehead starting at {@code ms}: bars in the window have room at both ends. */
    private float timeX(long ms) {
        int segment = 0;
        while (segment < windowBars.length && windowBars[segment] <= ms) segment++;
        return x0 + padStart + (ms - fromMs) * k + segment * (padStart + padEnd);
    }

    /** Where something ending at {@code ms} stops: a note ending on a bar line stops at that line. */
    private float timeXEnd(long ms) {
        int segment = 0;
        while (segment < windowBars.length && windowBars[segment] < ms) segment++;
        return x0 + padStart + (ms - fromMs) * k + segment * (padStart + padEnd);
    }

    /** Where the bar line at {@code bar} (inside the window) is drawn. */
    private float barX(long bar) {
        return timeXEnd(bar) + padEnd;
    }

    private void paintNotation(Song song, long playheadMs, int flags) {
        lowStep = Math.max(LOWEST_DRAWN_STEP, Math.min(BASS_BOTTOM - 2, songLowStep));
        highStep = Math.min(HIGHEST_DRAWN_STEP, Math.max(TREBLE_TOP + 2, songHighStep));
        int scale = digitScale();
        float numberRoom = scale * 5 + 3;
        // The outermost heads stick out half a step and ottava marks need a little more.
        halfSpace = (height - numberRoom - 3) / (highStep - lowStep + 3f);
        top = numberRoom + 1.5f * halfSpace;
        float space = halfSpace * 2;

        int first = firstBarAfter(fromMs);
        int last = first;
        while (last < bars.length && bars[last] < toMs) last++;
        windowBars = Arrays.copyOfRange(bars, first, last);
        x0 = Math.round(3 + space * 3.3f);
        x1 = width - 3;
        padStart = space * 1.35f;
        padEnd = headRx() * 2 + space * 0.35f;
        float pads = (windowBars.length + 1) * (padStart + padEnd);
        if (pads > (x1 - x0) * 0.6f) {
            float shrink = (x1 - x0) * 0.6f / pads;
            padStart *= shrink;
            padEnd *= shrink;
            pads *= shrink;
        }
        k = (x1 - x0 - pads) / (float) (toMs - fromMs);

        int systemTop = Math.round(stepY(TREBLE_TOP)), systemBottom = Math.round(stepY(BASS_BOTTOM)) + 1;
        if (staves != null && stavesLow == lowStep && stavesHigh == highStep) {
            System.arraycopy(staves, 0, pixels, 0, pixels.length);
        } else {
            Arrays.fill(pixels, PAPER);
            int staffLeft = 3;
            for (int step = TREBLE_BOTTOM; step <= TREBLE_TOP; step += 2) hLine(staffLeft, width - 3, stepY(step), STAFF_INK);
            for (int step = BASS_BOTTOM; step <= BASS_TOP; step += 2) hLine(staffLeft, width - 3, stepY(step), STAFF_INK);
            fillRect(staffLeft, systemTop, staffLeft + Math.max(1, Math.round(space * 0.12f)), systemBottom, INK);
            trebleClef(staffLeft + space * 0.45f, stepY(32), space);
            bassClef(staffLeft + space * 0.35f, stepY(24), space);
            if (staves == null) staves = new int[pixels.length];
            System.arraycopy(pixels, 0, staves, 0, pixels.length);
            stavesLow = lowStep;
            stavesHigh = highStep;
        }

        if (first > 0 && bars[first - 1] == fromMs) number(Math.round(x0) + 2, 1, first, scale, STAFF_INK);
        for (int i = 0; i < windowBars.length; i++) {
            int x = Math.round(barX(windowBars[i]));
            fillRect(x, systemTop, x + 1, systemBottom, STAFF_INK);
            number(x + 2, 1, first + i + 1, scale, STAFF_INK);
        }
        if (last < bars.length && bars[last] == toMs) fillRect(Math.round(x1) - 1, systemTop, Math.round(x1), systemBottom, STAFF_INK);

        paintNotes(song, playheadMs, flags);

        if ((flags & PLAYHEAD_LINE) != 0 && playheadMs >= fromMs && playheadMs < toMs) {
            int x = Math.round(xAt(playheadMs));
            fillRect(x - 1, Math.round(stepY(highStep) - halfSpace), x + 1, Math.round(stepY(lowStep) + halfSpace) + 1, PLAYHEAD);
        }
    }

    /** Index of the first bar line strictly after {@code ms}. */
    private int firstBarAfter(long ms) {
        int lo = 0, hi = bars.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (bars[mid] <= ms) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    private void paintNotes(Song song, long playheadMs, int flags) {
        int n = song.noteCount();
        // Notes still sounding from before the window: only their duration line.
        for (int j = song.firstNoteAtOrAfter(Math.max(0, fromMs - 60_000)); j < n && song.noteStart(j) < fromMs; j++) {
            if (song.noteEnd(j) > fromMs) {
                Placement p = place(song.noteMidi(j));
                durationLine(x0, xEnd(song.noteEnd(j)), stepY(p.step), colour(song, j, playheadMs, flags));
            }
        }
        // A sharp or natural lasts until the next bar line, so the state is rebuilt from the start
        // of the bar holding the window's start; notes before the window only update it.
        int barIndex = firstBarAfter(fromMs) - 1;
        long barStart = barIndex >= 0 ? bars[barIndex] : 0;
        int nextBar = barIndex + 1;
        boolean[] sharpened = new boolean[64];
        int[] chord = new int[96];
        int i = song.firstNoteAtOrAfter(barStart);
        while (i < n && song.noteStart(i) < toMs) {
            int start = song.noteStart(i);
            while (nextBar < bars.length && bars[nextBar] <= start) {
                Arrays.fill(sharpened, false);
                nextBar++;
            }
            int count = 0;
            int j = i;
            while (j < n && song.noteStart(j) - start <= CHORD_MS && count < chord.length) chord[count++] = j++;
            drawChord(song, chord, count, start >= fromMs, sharpened, playheadMs, flags);
            i = j;
        }
    }

    private record Placement(int step, int ottava) {}

    private Placement place(int midi) {
        int step = staffStep(midi);
        int ottava = 0;
        while (step > highStep) {
            step -= 7;
            ottava--;
        }
        while (step < lowStep) {
            step += 7;
            ottava++;
        }
        return new Placement(step, ottava);
    }

    private float xEnd(long endMs) {
        if (endMs >= toMs) return x1;
        return Math.min(x1, timeXEnd(endMs) + headRx());
    }

    /** One chord: accidentals, ledger lines, duration lines, heads (seconds side by side). */
    private void drawChord(Song song, int[] chord, int count, boolean visible, boolean[] sharpened, long playheadMs, int flags) {
        int[] steps = new int[count];
        for (int a = 0; a < count; a++) steps[a] = staffStep(song.noteMidi(chord[a]));
        // Low to high by written step; chords are small.
        for (int a = 1; a < count; a++) {
            int note = chord[a], step = steps[a], b = a - 1;
            while (b >= 0 && steps[b] > step) {
                chord[b + 1] = chord[b];
                steps[b + 1] = steps[b];
                b--;
            }
            chord[b + 1] = note;
            steps[b + 1] = step;
        }
        Placement[] placed = new Placement[count];
        int[] accidental = new int[count]; // 0 none, 1 sharp, 2 natural
        for (int a = 0; a < count; a++) {
            int midi = song.noteMidi(chord[a]);
            placed[a] = place(midi);
            boolean sharp = isSharp(midi);
            int step = steps[a];
            if (step >= 0 && step < sharpened.length && sharpened[step] != sharp) {
                accidental[a] = sharp ? 1 : 2;
                sharpened[step] = sharp;
            }
        }
        if (!visible) return;

        // Accidentals stack in columns, top note nearest the heads, so neighbours within a sixth never overlap.
        int[] column = new int[count];
        for (int a = count - 1; a >= 0; a--) {
            if (accidental[a] == 0) continue;
            int c = 0;
            for (boolean clash = true; clash; ) {
                clash = false;
                for (int b = a + 1; b < count; b++) {
                    if (accidental[b] != 0 && column[b] == c && Math.abs(placed[b].step - placed[a].step) < 6) {
                        clash = true;
                        c++;
                        break;
                    }
                }
            }
            column[a] = c;
        }

        float space = halfSpace * 2;
        float rx = headRx(), ry = headRy();
        float cx = timeX(song.noteStart(chord[0])) + rx;
        int previousStep = Integer.MIN_VALUE;
        boolean previousShifted = false;
        for (int a = 0; a < count; a++) {
            int note = chord[a];
            Placement p = placed[a];
            boolean shifted = p.step == previousStep + 1 && !previousShifted;
            float hx = shifted ? cx + rx * 1.9f : cx;
            float hy = stepY(p.step);
            int colour = colour(song, note, playheadMs, flags);

            durationLine(hx + rx * 0.9f, xEnd(song.noteEnd(note)), hy, colour);
            ledgers(hx, p.step, rx);
            long quarterMs = Math.max(1, song.tempoAt(song.noteStart(note)) / 1000L);
            notehead(hx, hy, rx, ry, song.noteLength(note) >= quarterMs * 7 / 4, colour);
            if (accidental[a] != 0) {
                float ax = cx - rx - space * (0.2f + 1.0f * (column[a] + 1));
                if (accidental[a] == 1) sharpSign(ax, hy, space, colour);
                else naturalSign(ax + space * 0.1f, hy, space, colour);
            }
            if (p.ottava != 0) {
                int scale = digitScale();
                int value = Math.abs(p.ottava) == 1 ? 8 : 15;
                int y = p.ottava < 0 ? Math.round(hy - ry - 2 - 5 * scale) : Math.round(hy + ry + 2);
                number(Math.round(hx - digitsWidth(value, scale) / 2f), y, value, scale, colour);
            }
            previousStep = p.step;
            previousShifted = shifted;
        }
    }

    private int colour(Song song, int note, long playheadMs, int flags) {
        int c = INK;
        if ((flags & HAND_COLOURS) != 0) {
            int hand = song.noteHand(note);
            if (hand == Song.HAND_RIGHT) c = RIGHT_HAND;
            else if (hand == Song.HAND_LEFT) c = LEFT_HAND;
        }
        if ((flags & DIM_PLAYED) != 0 && song.noteEnd(note) <= playheadMs) c = mix(c, PAPER, 0.62f);
        return c;
    }

    private void durationLine(float fromX, float toX, float y, int colour) {
        if (toX <= fromX + 1) return;
        int thickness = Math.max(1, Math.round(halfSpace * 0.3f));
        int yy = Math.round(y - thickness / 2f);
        fillRect(Math.round(fromX), yy, Math.round(toX), yy + thickness, mix(colour, PAPER, 0.5f));
    }

    private void ledgers(float cx, int step, float rx) {
        int a = Math.round(cx - rx * 1.6f), b = Math.round(cx + rx * 1.6f);
        for (int s = TREBLE_TOP + 2; s <= step; s += 2) hLine(a, b, stepY(s), INK);
        for (int s = BASS_BOTTOM - 2; s >= step; s -= 2) hLine(a, b, stepY(s), INK);
        if (step == MIDDLE_C) hLine(a, b, stepY(MIDDLE_C), INK);
    }

    // ------------------------------------------------------------------ piano roll

    private void paintRoll(Song song, long playheadMs, int flags) {
        Arrays.fill(pixels, PAPER);
        int lo = songLowKey - 2, hi = songHighKey + 2;
        while (hi - lo + 1 < 25 && (lo > 0 || hi < 87)) {
            if (lo > 0) lo--;
            if (hi - lo + 1 < 25 && hi < 87) hi++;
        }
        lowKey = Math.max(0, lo);
        highKey = Math.min(87, hi);
        rowHeight = (height - 2) / (float) (highKey - lowKey + 1);
        int keyW = Math.max(16, width / 20);
        x0 = keyW + 1;
        x1 = width - 1;
        k = (x1 - x0) / (float) (toMs - fromMs);
        windowBars = new long[0];

        for (int key = lowKey; key <= highKey; key++) {
            int y0 = rowTop(key), y1 = rowTop(key - 1);
            if (isSharp(key + 21)) fillRect(Math.round(x0), y0, width, y1, ROLL_BLACK_ROW);
            if ((key + 21) % 12 == 0) fillRect(Math.round(x0), y1 - 1, width, y1, ROLL_GRID);
        }
        for (int b = firstBarAfter(fromMs - 1); b < bars.length && bars[b] < toMs; b++) {
            int x = Math.round(x0 + (bars[b] - fromMs) * k);
            fillRect(x, 1, x + 1, height - 1, ROLL_GRID);
        }

        boolean[] sounding = new boolean[88];
        int[] soundingColour = new int[88];
        int n = song.noteCount();
        for (int i = song.firstNoteAtOrAfter(Math.max(0, fromMs - 60_000)); i < n && song.noteStart(i) < toMs; i++) {
            int key = song.noteKey(i);
            if (song.noteEnd(i) <= fromMs || key < lowKey || key > highKey) continue;
            int colour = (flags & HAND_COLOURS) != 0 && song.noteHand(i) != Song.HAND_UNKNOWN ? colour(song, i, Long.MIN_VALUE, flags) : ROLL_NOTE;
            if ((flags & DIM_PLAYED) != 0 && song.noteEnd(i) <= playheadMs) colour = mix(colour, PAPER, 0.62f);
            int xs = Math.round(Math.max(x0, x0 + (song.noteStart(i) - fromMs) * k));
            int xe = Math.max(xs + 2, Math.round(Math.min(x1, x0 + (song.noteEnd(i) - fromMs) * k)));
            int y0 = rowTop(key), y1 = Math.max(y0 + 2, rowTop(key - 1));
            fillRect(xs, y0, xe, y1, colour);
            outline(xs, y0, xe, y1, mix(colour, INK, 0.5f));
            if (song.noteStart(i) <= playheadMs && song.noteEnd(i) > playheadMs) {
                sounding[key] = true;
                soundingColour[key] = colour;
            }
        }

        // Keyboard strip: white keys full width, black keys shorter, sounding keys lit.
        fillRect(0, 0, keyW, height, 0xFFFFFFFF);
        for (int key = lowKey; key <= highKey; key++) {
            int midi = key + 21;
            if (isSharp(midi)) continue;
            int y0 = rowTop(key), y1 = rowTop(key - 1);
            if (sounding[key]) fillRect(0, y0, keyW, y1, mix(soundingColour[key], 0xFFFFFFFF, 0.35f));
            int pc = midi % 12;
            if (pc == 0 || pc == 5) fillRect(0, y1 - 1, keyW, y1, ROLL_GRID);
        }
        for (int key = lowKey; key <= highKey; key++) {
            if (isSharp(key + 21)) fillRect(0, rowTop(key), keyW * 3 / 5, rowTop(key - 1), sounding[key] ? soundingColour[key] : INK);
        }
        fillRect(keyW, 0, keyW + 1, height, STAFF_INK);

        if ((flags & PLAYHEAD_LINE) != 0 && playheadMs >= fromMs && playheadMs < toMs) {
            int x = Math.round(xAt(playheadMs));
            fillRect(x - 1, 0, x + 1, height, PLAYHEAD);
        }
    }

    /** Top pixel row of a key's lane; {@code rowTop(key - 1)} is its bottom. */
    private int rowTop(int key) {
        return Math.round(1 + (highKey - key) * rowHeight);
    }

    // ------------------------------------------------------------------ glyphs (sizes in staff spaces)

    private void notehead(float cx, float cy, float rx, float ry, boolean hollow, int colour) {
        beginMask(cx - rx - 2, cy - ry - 2, cx + rx + 2, cy + ry + 2);
        maskEllipse(cx, cy, rx, ry, (float) Math.toRadians(-20), true);
        if (hollow) maskEllipse(cx, cy, rx * 0.8f, ry * 0.42f, (float) Math.toRadians(-38), false);
        endMask(colour);
    }

    private void sharpSign(float x, float y, float s, int colour) {
        beginMask(x - s * 0.2f, y - s * 1.7f, x + s * 1.1f, y + s * 1.7f);
        float thin = Math.max(0.55f, s * 0.05f), thick = Math.max(0.9f, s * 0.11f);
        maskCapsule(x + s * 0.3f, y - s * 1.25f, x + s * 0.3f, y + s * 1.45f, thin);
        maskCapsule(x + s * 0.65f, y - s * 1.45f, x + s * 0.65f, y + s * 1.25f, thin);
        maskCapsule(x + s * 0.05f, y - s * 0.3f, x + s * 0.9f, y - s * 0.55f, thick);
        maskCapsule(x + s * 0.05f, y + s * 0.55f, x + s * 0.9f, y + s * 0.3f, thick);
        endMask(colour);
    }

    private void naturalSign(float x, float y, float s, int colour) {
        beginMask(x - s * 0.2f, y - s * 1.7f, x + s * 1.0f, y + s * 1.7f);
        float thin = Math.max(0.55f, s * 0.05f), thick = Math.max(0.9f, s * 0.1f);
        maskCapsule(x + s * 0.15f, y - s * 1.4f, x + s * 0.15f, y + s * 0.55f, thin);
        maskCapsule(x + s * 0.6f, y - s * 0.55f, x + s * 0.6f, y + s * 1.4f, thin);
        maskCapsule(x + s * 0.15f, y - s * 0.2f, x + s * 0.6f, y - s * 0.4f, thick);
        maskCapsule(x + s * 0.15f, y + s * 0.4f, x + s * 0.6f, y + s * 0.2f, thick);
        endMask(colour);
    }

    /** G clef curling around the G line. Points are (right, up, half width) in staff spaces from the anchor. */
    private static final float[] TREBLE = {
        1.42f, 0.05f, 0.07f,
        1.10f, 0.42f, 0.10f,
        0.60f, 0.30f, 0.13f,
        0.42f, -0.28f, 0.16f,
        0.85f, -0.82f, 0.15f,
        1.55f, -0.72f, 0.12f,
        1.92f, -0.10f, 0.13f,
        1.70f, 0.70f, 0.14f,
        1.05f, 1.15f, 0.15f,
        0.38f, 1.60f, 0.16f,
        0.15f, 2.30f, 0.15f,
        0.55f, 3.05f, 0.12f,
        1.10f, 3.75f, 0.09f,
        1.30f, 4.45f, 0.07f,
        1.05f, 4.80f, 0.06f,
        0.75f, 4.35f, 0.07f,
        0.82f, 3.40f, 0.08f,
        1.05f, 1.60f, 0.08f,
        1.22f, -0.40f, 0.08f,
        1.30f, -1.60f, 0.08f,
        1.18f, -2.20f, 0.08f,
        0.85f, -2.45f, 0.07f,
        0.55f, -2.30f, 0.06f,
    };

    private void trebleClef(float x, float gy, float s) {
        beginMask(x - s * 0.5f, gy - s * 5.2f, x + s * 2.6f, gy + s * 3.0f);
        strokePath(TREBLE, x, gy, s);
        maskDisc(x + 0.62f * s, gy + 2.15f * s, s * 0.26f);
        endMask(INK);
    }

    /** F clef: its head on the F line, the two dots either side of that line. */
    private static final float[] BASS = {
        0.32f, 0.02f, 0.10f,
        0.48f, 0.62f, 0.10f,
        1.10f, 0.98f, 0.11f,
        1.75f, 0.78f, 0.14f,
        2.05f, 0.10f, 0.17f,
        1.85f, -0.80f, 0.16f,
        1.30f, -1.55f, 0.12f,
        0.65f, -2.10f, 0.08f,
        0.10f, -2.45f, 0.05f,
    };

    private void bassClef(float x, float fy, float s) {
        beginMask(x - s * 0.3f, fy - s * 1.5f, x + s * 2.9f, fy + s * 3.0f);
        strokePath(BASS, x, fy, s);
        maskDisc(x + 0.36f * s, fy + 0.02f * s, s * 0.3f);
        maskDisc(x + 2.48f * s, fy - 0.5f * s, s * 0.14f);
        maskDisc(x + 2.48f * s, fy + 0.5f * s, s * 0.14f);
        endMask(INK);
    }

    /** Catmull-Rom stroke through (u, v, half width) points in staff spaces; v points up. */
    private void strokePath(float[] path, float ax, float ay, float s) {
        int points = path.length / 3;
        float px = Float.NaN, py = 0, pr = 0;
        for (int i = 0; i < points - 1; i++) {
            int i0 = Math.max(0, i - 1), i2 = i + 1, i3 = Math.min(points - 1, i + 2);
            for (int t = 0; t <= 8; t++) {
                float u = t / 8f;
                float cu = catmull(path[i0 * 3], path[i * 3], path[i2 * 3], path[i3 * 3], u);
                float cv = catmull(path[i0 * 3 + 1], path[i * 3 + 1], path[i2 * 3 + 1], path[i3 * 3 + 1], u);
                float r = (path[i * 3 + 2] + (path[i2 * 3 + 2] - path[i * 3 + 2]) * u) * s;
                float x = ax + cu * s, y = ay - cv * s;
                if (!Float.isNaN(px)) maskCapsule(px, py, x, y, Math.max(0.6f, Math.min(pr, r)));
                px = x;
                py = y;
                pr = r;
            }
        }
    }

    private static float catmull(float p0, float p1, float p2, float p3, float t) {
        float t2 = t * t, t3 = t2 * t;
        return 0.5f * (2 * p1 + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3);
    }

    // ------------------------------------------------------------------ digits

    private static final String[] DIGITS = {
        "111101101101111", "010110010010111", "111001111100111", "111001111001111", "101101111001001",
        "111100111001111", "111100111101111", "111001010010010", "111101111101111", "111101111001111",
    };

    private int digitScale() {
        return Math.max(1, Math.round(height / 190f));
    }

    private static int digitsWidth(int value, int scale) {
        return Integer.toString(Math.max(0, value)).length() * 4 * scale - scale;
    }

    private void number(int x, int y, int value, int scale, int colour) {
        String text = Integer.toString(Math.max(0, value));
        for (int c = 0; c < text.length(); c++) {
            String glyph = DIGITS[text.charAt(c) - '0'];
            for (int row = 0; row < 5; row++) {
                for (int col = 0; col < 3; col++) {
                    if (glyph.charAt(row * 3 + col) != '1') continue;
                    int px = x + (c * 4 + col) * scale, py = y + row * scale;
                    fillRect(px, py, px + scale, py + scale, colour);
                }
            }
        }
    }

    // ------------------------------------------------------------------ raster primitives

    private void hLine(int xa, int xb, float y, int colour) {
        int thickness = Math.max(1, Math.round(halfSpace * 0.16f));
        int ya = Math.round(y) - thickness / 2;
        fillRect(xa, ya, xb, ya + thickness, colour);
    }

    private void fillRect(int xa, int ya, int xb, int yb, int colour) {
        int fx0 = Math.max(0, Math.min(xa, xb)), fx1 = Math.min(width, Math.max(xa, xb));
        int fy0 = Math.max(0, Math.min(ya, yb)), fy1 = Math.min(height, Math.max(ya, yb));
        for (int y = fy0; y < fy1; y++) Arrays.fill(pixels, y * width + fx0, y * width + Math.max(fx0, fx1), colour);
    }

    private void outline(int xa, int ya, int xb, int yb, int colour) {
        fillRect(xa, ya, xb, ya + 1, colour);
        fillRect(xa, yb - 1, xb, yb, colour);
        fillRect(xa, ya, xa + 1, yb, colour);
        fillRect(xb - 1, ya, xb, yb, colour);
    }

    private void beginMask(float xa, float ya, float xb, float yb) {
        maskX0 = Math.max(0, (int) Math.floor(xa));
        maskY0 = Math.max(0, (int) Math.floor(ya));
        maskX1 = Math.max(maskX0, Math.min(width, (int) Math.ceil(xb) + 1));
        maskY1 = Math.max(maskY0, Math.min(height, (int) Math.ceil(yb) + 1));
        for (int y = maskY0; y < maskY1; y++) Arrays.fill(mask, y * width + maskX0, y * width + maskX1, 0f);
    }

    private void endMask(int colour) {
        for (int y = maskY0; y < maskY1; y++) {
            for (int x = maskX0; x < maskX1; x++) {
                int i = y * width + x;
                float coverage = mask[i];
                if (coverage > 0.004f) pixels[i] = mix(pixels[i], colour, Math.min(1f, coverage));
            }
        }
    }

    /** Adds (or with {@code add} false, cuts out) an anti-aliased rotated ellipse. */
    private void maskEllipse(float cx, float cy, float rx, float ry, float angle, boolean add) {
        float cos = (float) Math.cos(angle), sin = (float) Math.sin(angle);
        float reach = Math.max(rx, ry) + 1;
        int xa = Math.max(maskX0, (int) Math.floor(cx - reach)), xb = Math.min(maskX1, (int) Math.ceil(cx + reach) + 1);
        int ya = Math.max(maskY0, (int) Math.floor(cy - reach)), yb = Math.min(maskY1, (int) Math.ceil(cy + reach) + 1);
        float scale = Math.min(rx, ry);
        for (int y = ya; y < yb; y++) {
            for (int x = xa; x < xb; x++) {
                float dx = x + 0.5f - cx, dy = y + 0.5f - cy;
                float u = dx * cos - dy * sin, v = dx * sin + dy * cos;
                float d = (float) Math.sqrt((u * u) / (rx * rx) + (v * v) / (ry * ry));
                float coverage = clamp01(0.5f - (d - 1f) * scale);
                int i = y * width + x;
                mask[i] = add ? Math.max(mask[i], coverage) : Math.min(mask[i], 1f - coverage);
            }
        }
    }

    private void maskDisc(float cx, float cy, float r) {
        maskEllipse(cx, cy, r, r, 0f, true);
    }

    private void maskCapsule(float ax, float ay, float bx, float by, float r) {
        int xa = Math.max(maskX0, (int) Math.floor(Math.min(ax, bx) - r - 1)), xb = Math.min(maskX1, (int) Math.ceil(Math.max(ax, bx) + r + 1));
        int ya = Math.max(maskY0, (int) Math.floor(Math.min(ay, by) - r - 1)), yb = Math.min(maskY1, (int) Math.ceil(Math.max(ay, by) + r + 1));
        float vx = bx - ax, vy = by - ay;
        float len2 = vx * vx + vy * vy;
        for (int y = ya; y < yb; y++) {
            for (int x = xa; x < xb; x++) {
                float px = x + 0.5f - ax, py = y + 0.5f - ay;
                float t = len2 <= 1e-6f ? 0f : clamp01((px * vx + py * vy) / len2);
                float dx = px - vx * t, dy = py - vy * t;
                float coverage = clamp01(r + 0.5f - (float) Math.sqrt(dx * dx + dy * dy));
                int i = y * width + x;
                mask[i] = Math.max(mask[i], coverage);
            }
        }
    }

    private static float clamp01(float v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }

    /** {@code b} over opaque {@code a} by {@code t} (0 = a, 1 = b). */
    static int mix(int a, int b, float t) {
        float s = 1 - t;
        int r = Math.round(((a >> 16) & 0xFF) * s + ((b >> 16) & 0xFF) * t);
        int g = Math.round(((a >> 8) & 0xFF) * s + ((b >> 8) & 0xFF) * t);
        int bl = Math.round((a & 0xFF) * s + (b & 0xFF) * t);
        return 0xFF000000 | r << 16 | g << 8 | bl;
    }

    // ------------------------------------------------------------------ upload

    /** Copies the painted image into the top-left corner of an RGBA {@link NativeImage} at least as large. */
    public void copyTo(NativeImage image) {
        if (image.format() != NativeImage.Format.RGBA || image.getWidth() < width || image.getHeight() < height) {
            throw new IllegalArgumentException("Target " + image + " cannot hold a " + width + "x" + height + " sheet");
        }
        long base = image.getPointer();
        if (base == 0L) throw new IllegalStateException("Target image is already closed");
        long stride = image.getWidth() * 4L;
        for (int y = 0; y < height; y++) {
            long row = base + y * stride;
            int offset = y * width;
            for (int x = 0; x < width; x++) {
                int argb = pixels[offset + x];
                // NativeImage keeps RGBA bytes, which read as a little-endian ABGR int.
                MemoryUtil.memPutInt(row + x * 4L, (argb & 0xFF00FF00) | (argb & 0xFF) << 16 | (argb >> 16) & 0xFF);
            }
        }
    }
}
