package org.hurtorius.music.mixin.client;

import net.minecraft.client.sounds.SoundEngine;
import org.hurtorius.music.client.audio.AudioLifecycle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hears when Minecraft has tried to start its sound engine (at launch, on a resource reload, and when
 * the audio device changes or is lost): if no audio context came of it, the piano says there is no
 * audio device instead of waiting for one in silence.
 */
@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {
    @Inject(method = "loadLibrary()V", at = @At("TAIL"))
    private void music$libraryLoaded(CallbackInfo ci) {
        AudioLifecycle.soundEngineStarted();
    }
}
