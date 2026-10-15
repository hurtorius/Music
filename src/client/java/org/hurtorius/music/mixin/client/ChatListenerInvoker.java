package org.hurtorius.music.mixin.client;

import net.minecraft.client.multiplayer.chat.ChatListener;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.UUID;

/**
 * Who the game takes a system chat message to be from: the player named between "&lt;" and "&gt;" in
 * it, when that name is known. The game leaves such a message out of chat when it names a player the
 * player blocked; Fabric tells of the message before that check, so the piano screen makes the same
 * one before it shows the message on its status line.
 */
@Mixin(ChatListener.class)
public interface ChatListenerInvoker {
    /** The sender's id, or the nil id when the message names nobody the game knows. */
    @Invoker("guessChatUUID")
    UUID music$guessSender(Component message);
}
