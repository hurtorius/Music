package org.hurtorius.music.client.anim;

import net.minecraft.client.model.geom.PartPose;
import net.minecraft.core.Direction;
import org.hurtorius.music.piano.PianoLayout;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * The arms a seated performer sees of themselves, in the piano's model space (Y up, the performer
 * looking toward +Z, their left toward +X). Sizes of the arm itself are the player's, in pixels.
 *
 * <p>A Minecraft arm is one rigid box from the shoulder, which puts a seated player's arms high in
 * front of their eyes, right across the keys. Here each arm bends twice: the upper arm hangs from the
 * shoulder to an elbow beside the torso, a little below the key tops; the forearm rises gently from
 * there to a wrist at the front of the keys; and the hand bends at the wrist to lie flat on the keys
 * the {@link HandRig} gives it, its fingertips where the fingers play: on the keys' heads just behind
 * their front edge, so the rest of a key it holds down is seen going down. Holding keys down, the
 * hand goes down with them as far as the least of them goes down under the fingertips (the rig's
 * press), its palm just over each, its back still above the keys around them. Seen from above,
 * elbow, wrist and fingertips are in one line, which turns at most {@link #MAX_SPLAY} sideways; for
 * keys further out the elbow swings out with the hand. However the arm turns, the fingertips line up
 * along the front of the keys, as a pianist's curved fingers do: the hand's end is square to the
 * keys, not to the arm, so its outer corner never reaches further onto the keys than the fingertips'
 * middle (see {@link Segment#tipSlant}). The hand clears the keys under it, and the forearm the
 * front edges of the keybed and keys it crosses. Near either end of the keyboard the hand stays on it
 * as the camera sees it: its top lies higher than the keys, so a camera looking down from the middle
 * of the keyboard sees it further out than it is, and the hand comes in until no part of it is seen
 * past the middle of the outermost key, as beside a fifth finger playing it, so the outer half of
 * that key stays in sight going down. The arms are a little narrower than the player model's
 * ({@link #NARROW}) and flatter, and the hands keep only part of the rig's hovering, so the keys
 * around and beyond the hands stay in sight. Slim skins keep their narrower arms.</p>
 */
public final class SeatedArms {
    /** Elbow to wrist: 6 pixels. */
    public static final float FOREARM = 6f * PianoLayout.PIXEL;
    /** Wrist to fingertips: 3 pixels, the last three of the player model arm's twelve. */
    public static final float HAND = 3f * PianoLayout.PIXEL;
    /** How much narrower than the player model's these arms are drawn: a hand covers about five white keys. */
    public static final float NARROW = 0.85f;
    /** Model units per skin pixel across an arm: the player model's own 0.9375 pixels, narrowed. */
    public static final float ACROSS = 0.9375f * NARROW * PianoLayout.PIXEL;
    /** Front to back of the forearm and the upper arm: 1.5 pixels, so the arm lies flat. */
    public static final float THICKNESS = 1.5f * PianoLayout.PIXEL;
    /** Back of the hand to the palm: a pixel, flatter than the forearm, as a hand is. */
    public static final float HAND_THICKNESS = 1f * PianoLayout.PIXEL;
    /** Gap between the palm and the tops of the keys under it. */
    public static final float CLEARANCE = 0.15f;
    /** Gap between the palm and a key the hand holds down, at the fingertips, once the key is down: the fingers press it. */
    public static final float HELD_CLEARANCE = 0.03f;
    /**
     * How far a hand may dip below its resting height on the keys for a press of a key it does not
     * hold down: less than {@link #CLEARANCE}, so it never dips into the key. A hand holding keys down
     * goes down with them instead (see {@link HandRig#leftPress}).
     */
    static final float SINK = 0.1f;
    /**
     * Share of the rig's height above the keys (hovering, waiting, travelling, dipping) these hands
     * keep: a hand seen from just above lifts in plain sight, so a little lift goes a long way.
     */
    static final float HOVER_SHARE = 0.4f;
    /** The player model's arm reaches this far above its shoulder pivot (2 of its pixels). */
    static final float SHOULDER_TOP = 2f * 0.9375f * PianoLayout.PIXEL;
    /**
     * Each elbow is drawn toward a point 6 pixels over the seat (a little below the key tops, so the
     * forearms rise gently to the keys), a little outside the shoulder and 2 pixels toward the keys
     * from it: for an upright body 6.5 pixels from the middle, half a pixel outside where the player
     * model's wide arm hangs.
     */
    static final float ELBOW_X = 6.5f * PianoLayout.PIXEL, ELBOW_Y = PianoLayout.SEAT.y() + 6f * PianoLayout.PIXEL,
        ELBOW_Z = PianoLayout.SEAT.z() + 2f * PianoLayout.PIXEL;
    /**
     * How far the elbows' point lies outside and ahead of a posed body's drawn shoulder, whose middle
     * is 6 of the player model's (0.9375-pixel) pixels out when upright.
     */
    static final float ELBOW_OUT = ELBOW_X - 6f * 0.9375f * PianoLayout.PIXEL, ELBOW_FORWARD = ELBOW_Z - PianoLayout.SEAT.z();
    /**
     * Furthest toward the keys the elbows' point comes however far the body leans, 5 pixels short of
     * the keys' front edge: a forearm still rises to the keys from in front of the keyboard rather
     * than standing up steeply over its front.
     */
    static final float ELBOW_Z_LIMIT = PianoLayout.SEAT.z() + (PianoLayout.HIPS_TO_KEYS - 5f) * PianoLayout.PIXEL;
    /** Furthest the arm turns sideways, seen from above, from pointing straight at the keys. */
    static final float MAX_SPLAY = (float) Math.toRadians(30);
    /** Least the arm points into the keys (the cosine of its turn) for its hand's end to be laid along them: an arm turned past that keeps a square end. */
    private static final float MIN_SQUARE = 0.2f;
    /**
     * In first person the upper arms run up from the elbows to points this far from the camera along
     * its own axes (5 pixels out to the side, 2 down and 7.5 behind it), which no view can see: the
     * arms leave the picture at its edges instead of ending in mid-air, wherever the performer looks.
     */
    static final Vector3fc FIRST_PERSON_SHOULDER = new Vector3f(5f * PianoLayout.PIXEL, -2f * PianoLayout.PIXEL, -7.5f * PianoLayout.PIXEL);
    /** As the performer sits down, the hands come up from the lap (6 pixels below) and then forward (from 8 pixels back). */
    static final float SIT_DOWN_DROP = 6f * PianoLayout.PIXEL, SIT_DOWN_BACK = 8f * PianoLayout.PIXEL;
    /**
     * Most a camera's sight line may spread a point of the hand out across the keys: a camera barely
     * above the hand would see it without end, so beyond this the hand is placed as seen from above.
     */
    private static final float MAX_SPREAD = 4f;

    private SeatedArms() {}

    /**
     * One part of an arm as a box from {@code start} to {@code end}. {@code front} is square to it
     * and points where the player model arm's front would (up on a forearm or a hand, the back of the
     * hand; forward on the upper arm); {@code across} points to the performer's left, square to it on
     * a forearm or a hand, and on an upper arm the forearm's own across at the bend (so an upper arm
     * reaching out sideways is a slanted box). The box spans {@code ±width/2} across and
     * {@code ±thickness/2} front to back. Its end face is slanted by {@code tipSlant}: a point of it
     * {@code a} across lies {@code a * tipSlant} further along (a hand's end lies along the keys).
     */
    public record Segment(Vector3fc start, Vector3fc end, Vector3fc front, Vector3fc across, float width, float thickness, float tipSlant) {
        /** A box with its end face square to it. */
        public Segment(Vector3fc start, Vector3fc end, Vector3fc front, Vector3fc across, float width, float thickness) {
            this(start, end, front, across, width, thickness, 0f);
        }

        /** Direction from start to end, unit length. */
        public Vector3f along() {
            return new Vector3f(end).sub(start).normalize();
        }

        public float length() {
            return start.distance(end);
        }

        /** The eight corners: bit 0 across, bit 1 front, bit 2 start (0) or end (1). */
        public Vector3f[] corners() {
            Vector3f[] out = new Vector3f[8];
            Vector3f along = along();
            for (int i = 0; i < 8; i++) {
                boolean atEnd = (i & 4) != 0;
                float a = ((i & 1) == 0 ? -0.5f : 0.5f) * width, f = ((i & 2) == 0 ? -0.5f : 0.5f) * thickness;
                out[i] = new Vector3f(atEnd ? end : start).fma(a, across).fma(f, front).fma(atEnd ? a * tipSlant : 0f, along);
            }
            return out;
        }
    }

    /**
     * An arm from the elbow on: the forearm, which reaches back into the upper arm and ends at the
     * wrist, and the hand, which lies flat on the keys from just behind the wrist to the fingertips.
     */
    public record Arm(Segment forearm, Segment hand) {}

    /** Width of an arm for a wide (4 pixel) or slim (3 pixel) skin. */
    public static float width(boolean slim) {
        return (slim ? 3f : 4f) * ACROSS;
    }

    /**
     * The left arm from the elbow on as the performer sees it in first person, the hand where the rig
     * gives it (the middle of its fingertips, over the keys it plays) and the elbow drawn toward an
     * upright body's (see {@link #ELBOW_X}).
     *
     * @param press  how far the hand's fingers are down with the keys it holds (see {@link HandRig#leftPress})
     * @param eye    the camera, in the piano's model space: near either end the hand stays on the keyboard as it sees it
     * @param engage 0 just after sitting down .. 1 settled (see {@link HandRig#engage()})
     */
    public static Arm leftArm(KeyboardSpace keys, Vector3fc rigHand, float press, Vector3fc eye, boolean slim, float engage) {
        return arm(keys, rigHand, press, new Vector3f(ELBOW_X, ELBOW_Y, ELBOW_Z), eye, slim, engage);
    }

    public static Arm rightArm(KeyboardSpace keys, Vector3fc rigHand, float press, Vector3fc eye, boolean slim, float engage) {
        return arm(keys, rigHand, press, new Vector3f(-ELBOW_X, ELBOW_Y, ELBOW_Z), eye, slim, engage);
    }

    /**
     * The upper arm the performer sees of themselves in first person: from beside and behind the
     * camera (see {@link #FIRST_PERSON_SHOULDER}) down to the forearm's elbow. Everything is in the
     * piano's model space: the camera at {@code camera}, looking along the unit vector
     * {@code forward}, with {@code up} the top of its picture.
     */
    public static Segment firstPersonUpperArm(Vector3fc camera, Vector3fc forward, Vector3fc up, boolean left, Segment forearm) {
        // The performer's left, square to the view: up x forward in the model's right-handed axes.
        Vector3f side = new Vector3f(up).cross(forward).normalize(left ? FIRST_PERSON_SHOULDER.x() : -FIRST_PERSON_SHOULDER.x());
        Vector3f top = new Vector3f(camera).add(side).fma(FIRST_PERSON_SHOULDER.y(), up).fma(FIRST_PERSON_SHOULDER.z(), forward);
        return upperArm(top, forearm);
    }

    /**
     * A posed third-person body, for whole arms bent at the elbow and the wrist: its forearms' elbows
     * follow its shoulders as it leans and slides, and the upper arms hang from those shoulders.
     *
     * @param pose    the performer pose the body is drawn with (see {@link PerformerPoses#POSE})
     * @param x       the player's position relative to the corner of the piano's controller block, in blocks
     * @param y       likewise
     * @param z       likewise
     * @param scale   the player's render scale
     * @param bodyRot the player's body yaw, degrees
     * @param facing  the way the piano's keyboard faces
     * @param slim    slim (3 pixel) arms
     */
    public record Body(ArmPose pose, double x, double y, double z, float scale, float bodyRot, Direction facing, boolean slim) {
        /**
         * This body's left or right arm from the elbow on, to the hand position the rig gives, its
         * elbow drawn toward a point below, outside and ahead of the posed shoulder (see {@link #ELBOW_OUT}).
         *
         * @param press  how far the hand's fingers are down with the keys it holds (see {@link HandRig#leftPress})
         * @param eye    the camera, in the piano's model space
         * @param engage 0 just after sitting down .. 1 settled (see {@link HandRig#engage()})
         */
        public Arm arm(KeyboardSpace keys, boolean left, Vector3fc rigHand, float press, Vector3fc eye, float engage) {
            Matrix4f toPiano = PianoLayout.modelToBlock(facing, new Matrix4f()).invert();
            Vector3f shoulder = shoulder(left, torso(), toPiano);
            Vector3f elbow = new Vector3f(shoulder.x + (left ? ELBOW_OUT : -ELBOW_OUT), ELBOW_Y, Math.min(ELBOW_Z_LIMIT, shoulder.z + ELBOW_FORWARD));
            return SeatedArms.arm(keys, rigHand, press, elbow, eye, slim, engage);
        }

        /** The upper arm from this body's shoulder down to {@code forearm}'s elbow, in the piano's model space. */
        public Segment upperArm(boolean left, Segment forearm) {
            Matrix4f toPiano = PianoLayout.modelToBlock(facing, new Matrix4f()).invert();
            return SeatedArms.upperArm(shoulder(left, torso(), toPiano), forearm);
        }

        /** The torso's rotation as posed, blended in as the pose engages, in player model space. */
        private Quaternionf torso() {
            float w = Math.max(0f, Math.min(1f, pose.weight()));
            return new Quaternionf().rotationZYX(w * pose.body().zRot(), w * pose.body().yRot(), w * pose.body().xRot());
        }

        /**
         * The shoulder in the piano's model space: the player model's shoulder pivot as the pose has
         * moved it, moved so the drawn arm's inner side still meets the torso.
         */
        private Vector3f shoulder(boolean left, Quaternionf torso, Matrix4f toPiano) {
            float w = Math.max(0f, Math.min(1f, pose.weight()));
            float side = left ? 1f : -1f;
            PartPose arm = left ? pose.leftArm() : pose.rightArm();
            // The player model's own shoulder pivot is where the pose blends in from.
            Vector3f pivot = new Vector3f(5f * side, 2f, 0f).lerp(new Vector3f(arm.x(), arm.y(), arm.z()), w);
            pivot.add(torso.transform(new Vector3f(side * shoulderInset(slim), 0f, 0f)));
            return toPiano.transformPosition(ArmSolver.toWorld(pivot, scale, bodyRot).add((float) x, (float) y, (float) z));
        }

    }

    /**
     * The upper arm hanging from {@code shoulder} (the player model's shoulder pivot, already moved in
     * to sit against the torso; see {@link #shoulderInset}) to the forearm's elbow, reaching above the
     * shoulder pivot as the player model's arm does. It ends at the middle of the bend, where the
     * forearm turns, with the forearm's own cross-section: the forearm's across, and its depth square
     * to that and to the upper arm, facing forward. That end then lies wholly inside the forearm,
     * which reaches half the arm's width back past the bend, so the elbow closes on both sides
     * however far the arm reaches out.
     */
    public static Segment upperArm(Vector3fc shoulder, Segment forearm) {
        Vector3f elbow = elbow(forearm);
        Vector3f down = new Vector3f(elbow).sub(shoulder);
        if (down.lengthSquared() < 1e-6f) down.set(0f, -1f, 0f);
        down.normalize();
        Vector3f start = new Vector3f(shoulder).fma(-SHOULDER_TOP, down);
        Vector3f across = new Vector3f(forearm.across());
        Vector3f front = new Vector3f(down).cross(across);
        if (front.lengthSquared() < 1e-6f) {
            // An upper arm running along the forearm's across: square the box to it instead.
            front = square(new Vector3f(0f, 0f, 1f), down, new Vector3f(0f, 1f, 0f));
            across = new Vector3f(front).cross(down).normalize();
        } else {
            front.normalize();
        }
        return new Segment(start, elbow, front, across, forearm.width(), forearm.thickness());
    }

    /** Where a forearm turns from its upper arm: {@link #FOREARM} back from the wrist. */
    public static Vector3f elbow(Segment forearm) {
        return new Vector3f(forearm.end()).fma(-FOREARM, forearm.along());
    }

    /**
     * How far, in player model pixels toward the body's outside, the drawn arm's middle sits from
     * the player model's shoulder pivot, so its inner side still meets the torso (4 pixels out).
     */
    public static float shoulderInset(boolean slim) {
        return width(slim) / PianoLayout.PIXEL / 0.9375f * 0.5f - 1f;
    }

    /** An arm to the rig's hand whose elbow is drawn toward {@code elbowPoint}, seen from {@code eye}. */
    private static Arm arm(KeyboardSpace keys, Vector3fc rigHand, float press, Vector3fc elbowPoint, Vector3fc eye, boolean slim, float engage) {
        float width = width(slim);
        Vector3f at = new Vector3f(rigHand);
        Arm arm = resting(keys, at, rigHand.y(), press, elbowPoint, width, engage);
        // Near either end the hand comes in until the camera sees none of it past the middle of the outermost key.
        for (int i = 0; i < 6; i++) {
            float over = beyondEnds(keys, arm.hand(), eye);
            if (Math.abs(over) <= 1e-4f) break;
            at.x -= over;
            arm = resting(keys, at, rigHand.y(), press, elbowPoint, width, engage);
        }
        return arm;
    }

    /**
     * How far the hand must move across, toward the middle, for {@code eye} to see none of it past the
     * middle of the outermost key on either side (the outer half of A0 or C8 stays in sight beside it,
     * as beside a fifth finger playing it): positive toward the bass (+X), negative toward the treble,
     * 0 when it is seen within those lines. A point of the hand is seen where the line from the eye
     * through it meets the tops of the keys, further out than it is by as much as it lies above them.
     */
    private static float beyondEnds(KeyboardSpace keys, Segment hand, Vector3fc eye) {
        float plane = Math.max(keys.top(0), keys.top(KeyboardSpace.KEYS - 1));
        float most = Float.NEGATIVE_INFINITY, least = Float.POSITIVE_INFINITY, mostSpread = 1f, leastSpread = 1f;
        for (Vector3f corner : hand.corners()) {
            float spread = spread(corner, eye, plane);
            float seen = eye.x() + (corner.x - eye.x()) * spread;
            if (seen > most) {
                most = seen;
                mostSpread = spread;
            }
            if (seen < least) {
                least = seen;
                leastSpread = spread;
            }
        }
        // Moving the hand across moves where it is seen by its spread.
        float bass = keys.x(0), treble = keys.x(KeyboardSpace.KEYS - 1);
        if (most > bass) return (most - bass) / mostSpread;
        if (least < treble) return (least - treble) / leastSpread;
        return 0f;
    }

    /**
     * How much further from {@code eye}, across, a point is seen where its sight line meets the key
     * tops at height {@code plane} than it is: 1 for a point on them or below, more the higher it is.
     */
    private static float spread(Vector3fc point, Vector3fc eye, float plane) {
        float above = point.y() - plane, height = eye.y() - plane;
        if (above <= 0f) return 1f;
        if (height <= above * MAX_SPREAD / (MAX_SPREAD - 1f)) return MAX_SPREAD;
        return height / (height - above);
    }

    /**
     * The arm to a hand whose fingertips are over {@code at} (across and in depth), the elbow drawn
     * toward {@code elbowPoint}, the hand resting on the keys under it, down with the keys it holds by
     * {@code press}; {@code rigY} is the height the rig gives it.
     */
    private static Arm resting(KeyboardSpace keys, Vector3fc at, float rigY, float press, Vector3fc elbowPoint, float width, float engage) {
        Vector3f tips = new Vector3f(at.x(), 0f, at.z());
        Vector3f toward = splayed(tips, elbowPoint);
        // Seen from above, elbow, wrist and fingertips are in one line.
        Vector3f level = new Vector3f(tips.x - toward.x, 0f, tips.z - toward.z);
        if (level.lengthSquared() < 1e-6f) level.set(0f, 0f, 1f);
        level.normalize();
        // The rig holds the hand's middle the player model arm's half thickness above the key tops,
        // plus its hovering and dipping. This hand rests its palm just over the keys under it, up to
        // its fingertips, and keeps part of that motion. Holding keys down, it goes down with them
        // (press); a press it does not hold shows as a dip of at most SINK, never both at once.
        // The fingertips line up along the keys (see bent), so no part of the hand reaches past them.
        float middleX = tips.x - 0.5f * HAND * level.x;
        float halfSpan = 0.5f * (width / Math.max(MIN_SQUARE, Math.abs(level.z)) + HAND * Math.abs(level.x));
        float keyTop = keys.topUnder(middleX, halfSpan, tips.z);
        float lift = HOVER_SHARE * (rigY - HandRig.LIFT - keyTop);
        // Holding keys, the palm comes down with them to just over them: past CLEARANCE by up to the press itself.
        float held = press + Math.min(press, CLEARANCE - HELD_CLEARANCE);
        float down = Math.max(held, Math.min(SINK, -lift));
        tips.y = keyTop + CLEARANCE + Math.max(0f, lift) - down + HAND_THICKNESS * 0.5f;
        // Sitting down, the hands first rise from the lap, then reach forward over the keybed onto the
        // keys, in line with the forearm until they come over the keys and lie down on them.
        float away = 1f - Math.max(0f, Math.min(1f, engage));
        Vector3f along = level;
        if (away > 0f) {
            tips.add(0f, -SIT_DOWN_DROP * Math.max(0f, 2f * away - 1f), -SIT_DOWN_BACK * Math.min(1f, 2f * away));
            Vector3f straight = new Vector3f(tips).sub(toward);
            if (straight.lengthSquared() > 1e-6f) along = straight.normalize().lerp(level, Math.max(0f, 1f - 2f * away)).normalize();
        }
        Arm arm = bent(tips, along, toward, width);
        // The forearm rises toward the wrist, so its palm side is lowest where it crosses the front
        // edges of the keybed and the keys; raise the hand until it clears them. Raising it steepens
        // the forearm a little, so check again.
        for (int i = 0; i < 4; i++) {
            float missing = shortfall(keys, arm.forearm());
            if (missing <= 1e-4f) break;
            tips.y += missing;
            arm = bent(tips, along, toward, width);
        }
        return arm;
    }

    /** {@code elbowPoint}, swung across with the hand as far as keeps the arm within {@link #MAX_SPLAY} of straight ahead. */
    private static Vector3f splayed(Vector3fc hand, Vector3fc elbowPoint) {
        float across = hand.x() - elbowPoint.x();
        float most = Math.max(0f, hand.z() - elbowPoint.z()) * (float) Math.tan(MAX_SPLAY);
        if (Math.abs(across) <= most) return new Vector3f(elbowPoint);
        return new Vector3f(hand.x() - Math.copySign(most, across), elbowPoint.y(), elbowPoint.z());
    }

    /**
     * The arm whose fingertips are at {@code tips}: the hand along {@code along} (level on the keys:
     * seen from above, the arm's direction) back to the wrist, and reaching half the forearm's
     * thickness further back into it so the bend at the wrist is closed, its end lying along the keys
     * (square to the keyboard, not to the arm); the forearm, its palm side running on into the hand's,
     * from the wrist back toward {@code elbowPoint}, its elbow {@link #FOREARM} back and the box
     * reaching half the arm's width further, into the upper arm, so the bend at the elbow is closed
     * however steeply the upper arm comes down to it.
     */
    private static Arm bent(Vector3fc tips, Vector3fc along, Vector3fc elbowPoint, float width) {
        Vector3f up = new Vector3f(0f, 1f, 0f);
        Vector3f back = square(up, along, new Vector3f(0f, 0f, -1f));
        // The middle of the hand where it meets the wrist.
        Vector3f heel = new Vector3f(tips).fma(-HAND, along);
        Vector3f across = new Vector3f(back).cross(along).normalize();
        // How far along a point of the end lies per unit across for the end to run along the keys (no depth across).
        float slant = Math.abs(along.z()) < MIN_SQUARE ? 0f : -across.z / along.z();
        Segment hand = new Segment(new Vector3f(heel).fma(-THICKNESS * 0.5f, along), new Vector3f(tips), back, across, width, HAND_THICKNESS, slant);
        // The forearm is thicker than the hand: it stands above the hand's back, not below its palm.
        Vector3f wrist = new Vector3f(heel).fma((THICKNESS - HAND_THICKNESS) * 0.5f, back);
        Vector3f fore = new Vector3f(wrist).sub(elbowPoint);
        if (fore.lengthSquared() < 1e-6f) fore.set(along);
        fore.normalize();
        Vector3f start = new Vector3f(wrist).fma(-(FOREARM + width * 0.5f), fore);
        Vector3f front = square(up, fore, new Vector3f(0f, 0f, -1f));
        Segment forearm = new Segment(start, wrist, front, new Vector3f(front).cross(fore).normalize(), width, THICKNESS);
        return new Arm(forearm, hand);
    }

    /**
     * How much higher the forearm must be for its palm side to clear, by {@link #CLEARANCE}, every
     * surface step it crosses: the keybed's front, the white keys' front edge and the black keys' front
     * edge. The palm face is level across, so where it crosses a line of constant depth its lowest
     * point is at the side reaching that line nearest the elbow.
     */
    private static float shortfall(KeyboardSpace keys, Segment arm) {
        Vector3f along = arm.along();
        if (along.z < 1e-4f) return 0f;
        float halfWidth = arm.width() * 0.5f, length = arm.length();
        float reach = halfWidth * Math.abs(arm.across().z()), spanX = halfWidth * Math.abs(arm.across().x());
        Vector3f palm = new Vector3f(arm.start()).fma(-arm.thickness() * 0.5f, arm.front());
        float need = 0f;
        float[] edges = {0f, keys.frontZ(false), keys.frontZ(true)};
        for (int i = 0; i < edges.length; i++) {
            float s = (edges[i] - reach - palm.z) / along.z;
            if (s < 0f || s > length) continue;
            float x = palm.x + s * along.x, height = palm.y + s * along.y;
            if (x - spanX > keys.bassEdge() || x + spanX < keys.trebleEdge()) continue;
            float surface = i == 0 ? keys.keybedTop() : keys.topUnder(x, spanX, edges[i] + 1e-3f);
            need = Math.max(need, surface + CLEARANCE - height);
        }
        return need;
    }

    /** {@code wanted} made square to the unit vector {@code axis}; {@code fallback} when they are parallel. */
    private static Vector3f square(Vector3fc wanted, Vector3fc axis, Vector3fc fallback) {
        Vector3f out = new Vector3f(wanted).fma(-wanted.dot(axis), axis);
        if (out.lengthSquared() < 1e-6f) out.set(fallback).fma(-fallback.dot(axis), axis);
        return out.normalize();
    }
}
