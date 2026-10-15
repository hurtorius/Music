package org.hurtorius.music.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.sounds.Music;
import org.hurtorius.music.MusicClock;
import org.hurtorius.music.client.PauseClock;
import org.hurtorius.music.client.WorldExit;
import org.hurtorius.music.client.play.MusicHush;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Runs the mod's leaving-the-world work on the client thread before Minecraft lets go of the world,
 * times the singleplayer game's pauses where the game sets its pause flag each frame (see
 * {@link PauseClock}), and keeps the game's own music away from a playing piano ({@link MusicHush}) the
 * way the pale garden silences it: no music for the situation, music volume 0.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;ZZ)V", at = @At("HEAD"))
    private void music$leavingWorld(Screen screen, boolean transferring, boolean stopSound, CallbackInfo ci) {
        Minecraft mc = (Minecraft) (Object) this;
        if (mc.level != null || mc.getConnection() != null) WorldExit.run();
    }

    @Inject(method = "runTick(Z)V", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;pause:Z", opcode = 181, shift = At.Shift.AFTER))
    private void music$pauseSet(boolean advanceGameTime, CallbackInfo ci) {
        PauseClock.frame(((Minecraft) (Object) this).isPaused(), MusicClock.millis());
    }

    @Inject(method = "getSituationalMusic()Lnet/minecraft/sounds/Music;", at = @At("RETURN"), cancellable = true)
    private void music$noGameMusicOverThePiano(CallbackInfoReturnable<@Nullable Music> cir) {
        if (hushed()) cir.setReturnValue(null);
    }

    @Inject(method = "getMusicVolume()F", at = @At("RETURN"), cancellable = true)
    private void music$gameMusicFadesForThePiano(CallbackInfoReturnable<Float> cir) {
        if (hushed()) cir.setReturnValue(0f);
    }

    /** True while the piano keeps game music away, unless a screen with music of its own is open. */
    private boolean hushed() {
        Screen screen = ((Minecraft) (Object) this).screen;
        return MusicHush.active() && (screen == null || screen.getBackgroundMusic() == null);
    }
}
