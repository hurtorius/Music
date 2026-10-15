package org.hurtorius.music.client.midi;

import org.hurtorius.music.piano.Pedal;

/**
 * Receives what is played on the connected MIDI input. Calls arrive on Java Sound's MIDI input
 * thread (or, for {@link #deviceLost} and for the releases of keys and pedals an input still held
 * when it was switched away from or lost, on the {@code music-midi} thread), never on the client
 * thread: implementations hand the work over (for example with {@code Minecraft.execute}) and must
 * not block. Channels are 0..15; keys are raw MIDI numbers 0..127 and may lie outside the piano.
 */
public interface MidiListener {
    /** A key went down. {@code velocity} is 1..127, already shaped by the player's velocity curve. */
    void noteOn(int channel, int midi, int velocity);

    /** A key came up (note-off, or note-on with velocity 0). */
    void noteOff(int channel, int midi);

    /** Sustain, sostenuto or soft pedal went down ({@code value} 127) or up (0). */
    void pedal(int channel, Pedal pedal, int value);

    /** Any other controller (modulation, volume, expression...). */
    void control(int channel, int controller, int value);

    /** All notes off, all sound off or a mode change on the channel: release every key it holds. */
    void allNotesOff(int channel);

    /** A device that was connected went away (unplugged, switched off or failed). */
    void deviceLost(String name, boolean input);
}
