package org.hurtorius.music.client.ui;

/**
 * Geometry of the on-screen 88-key keyboard: where each key is drawn and which key a point hits.
 * The 52 white keys share the width evenly (boundaries rounded to whole pixels, so no gap or overlap
 * builds up); each black key sits over the gap between its white neighbours, nudged sideways the
 * way a real keyboard groups them. Coordinates are whatever unit the caller uses (the screen draws
 * in device pixels). Key 0 is A0 (MIDI 21), key 87 is C8. Pure Java.
 */
public final class KeyboardLayout {
    public static final int KEYS = 88, WHITE_KEYS = 52, LOWEST_MIDI = 21;
    /** Black keys are this share of the keyboard's height long and this share of a white key wide. */
    static final float BLACK_LENGTH = 0.62f, BLACK_WIDTH = 0.58f;
    /** Softest and loudest velocity a click can give: soft at the far end of a key, loud at its front. */
    static final int SOFTEST = 24, LOUDEST = 127;
    /** Sideways offset of each black key from the gap below it, in white-key widths, by pitch class. */
    private static final float[] BLACK_OFFSET = {0, -0.10f, 0, 0.10f, 0, 0, -0.12f, 0, 0, 0, 0.12f, 0};
    /** For a white key its index among the white keys; for a black key the index of the white key below it. */
    private static final int[] WHITE_INDEX = new int[KEYS];
    private static final int[] WHITE_KEY = new int[WHITE_KEYS];

    static {
        int white = -1;
        for (int key = 0; key < KEYS; key++) {
            if (!isBlack(key)) WHITE_KEY[++white] = key;
            WHITE_INDEX[key] = white;
        }
    }

    private final int x, y, width, height;
    private final int[] left = new int[KEYS], right = new int[KEYS];
    private final int blackBottom;

    /** A keyboard filling the rectangle {@code x, y, width, height}. */
    public KeyboardLayout(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = Math.max(0, width);
        this.height = Math.max(0, height);
        this.blackBottom = y + Math.round(this.height * BLACK_LENGTH);
        float whiteWidth = this.width / (float) WHITE_KEYS;
        int blackWidth = Math.max(1, Math.round(whiteWidth * BLACK_WIDTH));
        for (int key = 0; key < KEYS; key++) {
            int white = WHITE_INDEX[key];
            if (!isBlack(key)) {
                left[key] = whiteLeft(white);
                right[key] = whiteLeft(white + 1);
            } else {
                float centre = whiteLeft(white + 1) + BLACK_OFFSET[pitchClass(key)] * whiteWidth;
                left[key] = Math.round(centre - blackWidth / 2f);
                right[key] = left[key] + blackWidth;
            }
        }
    }

    /** True for the 36 black keys. */
    public static boolean isBlack(int key) {
        int pc = pitchClass(key);
        return pc == 1 || pc == 3 || pc == 6 || pc == 8 || pc == 10;
    }

    /** True for the eight C keys (C1..C8), which carry the octave labels. */
    public static boolean isC(int key) {
        return pitchClass(key) == 0;
    }

    /** Pitch class 0 (C) .. 11 (B) of a key. */
    public static int pitchClass(int key) {
        return Math.floorMod(key + LOWEST_MIDI, 12);
    }

    /** The key of white key number {@code index} (0..51). */
    public static int whiteKey(int index) {
        return WHITE_KEY[index];
    }

    private int whiteLeft(int index) {
        return x + (int) Math.round((double) index * width / WHITE_KEYS);
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** Left edge of a key (inclusive). */
    public int left(int key) {
        return left[key];
    }

    /** Right edge of a key (exclusive). */
    public int right(int key) {
        return right[key];
    }

    /** Bottom edge (exclusive) of a key: the front of the keyboard for white keys, shorter for black ones. */
    public int bottom(int key) {
        return isBlack(key) ? blackBottom : y + height;
    }

    /** Horizontal centre of a key. */
    public float centreX(int key) {
        return (left[key] + right[key]) / 2f;
    }

    /** The key under a point, or -1 outside the keyboard. Black keys are on top of the white ones. */
    public int keyAt(double px, double py) {
        if (px < x || px >= x + width || py < y || py >= y + height) return -1;
        if (py < blackBottom) {
            for (int key = 0; key < KEYS; key++) {
                if (isBlack(key) && px >= left[key] && px < right[key]) return key;
            }
        }
        int lo = 0, hi = WHITE_KEYS - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (whiteLeft(mid) <= px) lo = mid; else hi = mid - 1;
        }
        return WHITE_KEY[lo];
    }

    /**
     * How hard a click at height {@code py} strikes {@code key}: softly near the far end of the key,
     * loudly at its front edge, the way a finger has more leverage there.
     */
    public int velocityAt(int key, double py) {
        double length = Math.max(1, bottom(key) - y);
        double along = Math.max(0, Math.min(1, (py - y) / length));
        return (int) Math.round(SOFTEST + along * (LOUDEST - SOFTEST));
    }
}
