package org.hurtorius.music.mixin.client;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.hurtorius.music.client.play.KeyClick;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The seated performer's left click on their own piano plays the key under the crosshair (or says
 * how to click the keys, or that standing up comes before breaking it) instead of starting to break
 * the piano: before the game predicts any break, sends anything or draws cracks, on the click and on
 * every tick it is held (see {@link KeyClick}).
 */
@Mixin(MultiPlayerGameMode.class)
abstract class MultiPlayerGameModeMixin {
    @Inject(method = "startDestroyBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)Z", at = @At("HEAD"), cancellable = true)
    private void music$clickTheKeys(BlockPos pos, Direction face, CallbackInfoReturnable<Boolean> cir) {
        if (KeyClick.attack(pos)) cir.setReturnValue(false);
    }

    @Inject(method = "continueDestroyBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)Z", at = @At("HEAD"), cancellable = true)
    private void music$holdTheKey(BlockPos pos, Direction face, CallbackInfoReturnable<Boolean> cir) {
        if (KeyClick.attack(pos)) cir.setReturnValue(false);
    }
}
