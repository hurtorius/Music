package org.hurtorius.music.client.anim;

import org.jspecify.annotations.Nullable;

/**
 * Decides which hand plays which of a performer's active keys.
 *
 * <p>The sorted keys are either one cluster for one hand, or are split at a gap into a lower cluster
 * for the left hand and a higher one for the right. Every candidate is scored by how far the hands
 * would travel from where they are, how far a hand would have to stretch, how far a hand would be on
 * the wrong side of middle C, and whether the choice differs from the previous one (hysteresis), and
 * the cheapest wins. In practice: two clusters go to both hands, left taking the lower; a single
 * cluster goes to the nearer hand, and stays with it until it moves well past middle C.</p>
 *
 * <p>Positions are key indices (0 = A0 .. 87 = C8); distances are semitones.</p>
 */
public final class HandAssigner {
    /** Key index of middle C (C4, MIDI 60). */
    public static final int MIDDLE_C = 39;
    /** A hand covers this span without effort. */
    static final float COMFORT_SPAN = 9f;
    /** Beyond this span one hand cannot hold a cluster. */
    static final float MAX_SPAN = 14f;
    static final float STRETCH_COST = 2f;
    static final float OVERSTRETCH_COST = 10f;
    /** How far past middle C a hand may wander before it costs anything. */
    static final float SIDE_MARGIN = 3f;
    static final float SIDE_COST = 1.5f;
    /** Cost of choosing differently from last time. */
    static final float SWITCH_COST = 4f;
    /** Wide gaps favour splitting between the hands. */
    static final float GAP_FREE = 4f;
    static final float GAP_BONUS = 0.5f;

    /** What the previous decision was, for hysteresis. */
    public enum Choice { NONE, LEFT, RIGHT, SPLIT }

    /** An inclusive range of key indices played by one hand. */
    public record Cluster(int lo, int hi) {
        public float centre() {
            return (lo + hi) * 0.5f;
        }

        public int span() {
            return hi - lo;
        }

        public boolean contains(int key) {
            return key >= lo && key <= hi;
        }
    }

    /** Which cluster each hand plays; a null cluster means that hand is free. */
    public record Assignment(Choice choice, @Nullable Cluster left, @Nullable Cluster right) {
        public static final Assignment NONE = new Assignment(Choice.NONE, null, null);
    }

    private HandAssigner() {}

    /**
     * @param keys      active keys, sorted ascending, no duplicates
     * @param leftPos   where the left hand is now (fractional key index)
     * @param rightPos  where the right hand is now
     * @param previous  the previous decision
     */
    public static Assignment assign(int[] keys, float leftPos, float rightPos, Choice previous) {
        int n = keys.length;
        if (n == 0) return Assignment.NONE;
        Cluster all = new Cluster(keys[0], keys[n - 1]);
        Assignment best = new Assignment(Choice.LEFT, all, null);
        float bestCost = singleCost(all, leftPos, Choice.LEFT, previous);
        float rightCost = singleCost(all, rightPos, Choice.RIGHT, previous);
        if (rightCost < bestCost) {
            bestCost = rightCost;
            best = new Assignment(Choice.RIGHT, null, all);
        }
        for (int i = 1; i < n; i++) {
            Cluster low = new Cluster(keys[0], keys[i - 1]);
            Cluster high = new Cluster(keys[i], keys[n - 1]);
            float gap = keys[i] - keys[i - 1];
            float cost = Math.abs(low.centre() - leftPos) + Math.abs(high.centre() - rightPos)
                + stretch(low.span()) + stretch(high.span())
                - GAP_BONUS * Math.max(0f, gap - GAP_FREE)
                + (previous == Choice.SPLIT ? 0f : SWITCH_COST);
            if (cost < bestCost) {
                bestCost = cost;
                best = new Assignment(Choice.SPLIT, low, high);
            }
        }
        return best;
    }

    private static float singleCost(Cluster cluster, float handPos, Choice hand, Choice previous) {
        float centre = cluster.centre();
        float wrongSide = hand == Choice.LEFT ? centre - (MIDDLE_C + SIDE_MARGIN) : (MIDDLE_C - SIDE_MARGIN) - centre;
        return Math.abs(centre - handPos) + stretch(cluster.span()) + SIDE_COST * Math.max(0f, wrongSide)
            + (previous == hand ? 0f : SWITCH_COST);
    }

    static float stretch(float span) {
        return STRETCH_COST * Math.max(0f, span - COMFORT_SPAN) + OVERSTRETCH_COST * Math.max(0f, span - MAX_SPAN);
    }
}
