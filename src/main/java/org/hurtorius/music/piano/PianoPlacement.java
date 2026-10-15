package org.hurtorius.music.piano;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.hurtorius.music.registry.MusicContent;
import org.jspecify.annotations.Nullable;

/**
 * Where a piano goes when a player uses its item, and what keeps it from going there. It stands
 * centred on the block aimed at: that cell takes the bench (the {@link PianoLayout#CONTROLLER}), the
 * keyboard faces the player and the case runs away from them. Where that spot has no room but a spot
 * one cell to either side has (beside a wall, say), the piano goes there, the block aimed at still
 * in its front row. Every cell of both layers must be free and nobody may stand where a cell
 * collides. The same check refuses a placement on the server (naming what is in the way), draws the
 * preview while a player holds the item (framing it), and builds the rest of a piano that a command
 * set as its controller alone.
 */
public final class PianoPlacement {
    /** How far the spots tried beside the one centred on the aim are, along the performer's right. */
    private static final int[] BESIDE = {-1, 1};

    /** What keeps a piano out of one of its cells. */
    public enum Kind {
        /** The cell is above or below the world's build limits. */
        HEIGHT,
        /** The cell is outside the world border. */
        BORDER,
        /** A block that cannot be replaced is in the cell. */
        BLOCK,
        /** An entity stands where the cell collides. */
        ENTITY
    }

    /**
     * What stands in a piano's way.
     *
     * @param kind   what kind of obstacle it is
     * @param cell   the piano's cell it is in
     * @param state  the block in that cell
     * @param entity the entity in the way, for {@link Kind#ENTITY}
     */
    public record Blocker(Kind kind, BlockPos cell, BlockState state, @Nullable Entity entity) {}

    /**
     * A spot for a piano.
     *
     * @param controller where its controller would be
     * @param facing     where its keyboard would face
     * @param blocker    the first thing in its way, null when it fits
     */
    public record Spot(BlockPos controller, Direction facing, @Nullable Blocker blocker) {
        public boolean fits() {
            return blocker == null;
        }
    }

    private PianoPlacement() {}

    /** The way a piano placed in this context faces: its keyboard toward the player. */
    public static Direction facing(BlockPlaceContext context) {
        return context.getHorizontalDirection().getOpposite();
    }

    /**
     * Where the item in {@code context} puts the piano: centred on the cell placed into, else beside it
     * where that fits; when nothing fits, the centred spot with what is in its way.
     */
    public static Spot find(BlockPlaceContext context) {
        Direction facing = facing(context);
        BlockPos aimed = context.getClickedPos();
        Spot centred = new Spot(aimed, facing, blocker(context.getLevel(), aimed, facing, context));
        if (centred.fits()) return centred;
        for (int shift : BESIDE) {
            BlockPos controller = aimed.relative(PianoLayout.right(facing), shift);
            if (blocker(context.getLevel(), controller, facing, context) == null) return new Spot(controller, facing, null);
        }
        return centred;
    }

    /**
     * The first thing in the way of a piano with its controller at {@code controller}, or null when it
     * fits: the cells first (build limits, world border, blocks), then the entities where a cell
     * collides. With a placing {@code context} each cell must be replaceable by the item; without one
     * (a controller already standing, the rest of the piano still to be built) a cell that already
     * holds its part of this piano counts as free.
     */
    public static @Nullable Blocker blocker(Level level, BlockPos controller, Direction facing, @Nullable BlockPlaceContext context) {
        for (int part = 0; part < PianoLayout.PARTS; part++) {
            BlockPos pos = PianoLayout.partPos(controller, facing, part);
            BlockState state = level.getBlockState(pos);
            if (context == null && isPart(state, facing, part)) continue;
            if (level.isOutsideBuildHeight(pos)) return new Blocker(Kind.HEIGHT, pos, state, null);
            if (!level.getWorldBorder().isWithinBounds(pos)) return new Blocker(Kind.BORDER, pos, state, null);
            if (!(context == null ? state.canBeReplaced() : state.canBeReplaced(context))) return new Blocker(Kind.BLOCK, pos, state, null);
        }
        for (int part = 0; part < PianoLayout.PARTS; part++) {
            BlockPos pos = PianoLayout.partPos(controller, facing, part);
            BlockState state = level.getBlockState(pos);
            if (context == null && isPart(state, facing, part)) continue;
            Entity entity = standingIn(level, PianoShapes.collision(part, facing).move(pos));
            if (entity != null) return new Blocker(Kind.ENTITY, pos, state, entity);
        }
        return null;
    }

    /** True when {@code state} is the given part of a piano facing {@code facing}. */
    static boolean isPart(BlockState state, Direction facing, int part) {
        return state.is(MusicContent.GRAND_PIANO) && state.getValue(GrandPianoBlock.FACING) == facing && state.getValue(GrandPianoBlock.PART) == part;
    }

    /** The first entity that keeps blocks from being placed where {@code shape} is (as the game's own placing check counts them), or null. */
    private static @Nullable Entity standingIn(Level level, VoxelShape shape) {
        if (shape.isEmpty()) return null;
        for (Entity entity : level.getEntities((Entity) null, shape.bounds())) {
            if (!entity.isRemoved() && entity.blocksBuilding && Shapes.joinIsNotEmpty(shape, Shapes.create(entity.getBoundingBox()), BooleanOp.AND)) return entity;
        }
        return null;
    }

    /**
     * Why a piano cannot go where {@code blocker} is, in plain words for {@code to} (the player placing
     * it, or null for anyone): what is in the way. Short enough for the action bar, which does not wrap;
     * the room the piano takes is on its item and in the outline drawn while it is held.
     */
    public static Component reason(Blocker blocker, @Nullable Entity to) {
        return switch (blocker.kind()) {
            case HEIGHT -> Component.translatable("music.message.no_room.height");
            case BORDER -> Component.translatable("music.message.no_room.border");
            case BLOCK -> Component.translatable("music.message.no_room", blocker.state().getBlock().getName());
            case ENTITY -> blocker.entity() == to && to != null ? Component.translatable("music.message.in_the_way.you")
                : Component.translatable("music.message.in_the_way", blocker.entity() == null ? Component.empty() : blocker.entity().getDisplayName());
        };
    }

    /** Tells {@code player} why the piano cannot go there; the outline their game draws frames what is in the way. */
    public static void refuse(ServerPlayer player, Blocker blocker) {
        player.displayClientMessage(reason(blocker, player), true);
    }
}
