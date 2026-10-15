package org.hurtorius.music;

/**
 * The clock Music measures elapsed time with: song timelines, rate limits and timeouts. It is
 * monotonic, so a step of the wall clock (a time sync correcting it, someone setting the system
 * time) neither jumps nor ends a song and never refuses or lets through a burst of packets. Its
 * readings mean nothing outside this process: a timeline is sent and saved by its position, and
 * anchored again to this clock when it arrives or loads.
 */
public final class MusicClock {
    private MusicClock() {}

    /** Milliseconds since an arbitrary origin fixed for this process (may be negative). */
    public static long millis() {
        return Math.floorDiv(System.nanoTime(), 1_000_000L);
    }
}
