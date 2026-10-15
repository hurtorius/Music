package org.hurtorius.music.client.midi;

import org.hurtorius.music.piano.Pedal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.midi.MidiMessage;
import javax.sound.midi.Receiver;
import javax.sound.midi.ShortMessage;
import java.util.Arrays;
import java.util.function.IntUnaryOperator;

/**
 * The receiver attached to a MIDI input: decodes channel messages into {@link MidiListener} calls.
 * Clock, active sensing, pitch bend, aftertouch, program changes and sysex are ignored. The three
 * pedal controllers become pedal changes only: down (127) and up (0), with some room between the two
 * so a continuous pedal does not flicker. A listener
 * that throws is logged and the input keeps working (an exception escaping into Java Sound would
 * silently end its input thread). It remembers the keys and pedals it reported down, and
 * {@link #close() closing} it reports them released: once an input is switched away from or lost,
 * the releases from that device can no longer arrive.
 */
public final class MidiDispatch implements Receiver {
    private static final Logger LOG = LoggerFactory.getLogger("Music");
    private static final int CC_ALL_SOUND_OFF = 120, CC_RESET_CONTROLLERS = 121, CC_ALL_NOTES_OFF = 123;
    /** A pedal goes down at this value or more (the MIDI convention) and comes back up only below {@link #PEDAL_UP_BELOW}. */
    static final int PEDAL_DOWN_FROM = 64, PEDAL_UP_BELOW = 52;

    private final MidiListener listener;
    private final IntUnaryOperator velocity;
    /** Strikes reported and not yet released, per channel and key. */
    private final int[][] held = new int[16][128];
    /** Pedals reported down, per channel. */
    private final boolean[][] pedalDown = new boolean[16][Pedal.values().length];
    private boolean closed;

    /**
     * @param listener receives the decoded events
     * @param velocity shapes note-on velocities 1..127 (see {@link VelocityCurve})
     */
    public MidiDispatch(MidiListener listener, IntUnaryOperator velocity) {
        this.listener = listener;
        this.velocity = velocity;
    }

    @Override
    public synchronized void send(MidiMessage message, long timeStamp) {
        if (closed || !(message instanceof ShortMessage s) || s.getStatus() >= 0xF0) return;
        try {
            dispatch(s.getCommand(), s.getChannel(), s.getData1(), s.getData2());
        } catch (RuntimeException e) {
            LOG.error("MIDI input listener failed on {}", describe(s), e);
        }
    }

    private void dispatch(int command, int channel, int data1, int data2) {
        switch (command) {
            case ShortMessage.NOTE_ON -> {
                if (data2 == 0) {
                    noteOff(channel, data1);
                } else {
                    held[channel][data1]++;
                    listener.noteOn(channel, data1, Math.max(1, Math.min(127, velocity.applyAsInt(data2))));
                }
            }
            case ShortMessage.NOTE_OFF -> noteOff(channel, data1);
            case ShortMessage.CONTROL_CHANGE -> {
                Pedal pedal = Pedal.byController(data1);
                if (pedal != null) {
                    // A continuous (half-damper) pedal sends every position: down from 64, up only below
                    // PEDAL_UP_BELOW, so a foot resting near the middle does not pump the dampers.
                    boolean was = pedalDown[channel][pedal.ordinal()];
                    boolean down = was ? data2 >= PEDAL_UP_BELOW : data2 >= PEDAL_DOWN_FROM;
                    if (down == was) return;
                    pedalDown[channel][pedal.ordinal()] = down;
                    listener.pedal(channel, pedal, down ? 127 : 0);
                } else if (data1 == CC_RESET_CONTROLLERS) {
                    for (Pedal p : Pedal.values()) {
                        pedalDown[channel][p.ordinal()] = false;
                        listener.pedal(channel, p, 0);
                    }
                } else if (data1 == CC_ALL_SOUND_OFF || data1 >= CC_ALL_NOTES_OFF) {
                    Arrays.fill(held[channel], 0);
                    listener.allNotesOff(channel);
                } else {
                    listener.control(channel, data1, data2);
                }
            }
            default -> {
            }
        }
    }

    private void noteOff(int channel, int midi) {
        if (held[channel][midi] > 0) held[channel][midi]--;
        listener.noteOff(channel, midi);
    }

    private static String describe(ShortMessage s) {
        return String.format("status %02X data %d %d", s.getStatus(), s.getData1(), s.getData2());
    }

    /**
     * Stops passing messages on, then reports every key and pedal this input left down as released,
     * on the calling thread. Messages being dispatched finish first; later ones are dropped.
     */
    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        for (int c = 0; c < 16; c++) {
            int channel = c;
            for (int midi = 0; midi < 128; midi++) {
                int key = midi;
                for (; held[channel][key] > 0; held[channel][key]--) release(() -> listener.noteOff(channel, key));
            }
            for (Pedal pedal : Pedal.values()) {
                if (!pedalDown[channel][pedal.ordinal()]) continue;
                pedalDown[channel][pedal.ordinal()] = false;
                release(() -> listener.pedal(channel, pedal, 0));
            }
        }
    }

    private static void release(Runnable call) {
        try {
            call.run();
        } catch (RuntimeException e) {
            LOG.error("MIDI input listener failed releasing a closed input", e);
        }
    }
}
