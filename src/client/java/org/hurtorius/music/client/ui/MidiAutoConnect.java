package org.hurtorius.music.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.hurtorius.music.client.ClientPrefs;
import org.hurtorius.music.client.midi.MidiDevices;
import org.hurtorius.music.client.midi.MidiPort;
import org.hurtorius.music.client.midi.MidiPorts;
import org.hurtorius.music.client.play.Performer;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A MIDI keyboard that plays as soon as the player sits down with it plugged in. When the player has
 * not chosen a keyboard and exactly one is there, coming to a piano connects it and says so. It is
 * not saved as their choice, and leaving the piano lets it go again, so other programs can have it
 * while the player is not playing. One that cannot be opened (another program holds it) is let go
 * at once, so it is not taken later while the player is away; the screen says why on every visit,
 * and chat holds it once a world. With several keyboards the screen asks the player to choose one
 * under Devices. Choosing a keyboard there makes it theirs; choosing "None" stops this until they
 * choose one again ({@link ClientPrefs#midiInputOff}). Client thread.
 */
final class MidiAutoConnect {
    /** The keyboard connected this way, or null. */
    private static @Nullable String connected;
    /** Keyboards that could not be connected and are already in chat: chat has it once a world. */
    private static final Set<String> told = new HashSet<>();

    private MidiAutoConnect() {}

    /** The player came to a piano (sat down, or opened its screen standing). */
    static void atPiano() {
        ClientPrefs prefs = ClientPrefs.get();
        String name = toConnect(prefs.midiInput, prefs.midiInputOff, connected, names(MidiDevices.inputs()));
        if (name == null) return;
        connected = name;
        Minecraft mc = Minecraft.getInstance();
        MidiDevices.useInput(name).whenComplete((state, failure) -> mc.execute(() -> {
            // The player chose for themselves meanwhile, or left the piano.
            if (!name.equals(connected)) return;
            if (failure == null && state.status() == MidiPorts.Status.CONNECTED) {
                told.remove(name);
                Feedback.success(Component.translatable("music.midi.auto_connected", name));
                return;
            }
            // Not the player's choice: no wish may stay behind for a later scan to act on.
            connected = null;
            MidiDevices.useInput("");
            Throwable cause = failure == null ? null : Feedback.cause(failure);
            if (failure != null && cause == null) return;
            Component reason = cause == null ? DevicesTab.describe(state) : Component.translatable("music.screen.midi.failed", Feedback.describe(cause));
            // The keyboard stays silent on every visit, so the screen says why each time; chat once is enough.
            if (told.add(name)) Feedback.error(reason);
            else Feedback.errorOnScreen(reason);
        }));
    }

    /**
     * The keyboard to connect for a player who comes to a piano: the only one found, when they have
     * neither chosen one ({@code chosen} empty) nor chosen "None" ({@code off}) and none is connected
     * for them already; else null.
     */
    static @Nullable String toConnect(String chosen, boolean off, @Nullable String alreadyConnected, List<String> found) {
        return chosen.isEmpty() && !off && alreadyConnected == null && found.size() == 1 ? found.getFirst() : null;
    }

    /** True when several keyboards are found and the player has neither chosen one nor "None", and none is connected for them. */
    static boolean choiceNeeded(String chosen, boolean off, @Nullable String alreadyConnected, List<String> found) {
        return chosen.isEmpty() && !off && alreadyConnected == null && found.size() > 1;
    }

    /** True while several keyboards are plugged in and the player has not chosen one. */
    static boolean choiceNeeded() {
        ClientPrefs prefs = ClientPrefs.get();
        return choiceNeeded(prefs.midiInput, prefs.midiInputOff, connected, names(MidiDevices.inputs()));
    }

    private static List<String> names(List<MidiPort> ports) {
        List<String> names = new ArrayList<>(ports.size());
        for (MidiPort port : ports) names.add(port.name());
        return names;
    }

    /** The keyboard connected for the player, or null. */
    static @Nullable String connected() {
        return connected;
    }

    /** The player chose a MIDI keyboard, or "None", under Devices: from now on the choice is theirs. */
    static void chosen() {
        connected = null;
    }

    /** The player leaves the world: a keyboard that could not be connected is mentioned again in the next. */
    static void forget() {
        told.clear();
    }

    /** Lets the keyboard connected for the player go once they have left the piano. Client thread, every tick. */
    static void tick() {
        if (connected == null || Performer.isActive()) return;
        connected = null;
        MidiDevices.useInput("");
    }
}
