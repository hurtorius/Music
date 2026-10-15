package org.hurtorius.music.net;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.hurtorius.music.song.NoteEvent;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * The keys and pedals of one performer that the server told listeners are down: their press was
 * relayed and their release not yet. Releases of these are relayed even when the performer is over
 * the event budget, may no longer play or has moved off, and when the performer stops altogether
 * the server lets go of all of them for the listeners at once, so no listener keeps a key down that
 * its player let go of. Only what was relayed can be released this way, so it can never carry more
 * than the presses that were allowed.
 */
final class HeldNotes {
    final ResourceKey<Level> dimension;
    final BlockPos piano;
    final int playerId;
    final int session;
    private final BitSet keys = new BitSet(88);
    private final boolean[] pedals = new boolean[3];
    private int lastTimeMs;

    HeldNotes(ResourceKey<Level> dimension, BlockPos piano, int playerId, int session) {
        this.dimension = dimension;
        this.piano = piano.immutable();
        this.playerId = playerId;
        this.session = session;
    }

    boolean isFor(ResourceKey<Level> dimension, BlockPos piano, int session) {
        return this.dimension.equals(dimension) && this.piano.equals(piano) && this.session == session;
    }

    boolean isEmpty() {
        return keys.isEmpty() && !pedals[0] && !pedals[1] && !pedals[2];
    }

    /**
     * Notes events that were relayed to the listeners in reach. Returns those of them that let go of
     * something held, which listeners out of reach need too: they may have seen the press.
     */
    List<NoteEvent> relayed(List<NoteEvent> events) {
        List<NoteEvent> releases = new ArrayList<>();
        for (NoteEvent e : events) {
            lastTimeMs = Math.max(lastTimeMs, e.timeMs());
            if (letGo(e)) {
                releases.add(e);
            } else if (e.kind() == NoteEvent.NOTE_ON) {
                keys.set(e.data1());
            } else if (e.kind() == NoteEvent.PEDAL && e.data2() >= 64) {
                pedals[e.data1()] = true;
            }
        }
        return releases;
    }

    /** Of events that were refused, the releases that let go of something held; those still go out. */
    List<NoteEvent> releasesOf(List<NoteEvent> refused) {
        List<NoteEvent> releases = new ArrayList<>();
        for (NoteEvent e : refused) {
            if (!letGo(e)) continue;
            lastTimeMs = Math.max(lastTimeMs, e.timeMs());
            releases.add(e);
        }
        return releases;
    }

    /** Releases for everything still held, timed after the last event relayed; nothing is held afterwards. */
    List<NoteEvent> releaseAll() {
        List<NoteEvent> releases = new ArrayList<>();
        for (int k = keys.nextSetBit(0); k >= 0; k = keys.nextSetBit(k + 1)) releases.add(NoteEvent.off(lastTimeMs, k));
        for (int type = 0; type < 3; type++) {
            if (pedals[type]) releases.add(NoteEvent.pedal(lastTimeMs, type, 0));
        }
        keys.clear();
        pedals[0] = pedals[1] = pedals[2] = false;
        return releases;
    }

    /** True when {@code e} releases a key or pedal that is held; it no longer is. */
    private boolean letGo(NoteEvent e) {
        if (e.kind() == NoteEvent.NOTE_OFF && keys.get(e.data1())) {
            keys.clear(e.data1());
            return true;
        }
        if (e.kind() == NoteEvent.PEDAL && e.data2() < 64 && pedals[e.data1()]) {
            pedals[e.data1()] = false;
            return true;
        }
        return false;
    }
}
