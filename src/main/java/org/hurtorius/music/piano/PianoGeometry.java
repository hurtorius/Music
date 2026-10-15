package org.hurtorius.music.piano;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Hurtorius Grand model, read from {@code assets/music/geometry/grand_piano.json}.
 *
 * <p>Coordinates are model units: 16 units to the true-size metre ({@link PianoLayout} turns them
 * into blocks at its own scale). The keyboard faces -Z, bass is +X, Y is up. Group transforms
 * follow the kit's contract: world = parent x translate(pivot) x Rz x Ry x Rx (degrees, bind
 * rotation plus animation offset). Element vertices are relative to their group's pivot. Loaded
 * from the mod jar's classpath, so it works on dedicated servers.</p>
 *
 * <p>{@link #get()} is the kit as it stands in the game: its bench is moved to where the performer
 * sits (see {@link #placed}).</p>
 */
public final class PianoGeometry {
    public static final String RESOURCE = "/assets/music/geometry/grand_piano.json";
    public static final int KEY_COUNT = 88;
    public static final int LOWEST_MIDI = 21;
    /** The kit's bench: a root group of its own, which {@link #placed} moves toward the keys. */
    public static final String BENCH = "hurtorius_bench";
    /** The animation that closes the lid on the case, folding its prop and the music desk first. */
    public static final String LID_CLOSE = "lid_close";
    /** The kit's keybed: the board the keys lie on and sink toward, which shows in front of them. */
    public static final String KEYBED = "keybed";
    /** The kit's group of the keybed and the cheeks either side of the keyboard. */
    public static final String KEYBED_AND_CHEEKS = "keybed_and_cheeks";
    /** The kit's group of the pedal lyre, which holds the three pedals. */
    public static final String PEDAL_LYRE = "pedal_lyre";

    public record Group(int index, String name, int parent, Vector3f pivot, Vector3f rotation) {}

    /** positions: x,y,z per vertex (group-local units); uvs: u,v per vertex (0..1, top-left); triangles: vertex indices. */
    public record Element(String name, int group, int texture, float[] positions, float[] uvs, int[] triangles) {}

    /** times in seconds; values in degrees, added to the bind rotation of the named group. */
    public record Track(String group, float[] times, float[][] rotations) {
        public Vector3f sample(float time, Vector3f out) {
            if (times.length == 0) return out.zero();
            if (time <= times[0]) return out.set(rotations[0][0], rotations[0][1], rotations[0][2]);
            int last = times.length - 1;
            if (time >= times[last]) return out.set(rotations[last][0], rotations[last][1], rotations[last][2]);
            for (int i = 0; i < last; i++) {
                if (time <= times[i + 1]) {
                    float span = times[i + 1] - times[i];
                    float t = span <= 0 ? 1 : (time - times[i]) / span;
                    float[] a = rotations[i], b = rotations[i + 1];
                    return out.set(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t);
                }
            }
            return out.set(rotations[last][0], rotations[last][1], rotations[last][2]);
        }
    }

    public record Animation(String name, float length, Map<String, Track> tracks) {}

    /** One of the 88 keys. index 0 = A0 = MIDI 21. pivot and bounds are model-space units (bind pose). */
    public record Key(int index, int midi, String note, int group, boolean black, Vector3f pivot, Vector3f from, Vector3f to, Vector3f press) {
        public Vector3f center() {
            return new Vector3f(from).add(to).mul(0.5f);
        }
    }

    private static volatile PianoGeometry instance;

    public final List<Group> groups;
    public final List<Element> elements;
    public final Map<String, Animation> animations;
    public final List<Key> keys;
    public final List<String> textures;
    /** How far the bench stands closer to the keys than the kit stands it, in model units (0 for the kit itself). */
    public final float benchShift;
    private final Map<String, Integer> groupIndex;

    private PianoGeometry(List<Group> groups, List<Element> elements, Map<String, Animation> animations, List<Key> keys, List<String> textures,
                          float benchShift) {
        this.groups = Collections.unmodifiableList(groups);
        this.elements = Collections.unmodifiableList(elements);
        this.animations = Collections.unmodifiableMap(animations);
        this.keys = Collections.unmodifiableList(keys);
        this.textures = Collections.unmodifiableList(textures);
        this.benchShift = benchShift;
        Map<String, Integer> index = new HashMap<>();
        for (Group group : groups) index.put(group.name(), group.index());
        this.groupIndex = index;
    }

    /** The kit as it stands in the game, with its bench where the performer sits. */
    public static PianoGeometry get() {
        PianoGeometry geometry = instance;
        if (geometry == null) {
            synchronized (PianoGeometry.class) {
                geometry = instance;
                if (geometry == null) {
                    try (InputStream in = PianoGeometry.class.getResourceAsStream(RESOURCE)) {
                        if (in == null) throw new IllegalStateException("Missing " + RESOURCE + " in the Music jar");
                        geometry = placed(parse(new InputStreamReader(in, StandardCharsets.UTF_8)));
                    } catch (IOException e) {
                        throw new IllegalStateException("Could not read " + RESOURCE, e);
                    }
                    instance = geometry;
                }
            }
        }
        return geometry;
    }

    /**
     * The kit with its bench stood where the performer sits at the game's scale. The kit stands the
     * bench for a person at a life-size grand, but a Minecraft player's arms are short for their
     * height, so the bench comes toward the keys until the performer's hips are
     * {@link PianoLayout#HIPS_TO_KEYS} pixels behind the white keys' front edge, sitting
     * {@link PianoLayout#HIPS_ON_BENCH} pixels behind the bench's own front edge. (Both, like
     * {@link PianoLayout#PIXEL}, are compile-time constants, so reading them here never waits on
     * {@code PianoLayout}, which builds itself from this geometry.)
     */
    public static PianoGeometry placed(PianoGeometry kit) {
        float keyFront = Float.POSITIVE_INFINITY;
        for (Key key : kit.keys) if (!key.black()) keyFront = Math.min(keyFront, key.from().z);
        int bench = kit.group(BENCH);
        float benchFront = kit.bounds(bench, null)[5];
        float hips = keyFront - PianoLayout.HIPS_TO_KEYS * PianoLayout.PIXEL;
        float shift = hips + PianoLayout.HIPS_ON_BENCH * PianoLayout.PIXEL - benchFront;
        List<Group> groups = new ArrayList<>(kit.groups);
        Group old = groups.get(bench);
        groups.set(bench, new Group(old.index(), old.name(), old.parent(), new Vector3f(old.pivot()).add(0f, 0f, shift), old.rotation()));
        return new PianoGeometry(groups, kit.elements, kit.animations, kit.keys, kit.textures, kit.benchShift + shift);
    }

    public static PianoGeometry parse(Reader reader) {
        JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
        List<Group> groups = new ArrayList<>();
        JsonArray groupArray = root.getAsJsonArray("groups");
        for (int i = 0; i < groupArray.size(); i++) {
            JsonObject g = groupArray.get(i).getAsJsonObject();
            groups.add(new Group(i, g.get("name").getAsString(), g.get("parent").getAsInt(), vec(g.getAsJsonArray("pivot")), vec(g.getAsJsonArray("rotation"))));
        }
        List<Element> elements = new ArrayList<>();
        for (JsonElement el : root.getAsJsonArray("elements")) {
            JsonObject e = el.getAsJsonObject();
            elements.add(new Element(e.get("name").getAsString(), e.get("group").getAsInt(), e.get("texture").getAsInt(),
                floats(e.getAsJsonArray("positions")), floats(e.getAsJsonArray("uvs")), ints(e.getAsJsonArray("triangles"))));
        }
        Map<String, Animation> animations = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("animations").entrySet()) {
            JsonObject a = entry.getValue().getAsJsonObject();
            Map<String, Track> tracks = new HashMap<>();
            for (Map.Entry<String, JsonElement> t : a.getAsJsonObject("tracks").entrySet()) {
                JsonArray frames = t.getValue().getAsJsonArray();
                float[] times = new float[frames.size()];
                float[][] rotations = new float[frames.size()][];
                for (int i = 0; i < frames.size(); i++) {
                    JsonArray f = frames.get(i).getAsJsonArray();
                    times[i] = f.get(0).getAsFloat();
                    rotations[i] = new float[]{f.get(1).getAsFloat(), f.get(2).getAsFloat(), f.get(3).getAsFloat()};
                }
                tracks.put(t.getKey(), new Track(t.getKey(), times, rotations));
            }
            animations.put(entry.getKey(), new Animation(entry.getKey(), a.get("length").getAsFloat(), Collections.unmodifiableMap(tracks)));
        }
        List<Key> keys = new ArrayList<>();
        JsonArray keyArray = root.getAsJsonArray("keys");
        for (int i = 0; i < keyArray.size(); i++) {
            JsonObject k = keyArray.get(i).getAsJsonObject();
            keys.add(new Key(i, k.get("midi").getAsInt(), k.get("note").getAsString(), k.get("group").getAsInt(), k.get("black").getAsBoolean(),
                vec(k.getAsJsonArray("pivot")), vec(k.getAsJsonArray("from")), vec(k.getAsJsonArray("to")), vec(k.getAsJsonArray("press"))));
        }
        if (keys.size() != KEY_COUNT) throw new IllegalStateException("Expected 88 keys, found " + keys.size());
        for (int i = 0; i < KEY_COUNT; i++) {
            if (keys.get(i).midi() != LOWEST_MIDI + i) throw new IllegalStateException("Key " + i + " is not MIDI " + (LOWEST_MIDI + i));
        }
        List<String> textures = new ArrayList<>();
        for (JsonElement t : root.getAsJsonArray("textures")) textures.add(t.getAsString());
        return new PianoGeometry(groups, elements, animations, keys, textures, 0f);
    }

    public int group(String name) {
        Integer index = groupIndex.get(name);
        if (index == null) throw new IllegalArgumentException("No group " + name);
        return index;
    }

    public boolean hasGroup(String name) {
        return groupIndex.containsKey(name);
    }

    /** True when {@code group} is {@code ancestor} or one of its descendants. */
    public boolean isWithin(int group, int ancestor) {
        for (int g = group; g >= 0; g = groups.get(g).parent()) {
            if (g == ancestor) return true;
        }
        return false;
    }

    /**
     * Computes every group's model-space transform.
     *
     * @param offsets extra rotation in degrees per group index (may be null entries); added to the bind rotation.
     * @param out     one matrix per group, written in place.
     */
    public void transforms(Vector3f[] offsets, Matrix4f[] out) {
        for (int i = 0; i < groups.size(); i++) {
            Group g = groups.get(i);
            float rx = g.rotation().x, ry = g.rotation().y, rz = g.rotation().z;
            if (offsets != null && offsets[i] != null) {
                rx += offsets[i].x;
                ry += offsets[i].y;
                rz += offsets[i].z;
            }
            // Groups are listed parent-first in the kit, so a parent's matrix is always ready. Composed in
            // place (JOML post-multiplies) so the renderer can call this every frame without garbage.
            Matrix4f m = g.parent() >= 0 ? out[i].set(out[g.parent()]) : out[i].identity();
            m.translate(g.pivot())
                .rotateZ((float) Math.toRadians(rz)).rotateY((float) Math.toRadians(ry)).rotateX((float) Math.toRadians(rx));
        }
    }

    public Matrix4f[] newTransforms() {
        Matrix4f[] matrices = new Matrix4f[groups.size()];
        for (int i = 0; i < matrices.length; i++) matrices[i] = new Matrix4f();
        return matrices;
    }

    /**
     * Rotation offsets per group index (see {@link #transforms}) that pose the kit as {@code animation}
     * does at {@code time} seconds; groups the animation does not move get none.
     */
    public Vector3f[] pose(String animation, float time) {
        Animation clip = animations.get(animation);
        if (clip == null) throw new IllegalArgumentException("No animation " + animation);
        Vector3f[] offsets = new Vector3f[groups.size()];
        for (Track track : clip.tracks().values()) {
            if (hasGroup(track.group())) offsets[group(track.group())] = track.sample(time, new Vector3f());
        }
        return offsets;
    }

    /**
     * Model-space bounds {min x, y, z, max x, y, z} of the elements of {@code group} and the groups
     * below it, or of the whole kit when {@code group} is negative, posed with {@code offsets} (null
     * for the rest pose: lid open, desk up, keys and pedals at rest).
     */
    public float[] bounds(int group, Vector3f @Nullable [] offsets) {
        Matrix4f[] m = newTransforms();
        transforms(offsets, m);
        float[] b = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        Vector3f p = new Vector3f();
        for (Element e : elements) {
            if (group >= 0 && !isWithin(e.group(), group)) continue;
            float[] positions = e.positions();
            for (int i = 0; i < positions.length; i += 3) {
                m[e.group()].transformPosition(positions[i], positions[i + 1], positions[i + 2], p);
                b[0] = Math.min(b[0], p.x);
                b[1] = Math.min(b[1], p.y);
                b[2] = Math.min(b[2], p.z);
                b[3] = Math.max(b[3], p.x);
                b[4] = Math.max(b[4], p.y);
                b[5] = Math.max(b[5], p.z);
            }
        }
        if (b[0] > b[3]) throw new IllegalArgumentException("Group " + group + " has no elements");
        return b;
    }

    /** Rest-pose bounds of the group named {@code group} and the groups below it (see {@link #bounds(int, Vector3f[])}). */
    public float[] bounds(String group) {
        return bounds(group(group), null);
    }

    /** Height of the keybed's top, under the keys and in front of them, in model units. */
    public float keybedTop() {
        return elementBounds(KEYBED)[4];
    }

    /** Model-space bounds {min x, y, z, max x, y, z} of the one element named {@code name}, in the rest pose. */
    public float[] elementBounds(String name) {
        Matrix4f[] m = newTransforms();
        transforms(null, m);
        for (Element e : elements) {
            if (!e.name().equals(name)) continue;
            float[] b = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
            Vector3f p = new Vector3f();
            float[] positions = e.positions();
            for (int i = 0; i < positions.length; i += 3) {
                m[e.group()].transformPosition(positions[i], positions[i + 1], positions[i + 2], p);
                for (int a = 0; a < 3; a++) {
                    b[a] = Math.min(b[a], p.get(a));
                    b[a + 3] = Math.max(b[a + 3], p.get(a));
                }
            }
            return b;
        }
        throw new IllegalArgumentException("No element " + name);
    }

    private static Vector3f vec(JsonArray a) {
        return new Vector3f(a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat());
    }

    private static float[] floats(JsonArray a) {
        float[] f = new float[a.size()];
        for (int i = 0; i < f.length; i++) f[i] = a.get(i).getAsFloat();
        return f;
    }

    private static int[] ints(JsonArray a) {
        int[] f = new int[a.size()];
        for (int i = 0; i < f.length; i++) f[i] = a.get(i).getAsInt();
        return f;
    }
}
