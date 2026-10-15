package org.hurtorius.music.client.audio;

import org.hurtorius.music.piano.Pedal;

import java.util.BitSet;

/**
 * Keys and pedals of one {@link Voicing}: which keys are down, whose dampers are off the strings, and
 * what the three pedals do to them. Pure state machine; the engine turns "damper falls" into releases.
 *
 * <ul>
 *   <li>Sustain keeps every damper off: letting go of a key does nothing until the pedal comes up.</li>
 *   <li>Sostenuto catches the keys that are down at the moment it is pressed, and only those; keys
 *       struck afterwards are damped normally.</li>
 *   <li>Soft (una corda) changes how new notes sound, not which ones ring.</li>
 * </ul>
 */
public final class PedalBoard {
    public static final int KEYS = 88;

    private final BitSet down = new BitSet(KEYS);
    private final BitSet raised = new BitSet(KEYS);
    private final BitSet caught = new BitSet(KEYS);
    private boolean sustain, sostenuto, soft;

    /** A key goes down: its damper lifts. Striking a key that is still ringing simply restrikes it. */
    public void press(int key) {
        down.set(key);
        raised.set(key);
    }

    /** A key comes up. Returns true when its damper falls now, false when a pedal holds it or it was not sounding. */
    public boolean release(int key) {
        down.clear(key);
        if (!raised.get(key) || held(key)) return false;
        raised.clear(key);
        return true;
    }

    /** Sets a pedal. Returns the keys whose dampers fall because of it (empty when none). */
    public BitSet pedal(Pedal pedal, boolean pressed) {
        BitSet falls = new BitSet(KEYS);
        switch (pedal) {
            case SUSTAIN -> {
                sustain = pressed;
                if (!pressed) falls = settle();
            }
            case SOSTENUTO -> {
                if (pressed && !sostenuto) {
                    caught.clear();
                    caught.or(down);
                }
                sostenuto = pressed;
                if (!pressed) {
                    falls = settle();
                    caught.clear();
                }
            }
            case SOFT -> soft = pressed;
        }
        return falls;
    }

    /** Lets go of every key and pedal. Returns the keys whose dampers fall. */
    public BitSet reset() {
        down.clear();
        sustain = false;
        sostenuto = false;
        soft = false;
        caught.clear();
        BitSet falls = (BitSet) raised.clone();
        raised.clear();
        return falls;
    }

    /** True while the key's damper is off its string: struck, and not damped since. */
    public boolean ringing(int key) {
        return raised.get(key);
    }

    public boolean isDown(Pedal pedal) {
        return switch (pedal) {
            case SUSTAIN -> sustain;
            case SOSTENUTO -> sostenuto;
            case SOFT -> soft;
        };
    }

    /** Nothing down, nothing ringing, no pedal: the board can be forgotten. */
    public boolean idle() {
        return down.isEmpty() && raised.isEmpty() && !sustain && !sostenuto && !soft;
    }

    private boolean held(int key) {
        return sustain || (sostenuto && caught.get(key));
    }

    /** Drops the dampers of keys that are up and no longer held by a pedal. */
    private BitSet settle() {
        BitSet falls = new BitSet(KEYS);
        for (int k = raised.nextSetBit(0); k >= 0; k = raised.nextSetBit(k + 1)) {
            if (!down.get(k) && !held(k)) falls.set(k);
        }
        raised.andNot(falls);
        return falls;
    }
}
