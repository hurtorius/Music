package org.hurtorius.music.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.TerrainParticle;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.hurtorius.music.piano.GrandPianoBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A piano cell that breaks throws a few splinters over the room it takes, not the game's eight or
 * more over each box of its outline: the case is drawn across in thin slabs (see {@code PianoShapes}),
 * and a piano taken away whole by an explosion or a command, every cell of it breaking at once, threw
 * two thousand, a black cloud over the place. A player breaking it gets one burst over the whole
 * case from the server besides (see {@link GrandPianoBlock}).
 */
@Mixin(ClientLevel.class)
abstract class ClientLevelMixin {
    /** Splinters along each side of a broken piano cell, as the game's own spread puts them. */
    @Unique
    private static final int MUSIC$SPLINTERS_ACROSS = 2;

    @Inject(method = "addDestroyBlockEffect(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V",
        at = @At("HEAD"), cancellable = true)
    private void music$splintersOverTheCell(BlockPos pos, BlockState state, CallbackInfo ci) {
        if (!(state.getBlock() instanceof GrandPianoBlock)) return;
        ci.cancel();
        ClientLevel level = (ClientLevel) (Object) this;
        VoxelShape shape = state.getShape(level, pos);
        if (shape.isEmpty()) return;
        AABB box = shape.bounds();
        int n = MUSIC$SPLINTERS_ACROSS;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                for (int k = 0; k < n; k++) {
                    double s = (i + 0.5) / n, t = (j + 0.5) / n, u = (k + 0.5) / n;
                    double x = pos.getX() + box.minX + s * Math.min(1, box.getXsize());
                    double y = pos.getY() + box.minY + t * Math.min(1, box.getYsize());
                    double z = pos.getZ() + box.minZ + u * Math.min(1, box.getZsize());
                    Minecraft.getInstance().particleEngine.add(new TerrainParticle(level, x, y, z, s - 0.5, t - 0.5, u - 0.5, state, pos));
                }
            }
        }
    }
}
