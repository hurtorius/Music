package org.hurtorius.music.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.network.chat.Component;
import org.hurtorius.music.client.ClientPrefs;
import org.hurtorius.music.client.play.Performer;
import org.hurtorius.music.piano.Pedal;
import org.lwjgl.glfw.GLFW;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Everything the piano screen holds down at the performer: computer keys, the key under the mouse,
 * and pedals pressed with the space bar or the pedal buttons. Each computer key remembers the piano
 * key it struck, so changing the octave while holding a key still releases the right note; key
 * repeats never strike again. {@link #releaseAll} lets go of exactly what the screen pressed (a MIDI
 * keyboard's own keys stay down). Client thread.
 */
final class PlayingInput {
    /** Velocity step for the up and down arrow keys. */
    static final int VELOCITY_STEP = 8;

    /** Computer key (GLFW code) to the piano key it struck, or -1 when it struck none (off the keyboard). */
    private final Map<Integer, Integer> held = new HashMap<>();
    /** Keys that do something on press only (space in toggle mode, arrows), held so repeats are ignored. */
    private final Set<Integer> latched = new HashSet<>();
    private final Set<Pedal> pedals = EnumSet.noneOf(Pedal.class);
    private int mouseKey = -1;
    private boolean prefsChanged;
    private final Runnable refused;

    /** @param refused called when the player tries to play but may not (the screen explains why) */
    PlayingInput(Runnable refused) {
        this.refused = refused;
    }

    // ------------------------------------------------------------------ computer keyboard

    /**
     * Handles a key press (or repeat). Returns true when the key belongs to the piano: note keys,
     * space (sustain), left/right (octave) and up/down (velocity).
     */
    boolean keyPressed(int glfwKey) {
        ClientPrefs prefs = ClientPrefs.get();
        switch (glfwKey) {
            case GLFW.GLFW_KEY_SPACE -> {
                if (!latched.add(glfwKey)) return true;
                if (prefs.holdSustain) setPedal(Pedal.SUSTAIN, true);
                else setPedal(Pedal.SUSTAIN, !Performer.isPedalDown(Pedal.SUSTAIN));
                return true;
            }
            case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_RIGHT -> {
                if (latched.add(glfwKey)) shiftOctave(glfwKey == GLFW.GLFW_KEY_LEFT ? -1 : 1);
                return true;
            }
            case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_DOWN -> {
                changeVelocity(glfwKey == GLFW.GLFW_KEY_UP ? VELOCITY_STEP : -VELOCITY_STEP);
                return true;
            }
            default -> {
                if (!ComputerKeys.isNoteKey(glfwKey)) return false;
                if (held.containsKey(glfwKey)) return true;
                int key = ComputerKeys.pianoKey(glfwKey, prefs.keyboardBase);
                held.put(glfwKey, key);
                if (key >= 0) strike(key, prefs.keyVelocity);
                return true;
            }
        }
    }

    /** Handles a key release; true when the key was one the piano was holding. */
    boolean keyReleased(int glfwKey) {
        if (latched.remove(glfwKey)) {
            if (glfwKey == GLFW.GLFW_KEY_SPACE && ClientPrefs.get().holdSustain) setPedal(Pedal.SUSTAIN, false);
            return true;
        }
        Integer key = held.remove(glfwKey);
        if (key == null) return false;
        if (key >= 0) Performer.noteOff(key);
        return true;
    }

    /** Releases keys whose key-up never arrived (focus moved, an event got lost). Every tick. */
    void poll(Window window) {
        for (Iterator<Map.Entry<Integer, Integer>> it = held.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Integer> e = it.next();
            if (InputConstants.isKeyDown(window, e.getKey())) continue;
            it.remove();
            if (e.getValue() >= 0) Performer.noteOff(e.getValue());
        }
        for (Iterator<Integer> it = latched.iterator(); it.hasNext(); ) {
            int key = it.next();
            if (InputConstants.isKeyDown(window, key)) continue;
            it.remove();
            if (key == GLFW.GLFW_KEY_SPACE && ClientPrefs.get().holdSustain) setPedal(Pedal.SUSTAIN, false);
        }
    }

    void shiftOctave(int direction) {
        ClientPrefs prefs = ClientPrefs.get();
        int next = ComputerKeys.shiftOctave(prefs.keyboardBase, direction);
        if (next == prefs.keyboardBase) return;
        prefs.keyboardBase = next;
        prefsChanged = true;
    }

    void changeVelocity(int delta) {
        ClientPrefs prefs = ClientPrefs.get();
        int next = Math.max(1, Math.min(127, prefs.keyVelocity + delta));
        if (next == prefs.keyVelocity) return;
        prefs.keyVelocity = next;
        prefsChanged = true;
    }

    /** True once after the octave or velocity changed, so the screen saves the preferences. */
    boolean takePrefsChanged() {
        boolean changed = prefsChanged;
        prefsChanged = false;
        return changed;
    }

    // ------------------------------------------------------------------ mouse

    /** The mouse goes down on (or glides onto) {@code key}; -1 lets go. */
    void mouseKey(int key, int velocity) {
        if (key == mouseKey) return;
        if (mouseKey >= 0) Performer.noteOff(mouseKey);
        mouseKey = key;
        if (key >= 0) strike(key, velocity);
    }

    // ------------------------------------------------------------------ pedals

    /** Presses or releases a pedal for the screen; remembered so closing the screen lets it go. */
    void setPedal(Pedal pedal, boolean down) {
        if (down && !Performer.canPlay()) {
            refused.run();
            return;
        }
        Performer.pedal(pedal, down);
        if (down) pedals.add(pedal);
        else pedals.remove(pedal);
    }

    void togglePedal(Pedal pedal) {
        setPedal(pedal, !Performer.isPedalDown(pedal));
    }

    // ------------------------------------------------------------------ everything

    private void strike(int key, int velocity) {
        if (!Performer.canPlay()) {
            refused.run();
            return;
        }
        Performer.noteOn(key, velocity);
    }

    /** Lets go of every key and pedal the screen holds (the screen closes or the window lost focus). */
    void releaseAll() {
        for (int key : held.values()) if (key >= 0) Performer.noteOff(key);
        held.clear();
        latched.clear();
        mouseKey(-1, 0);
        for (Pedal pedal : pedals) Performer.pedal(pedal, false);
        pedals.clear();
    }

    /** A message for a key the player may not play. */
    static Component refusal(String owner) {
        return owner.isEmpty() ? Component.translatable("music.screen.cannot_play") : Component.translatable("music.message.owner_only", owner);
    }
}
