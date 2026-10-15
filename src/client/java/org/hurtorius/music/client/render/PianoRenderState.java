package org.hurtorius.music.client.render;

import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.core.Direction;

/** What {@link GrandPianoRenderer} extracted for one piano this frame. */
public final class PianoRenderState extends BlockEntityRenderState {
    public Direction facing = Direction.NORTH;
    /** The desk sheet is drawn this frame. */
    public boolean sheetVisible;
    /** Cracks to draw (0..9) while a cell is being broken, else -1. */
    public int breakStage = -1;
    PianoVisuals.Visual visual;
}
