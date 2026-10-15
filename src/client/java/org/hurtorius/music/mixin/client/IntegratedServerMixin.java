package org.hurtorius.music.mixin.client;

import net.minecraft.client.server.IntegratedServer;
import org.hurtorius.music.MusicClock;
import org.hurtorius.music.client.PauseClock;
import org.hurtorius.music.net.ServerNetwork;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * Tells the songs' timelines when the singleplayer game pauses and goes on, from the integrated
 * server's own pause flag, right where the server updates it and before it ticks, with the paused
 * time the player's game counted (see {@link PauseClock}) from the moment it paused: the server
 * notices a pause at its next tick, and its end only after the save it starts on pausing, which can
 * outlast the pause.
 */
@Mixin(IntegratedServer.class)
public abstract class IntegratedServerMixin {
    @Shadow
    private boolean paused;

    @Unique
    private boolean music$wasPaused = true;

    @Inject(method = "tickServer(Ljava/util/function/BooleanSupplier;)V", at = @At("HEAD"))
    private void music$beforePauseUpdate(BooleanSupplier haveTime, CallbackInfo ci) {
        music$wasPaused = paused;
    }

    @Inject(method = "tickServer(Ljava/util/function/BooleanSupplier;)V",
        at = @At(value = "FIELD", target = "Lnet/minecraft/client/server/IntegratedServer;paused:Z", opcode = 181, shift = At.Shift.AFTER))
    private void music$afterPauseUpdate(BooleanSupplier haveTime, CallbackInfo ci) {
        if (!music$wasPaused && paused) ServerNetwork.gamePaused(PauseClock.pausedBefore());
        else if (music$wasPaused && !paused) ServerNetwork.gameResumed(PauseClock.pausedMillis(MusicClock.millis()));
    }
}
