package org.hurtorius.music.client.anim;

import org.hurtorius.music.client.render.PianoPose;
import org.hurtorius.music.piano.PianoGeometry;
import org.hurtorius.music.piano.PianoLayout;

/**
 * The 88 keys in the piano's model space (see {@code PianoLayout}: keyboard toward -Z, bass toward +X),
 * as the hands see them: where each key is across the keyboard, how high its top is and where a
 * finger touches it. Touch points are the same points {@code PianoLayout.keyWorld} returns, before
 * the model-to-world transform.
 */
public final class KeyboardSpace {
    public static final int KEYS = PianoGeometry.KEY_COUNT;
    private static volatile KeyboardSpace instance;

    private final float[] x = new float[KEYS];
    private final float[] top = new float[KEYS];
    private final float[] touchZ = new float[KEYS];
    private final float[] pressDrop = new float[KEYS];
    private final float[] frontZ = new float[KEYS];
    private final float[] halfWidth = new float[KEYS];
    private final boolean[] black = new boolean[KEYS];
    private final float bassEdge;
    private final float trebleEdge;
    private final float lowestTop, highestTop;
    private final float whiteFront, blackFront;
    private final float keybedTop;

    public KeyboardSpace(PianoGeometry geometry) {
        float bass = Float.NEGATIVE_INFINITY, treble = Float.POSITIVE_INFINITY;
        for (int k = 0; k < KEYS; k++) {
            PianoGeometry.Key key = geometry.keys.get(k);
            x[k] = (key.from().x + key.to().x) * 0.5f;
            top[k] = key.to().y;
            touchZ[k] = key.from().z + PianoLayout.TOUCH;
            pressDrop[k] = PianoPose.dropAt(geometry, key, PianoLayout.TOUCH);
            frontZ[k] = key.from().z;
            halfWidth[k] = (key.to().x - key.from().x) * 0.5f;
            black[k] = key.black();
            bass = Math.max(bass, key.to().x);
            treble = Math.min(treble, key.from().x);
            if (k > 0 && x[k] >= x[k - 1]) throw new IllegalStateException("Key " + k + " is not trebleward of key " + (k - 1));
        }
        bassEdge = bass;
        trebleEdge = treble;
        float lowest = Float.POSITIVE_INFINITY, highest = Float.NEGATIVE_INFINITY, white = Float.POSITIVE_INFINITY, blackEdge = Float.POSITIVE_INFINITY;
        for (int k = 0; k < KEYS; k++) {
            lowest = Math.min(lowest, top[k]);
            highest = Math.max(highest, top[k]);
            if (black[k]) blackEdge = Math.min(blackEdge, frontZ[k]);
            else white = Math.min(white, frontZ[k]);
        }
        lowestTop = lowest;
        highestTop = highest;
        whiteFront = white;
        blackFront = blackEdge;
        keybedTop = geometry.keybedTop();
    }

    public static KeyboardSpace get() {
        KeyboardSpace space = instance;
        if (space == null) {
            synchronized (KeyboardSpace.class) {
                space = instance;
                if (space == null) instance = space = new KeyboardSpace(PianoGeometry.get());
            }
        }
        return space;
    }

    /** Centre of the key across the keyboard. */
    public float x(int key) {
        return x[key];
    }

    /** Height of the key's top surface at rest. */
    public float top(int key) {
        return top[key];
    }

    /** Depth where a finger plays the key: on its head, just behind its front edge (see {@code PianoLayout.TOUCH}). */
    public float touchZ(int key) {
        return touchZ[key];
    }

    /** How far the key's top goes down where a finger plays it (see {@link #touchZ}) when the key is fully down. */
    public float pressDrop(int key) {
        return pressDrop[key];
    }

    /** Depth of the key's front edge, nearest the performer. */
    public float frontZ(int key) {
        return frontZ[key];
    }

    /** Depth of the front edge of the white keys, or of the black keys. */
    public float frontZ(boolean blackKeys) {
        return blackKeys ? blackFront : whiteFront;
    }

    /** Height of the highest key top at rest: the black keys'. */
    public float highestTop() {
        return highestTop;
    }

    /** Height of the keybed's top, the surface in front of the keys' front edges. */
    public float keybedTop() {
        return keybedTop;
    }

    public boolean black(int key) {
        return black[key];
    }

    /**
     * Highest key top under a hand whose palm spans {@code modelX ± halfSpan} across the keyboard and
     * reaches in to {@code reachZ}: keys whose front edge lies beyond the fingertips do not count.
     * With no key under the hand, the lowest key top is returned.
     */
    public float topUnder(float modelX, float halfSpan, float reachZ) {
        float top = lowestTop;
        for (int k = 0; k < KEYS; k++) {
            if (frontZ[k] < reachZ && Math.abs(x[k] - modelX) < halfSpan + halfWidth[k]) top = Math.max(top, this.top[k]);
        }
        return top;
    }

    /** Outer edge of the lowest key (largest x). */
    public float bassEdge() {
        return bassEdge;
    }

    /** Outer edge of the highest key (smallest x). */
    public float trebleEdge() {
        return trebleEdge;
    }

    /** Model x of a fractional key position (0 = A0, 87 = C8), clamped to the keyboard. */
    public float xAt(float key) {
        if (!(key > 0f)) return x[0];
        if (key >= KEYS - 1) return x[KEYS - 1];
        int lo = (int) key;
        float t = key - lo;
        return x[lo] + (x[lo + 1] - x[lo]) * t;
    }

    /** Fractional key position over a model x; the inverse of {@link #xAt}, clamped to the keyboard. */
    public float keyAt(float modelX) {
        if (modelX >= x[0]) return 0f;
        if (modelX <= x[KEYS - 1]) return KEYS - 1;
        int lo = 0, hi = KEYS - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (x[mid] > modelX) lo = mid;
            else hi = mid;
        }
        return lo + (x[lo] - modelX) / (x[lo] - x[hi]);
    }
}
