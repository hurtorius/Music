package org.hurtorius.music.client.render;

import org.hurtorius.music.piano.Pedal;
import org.hurtorius.music.piano.PianoGeometry;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Arrays;

/**
 * The moving parts of one piano for one frame: how far each key, damper and pedal is down or up and
 * where the lid, its prop and the music desk are, resolved into per-group model transforms by
 * {@link PianoGeometry#transforms}, plus the shade each group is drawn with. Keeps its arrays between
 * frames, so posing allocates nothing.
 *
 * <p>A key turns about its balance point, {@link #BALANCE_BEHIND} behind the back of its visible part
 * under the fallboard and level with its top, as a grand's key turns on its balance rail: the whole
 * visible key sinks, its front most, and its front face goes down and a little back, never toward
 * the keys in front of it. The kit's few degrees about the back of the key are true to life but drop
 * the front a fraction of a Minecraft pixel; here the front of a white key goes down as far as the
 * keybed lets it ({@link #keyDip}), in the direction of the kit's press, and a black key until it is
 * {@link #BLACK_CLEARANCE} above the white keys, as on a real grand
 * ({@link #keyDip(PianoGeometry, PianoGeometry.Key)}); the white keys' heads are fitted to that
 * travel ({@code tools/build-piano-geometry.py}).</p>
 *
 * <p>A pressed key is drawn darker as it sinks: a white key into the shade of the keys beside it
 * ({@link #PRESSED_SHADE}), a black key more ({@link #BLACK_PRESSED_SHADE}). A black key moves little
 * and is dark against the dark case behind it, so a few blocks away its press shows as a change of
 * shade: the atlas gives the black keys' tops and fronts a sheen, and a black key fully down loses
 * it, back to about the kit's own black, as an ebony key loses its shine when it tips away from the
 * light. A pedal's toe goes down as far as a white key's front (the kit's 7 degrees would move it
 * half a pixel).</p>
 */
public final class PianoPose {
    public static final String LID = "lid_hinge", PROP = "lid_prop", DESK = "music_desk";
    /** Clip that closes the lid: folds the prop and the desk first, then lowers the lid. */
    public static final String LID_CLIP = "lid_close";
    /** Where a key turns: this far behind the back of its visible part (about 9 cm at life size), model units. */
    public static final float BALANCE_BEHIND = 1.5f;
    /** What a key fully down keeps between its top and the keybed, model units. */
    public static final float KEYBED_CLEARANCE = 0.05f;
    /** What the front of a black key fully down keeps above the white keys' tops, model units. */
    public static final float BLACK_CLEARANCE = 0.04f;
    /** How much darker a white key fully down is drawn: enough to tell a pressed key from six blocks away. */
    public static final float PRESSED_SHADE = 0.35f;
    /**
     * How much darker a black key fully down is drawn: its sheen in the atlas (the kit's greys lifted,
     * 28 to 70 at the front of its top) gone, back to about the kit's black.
     */
    public static final float BLACK_PRESSED_SHADE = 0.6f;
    private static final int WHITE = 0xFFFFFFFF;

    private final PianoGeometry geometry;
    private final Vector3f[] offsets;
    private final Matrix4f[] transforms;
    private final int[] tints;
    private final int[] keyGroups = new int[PianoGeometry.KEY_COUNT];
    /** Each key's turn when fully down, radians about its own x axis through its balance point. */
    private final float[] keyTurns = new float[PianoGeometry.KEY_COUNT];
    /** How high each key's balance point (its top) is above the key's own origin, model units. */
    private final float[] keyAxes = new float[PianoGeometry.KEY_COUNT];
    private final float[] keyDepths = new float[PianoGeometry.KEY_COUNT];
    /** How much darker each key fully down is drawn. */
    private final float[] keyShades = new float[PianoGeometry.KEY_COUNT];
    private final float[] damperLifts = new float[PianoGeometry.KEY_COUNT];
    private final int[] pedalGroups = new int[Pedal.values().length];
    /** Each pedal's tip when fully down, degrees about its x axis (toe down). */
    private final float[] pedalTurns = new float[Pedal.values().length];
    private final int lid, prop, desk;
    private final float clipLength;
    private final PianoGeometry.Track lidTrack, propTrack, deskTrack;
    private final Vector3f deskFoldedRotation = new Vector3f(), fold = new Vector3f();

    /** A pose at rest: keys, dampers and pedals up, lid open on its prop, desk up. */
    public PianoPose(PianoGeometry geometry) {
        this.geometry = geometry;
        this.offsets = new Vector3f[geometry.groups.size()];
        for (int i = 0; i < offsets.length; i++) offsets[i] = new Vector3f();
        this.transforms = geometry.newTransforms();
        this.tints = new int[geometry.groups.size()];
        Arrays.fill(tints, WHITE);
        float dip = keyDip(geometry), whiteTop = whiteTop(geometry);
        for (PianoGeometry.Key key : geometry.keys) {
            keyGroups[key.index()] = key.group();
            keyTurns[key.index()] = Math.signum(key.press().x) * turn(key, keyDip(key, dip, whiteTop));
            keyAxes[key.index()] = key.to().y - key.pivot().y;
            keyShades[key.index()] = key.black() ? BLACK_PRESSED_SHADE : PRESSED_SHADE;
        }
        for (Pedal pedal : Pedal.values()) {
            pedalGroups[pedal.ordinal()] = geometry.group(pedal.group());
            pedalTurns[pedal.ordinal()] = pedalTurn(geometry, pedal);
        }
        this.lid = geometry.group(LID);
        this.prop = geometry.group(PROP);
        this.desk = geometry.group(DESK);
        PianoGeometry.Animation clip = geometry.animations.get(LID_CLIP);
        if (clip == null) throw new IllegalStateException("The piano model has no " + LID_CLIP + " clip");
        this.clipLength = clip.length();
        this.lidTrack = track(clip, LID);
        this.propTrack = track(clip, PROP);
        this.deskTrack = track(clip, DESK);
        deskTrack.sample(clipLength, deskFoldedRotation);
        resolve();
    }

    private static PianoGeometry.Track track(PianoGeometry.Animation clip, String group) {
        PianoGeometry.Track track = clip.tracks().get(group);
        if (track == null) throw new IllegalStateException("The " + clip.name() + " clip does not move " + group);
        return track;
    }

    /**
     * How far the front edge of a white key fully down sits below where it rests, model units: down to
     * {@link #KEYBED_CLEARANCE} above the keybed. Any deeper and the keybed would cover the front of
     * the key. The pedals' toes go down as far.
     */
    public static float keyDip(PianoGeometry geometry) {
        float top = Float.POSITIVE_INFINITY;
        for (PianoGeometry.Key key : geometry.keys) top = Math.min(top, key.to().y);
        return top - geometry.keybedTop() - KEYBED_CLEARANCE;
    }

    /**
     * How far the front edge of {@code key} fully down sits below where it rests, model units: a white
     * key's {@link #keyDip(PianoGeometry)}, and for a black key as far as takes its front down to
     * {@link #BLACK_CLEARANCE} above the white keys' tops (the kit's black keys stand 0.295 above
     * them, so 0.255), or the white keys' dip if that is less.
     */
    public static float keyDip(PianoGeometry geometry, PianoGeometry.Key key) {
        return keyDip(key, keyDip(geometry), whiteTop(geometry));
    }

    /**
     * How far below where it rests the top of {@code key} fully down is {@code behind} model units
     * behind its front edge: it turns about its balance point, so it goes down less toward the back.
     */
    public static float dropAt(PianoGeometry geometry, PianoGeometry.Key key, float behind) {
        float lever = BALANCE_BEHIND + key.pivot().z - key.from().z;
        return keyDip(geometry, key) * (lever - behind) / lever;
    }

    private static float keyDip(PianoGeometry.Key key, float whiteDip, float whiteTop) {
        return key.black() ? Math.max(0f, Math.min(whiteDip, key.to().y - whiteTop - BLACK_CLEARANCE)) : whiteDip;
    }

    /** Height of the white keys' tops, model units. */
    static float whiteTop(PianoGeometry geometry) {
        float top = Float.NEGATIVE_INFINITY;
        for (PianoGeometry.Key key : geometry.keys) if (!key.black()) top = Math.max(top, key.to().y);
        return top;
    }

    /**
     * How far {@code pedal} turns fully down, degrees about its own x axis (negative: toe down): as far
     * as takes its toe down by a white key's {@link #keyDip(PianoGeometry)}.
     */
    public static float pedalTurn(PianoGeometry geometry, Pedal pedal) {
        return -(float) Math.toDegrees(Math.asin(keyDip(geometry) / toe(geometry, geometry.group(pedal.group()))));
    }

    /** How far a pedal reaches forward (toward -z) of its pivot: the length of the lever its toe is on. */
    static float toe(PianoGeometry geometry, int group) {
        float toe = 0f;
        for (PianoGeometry.Element element : geometry.elements) {
            if (element.group() != group) continue;
            float[] p = element.positions();
            for (int i = 2; i < p.length; i += 3) toe = Math.max(toe, -p[i]);
        }
        if (toe <= 0f) throw new IllegalStateException("A pedal of the piano model has no toe in front of its pivot");
        return toe;
    }

    /**
     * The turn (radians, unsigned) that lowers the front edge of the key's top by {@code dip}. That
     * edge lies {@code lever} in front of the balance point, level with it, so it drops
     * {@code lever sin a} when the key turns by {@code a}.
     */
    static float turn(PianoGeometry.Key key, float dip) {
        double lever = BALANCE_BEHIND + key.pivot().z - key.from().z;
        return (float) Math.asin(Math.min(1.0, dip / lever));
    }

    /** {@code depth} 0 (up) .. 1 (down on the keybed) of key 0..87. */
    public PianoPose key(int key, float depth) {
        float d = clamp01(depth);
        keyDepths[key] = d;
        int shade = Math.round(255f * (1f - keyShades[key] * d));
        tints[keyGroups[key]] = 0xFF000000 | shade << 16 | shade << 8 | shade;
        return this;
    }

    /** {@code lift} 0 (resting on its strings) .. 1 (lifted clear) of the damper of key 0..87. */
    public PianoPose damper(int key, float lift) {
        damperLifts[key] = clamp01(lift);
        return this;
    }

    /** {@code depth} 0 (up) .. 1 (fully down) of a pedal. */
    public PianoPose pedal(Pedal pedal, float depth) {
        offsets[pedalGroups[pedal.ordinal()]].set(pedalTurns[pedal.ordinal()] * clamp01(depth), 0, 0);
        return this;
    }

    /**
     * Lid, prop and desk from the lid clip at {@code lidClosed} (0 open .. 1 closed); the desk also
     * folds on its own by {@code deskFolded} (0 up .. 1 folded) toward the clip's folded angle.
     */
    public PianoPose lidAndDesk(float lidClosed, float deskFolded) {
        float t = clamp01(lidClosed) * clipLength;
        lidTrack.sample(t, offsets[lid]);
        propTrack.sample(t, offsets[prop]);
        Vector3f deskOffset = deskTrack.sample(t, offsets[desk]);
        fold.set(deskFoldedRotation).mul(clamp01(deskFolded));
        // Whichever folds the desk further wins, axis by axis.
        if (Math.abs(fold.x) > Math.abs(deskOffset.x)) deskOffset.x = fold.x;
        if (Math.abs(fold.y) > Math.abs(deskOffset.y)) deskOffset.y = fold.y;
        if (Math.abs(fold.z) > Math.abs(deskOffset.z)) deskOffset.z = fold.z;
        return this;
    }

    /** Computes every group's model transform from the current pose and returns them (owned by this pose). */
    public Matrix4f[] resolve() {
        geometry.transforms(offsets, transforms);
        for (int k = 0; k < keyDepths.length; k++) {
            if (keyDepths[k] <= 0f) continue;
            // About the key's own x axis through its balance point: level with its top, behind it along its own +z.
            transforms[keyGroups[k]].translate(0f, keyAxes[k], BALANCE_BEHIND).rotateX(keyTurns[k] * keyDepths[k]).translate(0f, -keyAxes[k], -BALANCE_BEHIND);
        }
        return transforms;
    }

    /** Group transforms as of the last {@link #resolve()}. */
    public Matrix4f[] transforms() {
        return transforms;
    }

    /** The colour each group is drawn with (opaque ARGB, white for most), as of the last posing call. */
    public int[] tints() {
        return tints;
    }

    /** How far each key's damper is lifted, 0..1, by key index. */
    public float[] damperLifts() {
        return damperLifts;
    }

    /** The music desk's transform as of the last {@link #resolve()}. */
    public Matrix4f desk() {
        return transforms[desk];
    }

    private static float clamp01(float v) {
        return v > 0 ? (v < 1 ? v : 1) : 0;
    }
}
