package org.hurtorius.music.client.audio;

import java.util.Comparator;
import java.util.List;

/**
 * Which voice gives way when the pool is full. Voices already fading out go first, then released
 * voices and one-shots, then sounding notes, and only then notes that have not started yet (the one
 * furthest in the future). Within each of the first three the quietest at the listener goes first
 * (the oldest on a tie): the level counts the piano's volume, lid and tone and the listener's distance,
 * so a far piano's notes give way before the ones the player holds at their own.
 */
public final class StealPolicy {
    /** Where a voice is in its life, in the order voices are given up. */
    public enum Stage { FADING, RELEASED, SOUNDING, SCHEDULED }

    /**
     * @param slot  the voice's index in the pool
     * @param since when it entered its stage (fade start, release, start, or scheduled start), in nanoseconds
     * @param level its estimated current loudness at the listener (linear)
     */
    public record Candidate(int slot, Stage stage, long since, float level) {}

    private static final Comparator<Candidate> ORDER = (a, b) -> {
        if (a.stage != b.stage) return Integer.compare(a.stage.ordinal(), b.stage.ordinal());
        return switch (a.stage) {
            case FADING -> Float.compare(a.level, b.level);
            case RELEASED, SOUNDING -> a.level != b.level ? Float.compare(a.level, b.level) : Long.compare(a.since, b.since);
            case SCHEDULED -> Long.compare(b.since, a.since);
        };
    };

    private StealPolicy() {}

    /** The slot to take over, or -1 when there is none. */
    public static int victim(List<Candidate> candidates) {
        Candidate best = null;
        for (Candidate c : candidates) if (best == null || ORDER.compare(c, best) < 0) best = c;
        return best == null ? -1 : best.slot;
    }
}
