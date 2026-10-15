package org.hurtorius.music.client.ui;

import java.util.Locale;

/** Text helpers shared by the piano screen: note names, durations and signed numbers. Pure Java. */
public final class Formats {
    private static final String[] PITCH_NAMES = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
    /** The minus sign, wider than a hyphen so signed numbers line up. */
    private static final char MINUS = '−';

    private Formats() {}

    /** Scientific pitch name of a MIDI note, spelled with sharps: 60 is "C4", 21 is "A0". */
    public static String noteName(int midi) {
        return PITCH_NAMES[Math.floorMod(midi, 12)] + (Math.floorDiv(midi, 12) - 1);
    }

    /** Name of a piano key 0..87 (A0..C8). */
    public static String keyName(int key) {
        return noteName(key + KeyboardLayout.LOWEST_MIDI);
    }

    /** "m:ss" (or "h:mm:ss" from an hour on); negative times count as zero. */
    public static String duration(long ms) {
        long seconds = Math.max(0, ms) / 1000;
        if (seconds >= 3600) return String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    /** "+3", "0" or "−3". */
    public static String signed(int value) {
        if (value > 0) return "+" + value;
        if (value < 0) return MINUS + Integer.toString(-value);
        return "0";
    }

    /** A float with at most one decimal and no trailing ".0": 440, 440.5. */
    public static String decimal(float value) {
        float rounded = Math.round(value * 10f) / 10f;
        if (rounded == Math.rint(rounded)) return Integer.toString(Math.round(rounded));
        return String.format(Locale.ROOT, "%.1f", rounded);
    }
}
