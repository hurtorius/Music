package org.hurtorius.music.client.audio;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.Predicate;

/**
 * Time-ordered events: earliest first, and events at the same instant in the order they were added.
 * Single-threaded (the audio thread owns it).
 */
public final class EventQueue<E> {
    private record Entry<E>(long at, long seq, E event) implements Comparable<Entry<E>> {
        @Override
        public int compareTo(Entry<E> o) {
            return at != o.at ? Long.compare(at, o.at) : Long.compare(seq, o.seq);
        }
    }

    private final PriorityQueue<Entry<E>> queue = new PriorityQueue<>();
    private long seq;

    public void add(long at, E event) {
        queue.add(new Entry<>(at, seq++, event));
    }

    /** Time of the earliest event, or {@link Long#MAX_VALUE} when empty. */
    public long nextTime() {
        Entry<E> head = queue.peek();
        return head == null ? Long.MAX_VALUE : head.at;
    }

    /** Removes and returns every event at or before {@code until}, in order. */
    public List<E> pollUntil(long until) {
        List<E> out = new ArrayList<>();
        while (!queue.isEmpty() && queue.peek().at <= until) out.add(queue.poll().event);
        return out;
    }

    /** Removes the events matching {@code filter}; returns how many. */
    public int removeIf(Predicate<E> filter) {
        int before = queue.size();
        queue.removeIf(e -> filter.test(e.event));
        return before - queue.size();
    }

    public int size() {
        return queue.size();
    }

    public void clear() {
        queue.clear();
    }
}
