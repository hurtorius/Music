package org.hurtorius.music.piano;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the piano sits in the world.
 *
 * <p>The model is a true-size grand at 16 units to the metre, drawn {@link #SCALE} times larger than
 * life. The Minecraft player is not proportioned like a person (shoulders a block wide, legs three
 * quarters of one, arms short for that), and next to that body a true-size piano reads as a toy. At
 * 1.75 the bench is a block wide, the key tops are a little above the seated player's elbows and
 * the open lid stands far above a standing player's head. The model stands on shorter legs than the
 * kit's (see {@code tools/build-piano-geometry.py}), so the rim is at a standing player's chest
 * rather than their eyes and a seated player's head is level with a standing one's; everything here
 * follows the model as it is. Sizes that belong to the player's body rather than to the piano are
 * written in pixels (a sixteenth of a block) and turned into model units with {@link #PIXEL}.</p>
 *
 * <p>The piano takes {@link #COLUMNS} x {@link #ROWS} cells on the floor in {@link #LAYERS} layers:
 * written down, because the cells are how a placed piano is saved (see {@link #COLUMNS}); the model
 * must fit them with at least {@link #MARGIN} to spare on every side and its lid closed (PianoLayoutTest
 * holds it to that). The raised lid swings up through {@link #LID_LAYERS} more layers above them,
 * which are not part of the piano (see {@code PianoShapes.lidRoom}). The {@link #CONTROLLER}, the
 * block that holds the block entity, is the block the player aims at to place it: the bench's cell,
 * the front cell of the lowest layer in the middle column ({@link #BENCH_COLUMN}), so the piano
 * stands centred on the aim. {@code facing} is the direction the keyboard faces, toward the player
 * who placed it. From the performer's seat, rows go away ("back"), columns go to the performer's
 * right (the treble side, column 0 on the bass side) and layers go up.</p>
 *
 * <p>Model space: keyboard toward -Z, bass toward +X. For facing NORTH the model axes equal world
 * axes and the model origin sits at the controller block's corner plus ({@link #ORIGIN_X}, 0,
 * {@link #ORIGIN_Z}) blocks, which centres the kit in its cells. Other facings rotate that layout
 * clockwise around the controller block's centre, the same way blockstate y-rotations do.</p>
 */
public final class PianoLayout {
    /** How much larger than life the piano is drawn; every model-to-block conversion goes through it. */
    public static final float SCALE = 1.75f;
    /** Blocks per model unit. */
    public static final float BLOCKS_PER_UNIT = SCALE / 16f;
    /** Model units per pixel (a sixteenth of a block): sizes of the player's body become model units with it. */
    public static final float PIXEL = 1f / SCALE;
    /**
     * How far the performer's hips sit behind the white keys' front edge, in pixels: near enough for
     * the player's short arms (ten of the player model's pixels) to play around middle C sitting
     * nearly upright, the forearms rising a little from elbows just below the keys. The bench stands
     * where the hips sit (see {@code PianoGeometry.placed}), which reads this while this class may
     * still be setting itself up from the model, so it and {@link #HIPS_ON_BENCH} stay compile-time constants.
     */
    public static final float HIPS_TO_KEYS = 9f;
    /** How far the hips sit behind the bench's front edge, in pixels: the performer perches on its front half. */
    public static final float HIPS_ON_BENCH = 1.5f;
    /**
     * How far behind its front edge a finger plays a key, in model units: half a pixel in, on the key's
     * head, so the hand on it leaves the rest of the key in sight going down. The key's sound comes
     * from there too.
     */
    public static final float TOUCH = 0.5f * PIXEL;
    /** Least room left between the piano (with its bench and raised lid) and the sides of its cells, in blocks. */
    public static final double MARGIN = 0.125;

    /** The whole kit as it stands at rest, the lid raised and the desk up, in model units. */
    private static final float[] OPEN = PianoGeometry.get().bounds(-1, null);

    /**
     * Cells across the piano. This, {@link #ROWS}, {@link #LAYERS}, {@link #CONTROLLER} and the order
     * of {@link #part} are how a placed piano is saved: each of its blocks keeps only its part number.
     * They are written down rather than worked out from the model, so a later tuning of the model or
     * of {@link #SCALE} can never renumber the cells of pianos already in worlds (the game cannot
     * update a mod block's state in a saved world). A piano that needs another grid needs a new block,
     * or a migration of the saved parts.
     */
    public static final int COLUMNS = 3;
    /** Cells from the front (the bench) to the tail; saved, like {@link #COLUMNS}. */
    public static final int ROWS = 5;
    /** Layers of cells the piano takes, everything it draws but the raised lid; saved, like {@link #COLUMNS}. */
    public static final int LAYERS = 2;
    public static final int CELLS_PER_LAYER = COLUMNS * ROWS;
    public static final int PARTS = CELLS_PER_LAYER * LAYERS;
    /**
     * The controller: the part that holds the block entity, the block a player aims at to place the
     * piano and the bench's cell (the front cell of the floor layer in the middle column). In the
     * middle, a mirrored structure keeps it in its own cell ({@link #mirroredPart}). Saved, like {@link #COLUMNS}.
     */
    public static final int CONTROLLER = part(COLUMNS / 2, 0, 0);
    /** Layers above the piano's own that the raised lid needs room in. */
    public static final int LID_LAYERS = layers(OPEN[4]) - LAYERS;
    /** Model origin inside the bass-side front cell (column 0), in blocks, for facing NORTH: the kit centred in the cells. */
    private static final double CORNER_X = 1 - (COLUMNS - width(OPEN[3] - OPEN[0])) / 2 - OPEN[3] * (double) BLOCKS_PER_UNIT;
    /** Model origin inside the controller block, in blocks, for facing NORTH: the kit centred in its cells. */
    public static final double ORIGIN_X = CORNER_X + column(CONTROLLER);
    public static final double ORIGIN_Z = (ROWS - width(OPEN[5] - OPEN[2])) / 2 - OPEN[2] * (double) BLOCKS_PER_UNIT;
    /** Height of the raised lid's top over the floor, in blocks. */
    public static final double LID_TOP = OPEN[4] * (double) BLOCKS_PER_UNIT;
    /** The bench as it stands (see {@code PianoGeometry.placed}), in model units. */
    private static final float[] BENCH = PianoGeometry.get().bounds(PianoGeometry.BENCH);
    /** The case from its underside to its rim, in model units. */
    private static final float[] CASE = PianoGeometry.get().bounds("rim_and_case");
    /**
     * Seat riding point in model units: on the bench top, across its middle,
     * {@link #HIPS_ON_BENCH} pixels behind its front edge, where the performer's hips sit.
     */
    public static final Vector3f SEAT = new Vector3f((BENCH[0] + BENCH[3]) * 0.5f, BENCH[4], BENCH[5] - HIPS_ON_BENCH * PIXEL);
    /** The middle of the bench's front edge, on its top, in model units: the seated performer's legs come down over it. */
    public static final Vector3f BENCH_EDGE = new Vector3f(SEAT.x, BENCH[4], BENCH[5]);
    /** Half the bench's width across the keyboard, in model units: how far the performer can slide along it. */
    public static final float BENCH_HALF_WIDTH = (BENCH[3] - BENCH[0]) * 0.5f;
    /** The column the bench and the seat stand in, in the front row: the {@link #CONTROLLER}'s (PianoLayoutTest holds it to that). */
    public static final int BENCH_COLUMN = -(int) Math.floor(CORNER_X + SEAT.x * (double) BLOCKS_PER_UNIT);

    private PianoLayout() {}

    /** Layers needed to hold everything up to {@code top} model units over the floor. */
    private static int layers(float top) {
        return (int) Math.ceil(width(top) - 1e-9);
    }

    private static double width(float units) {
        return units * (double) BLOCKS_PER_UNIT;
    }

    public static int column(int part) {
        return part % COLUMNS;
    }

    public static int row(int part) {
        return part / COLUMNS % ROWS;
    }

    /** 0 for the floor layer, counting up. */
    public static int layer(int part) {
        return part / CELLS_PER_LAYER;
    }

    /** The part at a cell of the floor layer. */
    public static int part(int column, int row) {
        return part(column, row, 0);
    }

    public static int part(int column, int row, int layer) {
        return (layer * ROWS + row) * COLUMNS + column;
    }

    /**
     * The part a cell becomes when its structure is mirrored: the layout is one-handed (treble to the
     * performer's right), so a mirrored piano stays playable only if each cell also swaps columns. The
     * {@link #CONTROLLER}, in the middle column, stays itself, so the block entity a structure saved
     * with it is loaded into the controller again.
     */
    public static int mirroredPart(int part) {
        return part(COLUMNS - 1 - column(part), row(part), layer(part));
    }

    /** Direction from the keyboard toward the tail; the direction the seated performer looks. */
    public static Direction back(Direction facing) {
        return facing.getOpposite();
    }

    /** The performer's right hand side (treble). */
    public static Direction right(Direction facing) {
        return back(facing).getClockWise();
    }

    public static BlockPos partPos(BlockPos controller, Direction facing, int part) {
        return cellPos(controller, facing, column(part), row(part), layer(part));
    }

    /**
     * Any cell in the piano's own frame, including the ones above it that the lid swings through and
     * those beside it (a column below 0 or from {@link #COLUMNS} on): columns count from the bass side,
     * rows from the front, layers from the floor.
     */
    public static BlockPos cellPos(BlockPos controller, Direction facing, int column, int row, int layer) {
        return controller.relative(right(facing), column - column(CONTROLLER)).relative(back(facing), row).above(layer);
    }

    /** Moves {@code out} to a part's cell without making a new position. */
    public static BlockPos.MutableBlockPos partPos(BlockPos.MutableBlockPos out, BlockPos controller, Direction facing, int part) {
        return out.set(controller).move(right(facing), column(part) - column(CONTROLLER)).move(back(facing), row(part)).move(Direction.UP, layer(part));
    }

    public static BlockPos controllerPos(BlockPos partPos, Direction facing, int part) {
        return partPos.relative(right(facing), column(CONTROLLER) - column(part)).relative(back(facing), -row(part)).below(layer(part));
    }

    /** Degrees for {@code Axis.YP.rotationDegrees}: the model's -Z must point along {@code facing}. */
    public static float yRotation(Direction facing) {
        return 180f - facing.toYRot();
    }

    /** Block-local transform from model units to the controller block's local block space. */
    public static Matrix4f modelToBlock(Direction facing, Matrix4f out) {
        return out.identity()
            .translate(0.5f, 0f, 0.5f)
            .rotateY((float) Math.toRadians(yRotation(facing)))
            .translate(-0.5f, 0f, -0.5f)
            .translate((float) ORIGIN_X, 0f, (float) ORIGIN_Z)
            .scale(BLOCKS_PER_UNIT);
    }

    /** World position of a model-space point. */
    public static Vec3 modelToWorld(BlockPos controller, Direction facing, float mx, float my, float mz) {
        Vector3f p = modelToBlock(facing, new Matrix4f()).transformPosition(new Vector3f(mx, my, mz));
        return new Vec3(controller.getX() + p.x, controller.getY() + p.y, controller.getZ() + p.z);
    }

    public static Vec3 modelToWorld(BlockPos controller, Direction facing, Vector3f model) {
        return modelToWorld(controller, facing, model.x, model.y, model.z);
    }

    /** A world position in the piano's model space (the inverse of {@link #modelToWorld}). */
    public static Vector3f worldToModel(BlockPos controller, Direction facing, Vec3 world) {
        return modelToBlock(facing, new Matrix4f()).invert()
            .transformPosition(new Vector3f((float) (world.x - controller.getX()), (float) (world.y - controller.getY()), (float) (world.z - controller.getZ())));
    }

    /** Where a key's sound comes from and where the hand touches it: the top of its head, {@link #TOUCH} behind its front edge. */
    public static Vec3 keyWorld(BlockPos controller, Direction facing, int keyIndex) {
        PianoGeometry.Key key = PianoGeometry.get().keys.get(keyIndex);
        return modelToWorld(controller, facing, (key.from().x + key.to().x) * 0.5f, key.to().y, key.from().z + TOUCH);
    }

    public static Vec3 seatWorld(BlockPos controller, Direction facing) {
        return modelToWorld(controller, facing, SEAT);
    }

    /**
     * The key a line of sight meets first, from {@code from} along {@code reach} (world space, as far
     * as it is long), or -1 when it meets none: each key as the box it fills at rest, so a black key,
     * standing over the white ones, is met before them.
     */
    public static int keyAt(BlockPos controller, Direction facing, Vec3 from, Vec3 reach) {
        Vector3f start = worldToModel(controller, facing, from), end = worldToModel(controller, facing, from.add(reach));
        int found = -1;
        float nearest = 1f;
        for (PianoGeometry.Key key : PianoGeometry.get().keys) {
            float[] box = {Math.min(key.from().x, key.to().x), Math.min(key.from().y, key.to().y), Math.min(key.from().z, key.to().z),
                Math.max(key.from().x, key.to().x), Math.max(key.from().y, key.to().y), Math.max(key.from().z, key.to().z)};
            float enters = PianoShapes.clip(box, start, end);
            if (enters < nearest) {
                nearest = enters;
                found = key.index();
            }
        }
        return found;
    }

    /**
     * Where a performer may stand up, best first: just in front of the bench (away from the keyboard),
     * then in front of the rest of the front row, then beside the front row on either side.
     */
    public static List<BlockPos> standingSpots(BlockPos controller, Direction facing) {
        List<BlockPos> spots = new ArrayList<>();
        spots.add(cellPos(controller, facing, BENCH_COLUMN, 0, 0).relative(facing));
        for (int column = 0; column < COLUMNS; column++) {
            if (column != BENCH_COLUMN) spots.add(cellPos(controller, facing, column, 0, 0).relative(facing));
        }
        spots.add(cellPos(controller, facing, -1, 0, 0));
        spots.add(cellPos(controller, facing, COLUMNS, 0, 0));
        return spots;
    }

    /** Yaw (Minecraft degrees) of a performer seated at the piano: looking at the keys. */
    public static float performerYaw(Direction facing) {
        return back(facing).toYRot();
    }

    /**
     * Centre of the instrument, used as the anchor for distance checks: across the middle of the
     * keyboard, halfway up the case and 14 units behind the keybed's front, where the case is widest.
     */
    public static Vec3 center(BlockPos controller, Direction facing) {
        return modelToWorld(controller, facing, 0f, (CASE[1] + CASE[4]) * 0.5f, 14f);
    }
}
