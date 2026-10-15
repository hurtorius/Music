package org.hurtorius.music.client.ui;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.hurtorius.music.client.ClientPrefs;
import org.hurtorius.music.client.anim.CameraMode;
import org.hurtorius.music.client.anim.PianoCamera;
import org.hurtorius.music.client.play.Metronome;
import org.hurtorius.music.client.play.Performer;
import org.hurtorius.music.client.play.Recorder;
import org.hurtorius.music.client.play.Uploader;
import org.hurtorius.music.piano.Pedal;
import org.hurtorius.music.song.Song;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Playing: which octave the computer keys play, transposition, key velocity, the three pedals, what
 * shows above the keys, the camera and first-person hands, the metronome (with its own volume), and
 * recording a take that can be listened to, saved to the world, exported as a MIDI file or thrown
 * away. Standing up from the bench is here too.
 */
final class PlayTab extends ScreenTab {
    private static final List<Integer> BEATS = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
    private static final DateTimeFormatter TAKE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);
    private static final int MIN_BPM = 20, MAX_BPM = 300;
    private static final Tooltip LISTEN_TIP = Tooltip.create(Component.translatable("music.screen.take.listen.tooltip"));
    /** Values of the switches, in the same sentence case as every other value on the screen. */
    private static final Component ON = Component.translatable("music.screen.on"), OFF = Component.translatable("music.screen.off");

    private Button octave, octaveDown, octaveUp, transpose, transposeDown, transposeUp;
    private ValueSlider velocity, tempo, click;
    private final Button[] pedals = new Button[Pedal.values().length];
    private Button record, slower, faster, save, export, discard;
    private CycleButton<Boolean> metronome;
    private EditBox name;
    /** The Listen tooltip is given once the pointer has left the button the click just turned into Listen. */
    private boolean listenTipGiven;
    /** Whether the transpose buttons' tooltips say that only the owner or an operator may change it. */
    private boolean transposeLocked;
    /** Whether the player sat at the piano when the tab was built, with Stand up among the pedals. */
    private boolean seated;

    PlayTab(PianoScreen screen) {
        super(Component.translatable("music.screen.tab.play"), screen);
    }

    @Override
    protected void addRows() {
        ClientPrefs prefs = ClientPrefs.get();
        float square = -geometry.rowHeight();
        octaveDown = button(Component.literal("−"), Component.translatable("music.screen.octave.down"), b -> {
            screen.input().shiftOctave(-1);
            screen.prefsChanged();
        });
        octave = button(Component.empty(), Component.translatable("music.screen.octave.reset"), b -> {
            prefs.keyboardBase = ComputerKeys.DEFAULT_BASE;
            screen.prefsChanged();
        });
        octaveUp = button(Component.literal("+"), Component.translatable("music.screen.octave.up"), b -> {
            screen.input().shiftOctave(1);
            screen.prefsChanged();
        });
        transposeDown = button(Component.literal("−"), b -> screen.settings().edit(s -> s.withTranspose(s.transpose() - 1)));
        transpose = button(Component.empty(), b -> screen.settings().edit(s -> s.withTranspose(0)));
        transposeUp = button(Component.literal("+"), b -> screen.settings().edit(s -> s.withTranspose(s.transpose() + 1)));
        lockTranspose(!screen.canTune());
        if (geometry.content().width() >= 300) {
            row(cells(square, 1, square, square, 1, square), octaveDown, octave, octaveUp, transposeDown, transpose, transposeUp);
        } else {
            row(cells(square, 1, square), octaveDown, octave, octaveUp);
            row(cells(square, 1, square), transposeDown, transpose, transposeUp);
        }

        velocity = slider(1, 127, 1, prefs.keyVelocity, v -> Component.translatable("music.screen.velocity", (int) v),
            v -> prefs.keyVelocity = (int) v, v -> prefs.save());
        velocity.setTooltip(Tooltip.create(Component.translatable("music.screen.velocity.tooltip")));
        CycleButton<Boolean> sustainKey = CycleButton.booleanBuilder(Component.translatable("music.screen.space.hold"),
                Component.translatable("music.screen.space.toggle"), prefs.holdSustain)
            .withTooltip(v -> Tooltip.create(Component.translatable("music.screen.space.tooltip")))
            .create(0, 0, 100, 20, Component.translatable("music.screen.space"), (b, v) -> {
                prefs.holdSustain = v;
                prefs.save();
            });
        List<String> views = List.of("roll", "notation", "off");
        CycleButton<String> sheet = CycleButton.<String>builder(v -> Component.translatable("music.screen.sheet_view." + v), prefs.sheetView)
            .withValues(views)
            .withTooltip(v -> Tooltip.create(Component.translatable("music.screen.sheet_view.tooltip")))
            .create(0, 0, 100, 20, Component.translatable("music.screen.sheet_view"), (b, v) -> {
                prefs.sheetView = v;
                prefs.save();
            });
        // Shares by what each needs at its longest, as the row of pedals has, so each reads whole where the row has room.
        row(cells(widest(Component.translatable("music.screen.velocity", 127)),
                longest(Component.translatable("music.screen.space"), new Boolean[]{true, false},
                    v -> Component.translatable(v ? "music.screen.space.hold" : "music.screen.space.toggle")),
                longest(Component.translatable("music.screen.sheet_view"), views.toArray(new String[0]),
                    v -> Component.translatable("music.screen.sheet_view." + v))),
            velocity, sustainKey, sheet);

        for (Pedal pedal : Pedal.values()) {
            Button button = button(pedalName(pedal), Component.translatable("music.screen.pedal." + key(pedal) + ".tooltip"),
                b -> screen.input().togglePedal(pedal));
            button.setOverrideRenderHighlightedSprite(() -> Performer.isPedalDown(pedal) || button.isHovered());
            pedals[pedal.ordinal()] = button;
        }
        Button standUp = button(Component.translatable("music.screen.stand_up"), Component.translatable("music.screen.stand_up.tooltip"), b -> screen.standUp());
        CycleButton<CameraMode> camera = CycleButton.<CameraMode>builder(CameraMode::displayName, PianoCamera.mode())
            .withValues(CameraMode.values())
            .withTooltip(c -> Tooltip.create(Component.translatable("music.screen.camera.tooltip")))
            .create(0, 0, 100, 20, Component.translatable("music.screen.camera"), (b, c) -> PianoCamera.setMode(c));
        CycleButton<Boolean> hands = CycleButton.booleanBuilder(ON, OFF, prefs.firstPersonHands)
            .withTooltip(v -> Tooltip.create(Component.translatable("music.screen.hands.tooltip")))
            .create(0, 0, 100, 20, Component.translatable("music.screen.hands"), (b, v) -> {
                prefs.firstPersonHands = v;
                prefs.save();
            });
        // The pedals and what goes with sitting at the piano (the view, the hands, standing up) share
        // a row where all their labels fit, so the tab stays short and leaves room for chat below it.
        // Stand up is there only for a player at the bench: one standing has no gap in its place.
        seated = screen.seatedHere();
        Button soft = pedals[Pedal.SOFT.ordinal()], sostenuto = pedals[Pedal.SOSTENUTO.ordinal()], sustain = pedals[Pedal.SUSTAIN.ordinal()];
        AbstractWidget[] seat = seated ? new AbstractWidget[]{soft, sostenuto, sustain, camera, hands, standUp}
            : new AbstractWidget[]{soft, sostenuto, sustain, camera, hands};
        AbstractWidget[] pedalRow = seated ? new AbstractWidget[]{soft, sostenuto, sustain, standUp} : new AbstractWidget[]{soft, sostenuto, sustain};
        float[] widths = natural(seat);
        widths[3] = longest(Component.translatable("music.screen.camera"), CameraMode.values(), CameraMode::displayName);
        widths[4] = longest(Component.translatable("music.screen.hands"), new Boolean[]{true, false}, v -> v ? ON : OFF);
        float total = geometry.spacing() * (seat.length - 1);
        for (float w : widths) total += w;
        if (total <= geometry.content().width()) {
            row(widths, seat);
        } else {
            row(even(pedalRow.length), pedalRow);
            row(cells(2, 1), camera, hands);
        }

        metronome = CycleButton.booleanBuilder(ON, OFF, prefs.metronome)
            .create(0, 0, 100, 20, Component.translatable("music.screen.metronome"), (b, v) -> {
                prefs.metronome = v;
                prefs.save();
            });
        slower = button(Component.literal("−"), Component.translatable("music.screen.tempo.down"), b -> nudgeTempo(-1));
        tempo = slider(MIN_BPM, MAX_BPM, 1, prefs.metronomeBpm, v -> Component.translatable("music.screen.tempo", (int) v),
            v -> prefs.metronomeBpm = (int) v, v -> {
                prefs.save();
                Metronome.restart();
            });
        faster = button(Component.literal("+"), Component.translatable("music.screen.tempo.up"), b -> nudgeTempo(1));
        CycleButton<Integer> bar = CycleButton.<Integer>builder(v -> Component.literal(Integer.toString(v)), Math.max(1, Math.min(12, prefs.metronomeBeats)))
            .withValues(BEATS)
            .create(0, 0, 100, 20, Component.translatable("music.screen.beats"), (b, v) -> {
                prefs.metronomeBeats = v;
                prefs.save();
                Metronome.restart();
            });
        click = slider(0, 100, 5, Math.round(prefs.metronomeVolume * 100), v -> Component.translatable("music.screen.click_volume", (int) v),
            v -> prefs.metronomeVolume = (float) v / 100f, v -> prefs.save());
        click.setTooltip(Tooltip.create(Component.translatable("music.screen.click_volume.tooltip")));
        row(cells(1.1f, square, 1.3f, square, 0.8f, 1.0f), metronome, slower, tempo, faster, bar, click);

        record = button(Component.empty(), b -> recordOrListen());
        record.setOverrideRenderHighlightedSprite(() -> Recorder.isRecording() || TakePreview.playing() || record.isHovered());
        listenTipGiven = false;
        name = new EditBox(minecraft.font, 100, 20, Component.translatable("music.screen.take.name"));
        name.setMaxLength(Song.MAX_NAME);
        name.setHint(Component.translatable("music.screen.take.name"));
        name.setValue(PianoSession.takeName);
        name.setResponder(v -> PianoSession.takeName = v);
        save = button(Component.translatable("music.screen.take.save"), Component.translatable("music.screen.take.save.tooltip"), b -> saveTake());
        export = button(Component.translatable("music.screen.export"), Component.translatable("music.screen.export.tooltip"), b -> {
            Song take = PianoSession.take;
            if (take != null) SongFiles.exportMidi(named(take));
        });
        discard = button(Component.translatable("music.screen.take.discard"), b -> screen.confirm(
            Component.translatable("music.screen.take.discard.title"), Component.translatable("music.screen.take.discard.message"), () -> {
                TakePreview.stop();
                PianoSession.take = null;
                PianoSession.takeName = "";
                screen.requestRebuild();
            }));
        // Shares by what each label needs at its longest (Record's four states, Save's progress), so
        // that at the smallest window every button reads whole and the name box takes what is left.
        float[] takeRow = natural(record, name, save, export, discard);
        takeRow[0] = widest(Component.translatable("music.screen.record"), Component.translatable("music.screen.take.listen"),
            Component.translatable("music.screen.take.listen.stop"), Component.translatable("music.screen.record.stop", Formats.duration(599_000)));
        takeRow[2] = widest(Component.translatable("music.screen.take.save"), Component.translatable("music.screen.take.saving", 100));
        row(takeRow, record, name, save, export, discard);
        refresh();
    }

    /**
     * Transposing is the owner's, as the sound is, unless they let everyone change it: the transpose
     * buttons' tooltips say so below what they do while the player may not.
     */
    private void lockTranspose(boolean locked) {
        transposeLocked = locked;
        transposeDown.setTooltip(Tooltip.create(locked(Component.translatable("music.screen.transpose.down"), locked)));
        transpose.setTooltip(Tooltip.create(locked(Component.translatable("music.screen.transpose.reset"), locked)));
        transposeUp.setTooltip(Tooltip.create(locked(Component.translatable("music.screen.transpose.up"), locked)));
    }

    private static String key(Pedal pedal) {
        return switch (pedal) {
            case SOFT -> "soft";
            case SOSTENUTO -> "sostenuto";
            case SUSTAIN -> "sustain";
        };
    }

    private static MutableComponent pedalName(Pedal pedal) {
        return Component.translatable("music.screen.pedal." + key(pedal));
    }

    /** The share of a row a cycle button needs for its longest "Name: value" label, as {@link #natural} gives. */
    private <T> float longest(Component name, T[] values, Function<T, Component> label) {
        int widest = 0;
        for (T value : values) widest = Math.max(widest, minecraft.font.width(CommonComponents.optionNameValue(name, label.apply(value))));
        return widest + 10;
    }

    /** The share of a row a button needs for the longest of the labels it shows, as {@link #natural} gives. */
    private float widest(Component... labels) {
        int widest = 0;
        for (Component label : labels) widest = Math.max(widest, minecraft.font.width(label));
        return widest + 10;
    }

    private void nudgeTempo(int step) {
        ClientPrefs prefs = ClientPrefs.get();
        int next = Math.max(MIN_BPM, Math.min(MAX_BPM, prefs.metronomeBpm + step));
        if (next == prefs.metronomeBpm) return;
        prefs.metronomeBpm = next;
        prefs.save();
        Metronome.restart();
        tempo.sync(next);
    }

    /** The record button: it stops a recording, plays back a take waiting to be saved, or starts recording. */
    private void recordOrListen() {
        if (Recorder.isRecording()) {
            String chosen = PianoSession.takeName.isBlank() ? defaultTakeName() : PianoSession.takeName.strip();
            Song take = Recorder.stop(chosen);
            if (take == null) {
                Feedback.info(Component.translatable("music.screen.take.empty"));
            } else {
                PianoSession.take = take;
                if (PianoSession.takeName.isBlank()) PianoSession.takeName = take.name();
                Feedback.info(Component.translatable(take.noteCount() == 1 ? "music.screen.take.done.one" : "music.screen.take.done",
                    take.noteCount(), Formats.duration(take.durationMs())));
            }
            screen.requestRebuild();
            return;
        }
        Song pending = PianoSession.take;
        if (pending != null) {
            if (TakePreview.playing()) TakePreview.stop();
            else TakePreview.start(screen.piano(), pending);
            return;
        }
        if (!Performer.canPlay()) {
            screen.refuse();
            return;
        }
        // A take still playing back would sound over the new one and fill the strip instead of it.
        TakePreview.stop();
        Recorder.start();
        PianoSession.takeName = "";
        Feedback.info(Component.translatable("music.screen.take.recording"));
        screen.requestRebuild();
    }

    static String defaultTakeName() {
        return Component.translatable("music.screen.take.default", LocalDateTime.now().format(TAKE_TIME)).getString();
    }

    /** The take with the name in the box. */
    static Song named(Song take) {
        String chosen = PianoSession.takeName.isBlank() ? take.name() : PianoSession.takeName.strip();
        return chosen.equals(take.name()) ? take : take.toBuilder().name(chosen).build();
    }

    private void saveTake() {
        Song take = PianoSession.take;
        if (take == null || PianoSession.takeUploading) return;
        TakePreview.stop();
        Song song = named(take);
        PianoSession.takeUploading = true;
        Uploader.upload(song).whenComplete((id, failure) -> minecraft.execute(() -> {
            PianoSession.takeUploading = false;
            Throwable cause = failure == null ? null : Feedback.cause(failure);
            if (failure == null && id != null) {
                if (PianoSession.take == take) {
                    PianoSession.take = null;
                    PianoSession.takeName = "";
                }
                PianoSession.selectedSong = id;
                PianoSession.selectWhenListed = id;
                Feedback.success(Component.translatable("music.screen.upload.done", song.name()));
            } else if (cause != null) {
                Feedback.error(Component.translatable("music.screen.upload.failed", song.name(), Feedback.describe(cause)));
            }
            PianoScreen.refreshOpen();
        }));
    }

    @Override
    protected void refresh() {
        ClientPrefs prefs = ClientPrefs.get();
        int base = ComputerKeys.normalizeBase(prefs.keyboardBase);
        octave.setMessage(Component.translatable("music.screen.octave", ComputerKeys.rangeLabel(base)));
        octaveDown.active = base > ComputerKeys.LOWEST_BASE;
        octaveUp.active = base < ComputerKeys.HIGHEST_BASE;
        int shift = screen.settings().current().transpose();
        transpose.setMessage(Component.translatable("music.screen.transpose", Formats.signed(shift)));
        boolean edit = screen.canTune();
        transpose.active = edit;
        transposeDown.active = edit && shift > -24;
        transposeUp.active = edit && shift < 24;
        if (transposeLocked == edit) lockTranspose(!edit);
        velocity.sync(prefs.keyVelocity);
        tempo.sync(prefs.metronomeBpm);
        slower.active = prefs.metronomeBpm > MIN_BPM;
        faster.active = prefs.metronomeBpm < MAX_BPM;
        click.sync(Math.round(prefs.metronomeVolume * 100));
        // Sitting down or getting up with the screen open: the row gains or loses Stand up.
        if (screen.seatedHere() != seated) screen.requestRebuild();
        boolean hasTake = PianoSession.take != null;
        // While the take is being saved it cannot be listened to: it is about to leave this tab.
        record.active = Recorder.isRecording() || (hasTake ? !PianoSession.takeUploading : Performer.canPlay());
        name.active = hasTake || Recorder.isRecording();
        name.setEditable(name.active);
        save.active = hasTake && !PianoSession.takeUploading;
        export.active = hasTake;
        discard.active = hasTake && !PianoSession.takeUploading;
    }

    @Override
    void frame() {
        for (Pedal pedal : Pedal.values()) {
            boolean down = Performer.isPedalDown(pedal);
            pedals[pedal.ordinal()].setMessage(down ? pedalName(pedal).withStyle(ChatFormatting.YELLOW) : pedalName(pedal));
        }
        boolean listening = PianoSession.take != null && !Recorder.isRecording();
        if (Recorder.isRecording()) {
            record.setMessage(Component.translatable("music.screen.record.stop", Formats.duration(Recorder.elapsedMs())).withStyle(ChatFormatting.RED));
        } else if (listening) {
            record.setMessage(Component.translatable(TakePreview.playing() ? "music.screen.take.listen.stop" : "music.screen.take.listen"));
        } else {
            record.setMessage(Component.translatable("music.screen.record"));
        }
        // A tooltip under the pointer the moment Stop turns into Listen would cover what the click just said.
        if (listening && !listenTipGiven && !pointerOver(record)) {
            record.setTooltip(LISTEN_TIP);
            listenTipGiven = true;
        }
        save.setMessage(PianoSession.takeUploading
            ? Component.translatable("music.screen.take.saving", Math.round(Uploader.progress() * 100))
            : Component.translatable("music.screen.take.save"));
        metronome.setMessage(metronomeLabel(ClientPrefs.get()));
    }

    /**
     * True while the mouse is over {@code widget}. Asked before the frame is drawn, which is when a
     * widget learns whether it is hovered, so a button just built would not know yet.
     */
    private boolean pointerOver(AbstractWidget widget) {
        Window window = minecraft.getWindow();
        return widget.getRectangle().containsPoint((int) minecraft.mouseHandler.getScaledXPos(window), (int) minecraft.mouseHandler.getScaledYPos(window));
    }

    /** "Metronome: Off", or the beats of the bar with the one sounding lit (the bar's first in gold). */
    private Component metronomeLabel(ClientPrefs prefs) {
        Component name = Component.translatable("music.screen.metronome");
        if (!prefs.metronome) return CommonComponents.optionNameValue(name, OFF);
        int count = Math.max(1, Math.min(12, prefs.metronomeBeats));
        int beat = Metronome.currentBeat();
        MutableComponent dots = Component.empty();
        for (int i = 0; i < count; i++) {
            MutableComponent dot = Component.literal(i == beat ? "●" : "○");
            dots.append(dot.withStyle(i != beat ? ChatFormatting.GRAY : i == 0 ? ChatFormatting.GOLD : ChatFormatting.YELLOW));
        }
        Component full = CommonComponents.optionNameValue(name, dots);
        if (minecraft.font.width(full) <= metronome.getWidth() - 8) return full;
        // Too many beats for the button: the beat's number instead.
        Component number = beat < 0 ? Component.literal("–/" + count)
            : Component.literal((beat + 1) + "/" + count).withStyle(beat == 0 ? ChatFormatting.GOLD : ChatFormatting.YELLOW);
        return CommonComponents.optionNameValue(name, number);
    }
}
