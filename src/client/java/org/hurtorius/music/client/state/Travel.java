package org.hurtorius.music.client.state;

import java.util.Arrays;

/**
 * How far each of a set of moving parts (keys, dampers, pedals) has travelled between rest (0) and
 * fully moved (1) over time.
 *
 * <p>A part is sent toward one end at a given instant and sets off from wherever it is at that
 * instant, so a change of direction half way never makes it jump. Moves are eased, never linear:
 * toward the moved end a part sets off quickly and settles softly; back to rest it either settles
 * softly too (a key rising under its own weight) or, when it falls freely, gathers speed until it
 * lands (a damper dropping onto its strings). A whole move takes the time given for that direction;
 * a part already part of the way there arrives sooner, at the same speed.</p>
 *
 * <p>A move may be sent for a later instant (a key that first finishes its stroke). Each part keeps its
 * last {@link #KEPT} moves and follows an earlier one until a later one begins. A move never starts
 * before the latest one sent; one asked for earlier starts with it.</p>
 */
public final class Travel {
    /** Moves kept per part: the one under way and those queued behind it. */
    static final int KEPT = 8;
    /** Start of a move made before anything was sent: the part is already where it was going. */
    private static final long LONG_AGO = Long.MIN_VALUE;

    private final long moveNanos, restNanos;
    private final boolean fallsToRest;
    private final long[] start;
    private final float[] from;
    private final boolean[] moving;
    /** Slot of each part's latest move. */
    private final int[] latest;

    /**
     * @param parts       how many parts move independently
     * @param moveNanos   a whole move from rest to fully moved
     * @param restNanos   a whole move back to rest
     * @param fallsToRest true when a part falls back to rest (speeding up until it lands) rather than settling softly
     */
    public Travel(int parts, long moveNanos, long restNanos, boolean fallsToRest) {
        if (moveNanos <= 0 || restNanos <= 0) throw new IllegalArgumentException("Travel times must be positive");
        this.moveNanos = moveNanos;
        this.restNanos = restNanos;
        this.fallsToRest = fallsToRest;
        this.start = new long[parts * KEPT];
        this.from = new float[parts * KEPT];
        this.moving = new boolean[parts * KEPT];
        this.latest = new int[parts];
        Arrays.fill(start, LONG_AGO);
    }

    /**
     * Sends {@code part} toward fully moved ({@code move}) or toward rest from instant {@code at}
     * ({@link System#nanoTime()}). Nothing changes when it is already going that way.
     */
    public void send(int part, boolean move, long at) {
        int now = part * KEPT + latest[part];
        if (moving[now] == move) return;
        long begin = Math.max(at, start[now]);
        if (begin == start[now]) {
            // The latest move would not have got under way: this one takes its place, from the same point.
            moving[now] = move;
            return;
        }
        float there = depth(part, begin);
        int next = (latest[part] + 1) % KEPT;
        latest[part] = next;
        int slot = part * KEPT + next;
        start[slot] = begin;
        from[slot] = there;
        moving[slot] = move;
    }

    /** 0 at rest .. 1 fully moved, at instant {@code at}. */
    public float depth(int part, long at) {
        int base = part * KEPT;
        int slot = latest[part];
        // The latest move that has begun by then; before all of them, the oldest one's starting point.
        for (int i = 0; i < KEPT - 1 && start[base + slot] > at; i++) slot = (slot + KEPT - 1) % KEPT;
        return along(base + slot, at);
    }

    /** True when the part's latest move goes toward fully moved. */
    public boolean moving(int part) {
        return moving[part * KEPT + latest[part]];
    }

    /** When the part's latest move begins (or began). */
    public long since(int part) {
        return start[part * KEPT + latest[part]];
    }

    /** When the part's latest move arrives where it is going. */
    public long arrives(int part) {
        int slot = part * KEPT + latest[part];
        return start[slot] == LONG_AGO ? LONG_AGO : start[slot] + duration(slot);
    }

    /**
     * When the part's latest move passes {@code depth} on its way, or when it begins if it starts
     * beyond that already.
     */
    public long passes(int part, float depth) {
        int slot = part * KEPT + latest[part];
        float f = from[slot];
        if (start[slot] == LONG_AGO || (moving[slot] ? f >= depth : f <= depth)) return start[slot];
        float t;
        if (moving[slot]) t = 1f - (float) Math.sqrt(1f - (depth - f) / (1f - f));
        else if (fallsToRest) t = (float) Math.sqrt(1f - depth / f);
        else t = 1f - (float) Math.sqrt(depth / f);
        return start[slot] + (long) (t * duration(slot));
    }

    private long duration(int slot) {
        return moving[slot] ? (long) (moveNanos * (1f - from[slot])) : (long) (restNanos * from[slot]);
    }

    private float along(int slot, long at) {
        boolean move = moving[slot];
        if (start[slot] == LONG_AGO) return move ? 1f : 0f;
        float f = from[slot];
        long elapsed = at - start[slot];
        if (elapsed <= 0) return f;
        long length = duration(slot);
        if (elapsed >= length) return move ? 1f : 0f;
        float t = elapsed / (float) length;
        if (move) return f + (1f - f) * (1f - (1f - t) * (1f - t));
        return fallsToRest ? f * (1f - t * t) : f * (1f - t) * (1f - t);
    }
}
