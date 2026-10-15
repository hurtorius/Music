package org.hurtorius.music.client.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.hurtorius.music.client.ClientPrefs;
import org.hurtorius.music.client.audio.PianoAudio;
import org.hurtorius.music.client.play.Pianos;
import org.hurtorius.music.piano.GrandPianoBlockEntity;
import org.hurtorius.music.piano.Instrument;
import org.hurtorius.music.piano.PianoSettings;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * How this piano sounds to everyone: the instrument (the grand piano, any note block instrument,
 * or any sound event as a custom sound with its own root note), volume, tone, reverb, how far it is
 * heard, its tuning and transposition. Every change goes to the server as the piano's settings. A
 * player who may play the piano but not change it (see {@link org.hurtorius.music.piano.PlayAccess})
 * sees them greyed out, and is told who may change them.
 */
final class SoundTab extends ScreenTab {
    /** A custom sound id is sent this long after the player stops typing it. */
    static final long TYPING_NANOS = 400_000_000L;
    /** The tone slider moves in steps of this many percent. */
    static final int TONE_STEP = 5;

    private CycleButton<Instrument> instrument;
    private ValueSlider volume, tone, reverb, range, tuning, transpose;
    private @Nullable EditBox sound;
    private @Nullable StringWidget soundStatus;
    private @Nullable ValueSlider root;
    private Label shared;
    private final List<CycleButton<?>> cycles = new ArrayList<>();
    private boolean customShown;
    /** The computer keys' velocity (-1 before it says any) and the grand piano's harder hammers the tone tooltip says. */
    private int toneTipVelocity = -1;
    private @Nullable List<Float> toneTipHammers;
    private long typedAt;
    private boolean typed;
    /** The custom sound the server had when the box was last filled from it. */
    private String serverSound = "";

    SoundTab(PianoScreen screen) {
        super(Component.translatable("music.screen.tab.sound"), screen);
    }

    @Override
    protected void addRows() {
        PianoSettings s = screen.settings().current();
        SettingsSender sender = screen.settings();
        instrument = CycleButton.<Instrument>builder(i -> Component.translatable(i.translationKey()), s.instrument())
            .withValues(Instrument.values())
            .withTooltip(i -> Tooltip.create(Component.translatable("music.screen.instrument.tooltip")))
            .create(0, 0, 100, 20, Component.translatable("music.screen.instrument"), (b, v) -> {
                sender.edit(x -> x.withInstrument(v));
                if ((v == Instrument.CUSTOM) != customShown) screen.requestRebuild();
            });
        cycles.add(instrument);
        transpose = slider(-24, 24, 1, s.transpose(), v -> Component.translatable("music.screen.transpose", Formats.signed((int) v)),
            v -> sender.edit(x -> x.withTranspose((int) v)), v -> sender.flush(true));
        row(cells(1.4f, 1), instrument, transpose);

        customShown = s.instrument() == Instrument.CUSTOM;
        if (customShown) {
            EditBox box = new EditBox(minecraft.font, 100, 20, Component.translatable("music.screen.custom_sound"));
            box.setMaxLength(PianoSettings.MAX_SOUND_ID);
            box.setHint(Component.translatable("music.screen.custom_sound.hint"));
            box.setValue(s.customSound());
            serverSound = s.customSound();
            box.setResponder(v -> {
                typed = true;
                typedAt = System.nanoTime();
                box.setSuggestion(completion(v));
                updateSoundStatus(v);
            });
            box.setTooltip(Tooltip.create(Component.translatable("music.screen.custom_sound.tooltip")));
            sound = box;
            soundStatus = text(Component.empty());
            root = slider(0, 127, 1, s.customRoot(), v -> Component.translatable("music.screen.custom_root", Formats.noteName((int) v)),
                v -> {
                }, v -> sender.edit(x -> x.withCustomSound(x.customSound(), (int) v)));
            root.setTooltip(Tooltip.create(Component.translatable("music.screen.custom_root.tooltip")));
            row(cells(1.6f, 1, 1), box, soundStatus, root);
            updateSoundStatus(s.customSound());
        }

        volume = slider(0, 100, 1, s.volume() * 100, v -> Component.translatable("music.screen.volume", (int) v),
            v -> sender.edit(x -> x.withVolume((float) (v / 100))), v -> sender.flush(true));
        volume.setTooltip(Tooltip.create(Component.translatable("music.screen.volume.tooltip", Component.translatable("soundCategory.record"))));
        tone = slider(-100, 100, TONE_STEP, s.tone() * 100, v -> Component.translatable("music.screen.tone", toneName((int) v)),
            v -> sender.edit(x -> x.withTone((float) (v / 100))), v -> sender.flush(true));
        toneTipVelocity = -1;
        updateToneTooltip();
        row(even(2), volume, tone);
        reverb = slider(0, 100, 1, s.reverb() * 100, v -> Component.translatable("music.screen.reverb", (int) v),
            v -> sender.edit(x -> x.withReverb((float) (v / 100))), v -> sender.flush(true));
        range = slider(PianoSettings.MIN_RANGE, PianoSettings.MAX_RANGE, 1, s.range(), v -> Component.translatable("music.screen.range", (int) v),
            v -> sender.edit(x -> x.withRange((int) v)), v -> sender.flush(true));
        range.setTooltip(Tooltip.create(Component.translatable("music.screen.range.tooltip")));
        row(even(2), reverb, range);
        tuning = slider(PianoSettings.MIN_TUNING, PianoSettings.MAX_TUNING, 0.5, s.tuning(),
            v -> Component.translatable("music.screen.tuning", Formats.decimal((float) v)),
            v -> sender.edit(x -> x.withTuning((float) v)), v -> sender.flush(true));
        tuning.setTooltip(Tooltip.create(Component.translatable("music.screen.tuning.tooltip")));
        shared = text(Component.empty());
        row(cells(1, 1), tuning, shared);
        refresh();
    }

    /** Who hears these settings (in fewer words where the full ones do not fit) or, for a player who may not change them, who may. */
    private void showSharedNote(boolean edit) {
        GrandPianoBlockEntity be = Pianos.at(screen.piano());
        if (edit || be == null) {
            shared.show(Component.translatable("music.screen.sound.shared").withStyle(ChatFormatting.GRAY),
                Component.translatable("music.screen.sound.shared.brief").withStyle(ChatFormatting.GRAY));
        } else {
            shared.show(Component.translatable("music.screen.sound.owner_tunes", be.ownerName()).withStyle(ChatFormatting.GRAY));
        }
    }

    private static Component toneName(int percent) {
        if (percent == 0) return Component.translatable("music.screen.tone.neutral");
        return Component.translatable(percent < 0 ? "music.screen.tone.dark" : "music.screen.tone.bright", Math.abs(percent));
    }

    /** What the custom sound id in the box stands for, shown beside it. */
    private void updateSoundStatus(String value) {
        if (soundStatus == null) return;
        String id = value.strip();
        Component status;
        if (id.isEmpty()) status = Component.translatable("music.screen.custom_sound.empty").withStyle(ChatFormatting.GRAY);
        else if (Identifier.tryParse(id) == null) status = Component.translatable("music.screen.custom_sound.invalid").withStyle(ChatFormatting.RED);
        else if (!exists(id)) status = Component.translatable("music.screen.custom_sound.unknown").withStyle(ChatFormatting.RED);
        else status = Component.translatable("music.screen.custom_sound.found").withStyle(ChatFormatting.GREEN);
        soundStatus.setMessage(status);
    }

    /** True when the sound manager knows a sound event with this id. */
    private boolean exists(String id) {
        Identifier parsed = Identifier.tryParse(id);
        if (parsed == null) return false;
        WeighedSoundEvents events = minecraft.getSoundManager().getSoundEvent(parsed);
        return events != null;
    }

    /** The rest of the first known sound id that starts with what was typed, for the box to suggest. */
    private @Nullable String completion(String typed) {
        String text = typed.strip().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) return null;
        String best = null;
        for (Identifier id : minecraft.getSoundManager().getAvailableSounds()) {
            String full = id.toString();
            String candidate = text.indexOf(':') >= 0 ? full : id.getNamespace().equals("minecraft") ? id.getPath() : full;
            if (!candidate.startsWith(text) || candidate.equals(text)) continue;
            if (best == null || candidate.length() < best.length() || candidate.length() == best.length() && candidate.compareTo(best) < 0) best = candidate;
        }
        return best == null ? null : best.substring(text.length());
    }

    /** Accepts the suggested completion of the custom sound id; true when there was one. */
    boolean complete() {
        EditBox box = sound;
        if (box == null || !box.isFocused()) return false;
        String rest = completion(box.getValue());
        if (rest == null) return false;
        box.setValue(box.getValue().strip() + rest);
        box.moveCursorToEnd(false);
        return true;
    }

    @Override
    protected void refresh() {
        PianoSettings s = screen.settings().current();
        boolean edit = screen.canTune();
        showSharedNote(edit);
        if ((s.instrument() == Instrument.CUSTOM) != customShown) screen.requestRebuild();
        if (instrument.getValue() != s.instrument()) instrument.setValue(s.instrument());
        for (CycleButton<?> cycle : cycles) cycle.active = edit;
        volume.active = tone.active = reverb.active = range.active = tuning.active = transpose.active = edit;
        volume.sync(s.volume() * 100);
        tone.sync(s.tone() * 100);
        updateToneTooltip();
        reverb.sync(s.reverb() * 100);
        range.sync(s.range());
        tuning.sync(s.tuning());
        transpose.sync(s.transpose());
        if (root != null) {
            root.active = edit;
            root.sync(s.customRoot());
        }
        EditBox box = sound;
        if (box != null) {
            box.setEditable(edit);
            // Sent once the player rests a moment, or leaves the box.
            if (typed && (!box.isFocused() || System.nanoTime() - typedAt > TYPING_NANOS)) commitTyped();
            if (!typed && !box.isFocused() && !serverSound.equals(s.customSound())) {
                // Someone else chose another sound: show it.
                serverSound = s.customSound();
                box.setValue(serverSound);
                typed = false;
            }
        }
    }

    /** Sends the custom sound typed into the box, if it changed and names a sound (or is empty). */
    void commitTyped() {
        EditBox box = sound;
        if (box == null || !typed) return;
        typed = false;
        String id = box.getValue().strip();
        if (!id.equals(screen.settings().current().customSound()) && (id.isEmpty() || exists(id))) sender().edit(x -> x.withCustomSound(id, x.customRoot()));
    }

    private SettingsSender sender() {
        return screen.settings();
    }

    /**
     * What the tone slider does, and where along it Brighter gives the grand piano a harder hammer for the
     * computer keys' velocity (between those steps, and past the last, it only thins the lows), kept up to
     * date as that velocity changes and once the samples have loaded.
     */
    private void updateToneTooltip() {
        int velocity = ClientPrefs.get().keyVelocity;
        List<Float> hammers = PianoAudio.harderHammers(velocity);
        if (velocity == toneTipVelocity && Objects.equals(hammers, toneTipHammers)) return;
        toneTipVelocity = velocity;
        toneTipHammers = hammers;
        Component text;
        if (hammers == null) {
            text = Component.translatable("music.screen.tone.tooltip");
        } else {
            // The slider moves in 5 % steps: a hammer comes in at the first step that reaches it.
            Object[] args = new Object[hammers.size() + 1];
            args[0] = velocity;
            for (int i = 0; i < hammers.size(); i++) args[i + 1] = (int) Math.ceil(hammers.get(i) * 100 / TONE_STEP - 1e-3) * TONE_STEP;
            text = Component.translatable("music.screen.tone.tooltip." + switch (hammers.size()) {
                case 0 -> "none";
                case 1 -> "one";
                default -> "two";
            }, args);
        }
        tone.setTooltip(Tooltip.create(text));
    }
}
