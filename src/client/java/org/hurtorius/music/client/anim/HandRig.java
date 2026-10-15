package org.hurtorius.music.client.anim;

import org.hurtorius.music.client.state.PianoRuntime;
import org.hurtorius.music.piano.PianoLayout;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.util.BitSet;
import java.util.List;

/**
 * Where one performer's two hands are over the keyboard, in the piano's model space.
 *
 * <p>Each frame the rig takes the keys the performer holds plus the ones pressed in the last moment,
 * lets {@link HandAssigner} share them between the hands, and moves each hand toward its target with
 * critically damped springs, so hands glide and never snap. A hand dips briefly on each new press it
 * plays, lifts a little while travelling far, waits where it last played for a moment when it has
 * nothing to do, then drifts slowly back to its resting place (left near C3, right near G4). The two
 * hands keep at least an arm's width apart and go out as far as the middle of the outermost keys;
 * each drawn hand then comes in as far as keeps it on the keyboard as the camera sees it (see
 * {@link SeatedArms}). While a hand holds keys down its fingers go down with them ({@link #leftPress}):
 * the hands drawn with elbows lie on the keys as they are drawn, pressed.</p>
 *
 * <p>The hands drawn with elbows (in first person, and under the keys and top cameras) go wherever
 * {@link #left} and {@link #right} put them, both ends of the keyboard at once included. The player
 * model's own arms are rigid and short: {@link #armTargets} keeps their hands clear of the key cheeks
 * and no further apart than those arms span ({@link #MAX_SPAN}, a little over five octaves on a
 * grand this large), so a performer seen from outside reaches either end of the keyboard, sliding
 * along the bench, but stops short of both ends at once.</p>
 *
 * <p>All times are {@link System#nanoTime()}; client thread only.</p>
 */
public final class HandRig {
    /** Presses this recent still count as "being played" after the key is let go. */
    public static final long RECENT_NANOS = 300_000_000L;
    /** A hand with nothing to play waits this long before it drifts home. */
    public static final long HOLD_NANOS = 450_000_000L;
    /** How long a hand stays dipped after a press (the spring rounds it off). */
    public static final long DIP_NANOS = 70_000_000L;
    public static final float TRAVEL_OMEGA = 34f;
    public static final float VERTICAL_OMEGA = 60f;
    public static final float IDLE_OMEGA = 6f;
    // Heights and widths below belong to the player's hand, not to the piano: written in pixels.
    /** Height of the hand's end above the key tops: half the player model arm's thickness and a little air. */
    public static final float LIFT = 2.0f * PianoLayout.PIXEL;
    /** Extra height while a hand plays nothing it holds down, waits or rests. */
    public static final float HOVER = 0.7f * PianoLayout.PIXEL;
    public static final float REST_HOVER = 1.4f * PianoLayout.PIXEL;
    public static final float DIP_MIN = 0.25f * PianoLayout.PIXEL;
    public static final float DIP_VELOCITY = 0.45f * PianoLayout.PIXEL;
    /** Lift per model unit still to travel, and its limit. */
    public static final float TRAVEL_LIFT = 0.35f;
    public static final float TRAVEL_LIFT_MAX = 2.5f * PianoLayout.PIXEL;
    /** Closest the two hand centres may come (a wide arm is 3.75 pixels across). */
    public static final float SEPARATION = 4.0f * PianoLayout.PIXEL;
    /**
     * Farthest apart the player model's hands may be (see {@link #armTargets}): what a seated
     * player's rigid arms span over the keys, leaning forward as far as the body does (see
     * {@link ArmSolver}).
     */
    public static final float MAX_SPAN = 24.5f * PianoLayout.PIXEL;
    /** Half the player model's arm width: the middles of its hands stay this far inside the cheeks. */
    public static final float HALF_WIDTH = 1.9f * PianoLayout.PIXEL;
    /** Seconds over which the pose blends in after sitting down. */
    public static final float ENGAGE_SECONDS = 0.35f;
    public static final int LEFT_REST = 27;
    public static final int RIGHT_REST = 46;

    private final KeyboardSpace keys;
    private final Hand left;
    private final Hand right;
    private HandAssigner.Choice choice = HandAssigner.Choice.NONE;
    private long lastUpdate;
    private long newestPress;
    private float engage;

    public HandRig(KeyboardSpace keys, long now) {
        this.keys = keys;
        this.left = new Hand(LEFT_REST);
        this.right = new Hand(RIGHT_REST);
        reset(now);
    }

    /** Both hands at rest, the pose about to blend in. */
    private void reset(long now) {
        left.rest(keys, now);
        right.rest(keys, now);
        choice = HandAssigner.Choice.NONE;
        lastUpdate = now;
        newestPress = now;
        engage = 0f;
    }

    /**
     * Advances to {@code now}.
     *
     * @param held   keys the performer holds now
     * @param recent the performer's presses since at least {@code now - RECENT_NANOS}, oldest first
     */
    public void update(BitSet held, List<PianoRuntime.Press> recent, long now) {
        float seconds = Math.min(1f, Math.max(0f, (now - lastUpdate) / 1e9f));
        lastUpdate = Math.max(lastUpdate, now);

        BitSet active = (BitSet) held.clone();
        for (PianoRuntime.Press p : recent) if (p.at() >= now - RECENT_NANOS && p.at() <= now) active.set(p.key());
        int[] sorted = active.stream().toArray();
        HandAssigner.Assignment assignment = HandAssigner.assign(sorted, keys.keyAt(left.x.value()), keys.keyAt(right.x.value()), choice);
        choice = assignment.choice();

        left.aim(keys, assignment.left(), active, held, now);
        right.aim(keys, assignment.right(), active, held, now);
        separateTargets();

        for (PianoRuntime.Press p : recent) {
            if (p.at() <= newestPress || p.at() > now) continue;
            newestPress = p.at();
            Hand hand = assignment.left() != null && assignment.left().contains(p.key()) ? left
                : assignment.right() != null && assignment.right().contains(p.key()) ? right : null;
            if (hand != null) hand.dip(now, DIP_MIN + DIP_VELOCITY * Math.max(0, Math.min(127, p.velocity())) / 127f);
        }

        left.step(seconds, now);
        right.step(seconds, now);
        separatePositions();
        engage = Math.min(1f, engage + seconds / ENGAGE_SECONDS);
    }

    /**
     * How far the left hand's fingers are down with the keys it holds, in model units at the touch
     * point (see {@link KeyboardSpace#pressDrop}): the least that any key it holds goes down there,
     * so it never presses into one, eased like the hand's height; 0 while it holds none.
     */
    public float leftPress() {
        return Math.max(0f, left.press.value());
    }

    public float rightPress() {
        return Math.max(0f, right.press.value());
    }

    /** Current position of a hand's end (the middle of the hand), model units. */
    public Vector3f left(Vector3f out) {
        return out.set(left.x.value(), left.y.value(), left.z.value());
    }

    public Vector3f right(Vector3f out) {
        return out.set(right.x.value(), right.y.value(), right.z.value());
    }

    /** 0 just after sitting down .. 1 fully posed, eased. */
    public float engage() {
        return engage * engage * (3f - 2f * engage);
    }

    /**
     * Where the player model's rigid arms reach for the hands this frame, in model units, written to
     * {@code left} and {@code right}: each hand's middle {@link #HALF_WIDTH} or more inside the
     * cheeks, so the model's hand stays on the keys, and the two no further apart than
     * {@link #MAX_SPAN}. A hand with nothing to play gives way to one that has; otherwise both do.
     */
    public void armTargets(Vector3f left, Vector3f right) {
        left(left);
        right(right);
        left.x = Math.min(left.x, keys.bassEdge() - HALF_WIDTH);
        right.x = Math.max(right.x, keys.trebleEdge() + HALF_WIDTH);
        float spread = left.x - right.x - MAX_SPAN;
        if (spread > 0f) {
            float share = this.left.active == this.right.active ? 0.5f : this.left.active ? 0f : 1f;
            left.x -= spread * share;
            right.x += spread * (1f - share);
        }
    }

    /** Model x limits for a hand centre: over the outermost key on the bass side (larger x), and on the treble side. */
    private float bassLimit() {
        return keys.x(0);
    }

    private float trebleLimit() {
        return keys.x(KeyboardSpace.KEYS - 1);
    }

    // The left hand plays the bass side, so its x must stay at least SEPARATION above the right's.
    private void separateTargets() {
        float overlap = SEPARATION - (left.tx - right.tx);
        if (overlap <= 0f) return;
        if (left.active == right.active) {
            left.tx += overlap * 0.5f;
            right.tx -= overlap * 0.5f;
        } else if (left.active) {
            right.tx -= overlap;
            right.omega = TRAVEL_OMEGA;
        } else {
            left.tx += overlap;
            left.omega = TRAVEL_OMEGA;
        }
        float bass = bassLimit(), treble = trebleLimit();
        if (left.tx > bass) {
            right.tx -= left.tx - bass;
            left.tx = bass;
        }
        if (right.tx < treble) {
            left.tx += treble - right.tx;
            right.tx = treble;
        }
        left.tx = Math.min(left.tx, bass);
        right.tx = Math.max(right.tx, treble);
    }

    // Springs can carry a hand a little past a target that moved; positions get the same limits.
    private void separatePositions() {
        float bass = bassLimit(), treble = trebleLimit();
        if (left.x.value() > bass) left.x.reset(bass);
        if (right.x.value() < treble) right.x.reset(treble);
        // A hand with nothing to play gives way to one that has; otherwise both do.
        float share = left.active == right.active ? 0.5f : left.active ? 0f : 1f;
        float overlap = SEPARATION - (left.x.value() - right.x.value());
        if (overlap <= 0f) return;
        float l = Math.min(bass, left.x.value() + overlap * share);
        right.x.reset(Math.max(treble, l - SEPARATION));
        left.x.reset(right.x.value() + SEPARATION);
    }

    /** One hand: springs for its position, its target, and its waiting and dipping state. */
    private final class Hand {
        final int restKey;
        final CriticalSpring x = new CriticalSpring(0f), y = new CriticalSpring(0f), z = new CriticalSpring(0f);
        /** How far its fingers are down with the keys it holds, and where that is going. */
        final CriticalSpring press = new CriticalSpring(0f);
        float tx, ty, tz, pressTarget;
        float heldX, heldY, heldZ;
        long activeAt;
        long dipAt;
        float dipDepth;
        boolean active;
        float omega = IDLE_OMEGA;

        Hand(int restKey) {
            this.restKey = restKey;
        }

        void rest(KeyboardSpace keys, long now) {
            restTarget(keys);
            x.reset(tx);
            y.reset(ty);
            z.reset(tz);
            press.reset(0f);
            pressTarget = 0f;
            active = false;
            activeAt = now - HOLD_NANOS;
            dipAt = now - DIP_NANOS;
        }

        private void restTarget(KeyboardSpace keys) {
            tx = keys.x(restKey);
            ty = keys.top(restKey) + LIFT + REST_HOVER;
            tz = keys.touchZ(restKey);
        }

        void aim(KeyboardSpace keys, HandAssigner.@Nullable Cluster cluster, BitSet playing, BitSet held, long now) {
            if (cluster != null) {
                // Height of the highest key played, depth averaged over the keys (black keys sit further in);
                // the fingers go down as far as the least any held key goes down.
                float top = Float.NEGATIVE_INFINITY, depth = 0f, drop = Float.POSITIVE_INFINITY;
                int count = 0;
                boolean holding = false;
                for (int k = playing.nextSetBit(cluster.lo()); k >= 0 && k <= cluster.hi(); k = playing.nextSetBit(k + 1)) {
                    top = Math.max(top, keys.top(k));
                    depth += keys.touchZ(k);
                    count++;
                    if (held.get(k)) {
                        holding = true;
                        drop = Math.min(drop, keys.pressDrop(k));
                    }
                }
                pressTarget = holding ? drop : 0f;
                // Middle of the cluster across the keyboard, no further out than the outermost keys.
                tx = Math.max(trebleLimit(), Math.min(bassLimit(), (keys.x(cluster.lo()) + keys.x(cluster.hi())) * 0.5f));
                tz = depth / count;
                heldX = tx;
                heldY = top + LIFT;
                heldZ = tz;
                ty = heldY + (holding ? 0f : HOVER);
                active = true;
                activeAt = now;
                omega = TRAVEL_OMEGA;
            } else if (now - activeAt < HOLD_NANOS) {
                tx = heldX;
                ty = heldY + HOVER;
                tz = heldZ;
                pressTarget = 0f;
                active = false;
                omega = TRAVEL_OMEGA;
            } else {
                restTarget(keys);
                pressTarget = 0f;
                active = false;
                omega = IDLE_OMEGA;
            }
        }

        void dip(long at, float depth) {
            dipAt = at;
            dipDepth = depth;
        }

        void step(float seconds, long now) {
            float travel = Math.abs(tx - x.value());
            float lift = Math.min(TRAVEL_LIFT_MAX, travel * TRAVEL_LIFT);
            float dip = now - dipAt >= 0 && now - dipAt < DIP_NANOS ? dipDepth : 0f;
            x.update(tx, omega, seconds);
            z.update(tz, omega, seconds);
            y.update(ty + lift - dip, Math.max(omega, VERTICAL_OMEGA), seconds);
            press.update(pressTarget, VERTICAL_OMEGA, seconds);
        }
    }
}
