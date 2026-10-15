package org.hurtorius.music.client.importer;

import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import java.util.function.LongSupplier;

/**
 * Progress reporting and cancellation for one stage of an import. A stage covers a slice of the
 * caller's 0..1 range; {@link #slice} hands sub-stages their part of it. Reports are throttled and
 * never go backwards.
 */
final class Progress {
    private static final double MIN_STEP = 0.002;

    private final DoubleConsumer sink;
    private final BooleanSupplier cancelled;
    private final double from, to;
    private final double[] last;
    private LongSupplier position = () -> 0;
    private long total;

    private Progress(DoubleConsumer sink, BooleanSupplier cancelled, double from, double to, double[] last) {
        this.sink = sink;
        this.cancelled = cancelled;
        this.from = from;
        this.to = to;
        this.last = last;
    }

    static Progress of(DoubleConsumer sink, BooleanSupplier cancelled) {
        return new Progress(sink, cancelled, 0, 1, new double[]{-1});
    }

    /** The part {@code a..b} (fractions of this stage) as its own stage. */
    Progress slice(double a, double b) {
        return new Progress(sink, cancelled, from + (to - from) * a, from + (to - from) * b, last);
    }

    /** Lets {@link #check()} report {@code position / total} without being told each fraction. */
    void track(LongSupplier position, long total) {
        this.position = position;
        this.total = total;
    }

    /** Throws if cancelled, then reports the tracked position. */
    void check() {
        if (cancelled.getAsBoolean()) throw new CancellationException("Import cancelled");
        if (total > 0) report(position.getAsLong() / (double) total);
    }

    /** Throws if cancelled, then reports {@code fraction} of this stage. */
    void at(double fraction) {
        if (cancelled.getAsBoolean()) throw new CancellationException("Import cancelled");
        report(fraction);
    }

    private void report(double fraction) {
        double v = from + (to - from) * Math.max(0, Math.min(1, fraction));
        if (v > last[0] + MIN_STEP || (v >= to && v > last[0])) {
            last[0] = v;
            sink.accept(v);
        }
    }
}
