package org.hurtorius.music.client.state;

import net.minecraft.core.BlockPos;
import org.hurtorius.music.client.audio.PedalBoard;
import org.hurtorius.music.piano.Pedal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * What one piano looks like right now on this client: which keys are down and how far, where its
 * dampers and pedals are, the lid, and which keys each player is holding (for their hands).
 *
 * <p>Writers (live play, remote players, self-play) call {@link #press}, {@link #release} and
 * {@link #pedal} with a {@link System#nanoTime()} timestamp; future timestamps are queued and applied
 * when {@link #update(long)} reaches them, so visuals line up with scheduled sound. Readers (the
 * renderer, the arm animation, the screen) call the depth getters. Everything runs on the client
 * thread.</p>
 *
 * <p>Every note shows: a key let go before it reached the keybed finishes its stroke first, and a key
 * struck again before it came back up first rises half way, so repeated notes and short ones still
 * go visibly down and up. Neither holds a key back more than {@link #MAX_LAG_NANOS} behind its note.</p>
 *
 * <p>The dampers show what the sound does to the strings. The sound keeps the keys and pedals of each
 * source (each player, and the piano playing by itself) on a {@link PedalBoard} of its own, so one
 * player's pedal never holds another's notes; this keeps the same boards. A damper is up while its
 * key is down or a source's board still lets its string ring (that source's sustain pedal held it, or
 * its sostenuto pedal caught it). Pressing the sustain pedal lifts every damper, as on a real grand;
 * a string the sound damps while it is down (another source let go of the key, without a pedal of
 * its own) gets its damper back, and they all fall once no source holds the sustain pedal.</p>
 */
public final class PianoRuntime {
    /** Source id for the piano playing by itself. Players use their entity id. */
    public static final int SELF_PLAY = -1;
    /** A whole key stroke: quick down to the keybed, softer back up. */
    public static final long PRESS_NANOS = 40_000_000L, RELEASE_NANOS = 110_000_000L;
    /** A damper lifts off its strings with the key, and drops back onto them. */
    public static final long DAMPER_LIFT_NANOS = 50_000_000L, DAMPER_FALL_NANOS = 80_000_000L;
    /** A pedal goes down under the foot, and its spring brings it back up. */
    public static final long PEDAL_DOWN_NANOS = 80_000_000L, PEDAL_UP_NANOS = 120_000_000L;
    /** How far a key struck again must first come back up before it goes down again. */
    public static final float RESTRIKE_DEPTH = 0.5f;
    /** The longest a key is held back behind its note, to finish a stroke or rise before a restrike. */
    public static final long MAX_LAG_NANOS = 40_000_000L;
    public static final long LID_NANOS = 1_000_000_000L;
    private static final int RECENT_LIMIT = 64;

    public final BlockPos pos;
    private final int[] holders = new int[88];
    private final int[] velocity = new int[88];
    private final Travel keys = new Travel(88, PRESS_NANOS, RELEASE_NANOS, false);
    private final Travel dampers = new Travel(88, DAMPER_LIFT_NANOS, DAMPER_FALL_NANOS, true);
    private final Travel pedals = new Travel(3, PEDAL_DOWN_NANOS, PEDAL_UP_NANOS, false);
    /** Per source, its keys and pedals as the sound keeps them: which strings it lets ring. */
    private final Map<Integer, PedalBoard> boards = new HashMap<>();
    /** Dampers a sustain pedal lifted that no damping has brought down since. */
    private final boolean[] sustained = new boolean[88];
    /** Per source, how many times each key is currently held (a source may strike a key again before letting go). */
    private final Map<Integer, int[]> held = new HashMap<>();
    private final Map<Integer, ArrayDeque<Press>> recent = new HashMap<>();
    /** Which pedals show down: those any source holds, counted in {@link #pedalHolders}. */
    private final boolean[] pedalDown = new boolean[3];
    private final int[] pedalHolders = new int[3];
    private final PriorityQueue<Scheduled> queue = new PriorityQueue<>();
    private boolean lidOpen = true, deskUp = true;
    /** Whether the lid and desk have been set yet: the first setting is how the piano already is, shown at rest. */
    private boolean lidKnown, deskKnown;
    private long lidChangedAt, deskChangedAt;
    private long seq;
    private long lastTouched = System.nanoTime();

    /** A key press remembered for hand animation. */
    public record Press(int key, int velocity, long at) {}

    private record Scheduled(long at, long order, int source, Runnable action) implements Comparable<Scheduled> {
        @Override
        public int compareTo(Scheduled o) {
            return at != o.at ? Long.compare(at, o.at) : Long.compare(order, o.order);
        }
    }

    public PianoRuntime(BlockPos pos) {
        this.pos = pos.immutable();
        lidChangedAt = deskChangedAt = System.nanoTime() - LID_NANOS;
    }

    // ------------------------------------------------------------------ writers

    public void press(int key, int vel, int source, long at) {
        if (key < 0 || key > 87) return;
        schedule(at, source, () -> {
            boards.computeIfAbsent(source, s -> new PedalBoard()).press(key);
            if (holders[key]++ == 0) strike(key, at);
            velocity[key] = vel;
            held.computeIfAbsent(source, s -> new int[88])[key]++;
            ArrayDeque<Press> list = recent.computeIfAbsent(source, s -> new ArrayDeque<>());
            list.addLast(new Press(key, vel, at));
            while (list.size() > RECENT_LIMIT) list.removeFirst();
        });
    }

    public void release(int key, int source, long at) {
        if (key < 0 || key > 87) return;
        schedule(at, source, () -> {
            int[] counts = held.get(source);
            if (counts == null || counts[key] == 0) return;
            counts[key]--;
            holders[key] = Math.max(0, holders[key] - 1);
            PedalBoard board = boards.get(source);
            if (board != null) {
                if (board.release(key)) damped(key);
                if (board.idle()) boards.remove(source);
            }
            if (holders[key] == 0) letGo(key, at);
        });
    }

    public void pedal(Pedal pedal, boolean down, int source, long at) {
        schedule(at, source, () -> {
            PedalBoard board = boards.computeIfAbsent(source, s -> new PedalBoard());
            if (board.isDown(pedal) == down) {
                if (board.idle()) boards.remove(source);
                return;
            }
            BitSet falls = board.pedal(pedal, down);
            if (board.idle()) boards.remove(source);
            for (int k = falls.nextSetBit(0); k >= 0; k = falls.nextSetBit(k + 1)) damped(k);
            pedalHeld(pedal, down, at);
            settleDampers(at);
        });
    }

    /** Lets go of every key and pedal a source holds, e.g. when a player stands up or playback stops. */
    public void releaseAll(int source, long at) {
        cancelScheduled(source);
        PedalBoard board = boards.remove(source);
        if (board != null) {
            for (Pedal pedal : Pedal.values()) if (board.isDown(pedal)) pedalHeld(pedal, false, at);
            BitSet falls = board.reset();
            for (int k = falls.nextSetBit(0); k >= 0; k = falls.nextSetBit(k + 1)) damped(k);
        }
        int[] counts = held.get(source);
        if (counts != null) {
            for (int k = 0; k < 88; k++) {
                if (counts[k] == 0) continue;
                holders[k] = Math.max(0, holders[k] - counts[k]);
                counts[k] = 0;
                if (holders[k] == 0) letGo(k, at);
            }
        }
        settleDampers(at);
    }

    /** A key goes down: at once, or once it has come far enough back up from its last note. */
    private void strike(int key, long at) {
        long begin = at;
        if (keys.depth(key, at) > RESTRIKE_DEPTH) begin = Math.min(Math.max(at, keys.passes(key, RESTRIKE_DEPTH)), at + MAX_LAG_NANOS);
        keys.send(key, true, begin);
        dampers.send(key, true, begin);
    }

    /** A key comes up once it has finished its stroke; its damper falls with it unless a pedal holds it. */
    private void letGo(int key, long at) {
        long begin = Math.min(Math.max(at, keys.arrives(key)), at + MAX_LAG_NANOS);
        keys.send(key, false, begin);
        if (!damperHeld(key)) dampers.send(key, false, begin);
    }

    /**
     * A source holds or lets go of a pedal: it shows down while any source holds it, and a sustain
     * pedal going down lifts every damper until none is held.
     */
    private void pedalHeld(Pedal pedal, boolean down, long at) {
        int i = pedal.ordinal();
        pedalHolders[i] = Math.max(0, pedalHolders[i] + (down ? 1 : -1));
        if (pedal == Pedal.SUSTAIN) {
            if (down) Arrays.fill(sustained, true);
            else if (pedalHolders[i] == 0) Arrays.fill(sustained, false);
        }
        boolean shown = pedalHolders[i] > 0;
        if (pedalDown[i] == shown) return;
        pedalDown[i] = shown;
        pedals.send(i, shown, at);
    }

    /** The sound damped the string of {@code key} for a source: its damper comes down, unless another source still lets it ring. */
    private void damped(int key) {
        for (PedalBoard board : boards.values()) if (board.ringing(key)) return;
        sustained[key] = false;
    }

    /**
     * After a pedal moves: every damper goes where the keys and boards say; one whose key is still
     * finishing its stroke falls once the key starts back up.
     */
    private void settleDampers(long at) {
        for (int k = 0; k < 88; k++) {
            if (damperHeld(k)) dampers.send(k, true, at);
            else dampers.send(k, false, Math.max(at, keys.since(k)));
        }
    }

    /** True when the key's damper stays off the strings: its key is down, a board lets its string ring, or a sustain pedal lifted it. */
    private boolean damperHeld(int key) {
        if (holders[key] > 0 || sustained[key]) return true;
        for (PedalBoard board : boards.values()) if (board.ringing(key)) return true;
        return false;
    }

    /** Drops queued (future) events of one source, used when self-play seeks or pauses. */
    public void cancelScheduled(int source) {
        queue.removeIf(s -> s.source == source);
    }

    /** The lid opens or closes over {@link #LID_NANOS}; the first call places it without moving. */
    public void setLid(boolean open, long now) {
        if (!lidKnown) {
            lidKnown = true;
            lidOpen = open;
            lidChangedAt = now - LID_NANOS;
            return;
        }
        if (open == lidOpen) return;
        long progress = Math.min(LID_NANOS, now - lidChangedAt);
        lidOpen = open;
        lidChangedAt = now - (LID_NANOS - progress);
    }

    /** The desk folds or rises over {@link #LID_NANOS}; the first call places it without moving. */
    public void setDesk(boolean up, long now) {
        if (!deskKnown) {
            deskKnown = true;
            deskUp = up;
            deskChangedAt = now - LID_NANOS;
            return;
        }
        if (up == deskUp) return;
        long progress = Math.min(LID_NANOS, now - deskChangedAt);
        deskUp = up;
        deskChangedAt = now - (LID_NANOS - progress);
    }

    /**
     * A piano was placed, or loaded again, where this one was: its lid and desk next show as they
     * are, without moving, as if this were the first time. A piano placed under a ceiling within
     * moments of breaking one with its lid open must not swing that lid shut through the ceiling.
     */
    public void placedAgain() {
        lidKnown = false;
        deskKnown = false;
    }

    /** Applies queued events whose time has come. Call once per frame/tick with System.nanoTime(). */
    public void update(long now) {
        while (!queue.isEmpty() && queue.peek().at <= now) queue.poll().action.run();
    }

    private void schedule(long at, int source, Runnable action) {
        touch();
        if (at <= System.nanoTime() && queue.isEmpty()) action.run();
        else queue.add(new Scheduled(at, seq++, source, action));
    }

    // ------------------------------------------------------------------ readers

    /** 0 (up) .. 1 (down on the keybed): quickly down over {@link #PRESS_NANOS}, softer back up over {@link #RELEASE_NANOS}. */
    public float keyDepth(int key, long now) {
        return keys.depth(key, now);
    }

    /** 0 (resting on its strings) .. 1 (lifted clear) for the damper of key 0..87. */
    public float damperLift(int key, long now) {
        return dampers.depth(key, now);
    }

    public boolean isDown(int key) {
        return holders[key] > 0;
    }

    public int velocity(int key) {
        return velocity[key];
    }

    /** 0 (up) .. 1 (down): eased down over {@link #PEDAL_DOWN_NANOS}, back up over {@link #PEDAL_UP_NANOS}. */
    public float pedalDepth(Pedal pedal, long now) {
        return pedals.depth(pedal.ordinal(), now);
    }

    public boolean isPedalDown(Pedal pedal) {
        return pedalDown[pedal.ordinal()];
    }

    /** 0 open .. 1 closed, following the lid_close animation timing. */
    public float lidClosed(long now) {
        float t = Math.min(1f, Math.max(0f, (now - lidChangedAt) / (float) LID_NANOS));
        return lidOpen ? 1 - t : t;
    }

    /** 0 up .. 1 folded. */
    public float deskFolded(long now) {
        float t = Math.min(1f, Math.max(0f, (now - deskChangedAt) / (float) LID_NANOS));
        return deskUp ? 1 - t : t;
    }

    /** Keys a source holds right now (a copy). */
    public BitSet heldBy(int source) {
        BitSet bits = new BitSet(88);
        int[] counts = held.get(source);
        if (counts != null) for (int k = 0; k < 88; k++) if (counts[k] > 0) bits.set(k);
        return bits;
    }

    /** The source's presses at or after {@code since}, oldest first. */
    public List<Press> recentPresses(int source, long since) {
        ArrayDeque<Press> list = recent.get(source);
        List<Press> out = new ArrayList<>();
        if (list == null) return out;
        for (Iterator<Press> it = list.iterator(); it.hasNext(); ) {
            Press p = it.next();
            if (p.at >= since) out.add(p);
        }
        return out;
    }

    public boolean anyKeyDown() {
        for (int h : holders) if (h > 0) return true;
        return false;
    }

    public int pendingEvents() {
        return queue.size();
    }

    /** System.nanoTime() of the last write or lookup; used to forget pianos that are gone. */
    public long lastTouched() {
        return lastTouched;
    }

    public void touch() {
        lastTouched = System.nanoTime();
    }
}
