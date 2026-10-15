package org.hurtorius.music.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import org.hurtorius.music.piano.GrandPianoBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The player seated at a piano cannot break it (they stand up first): every break, on the server and
 * as the player's own game predicts it, in creative and in survival, asks the game whether the player
 * may act on that block before anything else, so the piano is never taken away under its performer
 * by a click meant for its keys.
 */
@Mixin(Player.class)
abstract class PlayerMixin {
    @Inject(method = "blockActionRestricted(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/GameType;)Z",
        at = @At("HEAD"), cancellable = true)
    private void music$keepThePianoUnderItsPerformer(Level level, BlockPos pos, GameType mode, CallbackInfoReturnable<Boolean> cir) {
        if (GrandPianoBlock.isSeatedAt((Player) (Object) this, level, pos)) cir.setReturnValue(true);
    }
}
