package org.hurtorius.music.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.hurtorius.music.piano.Pedal;
import org.hurtorius.music.piano.PianoGeometry;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Hurtorius Grand as vertex data, built once from {@link PianoGeometry}.
 *
 * <p>Entity render types draw quads, each as the triangles {@code a, b, c} and {@code c, d, a}. So
 * two triangles of one face that share an edge become one quad that draws exactly them (half the
 * vertices of sending each alone); a triangle without such a partner is sent as {@code a, b, c, c}.
 * Every quad carries its face's flat normal (the kit winds faces outward, counter-clockwise).
 * Groups that never move are baked into model space up front. Animated groups (keys, pedals, lid,
 * lid prop, music desk and anything below them) keep group-local vertices and are placed with the
 * group transforms of the current frame, and drawn in their group's shade (a pressed key is darker).
 * The dampers, which the kit does not have, are {@link PianoDampers}. The one flush decal (the
 * wordmark) is lifted a hair off the fallboard so it never fights with it in the depth buffer.</p>
 *
 * <p>Emitting reuses scratch matrices, so call it from one thread (the render thread).</p>
 */
public final class PianoMesh {
    /** Floats per quad: normal (3), then x, y, z, u, v for each of the four corners. */
    static final int QUAD_FLOATS = 23;
    /** Two triangles pair into a quad only when their normals agree this closely (about 0.8 degrees). */
    static final float COPLANAR = 0.9999f;
    /** How far a flush decal is lifted along its normal, in model units. */
    static final float DECAL_LIFT = 0.03f;
    private static final int WHITE = 0xFFFFFFFF;
    private static volatile PianoMesh instance;

    public final PianoGeometry geometry;
    private final int textureCount;
    private final float[][] staticData;
    private final int[] animatedGroups;
    private final float[][][] animatedData;
    private final boolean[] animated;
    private final Vector3f min = new Vector3f(Float.POSITIVE_INFINITY), max = new Vector3f(Float.NEGATIVE_INFINITY);
    private final int triangles, staticTriangles, quads;

    private final Matrix4f position = new Matrix4f();
    private final Matrix3f normal = new Matrix3f(), groupNormal = new Matrix3f();
    private final Vector3f a = new Vector3f(), n = new Vector3f();

    private PianoMesh(PianoGeometry geometry) {
        this.geometry = geometry;
        this.textureCount = Math.max(1, geometry.textures.size());
        int groupCount = geometry.groups.size();
        this.animated = animatedGroups(geometry);
        Matrix4f[] bind = geometry.newTransforms();
        geometry.transforms(null, bind);

        List<List<float[]>> perTextureStatic = new ArrayList<>();
        for (int t = 0; t < textureCount; t++) perTextureStatic.add(new ArrayList<>());
        List<List<List<float[]>>> perGroup = new ArrayList<>();
        for (int g = 0; g < groupCount; g++) perGroup.add(null);
        int total = 0, still = 0, quadTotal = 0;
        for (PianoGeometry.Element element : geometry.elements) {
            int texture = element.texture();
            if (texture < 0 || texture >= textureCount) {
                throw new IllegalStateException("Element " + element.name() + " uses missing texture " + texture);
            }
            boolean moves = animated[element.group()];
            float[] data = quads(element, moves ? null : bind[element.group()]);
            int count = element.triangles().length / 3;
            total += count;
            quadTotal += data.length / QUAD_FLOATS;
            if (moves) {
                List<List<float[]>> lists = perGroup.get(element.group());
                if (lists == null) {
                    lists = new ArrayList<>();
                    for (int t = 0; t < textureCount; t++) lists.add(new ArrayList<>());
                    perGroup.set(element.group(), lists);
                }
                lists.get(texture).add(data);
            } else {
                perTextureStatic.get(texture).add(data);
                still += count;
            }
            includeBounds(element, bind[element.group()]);
        }
        this.triangles = total;
        this.staticTriangles = still;
        this.quads = quadTotal;
        this.staticData = new float[textureCount][];
        for (int t = 0; t < textureCount; t++) staticData[t] = concat(perTextureStatic.get(t));
        List<Integer> groups = new ArrayList<>();
        for (int g = 0; g < groupCount; g++) if (perGroup.get(g) != null) groups.add(g);
        this.animatedGroups = groups.stream().mapToInt(Integer::intValue).toArray();
        this.animatedData = new float[animatedGroups.length][textureCount][];
        for (int i = 0; i < animatedGroups.length; i++) {
            for (int t = 0; t < textureCount; t++) animatedData[i][t] = concat(perGroup.get(animatedGroups[i]).get(t));
        }
    }

    /** A mesh of any geometry: the game draws {@link #get()}; the tests also build meshes of parts of the kit, to time them on their own. */
    public static PianoMesh of(PianoGeometry geometry) {
        return new PianoMesh(geometry);
    }

    public static PianoMesh get() {
        PianoMesh mesh = instance;
        if (mesh == null) {
            synchronized (PianoMesh.class) {
                mesh = instance;
                if (mesh == null) instance = mesh = new PianoMesh(PianoGeometry.get());
            }
        }
        return mesh;
    }

    /**
     * Groups that move: the 88 keys, the three pedals, the lid on its hinge, the lid prop and the
     * music desk, plus anything parented to them.
     */
    public static boolean[] animatedGroups(PianoGeometry geometry) {
        List<Integer> roots = new ArrayList<>();
        for (PianoGeometry.Key key : geometry.keys) roots.add(key.group());
        for (Pedal pedal : Pedal.values()) roots.add(geometry.group(pedal.group()));
        roots.add(geometry.group(PianoPose.LID));
        roots.add(geometry.group(PianoPose.PROP));
        roots.add(geometry.group(PianoPose.DESK));
        boolean[] moves = new boolean[geometry.groups.size()];
        for (int g = 0; g < moves.length; g++) {
            for (int root : roots) {
                if (geometry.isWithin(g, root)) {
                    moves[g] = true;
                    break;
                }
            }
        }
        return moves;
    }

    private float[] quads(PianoGeometry.Element element, Matrix4f bake) {
        float[] p = element.positions(), uv = element.uvs();
        int[] corners = quadCorners(element);
        int count = corners.length / 4;
        float[] out = new float[count * QUAD_FLOATS];
        Vector3f lift = new Vector3f();
        if (element.name().contains("decal") && count > 0) {
            faceNormal(p, corners[0], corners[1], corners[2], lift).mul(DECAL_LIFT);
        }
        Matrix3f rotation = bake == null ? null : new Matrix3f(bake);
        Vector3f v = new Vector3f(), nn = new Vector3f();
        for (int q = 0; q < count; q++) {
            int o = q * QUAD_FLOATS;
            faceNormal(p, corners[q * 4], corners[q * 4 + 1], corners[q * 4 + 2], nn);
            if (rotation != null) rotation.transform(nn).normalize();
            out[o] = nn.x;
            out[o + 1] = nn.y;
            out[o + 2] = nn.z;
            for (int corner = 0; corner < 4; corner++) {
                int index = corners[q * 4 + corner];
                v.set(p[index * 3], p[index * 3 + 1], p[index * 3 + 2]).add(lift);
                if (bake != null) bake.transformPosition(v);
                int at = o + 3 + corner * 5;
                out[at] = v.x;
                out[at + 1] = v.y;
                out[at + 2] = v.z;
                out[at + 3] = uv[index * 2];
                out[at + 4] = uv[index * 2 + 1];
            }
        }
        return out;
    }

    /**
     * The element's triangles as quads, four vertex indices each. Triangle {@code a, b, c} pairs with
     * an unpaired triangle holding the edge {@code a -> c} (its own {@code c -> a} walked the other
     * way, so both face the same side) and the same normal: the quad {@code a, b, c, d} then draws
     * exactly the two of them. A triangle left alone becomes {@code a, b, c, c}.
     */
    public static int[] quadCorners(PianoGeometry.Element element) {
        float[] p = element.positions();
        int[] tri = element.triangles();
        int count = tri.length / 3;
        Vector3f[] normals = new Vector3f[count];
        Map<Long, Integer> edges = new HashMap<>();
        for (int t = 0; t < count; t++) {
            normals[t] = faceNormal(p, tri[t * 3], tri[t * 3 + 1], tri[t * 3 + 2], new Vector3f());
            for (int e = 0; e < 3; e++) edges.put(edge(tri[t * 3 + e], tri[t * 3 + (e + 1) % 3]), t);
        }
        boolean[] used = new boolean[count];
        int[] out = new int[count * 4];
        int n = 0;
        for (int t = 0; t < count; t++) {
            if (used[t]) continue;
            used[t] = true;
            int partner = -1, first = 0;
            for (int r = 0; r < 3 && partner < 0; r++) {
                int a = tri[t * 3 + r], c = tri[t * 3 + (r + 2) % 3];
                Integer other = edges.get(edge(a, c));
                if (other != null && !used[other] && normals[other].dot(normals[t]) > COPLANAR) {
                    partner = other;
                    first = r;
                }
            }
            int a = tri[t * 3 + first], b = tri[t * 3 + (first + 1) % 3], c = tri[t * 3 + (first + 2) % 3];
            out[n++] = a;
            out[n++] = b;
            out[n++] = c;
            int d = c;
            if (partner >= 0) {
                used[partner] = true;
                for (int k = 0; k < 3; k++) {
                    int corner = tri[partner * 3 + k];
                    if (corner != a && corner != c) d = corner;
                }
            }
            out[n++] = d;
        }
        return Arrays.copyOf(out, n);
    }

    private static long edge(int from, int to) {
        return (long) from << 32 | (to & 0xFFFFFFFFL);
    }

    /** Unit normal of triangle (i, j, k) by the right-hand rule; straight up for a degenerate triangle. */
    static Vector3f faceNormal(float[] p, int i, int j, int k, Vector3f out) {
        float ux = p[j * 3] - p[i * 3], uy = p[j * 3 + 1] - p[i * 3 + 1], uz = p[j * 3 + 2] - p[i * 3 + 2];
        float vx = p[k * 3] - p[i * 3], vy = p[k * 3 + 1] - p[i * 3 + 1], vz = p[k * 3 + 2] - p[i * 3 + 2];
        out.set(uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx);
        float length = out.length();
        return length < 1e-9f ? out.set(0, 1, 0) : out.div(length);
    }

    private void includeBounds(PianoGeometry.Element element, Matrix4f transform) {
        float[] p = element.positions();
        Vector3f v = new Vector3f();
        for (int i = 0; i < p.length; i += 3) {
            transform.transformPosition(v.set(p[i], p[i + 1], p[i + 2]));
            min.min(v);
            max.max(v);
        }
    }

    private static float[] concat(List<float[]> parts) {
        int size = 0;
        for (float[] part : parts) size += part.length;
        float[] out = new float[size];
        int at = 0;
        for (float[] part : parts) {
            System.arraycopy(part, 0, out, at, part.length);
            at += part.length;
        }
        return out;
    }

    // ------------------------------------------------------------------ queries

    public int textureCount() {
        return textureCount;
    }

    public int triangleCount() {
        return triangles;
    }

    public int staticTriangleCount() {
        return staticTriangles;
    }

    public int animatedTriangleCount() {
        return triangles - staticTriangles;
    }

    /** Quads sent per frame: pairs of triangles, and the triangles left over. */
    public int quadCount() {
        return quads;
    }

    public boolean isAnimated(int group) {
        return animated[group];
    }

    /** Smallest corner of the bind pose (lid open, desk up, keys and pedals at rest), model units. */
    public Vector3f min() {
        return new Vector3f(min);
    }

    /** Largest corner of the bind pose, model units. */
    public Vector3f max() {
        return new Vector3f(max);
    }

    // ------------------------------------------------------------------ emitting

    /**
     * Sends the quads of one texture through {@code out}, each moving group placed and shaded as
     * {@code pose} has it (resolved by the caller); with {@code backFacesAway}, quads that face away
     * from the origin of the target space are left out. When a block entity is drawn the camera sits
     * at that origin, and the piano's render types cull back faces anyway, so the picture is the same
     * with about half the vertices.
     *
     * @param target model space to the render target (the caller's pose stack entry)
     */
    public void emit(PoseStack.Pose target, VertexConsumer out, int texture, PianoPose pose, int light, int overlay, boolean backFacesAway) {
        put(target.pose(), target.normal(), staticData[texture], out, WHITE, light, overlay, backFacesAway);
        Matrix4f[] transforms = pose.transforms();
        int[] tints = pose.tints();
        for (int i = 0; i < animatedGroups.length; i++) {
            float[] data = animatedData[i][texture];
            if (data.length == 0) continue;
            Matrix4f group = transforms[animatedGroups[i]];
            position.set(target.pose()).mul(group);
            normal.set(target.normal()).mul(groupNormal.set(group));
            put(position, normal, data, out, tints[animatedGroups[i]], light, overlay, backFacesAway);
        }
    }

    private void put(Matrix4f m, Matrix3f nm, float[] data, VertexConsumer out, int colour, int light, int overlay, boolean backFacesAway) {
        for (int o = 0; o < data.length; o += QUAD_FLOATS) {
            nm.transform(data[o], data[o + 1], data[o + 2], n).normalize();
            m.transformPosition(data[o + 3], data[o + 4], data[o + 5], a);
            // A flat quad faces the viewer at the origin when its normal points back toward it.
            if (backFacesAway && n.dot(a) > 0) continue;
            out.addVertex(a.x, a.y, a.z, colour, data[o + 6], data[o + 7], overlay, light, n.x, n.y, n.z);
            for (int at = o + 8; at < o + QUAD_FLOATS; at += 5) {
                m.transformPosition(data[at], data[at + 1], data[at + 2], a);
                out.addVertex(a.x, a.y, a.z, colour, data[at + 3], data[at + 4], overlay, light, n.x, n.y, n.z);
            }
        }
    }
}
