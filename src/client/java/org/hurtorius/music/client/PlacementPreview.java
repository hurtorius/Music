package org.hurtorius.music.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.ARGB;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.hurtorius.music.piano.GrandPianoBlock;
import org.hurtorius.music.piano.PianoLayout;
import org.hurtorius.music.piano.PianoPlacement;
import org.hurtorius.music.piano.PianoShapes;
import org.hurtorius.music.registry.MusicContent;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * While a player holds the piano's item and aims where a right click would place it, the piano is
 * outlined where it would stand, in the game's own lines: the box of its cells, the bench's cell
 * (which shows the side the keyboard faces) and, fainter, the room above that its raised lid swings
 * through. Where it cannot go, the outline is red and whatever is in the way (a block's cell, or an
 * entity) is framed in red too, and the server's message on clicking names it. Where it fits but the
 * lid's room does not, that room and the block in it are amber: the piano would go in with its lid
 * closed. It follows {@link PianoPlacement}, beside the aim where only that fits included, and shows
 * nothing where the click would do something else: a piano or a block with a screen of its own is
 * aimed at (it opens instead, unless the player sneaks), or the piano is in the off hand and the main
 * hand holds a block of its own to place.
 *
 * <p>Every line is drawn over a dark one a little wider, as the game's high-contrast block outline
 * is, so it shows on snow and pale stone as on grass. What to outline is worked out when the frame's
 * render state is gathered, again only when the aim or the game tick changes, and drawn where and as
 * the game draws its outline of the block aimed at.</p>
 */
public final class PlacementPreview {
    /** The piano's cells and the bench's, where it fits: the light grey of the game's own frames. */
    private static final int FITS = ARGB.color(230, 230, 230, 230);
    /** The lid's room above, fainter. */
    private static final int LID_ROOM = ARGB.color(110, 230, 230, 230);
    /** The lid's room where a block is in it, and that block: the piano fits, its lid would stay closed. */
    private static final int LID_SHUT = ARGB.color(230, 255, 176, 32);
    /** Where it does not fit, and what is in its way. */
    private static final int BLOCKED = ARGB.color(230, 255, 70, 60);
    /** How much of a line's own opacity the dark line under it has. */
    private static final float UNDER_SHARE = 0.6f;
    /** How much wider than the line itself the dark line under it is. */
    private static final float UNDER_WIDTH = 2.5f;
    /** Lines stand this far out from the cells, so the ones on the ground are not lost in it. */
    private static final double LIFT = 0.004;

    /**
     * What the preview shows.
     *
     * @param spot       where the piano would go, and what is in its way
     * @param lidBlocker where it fits, the first block in the room its raised lid needs, or null
     */
    public record Shown(PianoPlacement.Spot spot, @Nullable BlockPos lidBlocker) {}

    /** A box to outline, ready to draw, and its colour. */
    private record Line(VoxelShape shape, int color) {}

    /** What the outline was worked out for: it holds until one of these changes. */
    private record Aim(ClientLevel level, long tick, BlockPos pos, Direction face, Direction facing, InteractionHand hand) {}

    /** The aim the lines below were worked out for, or null. Render thread. */
    private static @Nullable Aim aim;
    private static @Nullable Shown shown;
    /** What the current frame outlines; written while it is gathered and read while it is drawn, on the render thread. */
    private static List<Line> lines = List.of();

    private PlacementPreview() {}

    public static void register() {
        WorldRenderEvents.END_EXTRACTION.register(context -> gather(Minecraft.getInstance()));
        // In the frame's main pass, where the game draws its own outline of the block aimed at.
        WorldRenderEvents.AFTER_ENTITIES.register(PlacementPreview::draw);
    }

    /** What the preview shows in the frame being drawn, or null when it shows nothing. Render thread. */
    public static @Nullable Shown shown() {
        return lines.isEmpty() ? null : shown;
    }

    private static void gather(Minecraft mc) {
        Aim now = aim(mc);
        if (now == null) {
            aim = null;
            shown = null;
            lines = List.of();
            return;
        }
        if (now.equals(aim)) return;
        aim = now;
        BlockHitResult hit = (BlockHitResult) mc.hitResult;
        BlockPlaceContext context = new BlockPlaceContext(mc.player, now.hand(), mc.player.getItemInHand(now.hand()), hit);
        if (!context.canPlace()) {
            shown = null;
            lines = List.of();
            return;
        }
        PianoPlacement.Spot spot = PianoPlacement.find(context);
        BlockPos lidBlocker = spot.fits() ? GrandPianoBlock.lidBlocker(mc.level, spot.controller(), spot.facing()) : null;
        shown = new Shown(spot, lidBlocker);
        lines = lines(spot, lidBlocker);
    }

    /**
     * Where the player aims with the piano's item, when a right click would try to place it there,
     * or null: the game's view is the player's own, they may build, they aim at a block, the hand
     * that would be used holds the piano, and the block aimed at would not take the click itself.
     */
    private static @Nullable Aim aim(Minecraft mc) {
        if (mc.player == null || mc.level == null || mc.gameMode == null || mc.options.hideGui || mc.getCameraEntity() != mc.player) return null;
        if (mc.gameMode.getPlayerMode() == GameType.SPECTATOR || !mc.player.getAbilities().mayBuild) return null;
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return null;
        InteractionHand hand = held(mc);
        if (hand == null || usedFirst(mc, hit)) return null;
        return new Aim(mc.level, mc.level.getGameTime(), hit.getBlockPos(), hit.getDirection(), mc.player.getDirection(), hand);
    }

    /**
     * The hand a right click would place the piano with, or null: the main hand when it holds the
     * piano; the off hand when it holds the piano and the main hand no block, which it would place first.
     */
    private static @Nullable InteractionHand held(Minecraft mc) {
        ItemStack main = mc.player.getMainHandItem();
        if (main.is(MusicContent.GRAND_PIANO_ITEM)) return InteractionHand.MAIN_HAND;
        if (!mc.player.getOffhandItem().is(MusicContent.GRAND_PIANO_ITEM) || main.getItem() instanceof BlockItem) return null;
        return InteractionHand.OFF_HAND;
    }

    /**
     * True when the block aimed at takes the click before the item in hand does: a piano (which opens
     * its screen) or a block with a screen of its own, unless the player sneaks, which passes over it.
     */
    private static boolean usedFirst(Minecraft mc, BlockHitResult hit) {
        if (mc.player.isSecondaryUseActive()) return false;
        BlockState state = mc.level.getBlockState(hit.getBlockPos());
        return state.getBlock() instanceof GrandPianoBlock || state.getMenuProvider(mc.level, hit.getBlockPos()) != null;
    }

    /** The outline of a piano at {@code spot}, its lid's room held by the block at {@code lidBlocker} when that is given. */
    private static List<Line> lines(PianoPlacement.Spot spot, @Nullable BlockPos lidBlocker) {
        int color = spot.fits() ? FITS : BLOCKED;
        List<Line> out = new ArrayList<>();
        out.add(line(cells(spot, 0, 0, PianoLayout.COLUMNS, PianoLayout.ROWS, PianoLayout.LAYERS), color));
        out.add(line(cells(spot, PianoLayout.column(PianoLayout.CONTROLLER), 0, 1, 1, 1), color));
        AABB room = null;
        for (PianoShapes.RoomCell cell : PianoShapes.lidRoom()) {
            AABB at = new AABB(PianoLayout.cellPos(spot.controller(), spot.facing(), cell.column(), cell.row(), cell.layer()));
            room = room == null ? at : room.minmax(at);
        }
        if (room != null) out.add(line(room, lidBlocker == null ? LID_ROOM : LID_SHUT));
        if (lidBlocker != null) out.add(line(new AABB(lidBlocker), LID_SHUT));
        PianoPlacement.Blocker blocker = spot.blocker();
        if (blocker != null) {
            Entity entity = blocker.entity();
            out.add(line(entity != null ? entity.getBoundingBox() : new AABB(blocker.cell()), BLOCKED));
        }
        return List.copyOf(out);
    }

    private static Line line(AABB box, int color) {
        return new Line(Shapes.create(box.inflate(LIFT)), color);
    }

    /** The box of {@code columns} x {@code rows} x {@code layers} of the spot's cells from its cell at {@code column}, {@code row}, the floor. */
    private static AABB cells(PianoPlacement.Spot spot, int column, int row, int columns, int rows, int layers) {
        BlockPos first = PianoLayout.cellPos(spot.controller(), spot.facing(), column, row, 0);
        BlockPos last = PianoLayout.cellPos(spot.controller(), spot.facing(), column + columns - 1, row + rows - 1, layers - 1);
        return new AABB(first).minmax(new AABB(last));
    }

    private static void draw(WorldRenderContext context) {
        List<Line> drawn = lines;
        if (drawn.isEmpty()) return;
        // Set in the main pass, where this is drawn.
        PoseStack matrices = context.matrices();
        Vec3 camera = context.worldState().cameraRenderState.pos;
        float width = Minecraft.getInstance().getWindow().getAppropriateLineWidth();
        // As the game draws its high-contrast block outline: every dark line first, in the outline's own lines
        // that leave the depth as it was, then every line over them.
        VertexConsumer under = context.consumers().getBuffer(RenderTypes.secondaryBlockOutline());
        for (Line line : drawn) {
            int dark = ARGB.color(Math.round(ARGB.alpha(line.color()) * UNDER_SHARE), 0, 0, 0);
            ShapeRenderer.renderShape(matrices, under, line.shape(), -camera.x, -camera.y, -camera.z, dark, width * UNDER_WIDTH);
        }
        VertexConsumer over = context.consumers().getBuffer(RenderTypes.lines());
        for (Line line : drawn) {
            ShapeRenderer.renderShape(matrices, over, line.shape(), -camera.x, -camera.y, -camera.z, line.color(), width);
        }
    }
}
