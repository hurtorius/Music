package org.hurtorius.music.client.ui;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Which computer key plays which piano key. Keys are matched by physical position: GLFW key codes
 * name the positions of a US keyboard whatever layout the player uses, so the rows stay where
 * they are on AZERTY or QWERTZ (the screen labels them with the player's own key names).
 *
 * <p>The lower row (Z S X D C V G B H N J M , L . ; /) plays from the base C up a major third
 * above the next C; the upper row (Q 2 W 3 E R 5 T 6 Y 7 U I 9 O 0 P [ = ]) starts an octave
 * higher. White keys are on the letter rows, black keys on the row above, like a piano. Pure.</p>
 */
public final class ComputerKeys {
    /** Lower row, a semitone apart, from the base C. */
    static final int[] LOWER = {
        GLFW.GLFW_KEY_Z, GLFW.GLFW_KEY_S, GLFW.GLFW_KEY_X, GLFW.GLFW_KEY_D, GLFW.GLFW_KEY_C, GLFW.GLFW_KEY_V,
        GLFW.GLFW_KEY_G, GLFW.GLFW_KEY_B, GLFW.GLFW_KEY_H, GLFW.GLFW_KEY_N, GLFW.GLFW_KEY_J, GLFW.GLFW_KEY_M,
        GLFW.GLFW_KEY_COMMA, GLFW.GLFW_KEY_L, GLFW.GLFW_KEY_PERIOD, GLFW.GLFW_KEY_SEMICOLON, GLFW.GLFW_KEY_SLASH
    };
    /** Upper row, a semitone apart, from the C above the base. */
    static final int[] UPPER = {
        GLFW.GLFW_KEY_Q, GLFW.GLFW_KEY_2, GLFW.GLFW_KEY_W, GLFW.GLFW_KEY_3, GLFW.GLFW_KEY_E, GLFW.GLFW_KEY_R,
        GLFW.GLFW_KEY_5, GLFW.GLFW_KEY_T, GLFW.GLFW_KEY_6, GLFW.GLFW_KEY_Y, GLFW.GLFW_KEY_7, GLFW.GLFW_KEY_U,
        GLFW.GLFW_KEY_I, GLFW.GLFW_KEY_9, GLFW.GLFW_KEY_O, GLFW.GLFW_KEY_0, GLFW.GLFW_KEY_P, GLFW.GLFW_KEY_LEFT_BRACKET,
        GLFW.GLFW_KEY_EQUAL, GLFW.GLFW_KEY_RIGHT_BRACKET
    };
    /** Lowest and highest base: C0 lets the lower row reach A0, C6 lets the upper row reach C8. */
    public static final int LOWEST_BASE = 12, HIGHEST_BASE = 84, DEFAULT_BASE = 48;
    /** Semitones from the base to the top of the upper row. */
    public static final int SPAN = 12 + UPPER.length - 1;

    private ComputerKeys() {}

    /** Semitones above the base that a computer key plays, or -1 when it plays nothing. */
    public static int semitone(int glfwKey) {
        for (int i = 0; i < LOWER.length; i++) if (LOWER[i] == glfwKey) return i;
        for (int i = 0; i < UPPER.length; i++) if (UPPER[i] == glfwKey) return 12 + i;
        return -1;
    }

    /** True for keys that play notes (whether or not the note is on the piano at this base). */
    public static boolean isNoteKey(int glfwKey) {
        return semitone(glfwKey) >= 0;
    }

    /** The piano key (0..87) a computer key plays from {@code base}, or -1 when it plays none. */
    public static int pianoKey(int glfwKey, int base) {
        int semitone = semitone(glfwKey);
        if (semitone < 0) return -1;
        int key = normalizeBase(base) + semitone - KeyboardLayout.LOWEST_MIDI;
        return key >= 0 && key < KeyboardLayout.KEYS ? key : -1;
    }

    /** Every computer key that plays {@code key} from {@code base}: lower row first. Up to two keys. */
    public static List<Integer> keysFor(int key, int base) {
        int semitone = key + KeyboardLayout.LOWEST_MIDI - normalizeBase(base);
        List<Integer> keys = new ArrayList<>(2);
        if (semitone >= 0 && semitone < LOWER.length) keys.add(LOWER[semitone]);
        if (semitone >= 12 && semitone - 12 < UPPER.length) keys.add(UPPER[semitone - 12]);
        return keys;
    }

    /** The base as a C between {@link #LOWEST_BASE} and {@link #HIGHEST_BASE}, rounding to the nearest C. */
    public static int normalizeBase(int base) {
        int c = Math.round(base / 12f) * 12;
        return Math.max(LOWEST_BASE, Math.min(HIGHEST_BASE, c));
    }

    /** The base one octave down ({@code direction} -1) or up (+1), kept in range. */
    public static int shiftOctave(int base, int direction) {
        return normalizeBase(normalizeBase(base) + 12 * Integer.signum(direction));
    }

    /** Lowest MIDI note the rows play from {@code base}, not below A0. */
    public static int lowestMidi(int base) {
        return Math.max(KeyboardLayout.LOWEST_MIDI, normalizeBase(base));
    }

    /** Highest MIDI note the rows play from {@code base}, not above C8. */
    public static int highestMidi(int base) {
        return Math.min(KeyboardLayout.LOWEST_MIDI + KeyboardLayout.KEYS - 1, normalizeBase(base) + SPAN);
    }

    /** "C3–G5": the notes the computer keyboard reaches from {@code base}. */
    public static String rangeLabel(int base) {
        return Formats.noteName(lowestMidi(base)) + "–" + Formats.noteName(highestMidi(base));
    }
}
