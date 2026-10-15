package org.hurtorius.music.mixin.client;

import com.mojang.blaze3d.audio.Library;
import org.hurtorius.music.client.audio.AudioLifecycle;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.IntBuffer;

/**
 * Ties the piano's OpenAL objects to the life of Minecraft's audio context: built after every
 * {@code init} (start, resource reload, device change), deleted before every {@code cleanup} destroys
 * the context, and enough sources requested for Minecraft and the piano together.
 */
@Mixin(Library.class)
public abstract class LibraryMixin {
    @Inject(method = "init(Ljava/lang/String;Z)V", at = @At("TAIL"))
    private void music$contextCreated(@Nullable String deviceName, boolean directionalAudio, CallbackInfo ci) {
        AudioLifecycle.contextCreated();
    }

    @Inject(method = "cleanup()V", at = @At("HEAD"))
    private void music$contextDestroying(CallbackInfo ci) {
        AudioLifecycle.contextDestroying();
    }

    @Inject(method = "createAttributes(Lorg/lwjgl/system/MemoryStack;Z)Ljava/nio/IntBuffer;", at = @At("RETURN"), cancellable = true)
    private void music$requestSources(MemoryStack stack, boolean hrtf, CallbackInfoReturnable<IntBuffer> cir) {
        cir.setReturnValue(AudioLifecycle.withSourceRequest(cir.getReturnValue(), stack));
    }
}
