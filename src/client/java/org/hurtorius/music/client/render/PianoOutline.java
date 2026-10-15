package org.hurtorius.music.client.render;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.renderer.state.BlockOutlineRenderState;
import net.minecraft.client.renderer.state.LevelRenderState;
import net.minecraft.world.level.block.state.BlockState;
import org.hurtorius.music.piano.GrandPianoBlock;
import org.hurtorius.music.piano.PianoShapes;

/**
 * The outline drawn around the piano cell a player aims at: what the piano has in that cell's whole
 * column of cells ({@link PianoShapes#frame}), so aiming at the case frames it from its underside to
 * its rim across the seam of the piano's layers. The game's aiming itself keeps each cell's own
 * outline ({@link PianoShapes#outline}), so the cell it finds is the one the point aimed at is in.
 * The game draws the frame as it draws any outline, in its own colours and line width.
 */
public final class PianoOutline {
    private PianoOutline() {}

    public static void register() {
        WorldRenderEvents.AFTER_BLOCK_OUTLINE_EXTRACTION.register((context, hit) -> {
            LevelRenderState world = context.worldState();
            BlockOutlineRenderState outline = world.blockOutlineRenderState;
            if (outline == null) return;
            BlockState state = context.world().getBlockState(outline.pos());
            if (!(state.getBlock() instanceof GrandPianoBlock)) return;
            world.blockOutlineRenderState = new BlockOutlineRenderState(outline.pos(), outline.isTranslucent(), outline.highContrast(),
                PianoShapes.frame(state.getValue(GrandPianoBlock.PART), state.getValue(GrandPianoBlock.FACING)),
                outline.collisionShape(), outline.occlusionShape(), outline.interactionShape());
        });
    }
}
