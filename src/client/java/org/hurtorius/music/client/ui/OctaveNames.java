package org.hurtorius.music.client.ui;

import org.jspecify.annotations.Nullable;

import java.util.function.ToIntFunction;

/**
 * Where the on-screen keyboard prints the octave names of its C keys ("C1" .. "C8"). A name is
 * centred on its key when it fits there; otherwise it runs on over the D after it (which never shows
 * anything on that line) or, for C8, back over the B before it. A name that fits nowhere is left
 * off: cut down to its digit it would read like the number keys printed on the black keys nearby.
 * A C whose line holds the computer key that plays it gets a mark instead of its name. Pure.
 */
final class OctaveNames {
    /** An octave name and the left edge it is drawn from, in GUI units. */
    record Placed(String text, int x) {}

    private OctaveNames() {}

    /**
     * The octave name of {@code key}, or null when it is not printed.
     *
     * @param computerKey  true when a computer key plays this C and its label takes the same line
     * @param left         left edge of the key
     * @param right        right edge of the key's face (its outline left out)
     * @param nextRight    right edge of the D after it, or -1 for C8, which has none
     * @param previousLeft left edge of the B before it
     * @param width        how wide a text is drawn
     */
    static @Nullable Placed name(int key, boolean computerKey, int left, int right, int nextRight, int previousLeft, ToIntFunction<String> width) {
        if (!KeyboardLayout.isC(key) || computerKey) return null;
        String name = Formats.keyName(key);
        int w = width.applyAsInt(name);
        int room = right - left;
        if (w <= room - 1) return new Placed(name, left + (room - w + 1) / 2);
        if (nextRight >= 0) return w <= nextRight - left - 1 ? new Placed(name, left + 1) : null;
        int x = right - 1 - w;
        return x >= previousLeft + 1 ? new Placed(name, x) : null;
    }
}
