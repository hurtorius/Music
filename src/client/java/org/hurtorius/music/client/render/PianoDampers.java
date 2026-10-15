package org.hurtorius.music.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.hurtorius.music.client.audio.GrandPianoBank;
import org.hurtorius.music.client.audio.GrandPianoSpec;
import org.hurtorius.music.client.audio.PianoAudio;
import org.hurtorius.music.piano.PianoGeometry;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The row of dampers resting on the strings, seen through the open lid. The kit has none, so they are
 * generated here, one per damped key, from the model's own parts: on the strings (the soundboard's
 * top), just behind the plate's front bar, spread across the keyboard's width from the lowest key to
 * the last damped one, with a gap wherever a bar of the plate crosses the line, as on a real grand.
 * The top keys have no dampers: where they stop is the sample map's {@code undampedFrom}, the same
 * line past which the sound lets released strings ring on, read from the samples the sound has
 * loaded (a resource pack may bring its own), else from the map in the jar.
 *
 * <p>Each damper is a dark wooden head with a white felt underneath. Resting, the felt sits down
 * between the strings and only the head shows; lifted ({@link #LIFT}, while its key is down or a pedal
 * holds it, as {@link PianoPose#damperLifts} says how far) the felt comes up into sight with a gap of
 * strings under it, which shows the lift better than the small move itself. The head is five faces
 * (its underside never shows) and the felt four more, sent only while lifted; both wear one texel of
 * the piano's atlas, so the dampers go out with the body's own render type, in fewer quads than the
 * keys.</p>
 *
 * <p>Emitting reuses scratch vectors, so call it from one thread (the render thread).</p>
 */
public final class PianoDampers {
    /** The atlas the dampers are drawn from (the piano's body texture). */
    public static final int TEXTURE = 0;
    /** Texels of that atlas (512 x 512) the dampers wear: the black lacquer of the key felt strip, and the white of the keys. */
    static final int WOOD_U = 20, WOOD_V = 249, FELT_U = 51, FELT_V = 256, ATLAS = 512;
    /** Between neighbouring dampers, and between a damper and a bar of the plate, model units. */
    static final float GAP = 0.04f;
    /** How far behind the plate's front bar the dampers sit, and how long they are from front to back. */
    static final float SETBACK = 0.15f, LENGTH = 0.8f;
    /** How tall the wooden head is above the strings, and the felt below them. */
    static final float HEAD = 0.42f, FELT = 0.2f;
    /** How high a damper lifts off its strings: its felt and a gap under it come into sight. */
    public static final float LIFT = 0.3f;
    /** Parts of the model the dampers are placed by. */
    static final String PLATE = "cast_harp", FRONT_BAR = "harp_front", SOUNDBOARD = "soundboard_connected";
    static final String SAMPLE_MAP = "/assets/music/instruments/grand_piano/" + GrandPianoBank.MAP;
    /** Faces per damper: the head's top, front, back and two sides, then the felt's front, back and two sides. */
    static final int HEAD_FACES = 5, FACES = 9, FACE_FLOATS = 4 * 3, DAMPER_FLOATS = FACES * FACE_FLOATS;
    /** Outward normal of each face: top, front (toward the keyboard), back, bass side, treble side; the felt's as the head's sides. */
    private static final float[][] NORMALS = {{0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {1, 0, 0}, {-1, 0, 0}, {0, 0, -1}, {0, 0, 1}, {1, 0, 0}, {-1, 0, 0}};
    private static volatile PianoDampers bundled;
    private static PianoDampers loaded;

    private final int undampedFrom;
    private final int count;
    private final float[] corners;
    private final float[] middles;
    private final float woodU = (WOOD_U + 0.5f) / ATLAS, woodV = (WOOD_V + 0.5f) / ATLAS;
    private final float feltU = (FELT_U + 0.5f) / ATLAS, feltV = (FELT_V + 0.5f) / ATLAS;
    private final Vector3f[] normals = new Vector3f[FACES];
    private final Vector3f a = new Vector3f();

    PianoDampers(PianoGeometry geometry, int undampedFrom) {
        this.undampedFrom = undampedFrom;
        this.count = Math.max(0, Math.min(PianoGeometry.KEY_COUNT, undampedFrom - PianoGeometry.LOWEST_MIDI));
        for (int f = 0; f < FACES; f++) normals[f] = new Vector3f();
        Matrix4f[] rest = geometry.newTransforms();
        geometry.transforms(null, rest);
        float front = bounds(geometry, rest, FRONT_BAR).max.z + SETBACK, back = front + LENGTH;
        float strings = bounds(geometry, rest, SOUNDBOARD).max.y;

        // The row runs from the bass edge of the lowest key to the treble edge of the last damped one,
        // less wherever a bar of the plate crosses it (high enough to meet a damper, lifted or not).
        List<float[]> spans = new ArrayList<>();
        if (count > 0) spans.add(new float[]{geometry.keys.get(count - 1).from().x, geometry.keys.get(0).to().x});
        int plate = geometry.group(PLATE);
        for (PianoGeometry.Element element : geometry.elements) {
            if (!geometry.isWithin(element.group(), plate)) continue;
            float[] across = crossing(element.positions(), rest[element.group()], front, back, strings - FELT, strings + HEAD + LIFT);
            if (across != null) spans = cut(spans, across[0] - GAP, across[1] + GAP);
        }
        // Bass first, as the keys are numbered.
        spans.sort((p, q) -> Float.compare(q[1], p[1]));
        int[] shares = shares(spans, count);
        this.middles = new float[count];
        this.corners = new float[count * DAMPER_FLOATS];
        int d = 0;
        for (int s = 0; s < spans.size(); s++) {
            float[] span = spans.get(s);
            float pitch = (span[1] - span[0]) / Math.max(1, shares[s]);
            for (int j = 0; j < shares[s]; j++, d++) {
                float right = span[1] - pitch * j, left = right - pitch + GAP;
                middles[d] = (left + right) / 2f;
                int o = d * DAMPER_FLOATS;
                o = box(o, left, right, strings, strings + HEAD, front, back, true);
                box(o, left, right, strings - FELT, strings, front, back, false);
            }
        }
    }

    /** The dampers of the sample map in the Music jar, which the item and the tests draw. */
    public static PianoDampers get() {
        PianoDampers dampers = bundled;
        if (dampers == null) {
            synchronized (PianoDampers.class) {
                dampers = bundled;
                if (dampers == null) bundled = dampers = new PianoDampers(PianoGeometry.get(), undampedFrom());
            }
        }
        return dampers;
    }

    /**
     * The dampers for the grand piano samples the sound has loaded, so they stop where the sound stops
     * damping; until any have loaded, those of the map in the jar. Render thread.
     */
    public static PianoDampers current() {
        int from = PianoAudio.undampedFrom();
        if (from < 0) return get();
        PianoDampers dampers = loaded;
        if (dampers == null || dampers.undampedFrom != from) loaded = dampers = new PianoDampers(PianoGeometry.get(), from);
        return dampers;
    }

    /** The first MIDI note without a damper, from the sample map in the jar. */
    static int undampedFrom() {
        try (InputStream in = PianoDampers.class.getResourceAsStream(SAMPLE_MAP)) {
            if (in == null) throw new IllegalStateException("Missing " + SAMPLE_MAP + " in the Music jar");
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return GrandPianoSpec.parse(reader).undampedFrom;
            }
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("Could not read where the dampers stop from " + SAMPLE_MAP, e);
        }
    }

    private record Bounds(Vector3f min, Vector3f max) {}

    private static Bounds bounds(PianoGeometry geometry, Matrix4f[] rest, String name) {
        Vector3f min = new Vector3f(Float.POSITIVE_INFINITY), max = new Vector3f(Float.NEGATIVE_INFINITY), p = new Vector3f();
        for (PianoGeometry.Element element : geometry.elements) {
            if (!element.name().equals(name)) continue;
            float[] pos = element.positions();
            for (int i = 0; i < pos.length; i += 3) {
                rest[element.group()].transformPosition(p.set(pos[i], pos[i + 1], pos[i + 2]));
                min.min(p);
                max.max(p);
            }
        }
        if (min.x > max.x) throw new IllegalStateException("The piano model has no " + name + " element");
        return new Bounds(min, max);
    }

    /**
     * The x range an element (a convex block, like the plate's bars) covers between the planes
     * {@code z = front} and {@code z = back}, or null when it stays clear of them or of the heights
     * {@code bottom..top}. Its extremes there lie on its corners or where the lines between them
     * cross those planes.
     */
    static float[] crossing(float[] local, Matrix4f transform, float front, float back, float bottom, float top) {
        int n = local.length / 3;
        Vector3f[] p = new Vector3f[n];
        float low = Float.POSITIVE_INFINITY, high = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            p[i] = transform.transformPosition(new Vector3f(local[i * 3], local[i * 3 + 1], local[i * 3 + 2]));
            low = Math.min(low, p[i].y);
            high = Math.max(high, p[i].y);
        }
        if (high < bottom || low > top) return null;
        float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY;
        float[] planes = {front, back};
        for (int i = 0; i < n; i++) {
            if (p[i].z >= front && p[i].z <= back) {
                min = Math.min(min, p[i].x);
                max = Math.max(max, p[i].x);
            }
            for (int j = i + 1; j < n; j++) {
                for (float z : planes) {
                    if ((p[i].z - z) * (p[j].z - z) >= 0) continue;
                    float x = p[i].x + (z - p[i].z) / (p[j].z - p[i].z) * (p[j].x - p[i].x);
                    min = Math.min(min, x);
                    max = Math.max(max, x);
                }
            }
        }
        return min > max ? null : new float[]{min, max};
    }

    /** The spans (low x, high x) less the range {@code from..to}. */
    private static List<float[]> cut(List<float[]> spans, float from, float to) {
        List<float[]> out = new ArrayList<>();
        for (float[] span : spans) {
            if (to <= span[0] || from >= span[1]) {
                out.add(span);
                continue;
            }
            if (from > span[0]) out.add(new float[]{span[0], from});
            if (to < span[1]) out.add(new float[]{to, span[1]});
        }
        return out;
    }

    /** How many of {@code count} dampers each span holds: in proportion to its width, the remainders to the largest. */
    private static int[] shares(List<float[]> spans, int count) {
        int[] shares = new int[spans.size()];
        float total = 0;
        for (float[] span : spans) total += span[1] - span[0];
        if (total <= 0) return shares;
        float[] remainders = new float[spans.size()];
        int given = 0;
        for (int s = 0; s < shares.length; s++) {
            float exact = (spans.get(s)[1] - spans.get(s)[0]) / total * count;
            shares[s] = (int) exact;
            remainders[s] = exact - shares[s];
            given += shares[s];
        }
        for (; given < count; given++) {
            int best = 0;
            for (int s = 1; s < shares.length; s++) if (remainders[s] > remainders[best]) best = s;
            shares[best]++;
            remainders[best] = -1f;
        }
        return shares;
    }

    /**
     * Writes the faces of a box from {@code o}, each wound counter-clockwise seen from outside: with
     * {@code top}, its top and four sides, else its four sides. Returns where the next face goes.
     */
    private int box(int o, float x0, float x1, float y0, float y1, float z0, float z1, boolean top) {
        float[][] faces = {
            {x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0},
            {x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0},
            {x1, y0, z1, x1, y1, z1, x0, y1, z1, x0, y0, z1},
            {x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1},
            {x0, y0, z1, x0, y1, z1, x0, y1, z0, x0, y0, z0},
        };
        for (int f = top ? 0 : 1; f < faces.length; f++) {
            System.arraycopy(faces[f], 0, corners, o, FACE_FLOATS);
            o += FACE_FLOATS;
        }
        return o;
    }

    // ------------------------------------------------------------------ queries

    /** Number of dampers: one for each key from the lowest up to the last damped one. */
    public int count() {
        return count;
    }

    /** How high a damper lifts off its strings, model units. */
    public float lift() {
        return LIFT;
    }

    /** Model-space x of the middle of the damper of key {@code key} (0 .. {@link #count()} - 1). */
    public float middle(int key) {
        return middles[key];
    }

    /** Quads sent per frame at most, every damper lifted (before the faces turned away are left out). */
    public int quadCount() {
        return count * FACES;
    }

    // ------------------------------------------------------------------ emitting

    /**
     * Sends the dampers through {@code out} (into the {@link #TEXTURE} layer), each lifted as
     * {@code lifts} has it; a damper at rest sends only its head. With {@code backFacesAway}, faces
     * turned away from the origin of the target space are left out (see {@link PianoMesh#emit}).
     */
    public void emit(PoseStack.Pose target, VertexConsumer out, float[] lifts, int light, int overlay, boolean backFacesAway) {
        Matrix4f m = target.pose();
        Matrix3f nm = target.normal();
        for (int f = 0; f < FACES; f++) nm.transform(NORMALS[f][0], NORMALS[f][1], NORMALS[f][2], normals[f]).normalize();
        for (int d = 0; d < count; d++) {
            float up = lifts[d] * LIFT;
            int faces = up > 0f ? FACES : HEAD_FACES;
            for (int f = 0, o = d * DAMPER_FLOATS; f < faces; f++, o += FACE_FLOATS) {
                Vector3f n = normals[f];
                m.transformPosition(corners[o], corners[o + 1] + up, corners[o + 2], a);
                if (backFacesAway && n.dot(a) > 0) continue;
                float u = f < HEAD_FACES ? woodU : feltU, v = f < HEAD_FACES ? woodV : feltV;
                out.addVertex(a.x, a.y, a.z, 0xFFFFFFFF, u, v, overlay, light, n.x, n.y, n.z);
                for (int c = o + 3; c < o + FACE_FLOATS; c += 3) {
                    m.transformPosition(corners[c], corners[c + 1] + up, corners[c + 2], a);
                    out.addVertex(a.x, a.y, a.z, 0xFFFFFFFF, u, v, overlay, light, n.x, n.y, n.z);
                }
            }
        }
    }
}
