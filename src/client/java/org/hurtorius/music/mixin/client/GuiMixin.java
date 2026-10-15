package org.hurtorius.music.mixin.client;

import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;
import org.hurtorius.music.client.ui.PianoUi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every action bar message passes here, whoever sends it (the server's system messages and
 * {@code /title ... actionbar}, the game's own notices): while the piano screen covers the action
 * bar, it goes to the screen's status line instead (see {@link PianoUi#actionBar}).
 */
@Mixin(Gui.class)
public abstract class GuiMixin {
    @Inject(method = "setOverlayMessage(Lnet/minecraft/network/chat/Component;Z)V", at = @At("HEAD"), cancellable = true)
    private void music$actionBar(Component message, boolean animateColour, CallbackInfo ci) {
        if (PianoUi.actionBar(message)) ci.cancel();
    }
}
