package org.hurtorius.music.mixin.client;

import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.hurtorius.music.client.anim.PerformerPoses;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Leans the body and aims the arms of a seated performer after the model's own animation (armour and
 * cape models included). While a piano camera looks down from just above the local performer's head,
 * hides that head and the model's arms (the arms are drawn bent instead).
 */
@Mixin(PlayerModel.class)
abstract class PlayerModelMixin {
    /** Whether this model's head is hidden by us, so only our own change is ever undone. */
    @Unique
    private boolean music$headHidden;

    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("TAIL"))
    private void music$applyPerformerPose(AvatarRenderState state, CallbackInfo ci) {
        PlayerModel model = (PlayerModel) (Object) this;
        PerformerPoses.apply(model, state);
        boolean hide = PerformerPoses.ownView(state);
        if (hide != music$headHidden) {
            model.head.visible = !hide;
            music$headHidden = hide;
        }
        // The model shows its arms again at the start of every setupAnim.
        if (hide) {
            model.leftArm.visible = false;
            model.rightArm.visible = false;
        }
    }
}
