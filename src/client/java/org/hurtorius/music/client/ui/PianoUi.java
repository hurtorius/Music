package org.hurtorius.music.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.ChatVisiblity;
import org.hurtorius.music.client.MusicKeys;
import org.hurtorius.music.client.WorldExit;
import org.hurtorius.music.client.anim.PianoSeats;
import org.hurtorius.music.client.play.Performer;
import org.hurtorius.music.client.play.Pianos;
import org.hurtorius.music.mixin.client.ChatComponentAccessor;
import org.hurtorius.music.mixin.client.ChatListenerInvoker;
import org.hurtorius.music.net.MusicPayloads;
import org.hurtorius.music.registry.MusicContent;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Wires the piano screen into the client: the server's OpenPiano opens it, the "Open piano" key
 * (P by default, in the Music category) opens it again while seated, the played-notes view hears the
 * performer, a take played back and a MIDI keyboard connected on sitting down are looked after every
 * tick, an unsaved take is kept as a file and the session is forgotten when the player leaves the
 * world. While the screen is open it owns the bottom of the view: the game's crosshair, hotbar and
 * the bars around it are left out, every action bar message (which would show faintly through the
 * strip) goes to the screen's status line instead, and chat is drawn by the screen above its strip
 * (or, when it has no room there, its news goes to the status line). A seated player who closes the
 * screen is told, on a backing that reads over the white keys and clear of chat, how to open it again
 * or stand up.
 */
public final class PianoUi {
    /** The game HUD parts the open piano screen covers or replaces. */
    private static final List<Identifier> COVERED = List.of(VanillaHudElements.CROSSHAIR, VanillaHudElements.HOTBAR,
        VanillaHudElements.ARMOR_BAR, VanillaHudElements.HEALTH_BAR, VanillaHudElements.FOOD_BAR, VanillaHudElements.AIR_BAR,
        VanillaHudElements.MOUNT_HEALTH, VanillaHudElements.INFO_BAR, VanillaHudElements.EXPERIENCE_LEVEL,
        VanillaHudElements.HELD_ITEM_TOOLTIP, VanillaHudElements.OVERLAY_MESSAGE, VanillaHudElements.CHAT);
    /** How long the seated hint stays (and the last part of it, in which it fades), in GUI ticks. */
    private static final int HINT_TICKS = 100, HINT_FADE_TICKS = 20;
    /** The game's own action bar: centred, this far above the bottom of the screen. */
    static final int ACTION_BAR_RISE = 68;
    /** Dark enough that the white keys under the hint do not show through its letters. */
    private static final int HINT_BACKING_ALPHA = 0xC8;
    /** How far the hint's backing reaches around its text. */
    static final int HINT_PADDING = 3;
    /**
     * As the game draws chat ({@code ChatComponent.render}): its newest line ends this far above the
     * bottom, and a line shows for this many GUI ticks after its date.
     */
    static final int CHAT_BOTTOM = 40, CHAT_LINE_TICKS = 200;

    private static KeyMapping openKey;
    /** What the seated hint says, or null when it is not showing. */
    private static @Nullable Component seatedHint;
    private static int seatedHintSince;
    /** How far above the bottom of the screen the hint's text has been (it only rises while it shows). */
    private static int seatedHintRise;
    /** Where the hint's backing was drawn last frame. */
    private static ScreenRectangle seatedHintArea = ScreenRectangle.empty();

    private PianoUi() {}

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(MusicPayloads.OpenPiano.TYPE, (payload, context) ->
            PianoScreen.open(payload.pos(), standingNote(payload), payload.canPlay()));
        openKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.music.open_piano", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_P, MusicKeys.CATEGORY));
        // Before the game handles its own keys: opening the screen first clears the click vanilla's
        // social interactions key (also P by default) would otherwise act on.
        ClientTickEvents.START_CLIENT_TICK.register(mc -> {
            while (openKey.consumeClick()) reopen(mc);
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            PlayedNotes.INSTANCE.tick();
            TakePreview.tick(mc);
            MidiAutoConnect.tick();
            TakeKeeper.showPending(mc);
        });
        Performer.addListener(PlayedNotes.INSTANCE);
        for (Identifier element : COVERED) {
            HudElementRegistry.replaceElement(element, vanilla -> (graphics, delta) -> {
                if (!coversHud(Minecraft.getInstance())) vanilla.render(graphics, delta);
            });
        }
        // After chat, whose lines reach up to where the action bar is: the hint rises above those
        // showing, and nothing of the game's own is drawn over it.
        HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT, MusicContent.id("seated_hint"), PianoUi::renderSeatedHint);
        // Chat the screen has no room to show goes to its status line. Fabric tells of player chat once
        // the game has let it through, but of a system message before the game's own check.
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, received) -> chatArrived(message));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay && !leftOutOfChat(Minecraft.getInstance(), message)) chatArrived(message);
        });
        // An unsaved take is written to a file before the play layer forgets the recording, and the
        // session belongs to one world.
        WorldExit.register(WorldExit.Step.KEEP, () -> TakeKeeper.keep(Minecraft.getInstance()));
        WorldExit.register(WorldExit.Step.FORGET, () -> {
            TakePreview.cut();
            seatedHint = null;
            MidiAutoConnect.forget();
            PianoSession.reset();
        });
    }

    /** True while the piano screen is open, which covers the game's HUD and shows chat itself. */
    public static boolean coversHud(Minecraft mc) {
        return mc.screen instanceof PianoScreen;
    }

    /**
     * An action bar message is about to be shown (from {@code Gui.setOverlayMessage}). While the
     * piano screen is open it goes to the status line instead, and true is returned so the game
     * does not keep it for when the screen closes: the piano's own refusals as errors, anything
     * else as news, which waits behind the piano's own messages. The game's "press Shift to
     * dismount" is left out there (on the screen Shift does nothing; Stand up is on the Play tab).
     * Any other message takes the place of the seated hint.
     */
    public static boolean actionBar(Component message) {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof PianoScreen screen)) {
            seatedHint = null;
            return false;
        }
        if (isKey(message, "mount.onboard")) return true;
        if (fromMusic(message)) screen.status().show(message, Feedback.Kind.ERROR);
        else screen.status().aside(message, Feedback.Kind.INFO, false);
        return true;
    }

    private static void chatArrived(Component message) {
        if (Minecraft.getInstance().screen instanceof PianoScreen screen) screen.chatArrived(message);
    }

    /**
     * True for a system message the game keeps out of chat: one that names ("&lt;name&gt; …") a
     * player the player blocked or hid, while Hide Matched Names is on (the default), as
     * {@code ChatListener.handleSystemMessage} decides. Servers that pass chat on as system messages
     * send players' chat this way.
     */
    private static boolean leftOutOfChat(Minecraft mc, Component message) {
        return mc.options.hideMatchedNames().get() && mc.isBlocked(((ChatListenerInvoker) mc.getChatListener()).music$guessSender(message));
    }

    /**
     * Tells a seated player who closed the screen how to open it again (the Open Piano key, unless it
     * is unbound, or a right click) and how to stand up, where the action bar would (or above chat).
     */
    static void showSeatedHint(Minecraft mc) {
        Component standUp = mc.options.keyShift.getTranslatedKeyMessage();
        seatedHint = openKey.isUnbound() ? Component.translatable("music.screen.closed_seated.click", standUp)
            : Component.translatable("music.screen.closed_seated", openKey.getTranslatedKeyMessage(), standUp);
        seatedHintSince = mc.gui.getGuiTicks();
        seatedHintRise = 0;
        seatedHintArea = ScreenRectangle.empty();
    }

    /** True while the hint for a seated player who closed the screen shows. */
    public static boolean seatedHintShowing() {
        return seatedHint != null;
    }

    /** Where the seated hint's backing was drawn last frame (empty before it first is). */
    public static ScreenRectangle seatedHintArea() {
        return seatedHintArea;
    }

    /**
     * The seated hint, drawn where the action bar is on a dark backing whatever the player's text
     * background setting (from the bench it lies over the white keys), or above chat's lines when they
     * reach up to there: closing the screen puts its errors into chat at that moment, and friends keep
     * talking. It only rises while it shows, so a chat line fading under it does not make it jump back.
     * It fades out at the end, and goes once the player opens a screen or leaves the bench.
     */
    private static void renderSeatedHint(GuiGraphics g, DeltaTracker delta) {
        Component hint = seatedHint;
        if (hint == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || PianoSeats.of(mc.player) == null || mc.screen != null) {
            seatedHint = null;
            return;
        }
        float left = HINT_TICKS - (mc.gui.getGuiTicks() - seatedHintSince) - delta.getGameTimeDeltaPartialTick(false);
        if (left <= 0) {
            seatedHint = null;
            return;
        }
        float fade = Math.min(1f, left / HINT_FADE_TICKS);
        if (mc.options.hideGui) return;
        Font font = mc.font;
        int width = font.width(hint);
        int x = g.guiWidth() / 2 - width / 2;
        int top = hintTop(g.guiHeight(), font.lineHeight, chatTop(mc, g.guiHeight(), x - HINT_PADDING));
        seatedHintRise = Math.max(seatedHintRise, g.guiHeight() - top);
        int y = g.guiHeight() - seatedHintRise;
        ScreenRectangle area = new ScreenRectangle(x - HINT_PADDING, y - HINT_PADDING, width + 2 * HINT_PADDING, font.lineHeight + 2 * HINT_PADDING - 1);
        seatedHintArea = area;
        g.nextStratum();
        g.fill(area.left(), area.top(), area.right(), area.bottom(), ARGB.color(Math.round(HINT_BACKING_ALPHA * fade), 0));
        g.drawString(font, hint, x, y, ARGB.white(fade), true);
    }

    /**
     * Where the seated hint's text goes: where the game puts the action bar's, or higher, so that its
     * backing ends above {@code chatTop}, the top of chat's lines showing.
     */
    static int hintTop(int guiHeight, int lineHeight, int chatTop) {
        return Math.min(guiHeight - ACTION_BAR_RISE - 4, chatTop - lineHeight - HINT_PADDING);
    }

    /**
     * The top of chat's lines showing in the HUD now, or {@code guiHeight} when none show or chat ends
     * left of {@code left}. Chat's backing runs its whole width, from the left edge of the screen.
     */
    private static int chatTop(Minecraft mc, int guiHeight, int left) {
        if (mc.options.chatVisibility().get() == ChatVisiblity.HIDDEN) return guiHeight;
        double scale = mc.options.chatScale().get();
        // Chat's text is indented 4 units and its backing reaches 8 past chat's width.
        int right = Mth.floor((Mth.ceil(ChatComponent.getWidth(mc.options.chatWidth().get()) / scale) + 12) * scale);
        if (right <= left) return guiHeight;
        ChatComponent chat = mc.gui.getChat();
        int rows = chatRowsShowing(((ChatComponentAccessor) chat).music$lines(), chat.getLinesPerPage(), mc.gui.getGuiTicks());
        int pitch = (int) (9 * (mc.options.chatLineSpacing().get() + 1));
        return rows == 0 ? guiHeight : chatTop(guiHeight, rows, pitch, scale);
    }

    /** The top of chat's lowest {@code rows} rows, {@code pitch} apart before chat's {@code scale}. */
    static int chatTop(int guiHeight, int rows, int pitch, double scale) {
        return Mth.floor((Mth.floor((guiHeight - CHAT_BOTTOM) / scale) - rows * pitch) * scale);
    }

    /**
     * How many of chat's lowest rows (at most {@code rows}, those it has room for) reach up to its
     * highest line still showing at GUI tick {@code now}: chat gives every line a row counted from the
     * newest, shown or not.
     */
    static int chatRowsShowing(List<GuiMessage.Line> newestFirst, int rows, int now) {
        for (int k = Math.min(newestFirst.size(), rows) - 1; k >= 0; k--) {
            if (now - newestFirst.get(k).addedTime() < CHAT_LINE_TICKS) return k + 1;
        }
        return 0;
    }

    /**
     * How many of chat's lowest rows to show when {@code fit} of them fit: the most whose top row
     * starts a message, so that no message shows its last rows without its first (they read as half a
     * sentence); only a newest message taller than all of them shows its last {@code fit} rows. Rows
     * are counted from the newest (chat is not scrolled while it is closed: closing it scrolls it back),
     * and each message's last row ends its entry.
     */
    static int rowsFromMessageStart(List<GuiMessage.Line> newestFirst, int fit) {
        for (int rows = fit; rows > 0; rows--) {
            // The top row starts its message when the row above it ends another, or there is none.
            if (rows >= newestFirst.size() || newestFirst.get(rows).endOfEntry()) return rows;
        }
        return Math.max(0, fit);
    }

    /** Opens the screen of the piano the player sits at; nothing happens when they are not seated (P is also vanilla's social key). */
    private static void reopen(Minecraft mc) {
        if (mc.player == null || mc.screen != null) return;
        PianoSeats.Seat seat = PianoSeats.of(mc.player);
        if (seat == null) return;
        PianoScreen.open(seat.controller(), null, PianoScreen.mayPlay(Pianos.at(seat.controller())));
    }

    /**
     * Why the screen the server opened finds the player standing, in a line for its status: how to sit
     * down, or that someone else is at the bench and they can play along standing. Null when they sit,
     * and when the server's own message says why (no room for their head, or the piano is its owner's).
     */
    static @Nullable Component standingNote(MusicPayloads.OpenPiano opened) {
        return switch (opened.standing()) {
            case MusicPayloads.OpenPiano.SNEAKING -> Component.translatable("music.screen.standing");
            case MusicPayloads.OpenPiano.BENCH_TAKEN -> Component.translatable("music.screen.standing.bench_taken", opened.performer());
            case MusicPayloads.OpenPiano.RIDING -> Component.translatable("music.screen.standing.riding");
            case MusicPayloads.OpenPiano.COULD_NOT_SIT -> Component.translatable("music.screen.standing.could_not_sit");
            default -> null;
        };
    }

    /** True for the piano's own messages (refusals, warnings), which are all translated "music." keys. */
    private static boolean fromMusic(Component message) {
        return message.getContents() instanceof TranslatableContents t && t.getKey().startsWith("music.");
    }

    private static boolean isKey(Component message, String key) {
        return message.getContents() instanceof TranslatableContents t && t.getKey().equals(key);
    }
}
