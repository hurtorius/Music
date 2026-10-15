package org.hurtorius.music.client.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.hurtorius.music.mixin.client.ChatComponentAccessor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * Tells the player how something went: on the piano screen's status line while it is open, in chat
 * otherwise (an import or upload can finish after the screen was closed). An error shown on the
 * screen also goes to chat once the player leaves the screen (closing it, or opening chat), dated
 * when it happened and in its place among the chat of its time, so it can be read again; on the
 * screen it would show twice. Client thread.
 */
final class Feedback {
    private static final Logger LOG = LoggerFactory.getLogger("Music");

    enum Kind {
        INFO(0xFFE0E0E0, ChatFormatting.GRAY), SUCCESS(0xFF80FF80, ChatFormatting.GREEN), ERROR(0xFFFF6060, ChatFormatting.RED);

        final int colour;
        final ChatFormatting chat;

        Kind(int colour, ChatFormatting chat) {
            this.colour = colour;
            this.chat = chat;
        }
    }

    /**
     * An error the open screen showed, waiting to go to chat, and chat's newest message when it
     * happened (null when chat was empty): in chat it goes just after that one.
     */
    record ChatLater(GuiMessage message, @Nullable GuiMessage after) {}

    private Feedback() {}

    static void info(Component message) {
        show(message, Kind.INFO);
    }

    static void success(Component message) {
        show(message, Kind.SUCCESS);
    }

    static void error(Component message) {
        show(message, Kind.ERROR);
    }

    static void show(Component message, Kind kind) {
        Minecraft mc = Minecraft.getInstance();
        Component inChat = message.copy().withStyle(kind.chat);
        if (mc.screen instanceof PianoScreen screen) {
            screen.status().show(message, kind);
            if (kind == Kind.ERROR) {
                GuiMessageTag tag = mc.isSingleplayer() ? GuiMessageTag.systemSinglePlayer() : GuiMessageTag.system();
                List<GuiMessage> chat = ((ChatComponentAccessor) mc.gui.getChat()).music$messages();
                PianoSession.chatLater.add(new ChatLater(new GuiMessage(mc.gui.getGuiTicks(), inChat, null, tag), chat.isEmpty() ? null : chat.getFirst()));
            }
        } else if (mc.player != null) {
            mc.player.displayClientMessage(inChat, false);
        }
    }

    /**
     * An error that matters only while the player is at the piano and that chat already holds once
     * (a MIDI keyboard that is busy again on a later visit): on the open screen's status line, and
     * nowhere when the screen is closed.
     */
    static void errorOnScreen(Component message) {
        if (Minecraft.getInstance().screen instanceof PianoScreen screen) screen.status().show(message, Kind.ERROR);
    }

    /**
     * Puts the errors the piano screen showed into chat, now that the player left it, each dated when
     * it happened and in its place among the messages of its time. Chat gives every line a row by its
     * place from the newest, shown or not, so an old error put at the bottom would be an empty band
     * that pushes the chat after it up and out of view; in its place it shows again only while it is
     * recent (or when the player opens chat) and is kept to scroll back to. True when there were any.
     */
    static boolean errorsToChat(Minecraft mc) {
        if (PianoSession.chatLater.isEmpty()) return false;
        ChatComponentAccessor chat = (ChatComponentAccessor) mc.gui.getChat();
        for (ChatLater error : PianoSession.chatLater) chat.music$log(error.message());
        putAll(chat.music$messages(), PianoSession.chatLater);
        PianoSession.chatLater.clear();
        chat.music$layOut();
        return true;
    }

    /**
     * Puts {@code errors} (oldest first) into chat's messages (newest first), each just after the
     * message that was newest when it happened, or, when that one has gone from chat (deleted, or
     * past as many as chat keeps), after every message dated later. Errors that followed one message
     * keep their order. Nothing is taken out: chat trims its history itself with the next message
     * it takes in, to whatever length it keeps (a chat mod may keep more than the game's hundred).
     */
    static void putAll(List<GuiMessage> newestFirst, List<ChatLater> errors) {
        // The newest error first: an older one after the same message then goes in under it.
        for (ChatLater error : errors.reversed()) {
            int at = error.after() == null ? newestFirst.size() : indexOf(newestFirst, error.after());
            if (at < 0) {
                at = 0;
                while (at < newestFirst.size() && newestFirst.get(at).addedTime() > error.message().addedTime()) at++;
            }
            newestFirst.add(at, error.message());
        }
    }

    /** Where {@code message} itself (not an equal one) is in {@code messages}, or -1. */
    private static int indexOf(List<GuiMessage> messages, GuiMessage message) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) == message) return i;
        }
        return -1;
    }

    /** The real failure inside the wrappers a future adds, or null when the work was cancelled. */
    static @Nullable Throwable cause(Throwable failure) {
        Throwable t = failure;
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) t = t.getCause();
        return t instanceof CancellationException ? null : t;
    }

    /**
     * A failure in plain words. A file the system refused, or that is gone, says so with its name
     * (Java's own text for these is only the file's path); other failures give their own text, which
     * Music writes for players, or else their type (and a log entry).
     */
    static Component describe(Throwable failure) {
        if (failure instanceof AccessDeniedException denied) {
            return Component.translatable("music.screen.file.denied", String.valueOf(denied.getFile()));
        }
        if (failure instanceof NoSuchFileException missing) {
            return Component.translatable("music.screen.file.missing", fileName(missing.getFile()));
        }
        if (failure instanceof FileSystemException other) {
            LOG.warn("Music: a file could not be used", failure);
            String reason = other.getReason();
            return reason == null || reason.isBlank()
                ? Component.translatable("music.screen.file.unusable", fileName(other.getFile()))
                : Component.translatable("music.screen.file.failed", fileName(other.getFile()), reason.strip());
        }
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            LOG.warn("Music screen action failed", failure);
            return Component.literal(failure.getClass().getSimpleName());
        }
        return Component.literal(message);
    }

    /** The last part of a path as the system gave it, or the whole text when it is not a path. */
    private static String fileName(@Nullable String file) {
        if (file == null || file.isBlank()) return "?";
        try {
            Path name = Path.of(file).getFileName();
            return name == null ? file : name.toString();
        } catch (InvalidPathException e) {
            return file;
        }
    }
}
