package org.hurtorius.music.client.importer;

import org.hurtorius.music.song.Song;

import java.util.Arrays;
import java.util.Comparator;

/**
 * Assigns transcribed notes to the left or right hand by pitch. Notes struck together form a chord;
 * each chord is cut where the cut keeps every note close to where that hand has recently been
 * playing and keeps each hand within a playable span (about a tenth). Each hand's position then
 * follows the notes it was given, so the split moves with the music instead of sitting at middle C.
 */
final class HandSplitter {
    private static final double CHORD_SECONDS = 0.04;
    private static final int REACH = 14;
    private static final double FOLLOW = 0.25;
    private static final double MIN_SEPARATION = 7;

    private HandSplitter() {}

    /**
     * @param starts start times in seconds
     * @param keys   piano keys 0..87
     * @return {@link Song#HAND_LEFT} or {@link Song#HAND_RIGHT} for each note
     */
    static int[] split(double[] starts, int[] keys) {
        int n = starts.length;
        int[] hands = new int[n];
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, Comparator.comparingDouble((Integer i) -> starts[i]).thenComparingInt(i -> keys[i]));
        double left = 27, right = 51; // C3 and C5 as keys
        int i = 0;
        while (i < n) {
            int j = i + 1;
            while (j < n && starts[order[j]] - starts[order[i]] <= CHORD_SECONDS) j++;
            Integer[] chord = Arrays.copyOfRange(order, i, j);
            Arrays.sort(chord, Comparator.comparingInt(c -> keys[c]));
            int bestCut = 0;
            double bestCost = Double.MAX_VALUE;
            for (int cut = 0; cut <= chord.length; cut++) {
                double cost = 0;
                for (int c = 0; c < chord.length; c++) cost += Math.abs(keys[chord[c]] - (c < cut ? left : right));
                if (cut > 1) cost += 10 * Math.max(0, keys[chord[cut - 1]] - keys[chord[0]] - REACH);
                if (cut < chord.length - 1) cost += 10 * Math.max(0, keys[chord[chord.length - 1]] - keys[chord[cut]] - REACH);
                if (cost < bestCost) {
                    bestCost = cost;
                    bestCut = cut;
                }
            }
            double leftSum = 0, rightSum = 0;
            for (int c = 0; c < chord.length; c++) {
                boolean isLeft = c < bestCut;
                hands[chord[c]] = isLeft ? Song.HAND_LEFT : Song.HAND_RIGHT;
                if (isLeft) leftSum += keys[chord[c]];
                else rightSum += keys[chord[c]];
            }
            if (bestCut > 0) left += FOLLOW * (leftSum / bestCut - left);
            if (bestCut < chord.length) right += FOLLOW * (rightSum / (chord.length - bestCut) - right);
            if (right - left < MIN_SEPARATION) {
                double middle = (left + right) / 2;
                left = middle - MIN_SEPARATION / 2;
                right = middle + MIN_SEPARATION / 2;
            }
            i = j;
        }
        return hands;
    }
}
