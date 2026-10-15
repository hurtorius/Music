package org.hurtorius.music.client.audio;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The custom piano sounds ({@link org.hurtorius.music.piano.Instrument#CUSTOM}: any sound event) a client
 * keeps decoded. A sound that a piano nearby uses now is always kept; of the others only the most
 * recently used are, as many as {@code recent} and only while everything kept stays within
 * {@code maxBytes}. Anyone allowed to play a piano may change its sound, and every client nearby
 * decodes the new one, so without these bounds a stream of changes would fill every nearby player's
 * memory.
 *
 * <p>The samples of a sound let go of are handed to {@code retire}, so the audio thread frees their
 * OpenAL buffers; a decode that finishes after its sound was let go of is retired at once instead of
 * kept. Looked up from the audio thread, trimmed on the client thread, completed by the decoder.</p>
 */
public final class CustomSoundCache {
    /** Sounds kept besides the ones in use, so switching back and forth stays instant. */
    public static final int RECENT = 8;
    /** Memory all kept sounds may take together (sounds in use are kept regardless). */
    public static final long MAX_BYTES = 64L << 20;

    /** One custom sound: loading, loaded or missing. */
    public static final class Entry {
        public final String id;
        private volatile @Nullable PitchedSound sound;
        private volatile @Nullable String problem;
        private volatile long used;
        private boolean dropped;

        Entry(String id, long used) {
            this.id = id;
            this.used = used;
        }

        public @Nullable PitchedSound sound() {
            return sound;
        }

        /** Why the sound cannot play, or null. */
        public @Nullable String problem() {
            return problem;
        }

        /** True once the cache has let go of this sound: decoding it is no longer wanted. */
        public synchronized boolean dropped() {
            return dropped;
        }

        long bytes() {
            PitchedSound s = sound;
            long total = 0;
            if (s != null) for (PcmSample sample : s.samples()) total += sample.bytes();
            return total;
        }
    }

    private final int recent;
    private final long maxBytes;
    private final Consumer<PcmSample> retire;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicLong uses = new AtomicLong();

    /** @param retire receives every sample of a sound let go of, from whichever thread lets go of it */
    public CustomSoundCache(int recent, long maxBytes, Consumer<PcmSample> retire) {
        this.recent = recent;
        this.maxBytes = maxBytes;
        this.retire = retire;
    }

    /** The sound with this id, marked as used now; null when there is none. Any thread. */
    public @Nullable Entry get(String id) {
        Entry entry = entries.get(id);
        if (entry != null) entry.used = uses.incrementAndGet();
        return entry;
    }

    /** A new, loading sound with this id; null when there is one already (and someone else loads it). Any thread. */
    public @Nullable Entry add(String id) {
        Entry fresh = new Entry(id, uses.incrementAndGet());
        return entries.putIfAbsent(id, fresh) == null ? fresh : null;
    }

    /** Stores a finished decode; if the sound was let go of meanwhile its samples are retired instead. Returns whether it was kept. */
    public boolean complete(Entry entry, PitchedSound sound) {
        synchronized (entry) {
            if (!entry.dropped) {
                entry.sound = sound;
                return true;
            }
        }
        sound.samples().forEach(retire);
        return false;
    }

    /** Records why a sound cannot play. */
    public void fail(Entry entry, String problem) {
        entry.problem = problem;
    }

    /** Lets go of the sounds not in {@code inUse} beyond the most recent ones and the memory budget. Client thread. */
    public void trim(Set<String> inUse) {
        // Sorted on a snapshot of when each was used: the audio thread may use one meanwhile.
        List<Unused> others = new ArrayList<>();
        long bytes = 0;
        for (Entry entry : entries.values()) {
            if (inUse.contains(entry.id)) {
                entry.used = uses.incrementAndGet();
                bytes += entry.bytes();
            } else {
                others.add(new Unused(entry, entry.used, entry.bytes()));
            }
        }
        others.sort(Comparator.comparingLong(Unused::used).reversed());
        int kept = 0;
        for (Unused other : others) {
            if (kept < recent && bytes + other.bytes <= maxBytes) {
                kept++;
                bytes += other.bytes;
            } else {
                drop(other.entry);
            }
        }
    }

    private record Unused(Entry entry, long used, long bytes) {}

    /** Lets go of every sound (a resource reload, or leaving a world). */
    public void clear() {
        for (Entry entry : List.copyOf(entries.values())) drop(entry);
    }

    /** Every sound kept, loading, loaded or missing. */
    public Collection<Entry> entries() {
        return entries.values();
    }

    private void drop(Entry entry) {
        if (!entries.remove(entry.id, entry)) return;
        PitchedSound sound;
        synchronized (entry) {
            entry.dropped = true;
            sound = entry.sound;
        }
        if (sound != null) sound.samples().forEach(retire);
    }
}
