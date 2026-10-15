package org.hurtorius.music.client;

/**
 * How long the singleplayer game has stood paused, as the player saw it: from the frame the pause
 * menu stopped the game to the frame it went on, on the clock the songs' timelines keep
 * ({@link org.hurtorius.music.MusicClock}), counted up over every pause since the game started.
 *
 * <p>The integrated server notices a pause at its next tick, and that it ended only once the save it
 * starts on pausing is done, which can outlast a short pause. Measured there, a song's timeline would
 * fall behind what every listener heard by however long the save ran over. So the server takes out of
 * its timelines what this clock counted from the start of the pause it noticed to the tick it saw the
 * game going again (see {@code IntegratedServerMixin}). Written by the client thread
 * ({@code MinecraftMixin}), read by the server thread.</p>
 */
public final class PauseClock {
    /** Paused time of every pause that has ended, in milliseconds. */
    private static long ended;
    /** When the pause going on now began, or 0 while the game runs. */
    private static long since;

    private PauseClock() {}

    /** The game's pause flag was just set for this frame: {@code paused}, at {@code now}. */
    public static synchronized void frame(boolean paused, long now) {
        if (paused && since == 0) {
            since = now;
        } else if (!paused && since != 0) {
            ended += Math.max(0, now - since);
            since = 0;
        }
    }

    /** All the time the game stood paused in the pauses that are over, in milliseconds: everything before the pause going on now. */
    public static synchronized long pausedBefore() {
        return ended;
    }

    /** All the time the game has stood paused up to {@code now}, the pause going on now included, in milliseconds. */
    public static synchronized long pausedMillis(long now) {
        return ended + (since == 0 ? 0 : Math.max(0, now - since));
    }
}
