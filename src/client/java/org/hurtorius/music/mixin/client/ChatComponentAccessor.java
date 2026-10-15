package org.hurtorius.music.mixin.client;

import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/**
 * Chat's messages and lines, and its own steps for taking a message in. An error the piano screen
 * showed goes into chat when the player leaves the screen, dated when it happened and among the
 * messages of its time, the way the game itself puts a "deleted message" marker in place (it changes
 * the messages, then lays the lines out again): {@code ChatComponent.addMessage} would date it now,
 * and putting an old one at the bottom would leave empty rows under the chat that came after it. The
 * seated hint reads chat's lines to stay clear of those still showing.
 */
@Mixin(ChatComponent.class)
public interface ChatComponentAccessor {
    /** Writes the message to the game's log, as chat does with every message it takes in. */
    @Invoker("logChatMessage")
    void music$log(GuiMessage message);

    /** Chat's messages, newest first, as many as it keeps. */
    @Accessor("allMessages")
    List<GuiMessage> music$messages();

    /** Chat's lines as it draws them, newest first: each message split to chat's width. */
    @Accessor("trimmedMessages")
    List<GuiMessage.Line> music$lines();

    /** Lays chat's lines out again from its messages. */
    @Invoker("refreshTrimmedMessages")
    void music$layOut();
}
