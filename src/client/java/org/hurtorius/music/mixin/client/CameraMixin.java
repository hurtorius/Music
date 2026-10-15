package org.hurtorius.music.mixin.client;

import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.hurtorius.music.client.anim.PianoCamera;
import org.hurtorius.music.client.anim.SeatedView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Moves the camera while the local player is seated: to the chosen piano view (the body is then
 * drawn too), in the game's front view over the instrument (see {@code PianoCamera.front}), or in
 * first person to the performer's seated eye (see {@code SeatedView}).
 */
@Mixin(Camera.class)
abstract class CameraMixin {
    @Shadow
    private boolean detached;

    @Shadow
    protected abstract void setRotation(float yRot, float xRot);

    @Shadow
    protected abstract void setPosition(Vec3 position);

    @Shadow
    public abstract Vec3 position();

    @Inject(method = "setup(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;ZZF)V", at = @At("TAIL"))
    private void music$pianoCamera(Level level, Entity entity, boolean thirdPerson, boolean mirrored, float partialTick, CallbackInfo ci) {
        PianoCamera.Shot shot = PianoCamera.shot(entity);
        if (shot != null) {
            setRotation(shot.yaw(), shot.pitch());
            setPosition(shot.position());
            detached = true;
            return;
        }
        if (thirdPerson) {
            PianoCamera.Shot front = mirrored ? PianoCamera.front(entity, partialTick) : null;
            if (front != null) {
                setRotation(front.yaw(), front.pitch());
                setPosition(front.position());
            }
            return;
        }
        Vec3 eye = SeatedView.firstPersonEye(entity, position());
        if (eye != null) setPosition(eye);
    }
}
