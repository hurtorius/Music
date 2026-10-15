package org.hurtorius.music.client.audio;

import java.util.function.LongSupplier;

/**
 * Maps {@link System#nanoTime()} to the audio device's clock.
 *
 * <p>OpenAL Soft's device clock advances once per mixer update (a staircase a few milliseconds per
 * step), so a single reading lags the true device time by anything up to one update. The largest
 * {@code device - system} difference seen over the last few seconds is the reading taken closest to a
 * step, which tracks the true offset to well under a millisecond and still follows slow drift between
 * the two clocks. Written by the audio thread, read from any thread.</p>
 */
public final class ClockSync {
    /** Readings older than this are forgotten, so drift between the clocks is followed. */
    static final long WINDOW_NANOS = 4_000_000_000L;
    private static final int CAPACITY = 256;

    private final long[] systemAt = new long[CAPACITY];
    private final long[] difference = new long[CAPACITY];
    private int count, next;
    private volatile long offset;
    private volatile boolean valid;

    /** Forgets everything (a new device has a new clock). */
    public synchronized void reset() {
        count = 0;
        next = 0;
        offset = 0;
        valid = false;
    }

    /**
     * Reads the device clock, records the reading and returns it. The system clock is read after it:
     * the query can wait (OpenAL Soft holds its device lock while Minecraft lists the audio devices,
     * and while it mixes), and a wait must only make a reading lag, never lead, as the maximum
     * filter assumes.
     */
    public long read(LongSupplier deviceClock) {
        long device = deviceClock.getAsLong();
        sample(device, System.nanoTime());
        return device;
    }

    /** Records a device clock reading taken at {@code systemNanos}. */
    public synchronized void sample(long deviceNanos, long systemNanos) {
        systemAt[next] = systemNanos;
        difference[next] = deviceNanos - systemNanos;
        next = (next + 1) % CAPACITY;
        if (count < CAPACITY) count++;
        long best = Long.MIN_VALUE;
        for (int i = 0; i < count; i++) {
            if (systemNanos - systemAt[i] <= WINDOW_NANOS && difference[i] > best) best = difference[i];
        }
        offset = best;
        valid = true;
    }

    /** {@code device - system} in nanoseconds; 0 until the first reading. */
    public long offset() {
        return offset;
    }

    public boolean valid() {
        return valid;
    }

    public long toDevice(long systemNanos) {
        return systemNanos + offset();
    }
}
