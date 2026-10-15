package org.hurtorius.music.piano;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.hurtorius.music.registry.MusicContent;
import org.hurtorius.music.seat.PianoSeatEntity;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Hurtorius Grand: {@link PianoLayout#PARTS} cells in {@link PianoLayout#LAYERS} layers (see
 * {@link PianoLayout}). The {@link PianoLayout#CONTROLLER}, the bench's cell, holds the block entity;
 * every other part only gives collision and forwards interaction. It goes in centred on the block a
 * player aims at (see {@link PianoPlacement}); a player who tries to place it where it does not fit,
 * or where someone stands in its way, is told what is in the way. Where the raised lid would hit a
 * block, the piano is placed with its lid closed, and the player is told that too. A controller set
 * on its own (by a command) builds the rest of the piano, or goes away if it has no room.
 *
 * <p>A player breaking any part takes the whole piano apart as one thing: one crack, one burst of
 * splinters through the instrument, one piano dropped from the controller with everything set on it,
 * and the game's own steps for a block a player breaks (the stat, the tool's wear, other mods' break
 * events) for the part broken. The player seated at a piano cannot break it (they stand up first).
 * Anything else that removes a part (an explosion, a command) takes the other parts with it through
 * their shape updates, and only the controller drops.</p>
 */
public class GrandPianoBlock extends HorizontalDirectionalBlock implements EntityBlock {
    private static final Logger LOG = LoggerFactory.getLogger("Music");
    public static final MapCodec<GrandPianoBlock> CODEC = simpleCodec(GrandPianoBlock::new);
    public static final IntegerProperty PART = IntegerProperty.create("part", 0, PianoLayout.PARTS - 1);
    /** How high the raised lid reaches over the floor, in blocks, as the players are told it: rounded up to a tenth. */
    public static final String LID_HEIGHT = String.format(Locale.ROOT, "%.1f", Math.ceil(PianoLayout.LID_TOP * 10) / 10);
    /**
     * How the parts of a piano being built are set: sent to the players, but with no word to the
     * neighbours yet, which hear of every part once all of them stand (see {@link #assemble}).
     */
    static final int ASSEMBLE = Block.UPDATE_CLIENTS | Block.UPDATE_IMMEDIATE | Block.UPDATE_KNOWN_SHAPE;
    /**
     * How the other parts of a piano a player breaks are taken away: sent to the players, without a
     * drop and without a word to any cell, which would break the next part with a crack of its own;
     * the neighbours hear of every cell once all are gone (see {@link #dismantle}).
     */
    static final int DISMANTLE = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
    /** Splinters in the one burst a piano a player breaks throws: a few to each cell it fills. */
    public static final int SPLINTERS = 48;
    /** How fast the splinters start upward, before the game's own scatter: they hop and fall back over the piano rather than fly out sideways. */
    private static final double SPLINTER_HOP = 0.3;
    /** How far away players are told that a piano set by a command had no room, in blocks. */
    private static final double NOTICE_RANGE = 24;

    public GrandPianoBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(PART, PianoLayout.CONTROLLER));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PART);
    }

    public static BlockPos controllerOf(BlockPos pos, BlockState state) {
        return PianoLayout.controllerPos(pos, state.getValue(FACING), state.getValue(PART));
    }

    /** The controller block entity for any part, or null when the piano is incomplete. */
    public static @Nullable GrandPianoBlockEntity controller(BlockGetter level, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof GrandPianoBlock)) return null;
        return level.getBlockEntity(controllerOf(pos, state)) instanceof GrandPianoBlockEntity piano ? piano : null;
    }

    /** True when {@code player} sits on the bench of the piano the block at {@code pos} is a part of. */
    public static boolean isSeatedAt(Player player, BlockGetter level, BlockPos pos) {
        if (!(player.getVehicle() instanceof PianoSeatEntity seat)) return false;
        BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof GrandPianoBlock && controllerOf(pos, state).equals(seat.anchor());
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = PianoPlacement.facing(context);
        PianoPlacement.Blocker blocker = PianoPlacement.blocker(context.getLevel(), context.getClickedPos(), facing, context);
        if (blocker != null) {
            // Told once, on the server; the placing player's own game only predicts.
            if (context.getPlayer() instanceof ServerPlayer player) PianoPlacement.refuse(player, blocker);
            return null;
        }
        return defaultBlockState().setValue(FACING, facing).setValue(PART, PianoLayout.CONTROLLER);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (state.getValue(PART) != PianoLayout.CONTROLLER) return;
        Direction facing = state.getValue(FACING);
        if (level.isClientSide()) {
            // The placing player's game draws its own piano at once, before the server's arrives: with
            // the lid down where it has no room, as the server places it, so it never swings through a ceiling.
            if (level.getBlockEntity(pos) instanceof GrandPianoBlockEntity piano && piano.settings().lidOpen() && !hasLidRoom(level, pos, facing)) {
                piano.shutLid();
            }
            return;
        }
        assemble(level, pos, state);
        if (level.getBlockEntity(pos) instanceof GrandPianoBlockEntity piano) {
            if (placer instanceof Player player) piano.setOwner(player.getUUID(), player.getGameProfile().name());
            if (piano.settings().lidOpen() && !hasLidRoom(level, pos, facing)) {
                piano.shutLid();
                if (placer instanceof Player player) player.displayClientMessage(Component.translatable("music.message.lid_stays_closed", LID_HEIGHT), true);
            }
        }
    }

    /**
     * Puts in every other part of the piano whose controller stands at {@code controller}, then tells
     * the neighbours of every cell. Every part goes in before any neighbour hears of one (the item
     * placed the controller the same way): a neighbour reacting to one part could otherwise remove a
     * cell another part is still to fill, which the parts already there would take for a broken piano.
     * A tall plant whose two halves stand in two of the cells does exactly that when its lower half is
     * replaced.
     */
    private void assemble(Level level, BlockPos controller, BlockState state) {
        Direction facing = state.getValue(FACING);
        for (int part = 0; part < PianoLayout.PARTS; part++) {
            if (part != PianoLayout.CONTROLLER) level.setBlock(PianoLayout.partPos(controller, facing, part), state.setValue(PART, part), ASSEMBLE);
        }
        for (int part = 0; part < PianoLayout.PARTS; part++) {
            BlockPos at = PianoLayout.partPos(controller, facing, part);
            BlockState placed = level.getBlockState(at);
            level.updateNeighborsAt(at, this);
            level.updateNeighbourForOutputSignal(at, this);
            placed.updateNeighbourShapes(level, at, Block.UPDATE_ALL);
            placed.updateIndirectNeighbourShapes(level, at, Block.UPDATE_ALL);
        }
    }

    /**
     * A controller set on its own, by a command or a map's command block, rather than by the item:
     * the rest of the piano is built on the next tick, once the controller's block entity is there for
     * its neighbours to find. A structure, a clone or the item puts every part in within the same tick,
     * so by then there is nothing left to build.
     */
    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (!level.isClientSide() && state.getValue(PART) == PianoLayout.CONTROLLER && !oldState.is(this)) level.scheduleTick(pos, this, 1);
    }

    /**
     * Builds the rest of a piano whose controller was set on its own. Where its cells have no room, the
     * lone controller is taken away, since it would be drawn as a whole piano nobody can touch; players
     * nearby are told why in chat (where a command's own answer goes), and so is the log.
     */
    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (state.getValue(PART) != PianoLayout.CONTROLLER) return;
        Direction facing = state.getValue(FACING);
        if (isWhole(level, pos, facing)) return;
        PianoPlacement.Blocker blocker = PianoPlacement.blocker(level, pos, facing, null);
        if (blocker == null) {
            assemble(level, pos, state);
            return;
        }
        Component why = PianoPlacement.reason(blocker, null);
        LOG.info("A Grand Piano set at {} had no room for the rest of it and was taken away: {}", pos.toShortString(), why.getString());
        level.removeBlock(pos, false);
        Component told = Component.translatable("music.message.taken_away", pos.getX(), pos.getY(), pos.getZ(), why);
        for (ServerPlayer player : level.getPlayers(p -> p.position().closerThan(Vec3.atCenterOf(pos), NOTICE_RANGE))) player.displayClientMessage(told, false);
    }

    /** True when every part of the piano whose controller stands at {@code controller} is in its cell. */
    private static boolean isWhole(BlockGetter level, BlockPos controller, Direction facing) {
        for (int part = 0; part < PianoLayout.PARTS; part++) {
            if (!PianoPlacement.isPart(level.getBlockState(PianoLayout.partPos(controller, facing, part)), facing, part)) return false;
        }
        return true;
    }

    /**
     * True when the lid of the piano at {@code controller} can be raised without going through a
     * block: nothing in the space it, its prop and the music desk swing through above the piano's
     * cells (see {@link PianoShapes#lidRoom()}). Fluids and air leave room.
     */
    public static boolean hasLidRoom(BlockGetter level, BlockPos controller, Direction facing) {
        return lidBlocker(level, controller, facing) == null;
    }

    /**
     * The first block in the way of the raised lid of a piano at {@code controller} (see
     * {@link #hasLidRoom}), or null when the lid has its room.
     */
    public static @Nullable BlockPos lidBlocker(BlockGetter level, BlockPos controller, Direction facing) {
        for (PianoShapes.RoomCell cell : PianoShapes.lidRoom()) {
            BlockPos pos = PianoLayout.cellPos(controller, facing, cell.column(), cell.row(), cell.layer());
            BlockState state = level.getBlockState(pos);
            if (!state.isAir() && Shapes.joinIsNotEmpty(cell.shape(facing), state.getShape(level, pos), BooleanOp.AND)) return pos;
        }
        return null;
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos, Direction direction,
                                     BlockPos neighborPos, BlockState neighborState, RandomSource random) {
        Direction facing = state.getValue(FACING);
        BlockPos controller = PianoLayout.controllerPos(pos, facing, state.getValue(PART));
        for (int part = 0; part < PianoLayout.PARTS; part++) {
            if (part == state.getValue(PART)) continue;
            if (PianoLayout.partPos(controller, facing, part).equals(neighborPos)) {
                boolean intact = neighborState.is(this) && neighborState.getValue(FACING) == facing && neighborState.getValue(PART) == part;
                return intact ? state : Blocks.AIR.defaultBlockState();
            }
        }
        return super.updateShape(state, level, ticks, pos, direction, neighborPos, neighborState, random);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        dismantle(level, pos, state, player);
        return super.playerWillDestroy(level, pos, state, player);
    }

    /**
     * A player breaks the cell at {@code broken}: the piano comes apart as one thing. Its other cells
     * go quietly, leaving the broken cell's own crack and one burst of splinters through the
     * instrument; the piano drops once, from its controller with its block entity, so with everything
     * set on it (none in creative); and the neighbours of every cell hear of it once all are gone. The
     * broken cell itself still stands when this is done, and is left to the game, which removes it as
     * it removes any block a player breaks: with the stat, the tool's wear and the break events other
     * mods listen for, and, when it is the controller, the drop. Were it told of its neighbours going,
     * it would take itself away first (see {@link #updateShape}), with a second crack and a drop even
     * in creative, and the game would find nothing left to break. The breaking player's own game
     * predicts the same, so it does not break the cells one by one either.
     */
    private void dismantle(Level level, BlockPos broken, BlockState state, Player player) {
        Direction facing = state.getValue(FACING);
        BlockPos controller = controllerOf(broken, state);
        BlockState controllerState = level.getBlockState(controller);
        if (!PianoPlacement.isPart(controllerState, facing, PianoLayout.CONTROLLER)) return;
        int brokenPart = state.getValue(PART);
        if (level instanceof ServerLevel server && brokenPart != PianoLayout.CONTROLLER && !player.preventsBlockDrops()
            && player.hasCorrectToolForDrops(controllerState)) {
            Block.dropResources(controllerState, server, controller, server.getBlockEntity(controller), player, player.getMainHandItem());
        }
        List<BlockPos> removed = new ArrayList<>();
        for (int part = 0; part < PianoLayout.PARTS; part++) {
            BlockPos at = PianoLayout.partPos(controller, facing, part);
            if (part != brokenPart && PianoPlacement.isPart(level.getBlockState(at), facing, part) && level.setBlock(at, Blocks.AIR.defaultBlockState(), DISMANTLE)) {
                removed.add(at);
            }
        }
        if (!(level instanceof ServerLevel server)) return;
        BlockState air = Blocks.AIR.defaultBlockState();
        for (BlockPos at : removed) {
            server.updateNeighborsAt(at, this);
            server.updateNeighbourForOutputSignal(at, this);
            for (Direction side : UPDATE_SHAPE_ORDER) {
                BlockPos next = at.relative(side);
                if (!next.equals(broken)) server.neighborShapeChanged(side.getOpposite(), next, at, air, Block.UPDATE_ALL, Block.UPDATE_LIMIT);
            }
        }
        BlockParticleOption splinter = new BlockParticleOption(ParticleTypes.BLOCK, controllerState);
        for (Vec3 at : splinters(controller, facing, server.getRandom(), SPLINTERS)) {
            server.sendParticles(splinter, at.x, at.y, at.z, 0, 0, 1, 0, SPLINTER_HOP);
        }
    }

    /**
     * Where the {@code count} splinters of a piano broken at {@code controller} start: spread evenly
     * through what the piano is made of as it collides (its case, keybed, legs, lyre and bench), each
     * box as likely as it is large. None starts outside the instrument, between it and a player beside
     * it; the game then scatters each a little, as it does a broken block's.
     */
    private static List<Vec3> splinters(BlockPos controller, Direction facing, RandomSource random, int count) {
        List<AABB> boxes = new ArrayList<>();
        for (int part = 0; part < PianoLayout.PARTS; part++) {
            BlockPos cell = PianoLayout.partPos(controller, facing, part);
            for (AABB box : PianoShapes.collision(part, facing).toAabbs()) boxes.add(box.move(cell));
        }
        // The volume of every box and all those before it, to pick a box as likely as it is large.
        double[] upTo = new double[boxes.size()];
        double total = 0;
        for (int i = 0; i < upTo.length; i++) {
            AABB box = boxes.get(i);
            total += box.getXsize() * box.getYsize() * box.getZsize();
            upTo[i] = total;
        }
        List<Vec3> points = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double pick = random.nextDouble() * total;
            int index = 0;
            while (index < upTo.length - 1 && upTo[index] <= pick) index++;
            AABB box = boxes.get(index);
            points.add(new Vec3(Mth.lerp(random.nextDouble(), box.minX, box.maxX), Mth.lerp(random.nextDouble(), box.minY, box.maxY),
                Mth.lerp(random.nextDouble(), box.minZ, box.maxZ)));
        }
        return points;
    }

    /** With its data asked for (creative), any cell gives the piano with everything set on it, as breaking it would. */
    @Override
    protected ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state, boolean includeData) {
        ItemStack stack = super.getCloneItemStack(level, pos, state, includeData);
        GrandPianoBlockEntity piano = includeData ? controller(level, pos, state) : null;
        if (piano != null) stack.applyComponents(piano.collectComponents());
        return stack;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return PianoShapes.outline(state.getValue(PART), state.getValue(FACING));
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return PianoShapes.collision(state.getValue(PART), state.getValue(FACING));
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (player instanceof ServerPlayer serverPlayer) {
            GrandPianoBlockEntity piano = controller(level, pos, state);
            if (piano != null) piano.use(serverPlayer);
        }
        return InteractionResult.SUCCESS_SERVER;
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, @Nullable Orientation orientation, boolean movedByPiston) {
        if (level.isClientSide()) return;
        GrandPianoBlockEntity piano = controller(level, pos, state);
        if (piano != null) piano.updateRedstone();
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos, Direction direction) {
        GrandPianoBlockEntity piano = controller(level, pos, state);
        return piano == null ? 0 : piano.comparatorSignal();
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return false;
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    /** Mirrored structures keep a playable piano, its controller in its own cell: see {@link PianoLayout#mirroredPart}. */
    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        if (mirror == Mirror.NONE) return state;
        return state.rotate(mirror.getRotation(state.getValue(FACING))).setValue(PART, PianoLayout.mirroredPart(state.getValue(PART)));
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(PART) == PianoLayout.CONTROLLER ? new GrandPianoBlockEntity(pos, state) : null;
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide() || state.getValue(PART) != PianoLayout.CONTROLLER || type != MusicContent.GRAND_PIANO_ENTITY) return null;
        return (lvl, pos, st, be) -> ((GrandPianoBlockEntity) be).serverTick((ServerLevel) lvl);
    }
}
