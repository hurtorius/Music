package org.hurtorius.music.client.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.hurtorius.music.client.ClientPrefs;
import org.hurtorius.music.client.audio.AudioEngine;
import org.hurtorius.music.client.audio.PianoAudio;
import org.hurtorius.music.client.midi.MidiDevices;
import org.hurtorius.music.client.midi.MidiPort;
import org.hurtorius.music.client.midi.MidiPorts;
import org.hurtorius.music.client.midi.VelocityCurve;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * This player's own equipment: the MIDI keyboard that plays the piano and the MIDI output that hears
 * it (USB, or Bluetooth where the system offers it as a MIDI port), how a keyboard's velocities are
 * shaped, the delay that smooths other players' notes, how many voices may sound at once, how loud
 * other pianos are for them, whether game music waits for the piano, and how long their sound takes
 * to reach the speakers. When no device is found, or several keyboards wait to be chosen, the lists
 * say so. Nothing here changes what other players hear.
 */
final class DevicesTab extends ScreenTab {
    /** A slider's handle is 8 wide: at either end it stays a pixel clear of a label centred in a slider this much wider than it. */
    static final int SLIDER_CLEAR = 2 * (8 + 1);

    private TextList<String> inputs, outputs;
    private StringWidget inputStatus, outputStatus, outputDelay;
    private ValueSlider delay, voices, others;
    private int shownRevision = -1;
    private long shownDelay = Long.MIN_VALUE;
    /** True when the row has room for the speaker delay only in fewer words. */
    private boolean shortDelay;

    DevicesTab(PianoScreen screen) {
        super(Component.translatable("music.screen.tab.devices"), screen);
    }

    @Override
    protected boolean scrollable() {
        return false;
    }

    @Override
    protected void addRows() {
        ClientPrefs prefs = ClientPrefs.get();
        int gap = geometry.spacing();
        Component note = Component.translatable(MidiDevices.bluetoothHint()).withStyle(ChatFormatting.GRAY);
        int noteLines = Math.max(1, Math.min(2, minecraft.font.split(note, geometry.content().width()).size()));
        int noteHeight = noteLines * minecraft.font.lineHeight + 1;
        int listHeight = geometry.content().height() - 2 * TEXT_ROW - noteHeight - 2 * geometry.rowHeight() - 5 * gap;
        // Both lists always have their "None" row; what was (not) found shows as a hint under it.
        inputs = new TextList<>(minecraft, this::chooseInput, this::chooseInput);
        outputs = new TextList<>(minecraft, this::chooseOutput, this::chooseOutput);
        row(TEXT_ROW, even(2), text(Component.translatable("music.screen.midi.input")), text(Component.translatable("music.screen.midi.output")));
        // Whole rows, as the Songs tab's lists: none is cut across.
        row(TextList.wholeRows(Math.max(TextList.ROW_HEIGHT * 2 + 4, listHeight)), even(2), inputs, outputs);
        inputStatus = text(Component.empty());
        outputStatus = text(Component.empty());
        row(TEXT_ROW, even(2), inputStatus, outputStatus);

        var rescan = button(Component.translatable("music.screen.midi.rescan"), Component.translatable("music.screen.midi.rescan.tooltip"), b -> rescan());
        CycleButton<VelocityCurve> curve = CycleButton.<VelocityCurve>builder(c -> Component.translatable(c.translationKey()), VelocityCurve.byName(prefs.velocityCurve))
            .withValues(VelocityCurve.values())
            .withTooltip(c -> Tooltip.create(Component.translatable("music.screen.velocity_curve.tooltip")))
            .create(0, 0, 100, 20, Component.translatable("music.screen.velocity_curve"), (b, c) -> {
                prefs.velocityCurve = c.id();
                prefs.save();
            });
        CycleButton<Boolean> gameMusic = CycleButton.booleanBuilder(Component.translatable("music.screen.game_music.waits"),
                Component.translatable("music.screen.game_music.plays"), prefs.hushGameMusic)
            .withTooltip(v -> Tooltip.create(Component.translatable("music.screen.game_music.tooltip")))
            .create(0, 0, 100, 20, Component.translatable("music.screen.game_music"), (b, v) -> {
                prefs.hushGameMusic = v;
                prefs.save();
            });
        row(cells(1, 1.4f, 1.6f), rescan, curve, gameMusic);

        delay = slider(0, 500, 10, prefs.remoteDelayMs, v -> Component.translatable("music.screen.remote_delay", (int) v),
            v -> prefs.remoteDelayMs = (int) v, v -> prefs.save());
        delay.setTooltip(Tooltip.create(Component.translatable("music.screen.remote_delay.tooltip")));
        others = slider(0, 100, 5, prefs.otherPianosVolume * 100, v -> Component.translatable("music.screen.other_pianos", (int) v),
            v -> prefs.otherPianosVolume = (float) (v / 100), v -> prefs.save());
        others.setTooltip(Tooltip.create(Component.translatable("music.screen.other_pianos.tooltip")));
        voices = slider(AudioEngine.MIN_VOICES, AudioEngine.MAX_VOICES, 4, Math.min(AudioEngine.MAX_VOICES, prefs.voiceLimit),
            v -> Component.translatable("music.screen.voices", (int) v), v -> prefs.voiceLimit = (int) v, v -> prefs.save());
        voices.setTooltip(Tooltip.create(Component.translatable("music.screen.voices.tooltip")));
        // Beside the voices, both being this computer's own piano sound: how long it takes to reach the speakers.
        outputDelay = text(Component.empty());
        outputDelay.setTooltip(Tooltip.create(Component.translatable("music.screen.output_delay.tooltip")));
        shownDelay = Long.MIN_VALUE;
        // The speaker delay keeps the width its words need; the sliders share the rest in proportion to theirs,
        // each wide enough, where the row allows, for its handle to stay clear of its words at either end. A
        // row too narrow for that (most windows at the automatic GUI size) says the delay in fewer words.
        float[] sliders = {sliderNeeds(Component.translatable("music.screen.remote_delay", 500)), sliderNeeds(Component.translatable("music.screen.other_pianos", 100)),
            sliderNeeds(Component.translatable("music.screen.voices", AudioEngine.MAX_VOICES))};
        float needed = sliders[0] + sliders[1] + sliders[2], room = geometry.content().width() - 3 * gap - needed;
        shortDelay = room < delayWidth(false);
        float spare = room - delayWidth(shortDelay);
        // Whole widths, none short of its need by a rounding; where even that is too much, shares of what there is.
        if (spare >= 0) for (int i = 0; i < sliders.length; i++) sliders[i] = -(float) Math.floor(sliders[i] * (1 + spare / needed));
        row(cells(sliders[0], sliders[1], sliders[2], -delayWidth(shortDelay)), delay, others, voices, outputDelay);

        MultiLineTextWidget bluetooth = new MultiLineTextWidget(note, minecraft.font).setMaxWidth(geometry.content().width()).setMaxRows(noteLines);
        row(noteHeight, cells(1), bluetooth);
        refresh();
    }

    private float width(Component text) {
        return minecraft.font.width(text);
    }

    /** How wide a slider must be for its handle to stay clear of {@code label}, centred on it, at either end. */
    private float sliderNeeds(Component label) {
        return width(label) + SLIDER_CLEAR;
    }

    /** The width the speaker delay's words need, in full or in fewer words, whatever the delay. */
    private float delayWidth(boolean brief) {
        return Math.max(width(delayText(brief, 999)), width(delayText(brief, -1))) + 4;
    }

    /** The speaker delay of {@code ms} milliseconds (below 0: unknown), in full or in fewer words. */
    private static Component delayText(boolean brief, long ms) {
        String key = brief ? "music.screen.output_delay.brief" : "music.screen.output_delay";
        return ms < 0 ? Component.translatable(key + ".unknown") : Component.translatable(key, ms);
    }

    private void rescan() {
        MidiDevices.rescan().whenComplete((done, failure) -> minecraft.execute(() -> {
            if (failure != null) {
                Throwable cause = Feedback.cause(failure);
                if (cause != null) Feedback.error(Component.translatable("music.screen.midi.rescan_failed", Feedback.describe(cause)));
                return;
            }
            Feedback.info(Component.translatable("music.screen.midi.found", MidiDevices.inputs().size(), MidiDevices.outputs().size()));
        }));
    }

    private void chooseInput(String name) {
        ClientPrefs prefs = ClientPrefs.get();
        boolean none = name.isEmpty();
        // "None" always counts: it also undoes a keyboard connected for the player, which is not in the preferences.
        if (!none && name.equals(prefs.midiInput) || none && prefs.midiInputOff && MidiAutoConnect.connected() == null) return;
        prefs.midiInput = name;
        prefs.midiInputOff = none;
        prefs.save();
        MidiAutoConnect.chosen();
        report(MidiDevices.useInput(name));
    }

    private void chooseOutput(String name) {
        ClientPrefs prefs = ClientPrefs.get();
        if (name.equals(prefs.midiOutput)) return;
        prefs.midiOutput = name;
        prefs.save();
        report(MidiDevices.useOutput(name));
    }

    private void report(CompletableFuture<MidiPorts.State> connecting) {
        connecting.whenComplete((state, failure) -> minecraft.execute(() -> {
            if (failure != null) {
                Throwable cause = Feedback.cause(failure);
                if (cause != null) Feedback.error(Component.translatable("music.screen.midi.failed", Feedback.describe(cause)));
                return;
            }
            Component text = describe(state);
            switch (state.status()) {
                case CONNECTED -> Feedback.success(text);
                case UNAVAILABLE, LOST -> Feedback.error(text);
                default -> Feedback.info(text);
            }
        }));
    }

    /** A device's state in words: its name, how it is, and why when it failed. */
    static Component describe(MidiPorts.State state) {
        Component status = Component.translatable(state.status().translationKey());
        if (state.name().isEmpty()) return status;
        MutableComponent text = Component.literal(state.name() + ": ").append(status);
        if (!state.detail().isEmpty()) text.append(" (" + state.detail() + ")");
        return text;
    }

    private static ChatFormatting colour(MidiPorts.Status status) {
        return switch (status) {
            case CONNECTED -> ChatFormatting.GREEN;
            case UNAVAILABLE, LOST -> ChatFormatting.RED;
            case MISSING -> ChatFormatting.YELLOW;
            case NONE -> ChatFormatting.GRAY;
        };
    }

    private static List<TextList.Row<String>> rows(List<MidiPort> ports, String chosen) {
        List<TextList.Row<String>> rows = new ArrayList<>();
        rows.add(new TextList.Row<>("", Component.translatable("music.screen.midi.none").getString(), "", 0xFFFFFFFF));
        boolean listed = chosen.isEmpty();
        for (MidiPort port : ports) {
            rows.add(new TextList.Row<>(port.name(), port.name(), port.detail(), 0xFFFFFFFF));
            listed |= port.name().equals(chosen);
        }
        if (!listed) rows.add(new TextList.Row<>(chosen, chosen, Component.translatable("music.screen.midi.absent").getString(), 0xFFA0A0A0));
        return rows;
    }

    /** The keyboard the list shows as chosen: the player's own choice, or the one connected for them. */
    private static String shownInput(ClientPrefs prefs) {
        String connected = MidiAutoConnect.connected();
        return prefs.midiInput.isEmpty() && connected != null ? connected : prefs.midiInput;
    }

    /**
     * The grey line under the "None" row of a device list: that none was found, or (for keyboards)
     * that several wait to be chosen; empty otherwise. On macOS a device plugged in after the game
     * started only appears after a restart, so that is what the input list says there.
     */
    static Component listHint(boolean input, int found, boolean choiceNeeded, boolean needsRestart) {
        if (found == 0) {
            if (needsRestart) return Component.translatable("music.midi.restart_hint");
            return Component.translatable(input ? "music.screen.midi.no_input" : "music.screen.midi.no_output");
        }
        return input && choiceNeeded ? Component.translatable("music.screen.midi.choose") : Component.empty();
    }

    @Override
    protected void refresh() {
        ClientPrefs prefs = ClientPrefs.get();
        String input = shownInput(prefs);
        if (shownRevision != MidiDevices.revision() || !input.equals(inputs.selectedValue())) {
            shownRevision = MidiDevices.revision();
            inputs.setRows(rows(MidiDevices.inputs(), input), input);
            outputs.setRows(rows(MidiDevices.outputs(), prefs.midiOutput), prefs.midiOutput);
        }
        boolean restart = MidiDevices.newDevicesNeedRestart();
        inputs.hint(listHint(true, MidiDevices.inputs().size(), MidiAutoConnect.choiceNeeded(), restart));
        outputs.hint(listHint(false, MidiDevices.outputs().size(), false, restart));
        MidiPorts.State in = MidiDevices.inputState(), out = MidiDevices.outputState();
        inputStatus.setMessage(describe(in).copy().withStyle(colour(in.status())));
        outputStatus.setMessage(describe(out).copy().withStyle(colour(out.status())));
        delay.sync(prefs.remoteDelayMs);
        voices.sync(Math.min(AudioEngine.MAX_VOICES, prefs.voiceLimit));
        others.sync(prefs.otherPianosVolume * 100);
        long ms = PianoAudio.outputDelayMillis();
        if (ms != shownDelay) {
            shownDelay = ms;
            outputDelay.setMessage(delayText(shortDelay, ms).copy().withStyle(ChatFormatting.GRAY));
        }
    }
}
