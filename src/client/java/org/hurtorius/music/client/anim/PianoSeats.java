package org.hurtorius.music.client.anim;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import org.hurtorius.music.piano.GrandPianoBlock;
import org.hurtorius.music.piano.GrandPianoBlockEntity;
import org.hurtorius.music.seat.PianoSeatEntity;
import org.jspecify.annotations.Nullable;

/** Finds the piano an entity sits at on this client: its bench's anchor, with the facing from the block. */
public final class PianoSeats {
    /**
     * @param seatId     entity id of the bench seat; a new seat is made for every sitting
     * @param controller the piano's controller block
     * @param facing     the direction the keyboard faces
     */
    public record Seat(int seatId, BlockPos controller, Direction facing) {}

    private PianoSeats() {}

    /** The piano {@code entity} sits at, or null when it is not seated at a loaded piano. */
    public static @Nullable Seat of(Entity entity) {
        if (!(entity.getVehicle() instanceof PianoSeatEntity seat)) return null;
        BlockPos anchor = seat.anchor();
        if (!(entity.level().getBlockEntity(anchor) instanceof GrandPianoBlockEntity piano)) return null;
        return new Seat(seat.getId(), anchor, piano.facing());
    }

    /**
     * True when {@code pos} is a cell of the piano the local player sits at. Its block outline is not
     * drawn: from the bench the player looks across the keyboard and the lyre, which the outline would
     * cut through.
     */
    public static boolean atOwnPiano(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return false;
        Seat seat = of(mc.player);
        if (seat == null) return false;
        BlockState state = mc.level.getBlockState(pos);
        return state.getBlock() instanceof GrandPianoBlock && GrandPianoBlock.controllerOf(pos, state).equals(seat.controller());
    }
}
