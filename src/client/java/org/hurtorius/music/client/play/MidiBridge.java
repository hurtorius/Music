package org.hurtorius.music.client.play;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.hurtorius.music.client.midi.MidiDevices;
import org.hurtorius.music.client.midi.MidiListener;
import org.hurtorius.music.piano.Pedal;
import org.hurtorius.music.piano.PianoGeometry;

/**
 * Connects real MIDI devices to the performer. A keyboard on the chosen MIDI input plays the piano
 * the local player sits at (straight from the MIDI thread, so it sounds without waiting for a
 * frame); everything the performer plays, from any input, is echoed to the chosen MIDI output.
 */
final class MidiBridge implements MidiListener, Performer.Listener {
    private static final MidiBridge INSTANCE = new MidiBridge();

    private MidiBridge() {}

    static void register() {
        MidiDevices.setListener(INSTANCE);
        Performer.addListener(INSTANCE);
    }

    // ------------------------------------------------------------------ MIDI in (MIDI thread)

    @Override
    public void noteOn(int channel, int midi, int velocity) {
        int key = midi - PianoGeometry.LOWEST_MIDI;
        if (key >= 0 && key < PianoGeometry.KEY_COUNT) Performer.noteOn(key, velocity);
    }

    @Override
    public void noteOff(int channel, int midi) {
        int key = midi - PianoGeometry.LOWEST_MIDI;
        if (key >= 0 && key < PianoGeometry.KEY_COUNT) Performer.noteOff(key);
    }

    @Override
    public void pedal(int channel, Pedal pedal, int value) {
        Performer.pedal(pedal, value >= 64);
    }

    @Override
    public void control(int channel, int controller, int value) {
        // Only the three pedals mean something to a piano; other controllers are ignored.
    }

    @Override
    public void allNotesOff(int channel) {
        // Keys only: the pedal is still where the player's foot has it, and what it sustains rings on.
        Performer.releaseKeys();
    }

    @Override
    public void deviceLost(String name, boolean input) {
        if (input) Performer.releaseAll();
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) mc.player.displayClientMessage(Component.translatable("music.midi.device_lost", name), true);
        });
    }

    // ------------------------------------------------------------------ MIDI out (client thread)

    @Override
    public void noteOn(int soundingKey, int velocity) {
        MidiDevices.noteOn(soundingKey + PianoGeometry.LOWEST_MIDI, velocity);
    }

    @Override
    public void noteOff(int soundingKey) {
        MidiDevices.noteOff(soundingKey + PianoGeometry.LOWEST_MIDI);
    }

    @Override
    public void pedal(Pedal pedal, boolean down) {
        MidiDevices.pedal(pedal, down ? 127 : 0);
    }
}
