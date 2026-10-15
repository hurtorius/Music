package org.hurtorius.music.song.midi;

import org.hurtorius.music.song.Song;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.regex.Pattern;

/**
 * Decides which hand plays each note of an imported file. A part is one channel of one track.
 *
 * <ol>
 *   <li>Parts whose track is named for a hand ("Right hand", "RH", "Treble", "Left hand", "LH",
 *   "Bass", ...) are played by that hand.</li>
 *   <li>Otherwise, when two parts carry nearly all the notes and sit at clearly different heights
 *   (a typical two-staff piano file), the higher one is the right hand.</li>
 *   <li>Remaining notes split by pitch, window by window ({@value #WINDOW_MS} ms): the split goes
 *   where the fewest notes sit near it, pulled towards middle C when nothing stands out, smoothed
 *   over neighbouring windows. A window holding a single melodic line is played by one hand.</li>
 * </ol>
 */
final class HandAssigner {
    static final int WINDOW_MS = 2000;
    private static final int SPLIT_LOW = 48, SPLIT_HIGH = 72, MIDDLE = 60;
    private static final Pattern RIGHT = Pattern.compile(
        "(?i).*\\b(right|r\\.?\\s?h|treble|upper|primo|droite|rechts?|destra|derecha)\\b.*");
    private static final Pattern LEFT = Pattern.compile(
        "(?i).*\\b(left|l\\.?\\s?h|bass|lower|secondo|gauche|links?|sinistra|izquierda)\\b.*");

    private HandAssigner() {}

    /** One {@link Song} hand constant per note; the arrays are indexed 0..n-1 and sorted by start. */
    static byte[] assign(int[] start, int[] end, int[] midi, int[] part, int n, IntFunction<String> partName) {
        byte[] hand = new byte[n];
        Map<Integer, int[]> stats = new HashMap<>();   // part -> {notes, pitch sum}
        for (int i = 0; i < n; i++) {
            int[] s = stats.computeIfAbsent(part[i], p -> new int[2]);
            s[0]++;
            s[1] += midi[i];
        }

        Map<Integer, Byte> partHand = new HashMap<>();
        for (int p : stats.keySet()) {
            byte named = handFromName(partName.apply(p));
            if (named != Song.HAND_UNKNOWN) partHand.put(p, named);
        }
        if (partHand.isEmpty()) dominantPair(stats, n, partHand);

        List<Integer> rest = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Byte h = partHand.get(part[i]);
            if (h != null) hand[i] = h;
            else rest.add(i);
        }
        if (!rest.isEmpty()) splitByPitch(start, end, midi, rest, hand);
        return hand;
    }

    static byte handFromName(String name) {
        if (name == null || name.isBlank()) return Song.HAND_UNKNOWN;
        boolean right = RIGHT.matcher(name).matches(), left = LEFT.matcher(name).matches();
        if (right == left) return Song.HAND_UNKNOWN;
        return (byte) (right ? Song.HAND_RIGHT : Song.HAND_LEFT);
    }

    private static void dominantPair(Map<Integer, int[]> stats, int total, Map<Integer, Byte> partHand) {
        if (stats.size() < 2) return;
        List<Map.Entry<Integer, int[]>> parts = new ArrayList<>(stats.entrySet());
        parts.sort((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]));
        int[] first = parts.get(0).getValue(), second = parts.get(1).getValue();
        if (first[0] + second[0] < total * 0.85 || second[0] < total * 0.08) return;
        double firstPitch = first[1] / (double) first[0], secondPitch = second[1] / (double) second[0];
        if (Math.abs(firstPitch - secondPitch) < 5) return;
        boolean firstIsRight = firstPitch > secondPitch;
        partHand.put(parts.get(0).getKey(), (byte) (firstIsRight ? Song.HAND_RIGHT : Song.HAND_LEFT));
        partHand.put(parts.get(1).getKey(), (byte) (firstIsRight ? Song.HAND_LEFT : Song.HAND_RIGHT));
    }

    private static void splitByPitch(int[] start, int[] end, int[] midi, List<Integer> notes, byte[] hand) {
        int windows = start[notes.get(notes.size() - 1)] / WINDOW_MS + 1;
        int[] first = new int[windows], count = new int[windows];
        Arrays.fill(first, -1);
        for (int k = 0; k < notes.size(); k++) {
            int w = start[notes.get(k)] / WINDOW_MS;
            if (first[w] < 0) first[w] = k;
            count[w]++;
        }

        // Per window: a split key (left < split <= right), or a whole-window hand for a single line.
        int[] split = new int[windows];
        byte[] single = new byte[windows];
        List<Integer> splitWindows = new ArrayList<>();
        for (int w = 0; w < windows; w++) {
            if (count[w] == 0) continue;
            int[] pitches = new int[count[w]];
            boolean line = true;
            int low = 127, high = 0, sum = 0, lastEnd = Integer.MIN_VALUE;
            for (int k = 0; k < count[w]; k++) {
                int i = notes.get(first[w] + k);
                pitches[k] = midi[i];
                low = Math.min(low, midi[i]);
                high = Math.max(high, midi[i]);
                sum += midi[i];
                if (start[i] < lastEnd - 40) line = false;
                lastEnd = Math.max(lastEnd, end[i]);
            }
            if (line && high - low <= 19) {
                single[w] = (byte) (sum / (double) count[w] >= 57 ? Song.HAND_RIGHT : Song.HAND_LEFT);
            } else {
                split[w] = bestSplit(pitches);
                splitWindows.add(w);
            }
        }

        // Smooth the split over neighbouring windows (median of three) so it does not jump around.
        int[] smoothed = split.clone();
        for (int j = 0; j < splitWindows.size(); j++) {
            int a = split[splitWindows.get(Math.max(0, j - 1))];
            int b = split[splitWindows.get(j)];
            int c = split[splitWindows.get(Math.min(splitWindows.size() - 1, j + 1))];
            smoothed[splitWindows.get(j)] = Math.max(Math.min(a, b), Math.min(Math.max(a, b), c));
        }

        for (int i : notes) {
            int w = start[i] / WINDOW_MS;
            hand[i] = single[w] != 0 ? single[w] : (byte) (midi[i] < smoothed[w] ? Song.HAND_LEFT : Song.HAND_RIGHT);
        }
    }

    /** The split key with the fewest notes close to it, preferring middle C when there is no clear gap. */
    static int bestSplit(int[] pitches) {
        int best = MIDDLE;
        double bestCost = Double.MAX_VALUE;
        for (int s = SPLIT_LOW; s <= SPLIT_HIGH; s++) {
            double boundary = s - 0.5, cost = 0.08 * pitches.length * Math.abs(boundary - (MIDDLE - 0.5));
            for (int p : pitches) {
                double d = Math.abs(p - boundary);
                if (d < 3) cost += 3 - d;
            }
            if (cost < bestCost) {
                bestCost = cost;
                best = s;
            }
        }
        return best;
    }
}
