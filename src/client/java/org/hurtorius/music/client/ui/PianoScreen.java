package org.hurtorius.music.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.PlainTextButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.TabButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.SoundOptionsScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.util.Mth;
import org.hurtorius.music.client.ClientPrefs;
import org.hurtorius.music.client.play.Performer;
import org.hurtorius.music.client.play.Pianos;
import org.hurtorius.music.client.song.ClientSongs;
import org.hurtorius.music.mixin.client.ChatComponentAccessor;
import org.hurtorius.music.net.MusicPayloads;
import org.hurtorius.music.piano.GrandPianoBlockEntity;
import org.hurtorius.music.piano.Pedal;
import org.hurtorius.music.piano.PianoLayout;
import org.hurtorius.music.piano.PlayAccess;
import org.hurtorius.music.seat.PianoSeatEntity;
import org.hurtorius.music.song.SongInfo;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The piano's screen, opened by sitting at a piano (or with the "Open piano" key while seated). It
 * does not pause the game and does not cover the world: the tabs (Play, Songs, Sound, Piano,
 * Devices) sit on a translucent panel at the top, which a click on the open tab hides and shows,
 * and the sheet strip and the 88-key keyboard run along the bottom. While it is open (and no text
 * box is being typed in) the computer keyboard plays the piano by physical key position, the space
 * bar works the sustain pedal, the arrow keys change octave and velocity (or move a slider the
 * pointer rests on after clicking it), and Enter opens chat, which comes back here (Enter goes
 * first to a button or list row reached with Tab, and opens chat when that takes no Enter). Chat
 * itself is drawn in the room between the panel and the strip, where the game's own would be hidden
 * by the keys, as many whole lines as fit from the start of a message; a tab that leaves no room for
 * it gets chat's news on its status line, without the lines the game keeps out of chat. Opened from
 * the game, the screen gets the pointer in the middle of the window, where the player did not put
 * it: it hovers nothing (no tooltip over the hint or chat) until it moves, a visit to chat or a
 * question included. When the player cannot hear the piano, the status line says why in the hint's
 * place, as a link to where that is changed, and the game's subtitles show above the keyboard. Esc
 * in a text box leaves the box; otherwise it closes the screen, and a seated player is told how to
 * open it again or stand up. Closing it lets go of everything it held; a seated performer keeps
 * playing with a MIDI keyboard after it closes. Everything is built from vanilla widgets except the
 * keyboard and the strip, which are drawn in the same style.
 */
public final class PianoScreen extends Screen {
    /** The screen closes when the player is this far from the piano without sitting at it. */
    static final double REACH = 12;
    private static final int PANEL_SHADE = 0x60000000;
    /** Ctrl, Alt and Super: with these held, keys are shortcuts, not notes. */
    private static final int SHORTCUT_MODIFIERS = GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_ALT | GLFW.GLFW_MOD_SUPER;
    private static final long PREFS_SAVE_NANOS = 1_000_000_000L, REFUSAL_NANOS = 2_000_000_000L;
    private static final Tooltip HIDE_TIP = Tooltip.create(Component.translatable("music.screen.tab.hide"));
    /** Chat's newest line ends this far above the strip (or the keyboard when there is no strip). */
    private static final int CHAT_GAP = 2;
    /** Minecraft's lowest subtitle ends this far above the bottom of the screen; here it ends {@link #SUBTITLE_GAP} above the keyboard. */
    private static final int SUBTITLE_FOOT = 30, SUBTITLE_GAP = 2;

    private final BlockPos piano;
    private boolean canPlay;
    private final PlayingInput input;
    private final SettingsSender settings;
    private final StatusLine status = new StatusLine();
    private final KeyboardWidget keyboard;
    private final SheetStrip strip;
    private ScreenLayout geometry = ScreenLayout.compute(1, 1, false);
    private @Nullable TabNavigationBar tabBar;
    private @Nullable TabManager tabManager;
    private List<ScreenTab> tabs = List.of();
    private @Nullable SoundTab soundTab;
    private boolean rebuildPending;
    private boolean windowWasActive = true;
    private boolean prefsDirty;
    private long prefsChangedAt;
    private long refusedAt;
    private boolean libraryRequested;
    private @Nullable String renaming;
    private @Nullable String renameText;
    private @Nullable EditBox focusAfterBuild;
    /** The open tab's "click again to hide" tooltip is given once the pointer has left the tab it clicked. */
    private boolean hideTipGiven;
    /** Chat to open on the next tick (after the key that asked has been typed), or null. */
    private ChatComponent.@Nullable ChatMethod chatWanted;
    /** The focus was moved with Tab (not by a click or a rebuild): Enter then goes to what has it first. */
    private boolean focusFromTab;
    /** Where chat was drawn last frame (no height when it had no room), and where the status line's text (or the sound notice) was. */
    private ScreenRectangle chatArea = ScreenRectangle.empty(), statusArea = ScreenRectangle.empty();
    /**
     * The pointer is where the game put it on opening the screen from the game (the middle of the
     * window), not where the player did: nothing under it is hovered, and no tooltip covers the hint
     * or chat, until it moves, clicks or scrolls (in chat or a question too: then it is not where it
     * was parked on coming back). Where it was parked is learnt on the first frame.
     */
    private boolean pointerParked;
    private int parkedX = Integer.MIN_VALUE, parkedY = Integer.MIN_VALUE;
    /** Why this player does not hear this piano, on the status line (a link to where the remedy is), and the words it shows; null while they hear it. */
    private @Nullable AbstractWidget soundNotice;
    private @Nullable Component soundNoticeText;

    /**
     * @param piano    the piano's controller block
     * @param standing why the player stands rather than sits at its bench, for the status line; null when they sit
     * @param canPlay  whether the player may play it
     */
    public PianoScreen(BlockPos piano, @Nullable Component standing, boolean canPlay) {
        super(Component.translatable("music.screen.title"));
        this.piano = piano.immutable();
        this.canPlay = canPlay;
        this.input = new PlayingInput(this::refuse);
        this.settings = new SettingsSender(this.piano);
        this.keyboard = new KeyboardWidget(this.piano, input);
        this.strip = new SheetStrip(this.piano);
        if (standing != null) status.show(standing, Feedback.Kind.INFO);
    }

    /** Starts performing at the piano and opens its screen; from the server's OpenPiano and the open key. */
    public static void open(BlockPos piano, @Nullable Component standing, boolean canPlay) {
        Performer.begin(piano, canPlay);
        Minecraft.getInstance().setScreen(new PianoScreen(piano, standing, canPlay));
        MidiAutoConnect.atPiano();
    }

    /** Rebuilds the open piano screen, if there is one, after something it shows changed. */
    static void refreshOpen() {
        if (Minecraft.getInstance().screen instanceof PianoScreen screen) screen.requestRebuild();
    }

    /** True while the piano screen is open with its panel showing {@code tab}. */
    static boolean showingTab(int tab) {
        return Minecraft.getInstance().screen instanceof PianoScreen && !PianoSession.panelHidden && PianoSession.tab == tab;
    }

    // ------------------------------------------------------------------ building

    /**
     * The screen is set. Opened from the game, whose grabbed pointer the game lets go in the middle of
     * the window, the pointer counts only once the player moves it; so too after sending a chat
     * message, which hands the pointer to the game for a moment. Coming back from chat closed with Esc,
     * or from a question, the pointer is as it was: parked still unless the player moved it meanwhile.
     */
    @Override
    public void added() {
        if (!minecraft.mouseHandler.isMouseGrabbed()) return;
        pointerParked = true;
        parkedX = Integer.MIN_VALUE;
        parkedY = Integer.MIN_VALUE;
    }

    @Override
    protected void init() {
        soundNotice = null;
        soundNoticeText = null;
        geometry = ScreenLayout.compute(width, height, PianoSession.panelHidden);
        hideTipGiven = false;
        if (!libraryRequested) {
            libraryRequested = true;
            ClientSongs.requestLibrary();
        }
        TabManager manager = new TabManager(widget -> addRenderableWidget(widget), this::removeWidget, this::tabSelected, tab -> {
        });
        tabManager = manager;
        PlayTab play = new PlayTab(this);
        SongsTab songs = new SongsTab(this);
        SoundTab sound = new SoundTab(this);
        PianoTab body = new PianoTab(this);
        DevicesTab devices = new DevicesTab(this);
        tabs = List.of(play, songs, sound, body, devices);
        soundTab = sound;
        for (ScreenTab tab : tabs) tab.build();
        TabNavigationBar bar = TabNavigationBar.builder(manager, width).addTabs(tabs.toArray(new Tab[0])).build();
        tabBar = bar;
        addRenderableWidget(bar);
        bar.setWidth(width);
        bar.arrangeElements();
        ScreenRectangle k = geometry.keyboard(), s = geometry.strip();
        keyboard.setRectangle(k.width(), k.height(), k.left(), k.top());
        addRenderableWidget(keyboard);
        strip.setRectangle(s.width(), s.height(), s.left(), s.top());
        if (geometry.hasStrip()) addRenderableOnly(strip);
        manager.setTabArea(geometry.content());
        if (!PianoSession.panelHidden) bar.selectTab(Math.max(0, Math.min(tabs.size() - 1, PianoSession.tab)), false);
        EditBox focus = focusAfterBuild;
        focusAfterBuild = null;
        if (focus != null && children().contains(focus)) setFocused(focus);
    }

    /**
     * A tab was opened: by the player, or again by a rebuild of the widgets. It does not count as
     * the player acting (a click or key that changed tabs already did), or an error would leave
     * whenever something made the screen rebuild.
     */
    private void tabSelected(Tab tab) {
        int index = tabs.indexOf(tab);
        if (index < 0) return;
        PianoSession.tab = index;
        // The tip that a click on the open tab hides the panel comes once the pointer has left it:
        // right after the click it would cover the tab's first row.
        if (tabBar != null) for (int i = 0; i < tabs.size(); i++) tabBar.setTabTooltip(i, null);
        hideTipGiven = false;
        if (PianoSession.panelHidden) {
            PianoSession.panelHidden = false;
            requestRebuild();
        }
        if (index == PianoSession.SONGS) ClientSongs.requestLibrary();
    }

    /** Leaves the focus alone: a focused text box would take the computer keys away from the piano. */
    @Override
    protected void setInitialFocus() {
    }

    /** Rebuilds the widgets before the next frame (never in the middle of handling an event). */
    void requestRebuild() {
        rebuildPending = true;
    }

    private void rebuildIfAsked() {
        if (!rebuildPending) return;
        rebuildPending = false;
        rebuildWidgets();
    }

    /**
     * Builds the widgets again (asked for, a resize, or coming back from chat), keeping the cursor in
     * the text box that had it: letters typed after a rebuild must not turn into notes.
     */
    @Override
    protected void rebuildWidgets() {
        EditBox typed = typing() && deepestFocus() instanceof EditBox box ? box : null;
        Component name = typed == null ? null : typed.getMessage();
        int cursor = typed == null ? 0 : typed.getCursorPosition();
        focusFromTab = false;
        super.rebuildWidgets();
        if (name == null || getFocused() != null) return;
        for (GuiEventListener child : children()) {
            if (child instanceof EditBox box && box.isVisible() && box.getMessage().equals(name)) {
                setFocused(box);
                box.setCursorPosition(Math.min(cursor, box.getValue().length()));
                box.setHighlightPos(box.getCursorPosition());
                return;
            }
        }
    }

    // ------------------------------------------------------------------ what tabs use

    ScreenLayout geometry() {
        return geometry;
    }

    BlockPos piano() {
        return piano;
    }

    SettingsSender settings() {
        return settings;
    }

    PlayingInput input() {
        return input;
    }

    StatusLine status() {
        return status;
    }

    /** True when the player may play this piano and its songs (rechecked every tick). */
    boolean canEdit() {
        return canPlay;
    }

    /** True when the player may change this piano's sound and settings, not only play it (see {@link PlayAccess}). */
    boolean canTune() {
        GrandPianoBlockEntity be = Pianos.at(piano);
        return canPlay && be != null && (be.settings().access().everyoneTunes() || ownerOrOperator(be));
    }

    /** True for the piano's owner and operators, who decide who may play. */
    boolean canChangeAccess() {
        GrandPianoBlockEntity be = Pianos.at(piano);
        return be != null && ownerOrOperator(be);
    }

    /** True while the local player sits on this piano's bench. */
    boolean seatedHere() {
        LocalPlayer player = minecraft.player;
        return player != null && player.getVehicle() instanceof PianoSeatEntity seat && seat.anchor().equals(piano);
    }

    /** True when the local player added the song, or may manage every song (operator, or the host of a singleplayer world). */
    boolean mayManage(SongInfo info) {
        LocalPlayer player = minecraft.player;
        if (player == null) return false;
        return info.author().equals(player.getGameProfile().name()) || trusted();
    }

    @Nullable String renaming() {
        return renaming;
    }

    @Nullable String renameText() {
        return renameText;
    }

    void setRenaming(@Nullable String songId) {
        renaming = songId;
        renameText = null;
        requestRebuild();
    }

    void setRenameText(String text) {
        renameText = text;
    }

    /** Puts the cursor in a text box the tab being built just made (renaming, naming an import). */
    void focusAfterBuild(EditBox box) {
        focusAfterBuild = box;
    }

    /** Sends a packet, or tells the player the server cannot take it. */
    void send(CustomPacketPayload payload) {
        if (ClientPlayNetworking.canSend(payload.type())) ClientPlayNetworking.send(payload);
        else Feedback.error(Component.translatable("music.screen.not_connected"));
    }

    /** Sends a playback command without a song or value. */
    void control(int action) {
        send(MusicPayloads.PlaybackControl.of(piano, action));
    }

    /** Asks a yes/no question in vanilla's confirmation screen (which keeps the game running) and comes back here. */
    void confirm(Component title, Component message, Runnable yes) {
        minecraft.setScreen(new ConfirmScreen(ok -> {
            if (ok) yes.run();
            minecraft.setScreen(this);
        }, title, message) {
            @Override
            public boolean isPauseScreen() {
                return false;
            }
        });
    }

    /** Leaves the bench and closes the screen. */
    void standUp() {
        send(new MusicPayloads.StandUp(piano));
        Performer.end();
        onClose();
    }

    /** The preferences changed; they are saved once they rest a moment. */
    void prefsChanged() {
        prefsDirty = true;
        prefsChangedAt = System.nanoTime();
    }

    /** Says (not too often) why the player's keys make no sound. */
    void refuse() {
        long now = System.nanoTime();
        if (now - refusedAt < REFUSAL_NANOS) return;
        refusedAt = now;
        GrandPianoBlockEntity be = Pianos.at(piano);
        status.show(PlayingInput.refusal(be == null ? "" : be.ownerName()), Feedback.Kind.ERROR);
    }

    // ------------------------------------------------------------------ who may do what

    private boolean trusted() {
        LocalPlayer player = minecraft.player;
        return player != null && (player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER) || minecraft.hasSingleplayerServer());
    }

    /** The client's view of the server's rule for the owner: no owner, the owner, or an operator. */
    static boolean ownerOrOperator(GrandPianoBlockEntity be) {
        LocalPlayer player = Minecraft.getInstance().player;
        UUID owner = be.owner();
        return owner == null || player != null && (owner.equals(player.getUUID()) || player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER));
    }

    /** The client's view of the server's rule for playing: everyone may, or only the owner and operators. */
    static boolean mayPlay(@Nullable GrandPianoBlockEntity be) {
        return be != null && (be.settings().access().everyonePlays() || ownerOrOperator(be));
    }

    // ------------------------------------------------------------------ every tick

    @Override
    public void tick() {
        rebuildIfAsked();
        GrandPianoBlockEntity be = Pianos.at(piano);
        LocalPlayer player = minecraft.player;
        if (be == null || player == null) {
            // Nothing is left to ring: a take playing back stops at once.
            TakePreview.cut();
            Feedback.error(Component.translatable("music.screen.piano_gone"));
            onClose();
            return;
        }
        if (chatWanted != null) {
            ChatComponent.ChatMethod method = chatWanted;
            chatWanted = null;
            openChat(method);
            return;
        }
        if (!seatedHere() && player.position().distanceToSqr(PianoLayout.center(piano, be.facing())) > REACH * REACH) {
            onClose();
            return;
        }
        boolean windowActive = minecraft.isWindowActive();
        if (windowWasActive && !windowActive) input.releaseAll();
        windowWasActive = windowActive;
        input.poll(minecraft.getWindow());
        boolean may = mayPlay(be);
        if (may != canPlay) {
            canPlay = may;
            Performer.begin(piano, may);
        }
        settings.tick();
        for (ScreenTab tab : tabs) tab.tick();
        if (chatShows()) status.dropChat();
        if (input.takePrefsChanged()) prefsChanged();
        if (prefsDirty && System.nanoTime() - prefsChangedAt > PREFS_SAVE_NANOS) {
            prefsDirty = false;
            ClientPrefs.get().save();
        }
    }

    // ------------------------------------------------------------------ input

    /** The widget that has the keyboard, inside whatever holds it. */
    private @Nullable GuiEventListener deepestFocus() {
        GuiEventListener focus = getFocused();
        while (focus instanceof ContainerEventHandler container && container.getFocused() != null) focus = container.getFocused();
        return focus;
    }

    /** True while a text box has the keyboard: then keys type instead of playing. */
    private boolean typing() {
        return deepestFocus() instanceof EditBox box && box.canConsumeInput();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        status.acted();
        if (event.isEscape() && typing()) {
            // Esc leaves the text box (a rename is called off), as the hint says; another Esc closes.
            if (renaming != null) setRenaming(null);
            setFocused(null);
            return true;
        }
        if (event.isEscape() && shouldCloseOnEsc()) {
            boolean seated = seatedHere();
            onClose();
            if (seated) PianoUi.showSeatedHint(minecraft);
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_TAB) {
            if (soundTab != null && soundTab.complete()) return true;
            focusFromTab = true;
        }
        boolean shortcut = (event.modifiers() & SHORTCUT_MODIFIERS) != 0;
        if (!typing() && !shortcut) {
            // Enter goes to what the player reached with Tab (a button, a list row); when that takes
            // no Enter (a tab, a button that has gone inactive, the keys), Enter opens chat.
            if (isEnter(event) && focusFromTab && super.keyPressed(event)) return true;
            ChatComponent.ChatMethod chat = chatMethod(event);
            if (chat != null) {
                chatWanted = chat;
                return true;
            }
            // A slider clicked and still under the pointer takes the arrows a step at a time.
            if ((event.isLeft() || event.isRight()) && deepestFocus() instanceof ValueSlider slider && slider.isHovered() && slider.keyPressed(event)) return true;
            ClientPrefs prefs = ClientPrefs.get();
            int velocity = prefs.keyVelocity;
            boolean sustain = Performer.isPedalDown(Pedal.SUSTAIN);
            if (input.keyPressed(event.key())) {
                if (input.takePrefsChanged()) prefsChanged();
                noteChange(velocity, sustain);
                return true;
            }
        }
        if (tabBar != null && tabBar.keyPressed(event)) return true;
        return super.keyPressed(event);
    }

    /**
     * What a key just changed, said briefly on the status line unless the Play tab shows it: the
     * velocity the arrow keys set, and the sustain pedal when the space bar toggles it.
     */
    private void noteChange(int velocityBefore, boolean sustainBefore) {
        if (showingTab(PianoSession.PLAY)) return;
        ClientPrefs prefs = ClientPrefs.get();
        if (prefs.keyVelocity != velocityBefore) {
            status.note(Component.translatable("music.screen.velocity", prefs.keyVelocity));
        } else if (!prefs.holdSustain && Performer.isPedalDown(Pedal.SUSTAIN) != sustainBefore) {
            status.note(Component.translatable(Performer.isPedalDown(Pedal.SUSTAIN) ? "music.screen.sustain.down" : "music.screen.sustain.up"));
        }
    }

    /**
     * The chat a key opens: Enter (when what Tab reached did not take it), or the game's chat and
     * command keys when they are not note keys; null for other keys.
     */
    private ChatComponent.@Nullable ChatMethod chatMethod(KeyEvent event) {
        int key = event.key();
        if (isEnter(event)) return ChatComponent.ChatMethod.MESSAGE;
        if (ComputerKeys.isNoteKey(key)) return null;
        if (minecraft.options.keyCommand.matches(event)) return ChatComponent.ChatMethod.COMMAND;
        if (minecraft.options.keyChat.matches(event)) return ChatComponent.ChatMethod.MESSAGE;
        return null;
    }

    private static boolean isEnter(KeyEvent event) {
        return event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER;
    }

    /** Opens the game's chat; sending a message or closing it comes back to this screen. */
    private void openChat(ChatComponent.ChatMethod method) {
        Minecraft.ChatStatus chat = minecraft.getChatStatus();
        if (!chat.isChatAllowed(minecraft.isLocalServer())) {
            status.show(chat.getMessage(), Feedback.Kind.ERROR);
            return;
        }
        PianoScreen piano = this;
        minecraft.gui.getChat().openScreen(method, (text, draft) -> new ChatScreen(text, draft) {
            @Override
            public boolean keyPressed(KeyEvent event) {
                boolean handled = super.keyPressed(event);
                // Enter sent the message and closed chat: back to the keys.
                if (minecraft.screen == null) minecraft.setScreen(piano);
                return handled;
            }

            @Override
            public void onClose() {
                exitReason = ChatScreen.ExitReason.INTENTIONAL;
                minecraft.setScreen(piano);
            }
        });
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        if (input.keyReleased(event.key())) return true;
        return super.keyReleased(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        rebuildIfAsked();
        status.acted();
        focusFromTab = false;
        pointerParked = false;
        if (event.button() == 0 && tabBar != null && tabManager != null && tabManager.getCurrentTab() != null) {
            for (GuiEventListener child : tabBar.children()) {
                if (child instanceof TabButton button && button.isMouseOver(event.x(), event.y()) && button.tab() == tabManager.getCurrentTab()) {
                    // The open tab, clicked again: put the panel away to see the piano.
                    button.playDownSound(minecraft.getSoundManager());
                    PianoSession.panelHidden = true;
                    setFocused(null);
                    requestRebuild();
                    return true;
                }
            }
        }
        if (super.mouseClicked(event, doubleClick)) return true;
        // A click on the world takes the focus off any text box, so the keys play again.
        setFocused(null);
        return false;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == 0) input.mouseKey(-1, 0);
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        pointerParked = false;
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }

    /** Dropping MIDI files or recordings (or a folder of them) on the window imports them, one after another. */
    @Override
    public void onFilesDrop(List<Path> files) {
        if (files.isEmpty()) return;
        PianoSession.tab = PianoSession.SONGS;
        PianoSession.panelHidden = false;
        SongFiles.importDropped(files);
        requestRebuild();
    }

    // ------------------------------------------------------------------ drawing

    /**
     * The panel's backdrop, the first thing a frame draws (the game calls this before {@link #render}):
     * a rebuild asked for since the last frame happens here, so the backdrop, the widgets and the
     * status line all come from the same tab.
     */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        rebuildIfAsked();
        ScreenRectangle panel = shown().panel();
        if (!PianoSession.panelHidden && panel.height() > 0) {
            renderMenuBackground(g, panel.left(), panel.top(), panel.width(), panel.height());
            g.fill(panel.left(), panel.top(), panel.right(), panel.bottom(), PANEL_SHADE);
            g.blit(RenderPipelines.GUI_TEXTURED, Screen.INWORLD_FOOTER_SEPARATOR, panel.left(), panel.bottom(), 0f, 0f, panel.width(), 2, 32, 2);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (pointerParked && parkedX == Integer.MIN_VALUE) {
            parkedX = mouseX;
            parkedY = mouseY;
        } else if (pointerParked && (mouseX != parkedX || mouseY != parkedY)) {
            pointerParked = false;
        }
        // A parked pointer is off the screen to everything drawn.
        int x = pointerParked ? -1 : mouseX, y = pointerParked ? -1 : mouseY;
        for (ScreenTab tab : tabs) tab.frame();
        giveHideTip(x, y);
        showSoundNotice();
        // Why this player does not hear the piano stands where the hint would, on the hint's backing.
        ScreenRectangle notice = soundNoticeArea();
        if (notice.height() > 0) g.fill(notice.left(), notice.top(), notice.right(), notice.bottom(), StatusLine.HINT_BACKING);
        super.render(g, x, y, partialTick);
        // With the panel put away only news shows, so the view of the piano stays clear; why a piano is
        // silent shows too (a widget of its own, drawn with the others).
        StatusLine.Shown shown = soundNotice == null && (!PianoSession.panelHidden || status.message() != null) ? statusShown() : null;
        statusArea = shown != null ? shown.block() : notice;
        renderChat(g, x, y, statusArea);
        if (shown != null) {
            // A layer of its own: within one, text is drawn over every fill.
            g.nextStratum();
            status.render(g, font, shown, x, y);
        }
        renderSubtitles(g);
    }

    private void giveHideTip(int mouseX, int mouseY) {
        if (hideTipGiven || PianoSession.panelHidden || tabBar == null || tabManager == null || tabManager.getCurrentTab() == null) return;
        for (GuiEventListener child : tabBar.children()) {
            if (child instanceof TabButton button && button.tab() == tabManager.getCurrentTab() && !button.isMouseOver(mouseX, mouseY)) {
                tabBar.setTabTooltip(PianoSession.tab, HIDE_TIP);
                hideTipGiven = true;
            }
        }
    }

    /**
     * The status line's text this frame: at the foot of the panel, a long message going on below it
     * (as far as the keyboard), or, with the panel put away, just above the strip, going on upward.
     */
    private StatusLine.Shown statusShown() {
        boolean hidden = PianoSession.panelHidden;
        int bound = hidden ? ScreenLayout.TAB_BAR + 2 : geometry.keyboard().top() - 1;
        return status.layout(font, shown().status(), bound, !hidden, hint());
    }

    /**
     * Where chat may go: the room between the panel (and a message running on below it) and the
     * strip (the keyboard, without one), or, with the panel put away, everything above the strip and
     * what the status line shows. Chat's newest line sits at its foot. {@code line} is where the
     * status line's text (or the sound notice in its place) is this frame, no height when it has none.
     */
    private ScreenRectangle chatRoom(ScreenRectangle line) {
        boolean strip = geometry.hasStrip() && !"off".equals(ClientPrefs.get().sheetView);
        int bottom = (strip ? geometry.strip().top() : geometry.keyboard().top()) - CHAT_GAP;
        boolean used = line.width() > 0 && line.height() > 0;
        int top;
        if (PianoSession.panelHidden) {
            top = ScreenLayout.TAB_BAR + 2;
            if (used) bottom = Math.min(bottom, line.top() - CHAT_GAP);
        } else {
            // Below the panel and the line under it.
            top = shown().panel().bottom() + 2;
            if (used) top = Math.max(top, line.bottom() + 1);
        }
        return new ScreenRectangle(0, top, width, Math.max(0, bottom - top));
    }

    /** How far apart chat's lines are, in GUI units, with the player's chat settings. */
    private double chatLinePitch() {
        double spacing = minecraft.options.chatLineSpacing().get();
        return (int) (9 * (spacing + 1)) * minecraft.options.chatScale().get();
    }

    /**
     * The game's chat, which the keyboard would hide where the game draws it, in the room above the
     * strip: as many of its newest lines as fit there whole (a line cut through its letters would look
     * broken), from the start of a message (its last lines alone read as half a sentence), so it never
     * covers the controls or the news. {@code line} is where the status line's text is this frame.
     */
    private void renderChat(GuiGraphics g, int mouseX, int mouseY, ScreenRectangle line) {
        ScreenRectangle room = chatRoom(line);
        // The game puts chat's newest line 40 units above the bottom edge, on its own scale's grid:
        // moved to the foot of the room, that line ends here.
        float scale = minecraft.options.chatScale().get().floatValue();
        int rise = height - PianoUi.CHAT_BOTTOM;
        double newestBottom = room.bottom() - rise + Mth.floor(rise / scale) * scale;
        ChatComponent chat = minecraft.gui.getChat();
        int fit = Math.min((int) Math.floor((newestBottom - room.top()) / chatLinePitch()), chat.getLinesPerPage());
        int lines = PianoUi.rowsFromMessageStart(((ChatComponentAccessor) chat).music$lines(), fit);
        int top = Mth.ceil(newestBottom - lines * chatLinePitch());
        chatArea = lines > 0 ? new ScreenRectangle(room.left(), top, room.width(), room.bottom() - top) : ScreenRectangle.empty();
        if (lines <= 0 || minecraft.options.hideGui) return;
        g.nextStratum();
        g.enableScissor(chatArea.left(), chatArea.top(), chatArea.right(), chatArea.bottom());
        g.pose().pushMatrix();
        g.pose().translate(0, room.bottom() - rise);
        minecraft.gui.getChat().render(g, font, minecraft.gui.getGuiTicks(), mouseX, mouseY, false, false);
        g.pose().popMatrix();
        g.disableScissor();
    }

    /**
     * True while chat has room on the screen. Without it (a tab that fills the panel, a long message
     * using the room) chat's news goes to the status line instead.
     */
    boolean chatShows() {
        return chatArea.height() > 0;
    }

    /** A chat message came in; shown on the status line when chat has no room on the screen (it waits behind an error). */
    void chatArrived(Component message) {
        if (!chatShows()) status.aside(message, Feedback.Kind.INFO, true);
    }

    /**
     * While this player does not hear this piano, and no news is showing, the status line says why
     * ({@link SoundNotice}) in as few words as fit: in red when no piano can be heard, in grey when this one
     * is muted by their own Other pianos volume. Where the remedy is somewhere (Music & Sounds, opened over
     * this screen, or the Devices tab) it is a link there, plain words otherwise. Its tooltip has whatever
     * the line had no room for, and where the link goes.
     */
    private void showSoundNotice() {
        ScreenRectangle line = shown().status();
        SoundNotice.Notice notice = status.message() == null ? SoundNotice.at(minecraft, piano) : null;
        Component words = notice == null ? null : notice.within(font, line.width());
        if (!Objects.equals(words, soundNoticeText)) {
            if (soundNotice != null) removeWidget(soundNotice);
            soundNotice = null;
            soundNoticeText = words;
            if (notice != null) {
                String shownText = StatusLine.fit(font, words.getString(), line.width());
                Component text = Component.literal(shownText).withStyle(notice.warning() ? ChatFormatting.RED : ChatFormatting.GRAY);
                boolean cut = !shownText.equals(notice.full().getString());
                Component action = notice.remedy().action();
                AbstractWidget widget = action == null ? new StringWidget(font.width(text), font.lineHeight, text, font)
                    : new PlainTextButton(0, 0, font.width(text), font.lineHeight, text, button -> remedy(notice.remedy()), font) {
                        // What it opens takes over at once; kept focused, the link would stay underlined as if pointed at.
                        @Override
                        public boolean shouldTakeFocusAfterInteraction() {
                            return false;
                        }
                    };
                // The link's own words say where it goes, so the explanation above them leaves that out.
                Component tip = action == null ? (cut ? notice.full() : null) : cut ? notice.why().copy().append("\n").append(action) : action;
                if (tip != null) widget.setTooltip(Tooltip.create(tip));
                soundNotice = addRenderableWidget(widget);
            }
        }
        if (soundNotice != null) {
            soundNotice.setPosition(line.left() + (line.width() - soundNotice.getWidth()) / 2, line.top() + (line.height() - font.lineHeight) / 2 + 1);
        }
    }

    /** Where the sound notice's backing goes, as the hint's would around the same words; no height without a notice. */
    private ScreenRectangle soundNoticeArea() {
        AbstractWidget notice = soundNotice;
        if (notice == null) return ScreenRectangle.empty();
        return new ScreenRectangle(notice.getX() - 2, notice.getY() - 1, notice.getWidth() + 4, font.lineHeight + 1);
    }

    /** Opens where the player can change what keeps this piano from them (a notice that is a link). */
    private void remedy(SoundNotice.Remedy remedy) {
        if (remedy == SoundNotice.Remedy.SOUND_OPTIONS) minecraft.setScreen(new SoundOptionsScreen(this, minecraft.options));
        else if (remedy == SoundNotice.Remedy.DEVICES && tabBar != null) tabBar.selectTab(PianoSession.DEVICES, true);
    }

    /**
     * Subtitles over the world, as Minecraft shows them in game (the screen is in-game UI, so Minecraft
     * leaves them for it to draw), but above the keyboard, which covers their usual corner, and over
     * everything else the screen draws.
     */
    private void renderSubtitles(GuiGraphics g) {
        g.nextStratum();
        g.pose().pushMatrix();
        g.pose().translate(0, Math.min(0, geometry.keyboard().top() - SUBTITLE_GAP - (height - SUBTITLE_FOOT)));
        minecraft.gui.renderDeferredSubtitles();
        g.pose().popMatrix();
    }

    /** The layout as drawn: the panel ends below the open tab's last row. */
    private ScreenLayout shown() {
        Tab open = tabManager == null ? null : tabManager.getCurrentTab();
        return open instanceof ScreenTab tab && !PianoSession.panelHidden ? geometry.fitTo(tab.usedHeight()) : geometry;
    }

    /** What the status line says when there is no news: parts in order of importance (the last ones go first when short of room). */
    private List<Component> hint() {
        if (!canPlay) {
            GrandPianoBlockEntity be = Pianos.at(piano);
            return List.of(PlayingInput.refusal(be == null ? "" : be.ownerName()));
        }
        if (typing()) return List.of(Component.translatable("music.screen.hint.typing"));
        ClientPrefs prefs = ClientPrefs.get();
        List<Component> parts = new ArrayList<>();
        parts.add(Component.translatable("music.screen.hint.keys", keyName(ComputerKeys.LOWER[0]), keyName(ComputerKeys.LOWER[ComputerKeys.LOWER.length - 1]),
            keyName(ComputerKeys.UPPER[0]), keyName(ComputerKeys.UPPER[ComputerKeys.UPPER.length - 1]), ComputerKeys.rangeLabel(prefs.keyboardBase)));
        // A piano someone left transposed plays every key off its label: worth saying on every tab.
        int transpose = settings.current().transpose();
        if (transpose != 0) parts.add(Component.translatable("music.screen.hint.transpose", Formats.signed(transpose)).withStyle(ChatFormatting.GOLD));
        if (MidiAutoConnect.choiceNeeded()) parts.add(Component.translatable("music.screen.hint.midi").withStyle(ChatFormatting.YELLOW));
        parts.add(Component.translatable("music.screen.hint.sustain", keyName(GLFW.GLFW_KEY_SPACE)));
        // Chat's own key (T) plays a note here, so nothing else would say how to chat; what the
        // arrows change shows as they change it (the keys' labels move, a note gives the velocity).
        parts.add(Component.translatable("music.screen.hint.chat", keyName(GLFW.GLFW_KEY_ENTER)));
        parts.add(Component.translatable("music.screen.hint.octave"));
        parts.add(Component.translatable("music.screen.hint.velocity", prefs.keyVelocity));
        return parts;
    }

    private static String keyName(int glfwKey) {
        return InputConstants.Type.KEYSYM.getOrCreate(glfwKey).getDisplayName().getString();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * A deliberate close (Esc, Stand up, walking out of reach) also stops a take playing back, its
     * dampers falling on the piano still there; with the piano gone it was cut off already.
     */
    @Override
    public void onClose() {
        TakePreview.stop();
        super.onClose();
    }

    /** The world stays in view under the screen, so Minecraft treats it as in-game UI: its subtitles keep showing. */
    @Override
    public boolean isInGameUi() {
        return true;
    }

    /**
     * The screen closes, or makes way for chat, a question or the sound options: the errors it showed
     * go to chat, to be read again there.
     */
    @Override
    public void removed() {
        input.releaseAll();
        strip.release();
        if (Feedback.errorsToChat(minecraft)) status.dismissError();
        if (soundTab != null) soundTab.commitTyped();
        settings.flush(true);
        if (prefsDirty) {
            prefsDirty = false;
            ClientPrefs.get().save();
        }
    }

    // ------------------------------------------------------------------ for tests and tools

    /** How far down the on-screen keyboard shows a key (0..1). */
    public float shownKeyDepth(int key) {
        return keyboard.depth(key);
    }

    /** GUI position of a key: its centre across, {@code along} its length (0 far end, 1 front edge). */
    public double[] keyPoint(int key, double along) {
        return keyboard.point(key, along);
    }

    /** Song position (or recording time) the sheet strip showed last frame, -1 when it showed nothing. */
    public long sheetPosition() {
        return strip.shownPosition();
    }

    /** True once the open tab carries its "click again to hide" tooltip (given when the pointer has left the tab). */
    public boolean tabTipGiven() {
        return hideTipGiven;
    }

    /** Index of the open tab (0 Play, 1 Songs, 2 Sound, 3 Piano, 4 Devices), or -1 while the panel is hidden. */
    public int openTab() {
        return PianoSession.panelHidden ? -1 : PianoSession.tab;
    }

    /** Imports a MIDI file exactly as the Import MIDI button does once its file dialog returns. */
    public void importMidiFile(Path file) {
        SongFiles.importMidi(file);
    }

    /** The message on the status line now, or null while it shows a hint. */
    public @Nullable Component statusMessage() {
        return status.message();
    }

    /** The hint the status line drew last, or empty when it last showed a message instead. */
    public String statusHint() {
        return status.drawnHint();
    }

    /** Where the status line's text (with the lines a long message runs on to), or the sound notice in its place, was drawn last frame. */
    public ScreenRectangle statusArea() {
        return statusArea;
    }

    /** The status line's own place: the line under the open tab's last row, or above the strip with the panel put away. */
    public ScreenRectangle statusLine() {
        return shown().status();
    }

    /** The room chat was drawn in last frame, as many of its rows as fit whole; no height when it had none. */
    public ScreenRectangle chatArea() {
        return chatArea;
    }

    /** True while the pointer is where the game put it on opening the screen, so nothing under it is hovered. */
    public boolean pointerParked() {
        return pointerParked;
    }

    /** The controller block of the piano this screen plays. */
    public BlockPos pianoPos() {
        return piano;
    }
}
