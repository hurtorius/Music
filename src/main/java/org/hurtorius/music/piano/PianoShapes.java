package org.hurtorius.music.piano;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Collision and outline shapes for the piano's cells, built from the model's solid parts: keybed,
 * the case outline, the three legs, the pedal lyre and the bench. Each part is the box its group of
 * the kit fills as the kit stands in the game (the case's height too; only its outline across is
 * written here), in model units for facing NORTH, placed with {@link PianoLayout}'s own
 * model-to-block transform, cut to each cell of every layer and rotated like every other block, so
 * every cell's shapes stay inside its own block, as the game's aiming and collision expect, and the
 * keys, rim and case can be aimed at and walked against wherever they are drawn. A face within a pixel
 * of a cell's side is moved onto it, so no cell keeps a sliver of a part that only just reaches into it.
 *
 * <p>The game's aiming walks the blocks along a line of sight and takes the first whose outline the
 * line meets, as the block aimed at; so each cell's outline ({@link #outline}) stays inside its own
 * block, and the cell aimed at is the one the point aimed at is in (a block placed against the case
 * goes beside the face clicked). What is drawn around the cell aimed at is its {@link #frame}: all
 * the piano has in that cell's column of cells, every layer of it, since the case stands across the
 * layers' seam (from its underside under the first layer's top to its rim in the second) and a
 * frame cut at the seam would show half of it.</p>
 *
 * <p>A floor cell the piano does not reach (beside the bench, behind the bentside, under the case
 * away from the legs) collides with nothing; its outline is a thin plate on the floor, so the cell
 * can still be aimed at and broken like the rest of the piano. Higher cells the piano does not reach
 * (over the bench, where the performer sits) have no shape at all, so aiming passes through them.</p>
 *
 * <p>The raised lid has no collision. What it needs is room: {@link #lidRoom()} is what the lid, its
 * prop and the music desk sweep through above the piano's own layers while the lid opens or closes.</p>
 */
public final class PianoShapes {
    /** Case outline (x, z) in model units, taken from the case_shell top face: straight bass side (+X), angled bentside. */
    static final float[][] CASE_OUTLINE = {
        {-12f, 5f}, {12f, 5f}, {12f, 29.94f}, {8.4f, 34f}, {3.6f, 34f}, {-0.96f, 30.52f}, {-4.8f, 23.85f}, {-7.68f, 15.44f}, {-12f, 10.8f}
    };
    /** The kit's groups the shapes are made of: the case (its height), keybed and cheeks, the legs (one box each), the pedal lyre. */
    static final String CASE = "rim_and_case", KEYBED = PianoGeometry.KEYBED_AND_CHEEKS, LEGS = "legs", LYRE = PianoGeometry.PEDAL_LYRE;
    /** The bench's seat and frame, one box; each other group of the bench (its legs) is a box of its own. */
    static final String BENCH_SEAT = "bench_seat", BENCH_FRAME = "bench_frame";
    /** Outline of a floor cell the piano does not reach: a plate one pixel thick on the floor. */
    static final VoxelShape FLOOR_PLATE = Shapes.box(0, 0, 0, 1, 1 / 16.0, 1);
    /** How close to a cell's side a face is moved onto it, in blocks: a pixel. */
    static final double SNAP = 1 / 16.0;
    /** Steps the lid's closing is measured in for the room it needs: the lid turns under two degrees a step. */
    static final int SWEEP_STEPS = 32;
    /** Width of the slices the moving parts are cut into across their turning, in model units, so their sweeps stay tight. */
    static final float SLICE = 0.5f;
    /** Added to every side of a swept box, in model units: more than a step's arc bulges past its ends. */
    static final float SWEEP_PAD = 0.1f;

    /**
     * One cell above the piano's own that the moving lid needs: its place in the piano's frame (see
     * {@link PianoLayout#cellPos}) and the part of it the lid, prop and desk sweep through.
     */
    public record RoomCell(int column, int row, int layer, Map<Direction, VoxelShape> shapes) {
        public VoxelShape shape(Direction facing) {
            return shapes.get(facing);
        }
    }

    private static final List<float[]> SOLID = new ArrayList<>();
    private static final List<float[]> LID_SWEPT, LID_SHUT;
    private static final List<Map<Direction, VoxelShape>> COLLISION = new ArrayList<>();
    private static final List<Map<Direction, VoxelShape>> OUTLINE = new ArrayList<>();
    private static final List<Map<Direction, VoxelShape>> FRAME = new ArrayList<>();
    private static final List<RoomCell> LID_ROOM = new ArrayList<>();

    static {
        PianoGeometry geometry = PianoGeometry.get();
        float[] shell = geometry.bounds(CASE);
        SOLID.add(geometry.bounds(KEYBED));
        int legs = geometry.group(LEGS);
        for (PianoGeometry.Group group : geometry.groups) if (group.parent() == legs) SOLID.add(geometry.bounds(group.index(), null));
        // The lyre hangs from the keybed: solid up to the case's underside, with no gap under it.
        float[] lyre = geometry.bounds(LYRE);
        lyre[4] = Math.max(lyre[4], shell[1]);
        SOLID.add(lyre);
        // The case: one slab per half unit of depth, spanning the outline's width at that depth.
        for (float z = 5f; z < 34f; z += 0.5f) {
            float[] span = outlineSpan(z + 0.25f);
            if (span != null) SOLID.add(new float[]{span[0], shell[1], z, span[1], shell[4], z + 0.5f});
        }
        List<float[]> withBench = new ArrayList<>(SOLID);
        withBench.addAll(bench(geometry));
        List<VoxelShape> cells = cut(withBench, 0, PianoLayout.LAYERS);
        for (int part = 0; part < PianoLayout.PARTS; part++) {
            VoxelShape cell = cells.get(part);
            VoxelShape outline = cell.isEmpty() && PianoLayout.layer(part) == 0 ? FLOOR_PLATE : cell;
            COLLISION.add(byFacing(cell));
            OUTLINE.add(byFacing(outline));
            FRAME.add(byFacing(cell.isEmpty() ? outline : column(cells, part)));
        }

        float length = geometry.animations.get(PianoGeometry.LID_CLOSE).length();
        List<Integer> moving = moving(geometry);
        List<float[]> room = sweep(geometry, moving, 0f, length, SWEEP_STEPS);
        List<VoxelShape> above = pixels(room, PianoLayout.LAYERS, PianoLayout.LAYERS + PianoLayout.LID_LAYERS);
        for (int i = 0; i < above.size(); i++) {
            if (above.get(i).isEmpty()) continue;
            int column = i % PianoLayout.COLUMNS, row = i / PianoLayout.COLUMNS % PianoLayout.ROWS;
            LID_ROOM.add(new RoomCell(column, row, PianoLayout.LAYERS + i / PianoLayout.CELLS_PER_LAYER, byFacing(above.get(i))));
        }
        List<Integer> lid = List.of(geometry.group("lid_hinge"));
        LID_SWEPT = sweep(geometry, lid, 0f, length, 1);
        LID_SHUT = sweep(geometry, lid, length, length, 1);
    }

    private PianoShapes() {}

    /** The bench as it stands (see {@link PianoGeometry#placed}): its seat and frame as one box, then each of its legs. */
    private static List<float[]> bench(PianoGeometry geometry) {
        float[] seat = geometry.bounds(BENCH_SEAT), frame = geometry.bounds(BENCH_FRAME);
        for (int a = 0; a < 3; a++) {
            seat[a] = Math.min(seat[a], frame[a]);
            seat[a + 3] = Math.max(seat[a + 3], frame[a + 3]);
        }
        List<float[]> boxes = new ArrayList<>(List.of(seat));
        int bench = geometry.group(PianoGeometry.BENCH);
        for (PianoGeometry.Group group : geometry.groups) {
            if (group.parent() == bench && !group.name().equals(BENCH_SEAT) && !group.name().equals(BENCH_FRAME)) boxes.add(geometry.bounds(group.index(), null));
        }
        return boxes;
    }

    /** Everything the piano has in the column of cells {@code part} stands in, every layer of it, in that part's block space. */
    private static VoxelShape column(List<VoxelShape> cells, int part) {
        VoxelShape column = Shapes.empty();
        for (int layer = 0; layer < PianoLayout.LAYERS; layer++) {
            VoxelShape cell = cells.get(PianoLayout.part(PianoLayout.column(part), PianoLayout.row(part), layer));
            if (!cell.isEmpty()) column = Shapes.joinUnoptimized(column, cell.move(0, layer - PianoLayout.layer(part), 0), BooleanOp.OR);
        }
        return column.optimize();
    }

    /** What a cell collides with: empty where the piano does not reach. */
    public static VoxelShape collision(int part, Direction facing) {
        return COLLISION.get(part).get(facing);
    }

    /** The cell's outline for aiming and breaking, inside its own block: never empty on the floor, empty above it where the piano does not reach. */
    public static VoxelShape outline(int part, Direction facing) {
        return OUTLINE.get(part).get(facing);
    }

    /**
     * What is drawn around the cell when it is aimed at, in its block space: what the piano has in the
     * cell's whole column of cells (see the class description), so it reaches into the cell above or
     * below it; the cell's own outline where the piano does not reach it.
     */
    public static VoxelShape frame(int part, Direction facing) {
        return FRAME.get(part).get(facing);
    }

    /** The cells above the piano the lid, its prop and the music desk sweep through as the lid opens or closes. */
    public static List<RoomCell> lidRoom() {
        return Collections.unmodifiableList(LID_ROOM);
    }

    /**
     * How far along the segment from {@code from} to {@code to} (model units) it first enters the
     * instrument as it is drawn, as a share of its length, or 1 when it never does: the case, keybed,
     * legs and lyre, and the lid, with the room it swings through when {@code lidOpen}. The bench and
     * its performer are left out.
     */
    public static float clip(Vector3fc from, Vector3fc to, boolean lidOpen) {
        float nearest = 1f;
        for (float[] box : SOLID) nearest = Math.min(nearest, clip(box, from, to));
        for (float[] box : lidOpen ? LID_SWEPT : LID_SHUT) nearest = Math.min(nearest, clip(box, from, to));
        return nearest;
    }

    /** Where a segment enters a box (slab test), as a share of its length; 1 when it misses, 0 when it starts inside. */
    static float clip(float[] box, Vector3fc from, Vector3fc to) {
        float enter = 0f, leave = 1f;
        for (int axis = 0; axis < 3; axis++) {
            float start = from.get(axis), delta = to.get(axis) - start;
            if (Math.abs(delta) < 1e-7f) {
                if (start < box[axis] || start > box[axis + 3]) return 1f;
                continue;
            }
            float a = (box[axis] - start) / delta, b = (box[axis + 3] - start) / delta;
            enter = Math.max(enter, Math.min(a, b));
            leave = Math.min(leave, Math.max(a, b));
            if (enter > leave) return 1f;
        }
        return enter;
    }

    private static Map<Direction, VoxelShape> byFacing(VoxelShape north) {
        Map<Direction, VoxelShape> byFacing = new EnumMap<>(Direction.class);
        byFacing.putAll(Shapes.rotateHorizontal(north));
        return byFacing;
    }

    /**
     * Model-space boxes cut into every cell from layer {@code fromLayer} up to (not including)
     * {@code toLayer}, for facing NORTH, each in its own cell's block space; listed as parts are,
     * column fastest, then row, then layer.
     */
    private static List<VoxelShape> cut(List<float[]> boxes, int fromLayer, int toLayer) {
        Matrix4f toBlock = PianoLayout.modelToBlock(Direction.NORTH, new Matrix4f());
        List<double[]> placed = new ArrayList<>();
        for (float[] b : boxes) {
            Vector3f min = toBlock.transformPosition(new Vector3f(b[0], b[1], b[2]));
            Vector3f max = toBlock.transformPosition(new Vector3f(b[3], b[4], b[5]));
            placed.add(new double[]{snap(Math.min(min.x, max.x)), snap(Math.min(min.y, max.y)), snap(Math.min(min.z, max.z)),
                snap(Math.max(min.x, max.x)), snap(Math.max(min.y, max.y)), snap(Math.max(min.z, max.z))});
        }
        List<VoxelShape> result = new ArrayList<>();
        for (int layer = fromLayer; layer < toLayer; layer++) {
            for (int row = 0; row < PianoLayout.ROWS; row++) {
                for (int column = 0; column < PianoLayout.COLUMNS; column++) {
                    // For facing NORTH a cell's corner is a block west of the controller's per column it is to its treble side.
                    double cx = PianoLayout.column(PianoLayout.CONTROLLER) - column, cy = layer, cz = row;
                    VoxelShape shape = Shapes.empty();
                    for (double[] b : placed) {
                        double x0 = Math.max(0, b[0] - cx), x1 = Math.min(1, b[3] - cx);
                        double y0 = Math.max(0, b[1] - cy), y1 = Math.min(1, b[4] - cy);
                        double z0 = Math.max(0, b[2] - cz), z1 = Math.min(1, b[5] - cz);
                        if (x1 - x0 < 1e-6 || y1 - y0 < 1e-6 || z1 - z0 < 1e-6) continue;
                        shape = Shapes.joinUnoptimized(shape, Shapes.box(x0, y0, z0, x1, y1, z1), BooleanOp.OR);
                    }
                    result.add(shape.optimize());
                }
            }
        }
        return result;
    }

    /**
     * Like {@link #cut}, but every box is grown out to whole pixels: shapes of the many small boxes
     * a sweep makes, which stay cheap to build and to test against other shapes.
     */
    private static List<VoxelShape> pixels(List<float[]> boxes, int fromLayer, int toLayer) {
        Matrix4f toBlock = PianoLayout.modelToBlock(Direction.NORTH, new Matrix4f());
        int columns = PianoLayout.COLUMNS, rows = PianoLayout.ROWS, layers = toLayer - fromLayer;
        // One bit per pixel over all the cells: x counts from the treble side's outer edge, (columns - 1 - c)
        // blocks west of the controller's west side, c being the controller's column.
        int treble = columns - 1 - PianoLayout.column(PianoLayout.CONTROLLER);
        int sx = columns * 16, sy = layers * 16, sz = rows * 16;
        boolean[] full = new boolean[sx * sy * sz];
        for (float[] b : boxes) {
            Vector3f min = toBlock.transformPosition(new Vector3f(b[0], b[1], b[2]));
            Vector3f max = toBlock.transformPosition(new Vector3f(b[3], b[4], b[5]));
            int x0 = Math.max(0, (int) Math.floor((Math.min(min.x, max.x) + treble) * 16)), x1 = Math.min(sx, (int) Math.ceil((Math.max(min.x, max.x) + treble) * 16));
            int y0 = Math.max(0, (int) Math.floor((Math.min(min.y, max.y) - fromLayer) * 16)), y1 = Math.min(sy, (int) Math.ceil((Math.max(min.y, max.y) - fromLayer) * 16));
            int z0 = Math.max(0, (int) Math.floor(Math.min(min.z, max.z) * 16)), z1 = Math.min(sz, (int) Math.ceil(Math.max(min.z, max.z) * 16));
            for (int y = y0; y < y1; y++) for (int z = z0; z < z1; z++) for (int x = x0; x < x1; x++) full[(y * sz + z) * sx + x] = true;
        }
        List<VoxelShape> result = new ArrayList<>();
        for (int layer = 0; layer < layers; layer++) {
            for (int row = 0; row < rows; row++) {
                for (int column = 0; column < columns; column++) {
                    // A column's west side is (columns - 1 - column) blocks east of the treble side's outer edge.
                    int ox = (columns - 1 - column) * 16, oy = layer * 16, oz = row * 16;
                    VoxelShape shape = Shapes.empty();
                    for (int y = 0; y < 16; y++) {
                        // Runs along x, each grown along z while the next rows repeat it.
                        boolean[][] used = new boolean[16][16];
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                if (used[z][x] || !full[((oy + y) * sz + oz + z) * sx + ox + x]) continue;
                                int xEnd = x;
                                while (xEnd < 16 && !used[z][xEnd] && full[((oy + y) * sz + oz + z) * sx + ox + xEnd]) xEnd++;
                                int zEnd = z + 1;
                                while (zEnd < 16 && run(full, used, sx, sz, ox, oy + y, oz + zEnd, zEnd, x, xEnd)) zEnd++;
                                for (int zz = z; zz < zEnd; zz++) for (int xx = x; xx < xEnd; xx++) used[zz][xx] = true;
                                shape = Shapes.joinUnoptimized(shape, Shapes.box(x / 16.0, y / 16.0, z / 16.0, xEnd / 16.0, (y + 1) / 16.0, zEnd / 16.0), BooleanOp.OR);
                            }
                        }
                    }
                    result.add(shape.optimize());
                }
            }
        }
        return result;
    }

    /** True when row {@code z} of a cell repeats the run from {@code x} to {@code xEnd}, none of it used yet. */
    private static boolean run(boolean[] full, boolean[][] used, int sx, int sz, int ox, int y, int z, int localZ, int x, int xEnd) {
        for (int xx = x; xx < xEnd; xx++) if (used[localZ][xx] || !full[(y * sz + z) * sx + ox + xx]) return false;
        return true;
    }

    /** A block coordinate within a pixel of a cell's side, moved onto it. */
    private static double snap(double v) {
        double side = Math.rint(v);
        return Math.abs(v - side) < SNAP ? side : v;
    }

    /** x range inside the case outline at depth z, or null outside it. */
    static float[] outlineSpan(float z) {
        float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY;
        int n = CASE_OUTLINE.length;
        for (int i = 0; i < n; i++) {
            float[] a = CASE_OUTLINE[i], b = CASE_OUTLINE[(i + 1) % n];
            if ((a[1] <= z && b[1] >= z) || (b[1] <= z && a[1] >= z)) {
                if (Math.abs(b[1] - a[1]) < 1e-6) {
                    min = Math.min(min, Math.min(a[0], b[0]));
                    max = Math.max(max, Math.max(a[0], b[0]));
                } else {
                    float x = a[0] + (b[0] - a[0]) * (z - a[1]) / (b[1] - a[1]);
                    min = Math.min(min, x);
                    max = Math.max(max, x);
                }
            }
        }
        return min <= max ? new float[]{min, max} : null;
    }

    /** The groups the lid's closing animation moves: the lid on its hinge, its prop and the music desk. */
    private static List<Integer> moving(PianoGeometry geometry) {
        List<Integer> groups = new ArrayList<>();
        for (PianoGeometry.Track track : geometry.animations.get(PianoGeometry.LID_CLOSE).tracks().values()) {
            if (geometry.hasGroup(track.group())) groups.add(geometry.group(track.group()));
        }
        return groups;
    }

    /**
     * Boxes in model units holding everything the given groups (and the groups below them) pass
     * through between {@code from} and {@code to} seconds of the lid's closing, measured in
     * {@code steps} steps. Each element is cut into slices across the axis its group turns about;
     * a slice's corners travel on arcs of under a quarter turn, which stay inside the box of where
     * they start and end a step.
     */
    static List<float[]> sweep(PianoGeometry geometry, List<Integer> roots, float from, float to, int steps) {
        PianoGeometry.Animation closing = geometry.animations.get(PianoGeometry.LID_CLOSE);
        Matrix4f[][] poses = new Matrix4f[steps + 1][];
        for (int i = 0; i <= steps; i++) {
            poses[i] = geometry.newTransforms();
            geometry.transforms(geometry.pose(PianoGeometry.LID_CLOSE, from + (to - from) * i / steps), poses[i]);
        }
        List<float[]> boxes = new ArrayList<>();
        for (int root : roots) {
            int turning = turningAxis(closing.tracks().get(geometry.groups.get(root).name()));
            for (PianoGeometry.Element element : geometry.elements) {
                if (!geometry.isWithin(element.group(), root)) continue;
                for (float[] slice : slices(element, turning)) {
                    float[] last = null;
                    for (int i = 0; i < steps; i++) {
                        float[] box = swept(slice, poses[i][element.group()], poses[i + 1][element.group()]);
                        if (last == null || !Arrays.equals(box, last)) boxes.add(box);
                        last = box;
                    }
                }
            }
        }
        return boxes;
    }

    /** The axis (0 x, 1 y, 2 z) a track turns its group about the most. */
    private static int turningAxis(PianoGeometry.Track track) {
        float[] first = track.rotations()[0];
        float[] turn = new float[3];
        for (float[] r : track.rotations()) for (int a = 0; a < 3; a++) turn[a] = Math.max(turn[a], Math.abs(r[a] - first[a]));
        return turn[0] >= turn[1] && turn[0] >= turn[2] ? 0 : turn[1] >= turn[2] ? 1 : 2;
    }

    /**
     * An element cut into slices {@link #SLICE} wide along its longest group-local axis other than
     * {@code turning}, each as the group-local box {minX, minY, minZ, maxX, maxY, maxZ} of the
     * element's triangles within it.
     */
    private static List<float[]> slices(PianoGeometry.Element element, int turning) {
        float[] p = element.positions();
        float[] lo = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
        float[] hi = {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        for (int i = 0; i < p.length; i += 3) {
            for (int a = 0; a < 3; a++) {
                lo[a] = Math.min(lo[a], p[i + a]);
                hi[a] = Math.max(hi[a], p[i + a]);
            }
        }
        int along = -1;
        for (int a = 0; a < 3; a++) if (a != turning && (along < 0 || hi[a] - lo[a] > hi[along] - lo[along])) along = a;
        List<float[]> slices = new ArrayList<>();
        int count = Math.max(1, (int) Math.ceil((hi[along] - lo[along]) / SLICE));
        int[] t = element.triangles();
        for (int s = 0; s < count; s++) {
            float a0 = lo[along] + (hi[along] - lo[along]) * s / count, a1 = lo[along] + (hi[along] - lo[along]) * (s + 1) / count;
            float[] box = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
            for (int i = 0; i < t.length; i += 3) {
                List<float[]> polygon = new ArrayList<>(List.of(vertex(p, t[i]), vertex(p, t[i + 1]), vertex(p, t[i + 2])));
                polygon = clipAbove(polygon, along, a0, 1f);
                polygon = clipAbove(polygon, along, a1, -1f);
                for (float[] v : polygon) {
                    for (int a = 0; a < 3; a++) {
                        box[a] = Math.min(box[a], v[a]);
                        box[a + 3] = Math.max(box[a + 3], v[a]);
                    }
                }
            }
            if (box[0] <= box[3]) slices.add(box);
        }
        return slices;
    }

    private static float[] vertex(float[] positions, int index) {
        return new float[]{positions[index * 3], positions[index * 3 + 1], positions[index * 3 + 2]};
    }

    /** The part of a convex polygon where {@code sign * (v[axis] - at) >= 0} (one Sutherland and Hodgman step). */
    private static List<float[]> clipAbove(List<float[]> polygon, int axis, float at, float sign) {
        List<float[]> out = new ArrayList<>();
        for (int i = 0; i < polygon.size(); i++) {
            float[] a = polygon.get(i), b = polygon.get((i + 1) % polygon.size());
            float da = sign * (a[axis] - at), db = sign * (b[axis] - at);
            if (da >= 0) out.add(a);
            if ((da >= 0) != (db >= 0)) {
                float k = da / (da - db);
                out.add(new float[]{a[0] + (b[0] - a[0]) * k, a[1] + (b[1] - a[1]) * k, a[2] + (b[2] - a[2]) * k});
            }
        }
        return out;
    }

    /** The model-space box around a group-local box at two poses, padded by {@link #SWEEP_PAD}. */
    private static float[] swept(float[] local, Matrix4f start, Matrix4f end) {
        float[] box = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        Vector3f corner = new Vector3f();
        for (Matrix4f pose : new Matrix4f[]{start, end}) {
            for (int c = 0; c < 8; c++) {
                pose.transformPosition((c & 1) == 0 ? local[0] : local[3], (c & 2) == 0 ? local[1] : local[4], (c & 4) == 0 ? local[2] : local[5], corner);
                for (int a = 0; a < 3; a++) {
                    box[a] = Math.min(box[a], corner.get(a) - SWEEP_PAD);
                    box[a + 3] = Math.max(box[a + 3], corner.get(a) + SWEEP_PAD);
                }
            }
        }
        return box;
    }
}
