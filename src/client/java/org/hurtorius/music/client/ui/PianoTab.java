package org.hurtorius.music.client.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import org.hurtorius.music.client.play.Pianos;
import org.hurtorius.music.piano.GrandPianoBlockEntity;
import org.hurtorius.music.piano.PianoSettings;
import org.hurtorius.music.piano.PlayAccess;
import org.hurtorius.music.piano.RedstoneMode;
import org.hurtorius.music.piano.SheetMode;

import java.util.ArrayList;
import java.util.List;

/**
 * The instrument itself: the lid (open on its prop, or closed: softer and darker), the music desk
 * and what it shows, how redstone drives the self-playing, and who may play it and who may change
 * it. Only the owner or an operator may change who plays; everyone sees who the owner is. Whoever may
 * play opens the lid and the desk; redstone, like the sound, is the owner's unless all may change it.
 */
final class PianoTab extends ScreenTab {
    private CycleButton<Boolean> lid, desk;
    private CycleButton<SheetMode> sheet;
    private CycleButton<RedstoneMode> redstone;
    private CycleButton<PlayAccess> access;
    private StringWidget owner;
    /** Whether the redstone button's tooltip says that only the owner may change it. */
    private boolean redstoneLocked;
    private final List<CycleButton<?>> settings = new ArrayList<>();

    PianoTab(PianoScreen screen) {
        super(Component.translatable("music.screen.tab.piano"), screen);
    }

    @Override
    protected void addRows() {
        PianoSettings s = screen.settings().current();
        SettingsSender sender = screen.settings();
        lid = CycleButton.booleanBuilder(Component.translatable("music.screen.lid.open"), Component.translatable("music.screen.lid.closed"), s.lidOpen())
            .withTooltip(v -> Tooltip.create(Component.translatable("music.screen.lid.tooltip")))
            .create(0, 0, 100, 20, Component.translatable("music.screen.lid"), (b, v) -> sender.edit(x -> x.withLidOpen(v)));
        desk = CycleButton.booleanBuilder(Component.translatable("music.screen.desk.up"), Component.translatable("music.screen.desk.down"), s.deskUp())
            .create(0, 0, 100, 20, Component.translatable("music.screen.desk"), (b, v) -> sender.edit(x -> x.withDeskUp(v)));
        row(even(2), lid, desk);
        sheet = CycleButton.<SheetMode>builder(m -> Component.translatable(m.translationKey()), s.sheet())
            .withValues(SheetMode.NOTATION, SheetMode.ROLL, SheetMode.OFF)
            .withTooltip(m -> Tooltip.create(Component.translatable("music.screen.desk_shows.tooltip")))
            .create(0, 0, 100, 20, Component.translatable("music.screen.desk_shows"), (b, v) -> sender.edit(x -> x.withSheet(v)));
        redstone = CycleButton.<RedstoneMode>builder(m -> Component.translatable(m.translationKey()), s.redstone())
            .withValues(RedstoneMode.values())
            .withTooltip(m -> Tooltip.create(locked(Component.translatable("music.screen.redstone.tooltip." + m.getSerializedName()), !screen.canTune())))
            .create(0, 0, 100, 20, Component.translatable("music.screen.redstone"), (b, v) -> sender.edit(x -> x.withRedstone(v)));
        redstoneLocked = !screen.canTune();
        row(even(2), sheet, redstone);
        access = CycleButton.<PlayAccess>builder(a -> Component.translatable(a.translationKey()), s.access())
            .withValues(PlayAccess.values())
            .withTooltip(a -> Tooltip.create(locked(Component.translatable(a.translationKey() + ".tooltip"), !screen.canChangeAccess())))
            .create(0, 0, 100, 20, Component.translatable("music.screen.access"), (b, v) -> sender.edit(x -> x.withAccess(v)));
        owner = text(Component.empty());
        row(even(2), access, owner);
        row(TEXT_ROW, ScreenTab.cells(1), text(Component.translatable("music.screen.comparator").withStyle(ChatFormatting.GRAY)));
        settings.add(lid);
        settings.add(desk);
        settings.add(sheet);
        settings.add(redstone);
        refresh();
    }

    @Override
    protected void refresh() {
        PianoSettings s = screen.settings().current();
        boolean edit = screen.canEdit();
        for (CycleButton<?> button : settings) button.active = edit;
        // The lid, the desk and what it shows belong to playing; redstone only the owner changes, unless all may.
        boolean tunes = screen.canTune();
        redstone.active = tunes;
        if (lid.getValue() != s.lidOpen()) lid.setValue(s.lidOpen());
        if (desk.getValue() != s.deskUp()) desk.setValue(s.deskUp());
        if (sheet.getValue() != s.sheet()) sheet.setValue(s.sheet());
        // Set again when only who may change it changed, so its tooltip says so.
        if (redstone.getValue() != s.redstone() || redstoneLocked == tunes) {
            redstoneLocked = !tunes;
            redstone.setValue(s.redstone());
        }
        if (access.getValue() != s.access()) access.setValue(s.access());
        boolean mayChange = screen.canChangeAccess();
        access.active = mayChange;
        GrandPianoBlockEntity be = Pianos.at(screen.piano());
        String name = be == null ? "" : be.ownerName();
        owner.setMessage(name.isEmpty() ? Component.translatable("music.screen.owner.none") : Component.translatable("music.screen.owner", name));
    }
}
