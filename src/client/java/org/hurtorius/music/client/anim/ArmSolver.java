package org.hurtorius.music.client.anim;

import net.minecraft.client.model.geom.PartPose;
import org.hurtorius.music.piano.PianoLayout;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.List;

/**
 * Turns two hand targets into a performer pose: rigid arms (Minecraft has no elbows) aimed so the
 * middle of each hand's end lands on its target, with the torso leaning forward, twisting and, last,
 * leaning sideways about the hips so far keys are within reach and near ones are not overshot. The
 * bench is set close enough that the keys around middle C need hardly any lean. A grand piano's
 * keyboard is wider than a Minecraft player can span from a fixed seat, so for far keys the hips
 * first slide along the bench toward them, the body staying square to the keyboard, and only then
 * does the torso lean that way. The torso bows forward only a little ({@link #MAX_LEAN}), so the
 * face stays well behind the fallboard: for keys further forward and far apart the hips come forward
 * to the bench's front edge, the way pianists shift their weight, and a shoulder still short of its
 * key comes forward a little along the torso, as a reaching shoulder does (one whose key is too near
 * draws back a little instead). The arms never stretch: how far apart the hands may be at all is the
 * hand rig's to keep (see {@code HandRig.armTargets}). The legs, which have no knee either, come down
 * over the bench's front edge, resting on its corner, to the floor, the right foot on the sustain pedal
 * where it reaches it (see {@link #leftLeg}, {@link #rightLeg}).
 *
 * <p>Everything is in player model space: pixels, Y down, the body facing -Z, its left toward +X,
 * the same space {@code ModelPart} positions use. Rotations follow {@code ModelPart}: Z, then Y,
 * then X applied to the part's own axes.</p>
 */
public final class ArmSolver {
    /** Shoulder pivot to the end of the hand. */
    public static final float ARM_LENGTH = 10f;
    /** Hip to shoulder height: the lean's lever. */
    static final float TORSO = 10f;
    static final float SHOULDER_HALF = 5f;
    /** Half the width of the body (and of the legs side by side), in pixels. */
    static final float BODY_HALF = 4f;
    /** The player model's pixel, in sixteenths of a block. */
    static final float MODEL_PIXEL = 0.9375f;
    static final Vector3fc HIP = new Vector3f(0f, 12f, 0f);
    static final Vector3fc RIGHT_SHOULDER = new Vector3f(-SHOULDER_HALF, 2f, 0f);
    static final Vector3fc LEFT_SHOULDER = new Vector3f(SHOULDER_HALF, 2f, 0f);
    public static final float BASE_LEAN = rad(5f);
    public static final float MIN_LEAN = rad(-6f);
    /** Furthest the torso leans forward: far keys are reached by sliding the hips and twisting, not by bowing over the keys. */
    public static final float MAX_LEAN = rad(16f);
    public static final float MAX_SIDE = rad(12f);
    public static final float MAX_TWIST = rad(15f);
    /** Furthest the hips slide along the bench, in pixels: until the side of the body reaches the end of the bench. */
    public static final float MAX_SLIDE = PianoLayout.BENCH_HALF_WIDTH * PianoLayout.BLOCKS_PER_UNIT * 16f / MODEL_PIXEL - BODY_HALF;
    /** Furthest the hips come forward on the bench, in pixels: to its front edge. */
    public static final float MAX_SCOOT = PianoLayout.HIPS_ON_BENCH / MODEL_PIXEL;
    /** Furthest a shoulder comes forward along the torso to reach a far key, or draws back from a near one, in pixels. */
    public static final float MAX_REACH = 1.5f;
    /** A bare leg from its pivot at the hip to the sole, and half its width and depth, in pixels. */
    static final float LEG = 12f, LEG_HALF = 2f;
    /** How far either side of the middle the player model's legs hang, in pixels: the right leg at -X, the left at +X. */
    public static final float LEG_X = 1.9f;
    /** How far over the sustain pedal's top a toe may rest for the foot to count as resting on it, in pixels. */
    public static final float ON_PEDAL = 1f;
    /** Where on the sustain pedal the right toe rests: this far behind its front edge along its top, in pixels. */
    static final float TOE_ON_PEDAL = 1f;
    /** How far the right shoe keeps from the pedal beside the one it rests on, in pixels. */
    static final float BESIDE = 0.3f;
    /** Furthest the right toe comes down further out on the pedal, turned across toward it, to keep the shoe off the pedals beside it, in pixels. */
    static final float MAX_SHIFT = 3f;
    /** Furthest the right leg comes forward over the bench's corner to keep its toe on the pedal, in pixels. */
    public static final float LEG_FORWARD = 0.75f;
    /** Furthest a leg on the floor turns out to keep its foot beside the pedal lyre. */
    static final float MAX_TURN_OUT = (float) Math.toRadians(60);
    /**
     * How much larger all round than the bare leg each layer that can be drawn over it is, in pixels:
     * the skin's trousers layer, and the game's armour on the legs, leggings and boots (its inner and
     * outer layers, a half and a whole pixel larger, each a tenth less on the legs).
     */
    public static final float TROUSERS = 0.25f, LEGGINGS = 0.4f, BOOTS = 0.9f;
    private static final int POSTURE_STEPS = 8;
    /** Steps of the halving searches for how a leg swings or turns, or where its toe comes down: to about a thousandth of a degree or a ten-thousandth of a pixel. */
    private static final int LEG_STEPS = 16;
    /** Steps of the search for the swing at which a leg reaches furthest down: each keeps two thirds, to under a thousandth of a degree. */
    private static final int DEEPEST_STEPS = 32;
    /**
     * Steps of the search for how far a foot on the floor turns out beside the lyre: to under a fifth of
     * a degree, the foot then clear of it by at most that much more turn than it needs.
     */
    private static final int TURN_STEPS = 8;

    private ArmSolver() {}

    /**
     * Where a seated performer's legs hang from and come down to, in their own model space (pixels, Y
     * down, facing -Z): the floor the bench stands on (its Y), the bench's top and front edge, whose
     * corner the legs come down over (its Y and Z, ahead of the hips), how much larger than the bare leg
     * the largest layer drawn on the legs is ({@link #TROUSERS}, {@link #LEGGINGS}, {@link #BOOTS}, or
     * 0), the sustain pedal's top as it is now and at rest, and the boxes {min x, y, z, max x, y, z} the
     * parts of the pedal lyre and its other pedals fill, which the feet keep out of (null where there
     * are none).
     */
    public record Footing(float floor, float top, float edge, float growth, @Nullable PedalTop pedal, @Nullable PedalTop pedalAtRest,
                          @Nullable List<float[]> lyre) {
        /** True when {@code other} has every value this has, but perhaps the pedal as it is now: the same floor, bench, legs, pedal at rest and lyre. */
        boolean sameGround(Footing other) {
            if (this == other) return true;
            if (floor != other.floor || top != other.top || edge != other.edge || growth != other.growth) return false;
            if (!PedalTop.same(pedalAtRest, other.pedalAtRest)) return false;
            if (lyre == null || other.lyre == null) return lyre == other.lyre;
            if (lyre.size() != other.lyre.size()) return false;
            for (int i = 0; i < lyre.size(); i++) if (!Arrays.equals(lyre.get(i), other.lyre.get(i))) return false;
            return true;
        }

        /** True when {@code other} has every value this has. */
        boolean same(Footing other) {
            return PedalTop.same(pedal, other.pedal) && sameGround(other);
        }
    }

    /**
     * The middle of a pedal's top in a performer's model space: at the end of its toe, nearest the
     * performer, and at its back, where it goes into the lyre. Across the keyboard it is level. A foot
     * on it keeps between {@code low} and {@code high} across (model X), off the pedals beside it; a
     * foot on the floor keeps out of {@code box}, {min x, y, z, max x, y, z}, the pedal at rest.
     */
    public record PedalTop(Vector3fc toe, Vector3fc back, float low, float high, float[] box) {
        /** Height (model Y, down) of the pedal's top at depth {@code z}, on the line through its toe and its back end. */
        float height(float z) {
            return toe.y() + (z - toe.z()) / (back.z() - toe.z()) * (back.y() - toe.y());
        }

        /** True when both are none, or both have every value the same. */
        static boolean same(@Nullable PedalTop a, @Nullable PedalTop b) {
            if (a == b) return true;
            if (a == null || b == null) return false;
            return a.toe.equals(b.toe) && a.back.equals(b.back) && a.low == b.low && a.high == b.high && Arrays.equals(a.box, b.box);
        }
    }

    /**
     * A performer's legs as last placed (see {@link #rightLeg}, {@link #leftLeg}) and what for, so
     * they are placed again only when the footing or the hips' slide along the bench changes: a foot
     * turned out beside the pedal lyre (a large one, in boots) takes many trials to place, and most
     * frames nothing it depends on moves. Whether the right foot reaches the sustain pedal at all
     * depends only on where the performer sits and what they wear, so it is kept until that changes.
     * One per performer; not thread-safe.
     */
    public static final class Legs {
        private @Nullable Footing ground;
        private boolean onPedal;
        private @Nullable Footing placedFor;
        private float placedHipX;
        private ArmPose.@Nullable Leg right, left;

        /** Places the right leg and the left ({@link #right}, {@link #left}) for {@code footing} with the hips slid {@code hipX} along the bench. */
        void place(Footing footing, float hipX) {
            if (placedFor != null && placedHipX == hipX && placedFor.same(footing) && right != null && left != null) return;
            if (ground == null || !ground.sameGround(footing)) {
                onPedal = reachesPedal(footing);
                ground = footing;
            }
            right = rightLeg(footing, hipX, onPedal);
            left = leftLeg(footing, hipX);
            placedFor = footing;
            placedHipX = hipX;
        }
    }

    /**
     * @param leftTarget  where the end of the left hand should be
     * @param rightTarget where the end of the right hand should be
     * @param footing     where the legs come down over the bench and to the floor, in the same space
     * @param slim        slim (3 pixel) arms: their centre line sits half a pixel closer to the shoulder
     * @param weight      how much of the pose to apply over the model's own (see {@link ArmPose})
     */
    public static ArmPose solve(Vector3fc leftTarget, Vector3fc rightTarget, Footing footing, boolean slim, float weight) {
        return solve(leftTarget, rightTarget, footing, slim, weight, new Legs());
    }

    /**
     * As {@link #solve(Vector3fc, Vector3fc, Footing, boolean, float)}, the legs placed through this
     * performer's {@code legs}, which keeps them while nothing they depend on changes.
     */
    public static ArmPose solve(Vector3fc leftTarget, Vector3fc rightTarget, Footing footing, boolean slim, float weight, Legs legs) {
        float lean = BASE_LEAN, side = 0f, twist = 0f;
        Vector3f hips = new Vector3f();
        Vector3f left = new Vector3f(), right = new Vector3f(), needLeft = new Vector3f(), needRight = new Vector3f();
        Quaternionf torso = new Quaternionf();
        for (int i = 0; i < POSTURE_STEPS; i++) {
            torso.rotationZYX(side, twist, lean);
            shoulder(torso, hips, LEFT_SHOULDER, left);
            shoulder(torso, hips, RIGHT_SHOULDER, right);
            excess(left, leftTarget, needLeft);
            excess(right, rightTarget, needRight);
            // The forward (-Z) need both shoulders share goes to lean, and what the lean cannot give
            // to the hips coming forward. The sideways need goes to the hips sliding along the bench,
            // and what the bench cannot give to leaning sideways. The shoulders' difference goes to twist.
            float forward = -(needLeft.z + needRight.z) * 0.5f, across = (needLeft.x + needRight.x) * 0.5f;
            float wantLean = lean + asin(forward / TORSO), nextLean = clamp(wantLean, MIN_LEAN, MAX_LEAN);
            float scoot = nextLean == wantLean ? 0f : forward - TORSO * (sin(nextLean) - sin(lean));
            // Where the middle of the shoulders should be across, shared out afresh each step, so the
            // body squares up again whenever the bench alone will do.
            float lateral = hips.x + TORSO * sin(side) + across, slid = clamp(lateral, -MAX_SLIDE, MAX_SLIDE);
            lean = nextLean;
            side = clamp(asin((lateral - slid) / TORSO), -MAX_SIDE, MAX_SIDE);
            twist = clamp(twist + asin((needRight.z - needLeft.z) * 0.5f / SHOULDER_HALF), -MAX_TWIST, MAX_TWIST);
            hips.z = clamp(hips.z - scoot, -MAX_SCOOT, 0f);
            hips.x = slid;
        }
        torso.rotationZYX(side, twist, lean);
        Vector3f forward = torso.transform(new Vector3f(0f, 0f, -1f));
        reach(shoulder(torso, hips, LEFT_SHOULDER, left), leftTarget, forward);
        reach(shoulder(torso, hips, RIGHT_SHOULDER, right), rightTarget, forward);
        Vector3f neck = torso.transform(new Vector3f(HIP).negate()).add(HIP).add(hips);
        PartPose body = PartPose.offsetAndRotation(neck.x, neck.y, neck.z, lean, twist, side);
        float handOffset = slim ? 0.5f : 1f;
        legs.place(footing, hips.x);
        return new ArmPose(weight, body, aim(right, rightTarget, -handOffset), aim(left, leftTarget, handOffset), hips.x, hips.z, legs.right, legs.left);
    }

    /**
     * The seated legs come down over the corner of the bench's top and front edge: each hangs from
     * where its back, as it is drawn, just rests on that corner (see {@link #hang}), so no part of it
     * is inside the bench. The left leg, its hips slid {@code hipX} along the bench, comes down to the
     * floor (see {@link #floorLeg}).
     */
    static ArmPose.Leg leftLeg(Footing footing, float hipX) {
        return floorLeg(footing, LEG_X + hipX, -1f);
    }

    /**
     * A leg pivoted {@code x} across that comes down to the floor (see {@link #floorSwing}), turned out a
     * little as the game's riding pose turns it ({@link ArmPose#SPREAD_YAW}, {@link ArmPose#SPREAD_ROLL};
     * {@code out} 1 for the right leg, -1 for the left), or, where its foot would reach into the pedal
     * lyre or a pedal (a large foot, in boots), turned out further, as far as keeps it beside them.
     */
    static ArmPose.Leg floorLeg(Footing footing, float x, float out) {
        ArmPose.Leg leg = turnedOut(footing, out, ArmPose.SPREAD_YAW);
        if (!besidePedals(footing, leg, x)) return leg;
        float lo = ArmPose.SPREAD_YAW, hi = MAX_TURN_OUT;
        for (int i = 0; i < TURN_STEPS; i++) {
            float mid = (lo + hi) * 0.5f;
            if (besidePedals(footing, turnedOut(footing, out, mid), x)) lo = mid;
            else hi = mid;
        }
        return turnedOut(footing, out, hi);
    }

    /** True when a leg on the floor reaches into the lyre, its other pedals or the sustain pedal (see {@link #intoLyre}). */
    private static boolean besidePedals(Footing footing, ArmPose.Leg leg, float x) {
        return footing.lyre() != null && intoLyre(footing, leg, x) || footing.pedalAtRest() != null && new LegBox(footing, leg, x).into(footing.pedalAtRest().box());
    }

    /** A leg on the floor turned out by {@code turnOut} (radians), hanging from where it rests on the bench's corner. */
    private static ArmPose.Leg turnedOut(Footing footing, float out, float turnOut) {
        float swing = floorSwing(footing, turnOut, ArmPose.SPREAD_ROLL);
        float yaw = out * turnOut, roll = out * ArmPose.SPREAD_ROLL;
        return new ArmPose.Leg(hang(footing, new Quaternionf().rotationZYX(roll, yaw, -swing)), -swing, yaw, roll);
    }

    /**
     * True when a leg posed as {@code leg}, pivoted {@code x} across, reaches into a part of the pedal
     * lyre or its other pedals ({@link Footing#lyre}).
     */
    static boolean intoLyre(Footing footing, ArmPose.Leg leg, float x) {
        LegBox drawn = new LegBox(footing, leg, x);
        for (float[] box : footing.lyre()) if (drawn.into(box)) return true;
        return false;
    }

    /**
     * A leg posed as {@code leg}, pivoted {@code x} across, as drawn (the largest layer on it): the box
     * around {@code centre} along its unit axes (across, along the leg, front to back), {@code extent}
     * out along each.
     */
    private record LegBox(Vector3f centre, Vector3f[] axes, float[] extent) {
        LegBox(Footing footing, ArmPose.Leg leg, float x) {
            this(footing.growth(), new Quaternionf().rotationZYX(leg.zRot(), leg.yRot(), leg.xRot()), x, leg.z());
        }

        private LegBox(float grown, Quaternionf turn, float x, float z) {
            this(turn.transform(new Vector3f(0f, LEG * 0.5f, 0f)).add(x, HIP.y(), z),
                new Vector3f[]{turn.transform(new Vector3f(1f, 0f, 0f)), turn.transform(new Vector3f(0f, 1f, 0f)), turn.transform(new Vector3f(0f, 0f, 1f))},
                new float[]{LEG_HALF + grown, LEG * 0.5f + grown, LEG_HALF + grown});
        }

        /**
         * True when this box and {@code box}, {min x, y, z, max x, y, z} along the model's axes, overlap:
         * no line along one of either's axes, or square to one of each, keeps them apart. The model's
         * axes go first, which part a leg from a box it is nowhere near.
         */
        boolean into(float[] box) {
            float[] half = {(box[3] - box[0]) * 0.5f, (box[4] - box[1]) * 0.5f, (box[5] - box[2]) * 0.5f};
            Vector3f apart = new Vector3f(centre).sub((box[0] + box[3]) * 0.5f, (box[1] + box[4]) * 0.5f, (box[2] + box[5]) * 0.5f);
            Vector3f axis = new Vector3f();
            for (int a = 0; a < 3; a++) {
                if (apart(apart, axis.zero().setComponent(a, 1f), half)) return false;
            }
            for (Vector3f own : axes) {
                if (apart(apart, own, half)) return false;
            }
            for (int a = 0; a < 3; a++) {
                for (Vector3f own : axes) {
                    axis.zero().setComponent(a, 1f).cross(own);
                    if (axis.lengthSquared() >= 1e-8f && apart(apart, axis, half)) return false;
                }
            }
            return true;
        }

        /** True when, along {@code axis}, this box and a box {@code half} out along each model axis, whose centre this one's is {@code apart} from, do not meet. */
        private boolean apart(Vector3fc apart, Vector3fc axis, float[] half) {
            float reach = half[0] * Math.abs(axis.x()) + half[1] * Math.abs(axis.y()) + half[2] * Math.abs(axis.z());
            for (int i = 0; i < 3; i++) reach += extent[i] * Math.abs(axes[i].dot(axis));
            return Math.abs(apart.dot(axis)) >= reach;
        }
    }

    /**
     * The right leg, its hips slid {@code hipX} along the bench: on the sustain pedal, as a pianist
     * keeps the right foot, where a leg of this performer's size reaches it from where they sit (see
     * {@link #restsOnPedal}); otherwise down to the floor like the left, turned out the other way (see
     * {@link #floorLeg}).
     */
    static ArmPose.Leg rightLeg(Footing footing, float hipX) {
        return rightLeg(footing, hipX, reachesPedal(footing));
    }

    /** The right leg (see {@link #rightLeg(Footing, float)}), {@code onPedal} being what {@link #reachesPedal} says of {@code footing}. */
    private static ArmPose.Leg rightLeg(Footing footing, float hipX, boolean onPedal) {
        return onPedal && footing.pedal() != null ? onPedal(footing, footing.pedal(), -LEG_X + hipX) : floorLeg(footing, -LEG_X + hipX, 1f);
    }

    /** True when there is a sustain pedal and the right foot of a performer with this footing rests on it (see {@link #restsOnPedal}). */
    private static boolean reachesPedal(Footing footing) {
        return footing.pedal() != null && footing.pedalAtRest() != null && restsOnPedal(footing, footing.pedalAtRest());
    }

    /**
     * True when the right leg, the hips where they sit and the pedal up, puts its foot on the pedal: the
     * toe on its top (resting on it, or over it by at most {@link #ON_PEDAL} where the floor stops the
     * leg first) between its toe and its back end, and the heel in front of it. A performer much smaller
     * or larger than usual cannot, and keeps the right foot on the floor.
     */
    static boolean restsOnPedal(Footing footing, PedalTop pedal) {
        ArmPose.Leg leg = onPedal(footing, pedal, -LEG_X);
        Vector3f toe = sole(footing, leg, -LEG_X, -1f), heel = sole(footing, leg, -LEG_X, 1f);
        return toe.z <= pedal.toe().z() && toe.z >= pedal.back().z() && heel.z >= pedal.toe().z() && toe.y >= pedal.height(toe.z) - ON_PEDAL;
    }

    /**
     * The right leg on the pedal, its pivot {@code x} across: turned so its toe comes down on the
     * pedal, {@link #TOE_ON_PEDAL} behind its front edge (across, on its middle, or nearer its outer
     * side where the shoe, wider than the pedal, would cover the pedal beside it), and swung forward
     * just so far that the toe is as high as the pedal's top there as it is now, down with it when it
     * is pressed (or, where the floor stops the leg first, so far that it rests on the floor, the toe
     * just over the pedal). The sole rises toward the toe, so behind it the shoe covers the pedal's
     * front edge, as a foot on a pedal does. Where the leg resting on the bench's corner (see
     * {@link #hang}) would put the toe short of that point (the pedal pressed takes its top down and
     * the toe, swinging down, back), the leg comes forward over the corner, by at most
     * {@link #LEG_FORWARD}. Slid along the bench, the hips take the leg off the pedal's line: further
     * than it reaches, the toe stays at the height of the pedal's top short of that point, never under
     * it; turned across toward it, the shoe comes down further out on the pedal, by at most
     * {@link #MAX_SHIFT}, as far as keeps it off the pedals beside it, and failing that rises over them.
     */
    static ArmPose.Leg onPedal(Footing footing, PedalTop pedal, float x) {
        float half = LEG_HALF + footing.growth();
        Vector3f along = new Vector3f(pedal.back()).sub(pedal.toe());
        Vector3f rest = new Vector3f(pedal.toe()).fma(TOE_ON_PEDAL / along.length(), along);
        // Wider than the pedal, the shoe comes down on it as far to its outer side as keeps it off the pedal beside it.
        float side = half + BESIDE;
        if (pedal.high() - pedal.low() > 2f * side) rest.x = Math.max(pedal.low() + side, Math.min(pedal.high() - side, rest.x));
        // Not tipped sideways, a leg reaches as far down whichever way it turns: how far it swings to the floor is the same for any turn.
        float floor = floorSwing(footing, 0f, 0f);
        ArmPose.Leg leg = toward(footing, rest, x, floor);
        if (footing.lyre() == null || !intoLyre(footing, leg, x)) return leg;
        // Away from the nearer pedal beside it.
        float out = pedal.high() - rest.x < rest.x - pedal.low() ? -1f : 1f, from = rest.x;
        rest.x = from + out * MAX_SHIFT;
        if (!intoLyre(footing, toward(footing, rest, x, floor), x)) {
            float lo = 0f, hi = MAX_SHIFT;
            for (int i = 0; i < LEG_STEPS; i++) {
                float mid = (lo + hi) * 0.5f;
                rest.x = from + out * mid;
                if (intoLyre(footing, toward(footing, rest, x, floor), x)) lo = mid;
                else hi = mid;
            }
            rest.x = from + out * hi;
            return toward(footing, rest, x, floor);
        }
        // Even so the shoe would come down on them: it rises over them.
        leg = toward(footing, rest, x, floor);
        float lo = -leg.xRot(), hi = (float) (Math.PI / 2);
        for (int i = 0; i < LEG_STEPS; i++) {
            float mid = (lo + hi) * 0.5f;
            if (intoLyre(footing, towardPedal(footing, rest, leg.yRot(), mid), x)) lo = mid;
            else hi = mid;
        }
        return towardPedal(footing, rest, leg.yRot(), hi);
    }

    /**
     * The right leg turned so its toe comes down on {@code rest}, swung to the height of it, or to the
     * floor where the floor stops it first, {@code floor} being how far it swings to the floor (see {@link #onPedal}).
     */
    private static ArmPose.Leg toward(Footing footing, Vector3fc rest, float x, float floor) {
        float grown = footing.growth(), half = LEG_HALF + grown, length = LEG + grown;
        float swing = Math.max(toeSwing(rest.y() - HIP.y(), length, half), floor);
        // Seen from above the toe lies (length sin s + half cos s) out from the pivot; turned by the yaw it is that far across.
        float reach = length * (float) Math.sin(swing) + half * (float) Math.cos(swing);
        float yaw = reach <= Math.abs(x - rest.x()) ? Math.copySign((float) (Math.PI / 2), x - rest.x()) : (float) Math.asin((x - rest.x()) / reach);
        return towardPedal(footing, rest, yaw, swing);
    }

    /**
     * A leg turned by {@code yaw} and swung by {@code swing} hanging from where it rests on the bench's
     * corner, or up to {@link #LEG_FORWARD} further forward where that brings its toe over {@code rest}.
     */
    private static ArmPose.Leg towardPedal(Footing footing, Vector3fc rest, float yaw, float swing) {
        float grown = footing.growth();
        float reach = (LEG + grown) * (float) Math.sin(swing) + (LEG_HALF + grown) * (float) Math.cos(swing);
        float corner = hang(footing, new Quaternionf().rotationZYX(0f, yaw, -swing));
        return new ArmPose.Leg(Math.min(corner, Math.max(rest.z() + reach * (float) Math.cos(yaw), corner - LEG_FORWARD)), -swing, yaw, 0f);
    }

    /** How far a leg swings forward for the middle of its toe's edge to be {@code down} pixels under its pivot. */
    private static float toeSwing(float down, float length, float half) {
        // Under its pivot by length cos s - half sin s, which only lessens as the leg swings forward.
        float lo = 0f, hi = (float) (Math.PI / 2);
        for (int i = 0; i < LEG_STEPS; i++) {
            float mid = (lo + hi) * 0.5f;
            if (length * (float) Math.cos(mid) - half * (float) Math.sin(mid) > down) lo = mid;
            else hi = mid;
        }
        return (lo + hi) * 0.5f;
    }

    /**
     * The middle of the front ({@code side} -1: the toe) or back ({@code side} 1: the heel) edge of the
     * sole of a leg posed as {@code leg}, its pivot {@code x} across, in the performer's model space.
     */
    static Vector3f sole(Footing footing, ArmPose.Leg leg, float x, float side) {
        float grown = footing.growth();
        return new Quaternionf().rotationZYX(leg.zRot(), leg.yRot(), leg.xRot())
            .transform(new Vector3f(0f, LEG + grown, side * (LEG_HALF + grown))).add(x, HIP.y(), leg.z());
    }

    /**
     * Where a leg turned by {@code turn} hangs from (its pivot's Z, pixels) for its back, as drawn, to
     * rest on the corner of the bench's top and front edge without reaching into the bench: as far
     * back as keeps every part of it under the bench's top in front of the bench's front.
     */
    static float hang(Footing footing, Quaternionf turn) {
        float grown = footing.growth(), half = LEG_HALF + grown;
        Vector3f[] corners = new Vector3f[8];
        for (int c = 0; c < 8; c++) {
            corners[c] = turn.transform(new Vector3f((c & 1) == 0 ? -half : half, (c & 2) == 0 ? -grown : LEG + grown, (c & 4) == 0 ? -half : half)).add(0f, HIP.y(), 0f);
        }
        // The furthest back the leg pivoted at Z 0 is anywhere below the bench's top: at a corner, or
        // where an edge of the box crosses the top's height.
        float back = Float.NEGATIVE_INFINITY;
        for (int a = 0; a < 8; a++) {
            if (corners[a].y >= footing.top()) back = Math.max(back, corners[a].z);
            for (int bit = 1; bit < 8; bit <<= 1) {
                Vector3f p = corners[a], q = corners[a ^ bit];
                if ((p.y - footing.top()) * (q.y - footing.top()) >= 0f) continue;
                float t = (footing.top() - p.y) / (q.y - p.y);
                back = Math.max(back, p.z + t * (q.z - p.z));
            }
        }
        return back == Float.NEGATIVE_INFINITY ? footing.edge() : footing.edge() - back;
    }

    /**
     * How far a seated leg turned out by {@code yaw} and tipped by {@code roll} swings forward from
     * hanging straight down to the floor, in radians (positive). A Minecraft leg is one box with no
     * knee: it comes down over the bench's front edge and swings forward just so far that the lowest
     * corner of the largest layer drawn on it (see {@link Footing#growth}), turned as the leg is drawn
     * (see {@link ArmPose}), rests on the floor, toward the pedals (32 degrees for a player of the
     * usual size in trousers on the bench as it stands, turned out as the riding pose turns it; the
     * game's riding pose holds the legs out at 81). Hips too high for that hang the legs where they
     * reach furthest down.
     */
    private static float floorSwing(Footing footing, float yaw, float roll) {
        float down = footing.floor() - HIP.y(), grown = footing.growth();
        // Swung forward from hanging, a leg first reaches a little further down (the back of its sole
        // comes under it), then less and less, until it lies level. One that reaches the floor hanging
        // straight down (as from the bench) stays below it until past that deepest swing, so the floor
        // is met where it first comes up to it; only one that does not is first swung to its deepest.
        float lo = 0f, hi = (float) (Math.PI / 2), level = hi;
        if (down <= reachDown(level, grown, yaw, roll)) return level;
        if (down > reachDown(0f, grown, yaw, roll)) {
            for (int i = 0; i < DEEPEST_STEPS; i++) {
                float a = lo + (hi - lo) / 3f, b = hi - (hi - lo) / 3f;
                if (reachDown(a, grown, yaw, roll) < reachDown(b, grown, yaw, roll)) lo = a;
                else hi = b;
            }
            float deepest = (lo + hi) * 0.5f;
            if (down >= reachDown(deepest, grown, yaw, roll)) return deepest;
            lo = deepest;
            hi = level;
        }
        for (int i = 0; i < LEG_STEPS; i++) {
            float mid = (lo + hi) * 0.5f;
            if (reachDown(mid, grown, yaw, roll) > down) lo = mid;
            else hi = mid;
        }
        return (lo + hi) * 0.5f;
    }

    /**
     * How far under its pivot (pixels, model Y) the lowest corner of a leg grown by {@code grown} all
     * round is, swung forward by {@code swing} radians, turned out by {@code yaw} and tipped by
     * {@code roll} as {@link ArmPose} draws it.
     */
    static float reachDown(float swing, float grown, float yaw, float roll) {
        Quaternionf turn = new Quaternionf().rotationZYX(roll, yaw, -swing);
        // The lowest corner of a box is as low as its middle plus each half extent times how far down its axis points.
        Vector3f axis = new Vector3f();
        float acrossDown = turn.transform(axis.set(1f, 0f, 0f)).y, alongDown = turn.transform(axis.set(0f, 1f, 0f)).y;
        float depthDown = turn.transform(axis.set(0f, 0f, 1f)).y, half = LEG_HALF + grown;
        return alongDown * LEG * 0.5f + half * Math.abs(acrossDown) + (LEG * 0.5f + grown) * Math.abs(alongDown) + half * Math.abs(depthDown);
    }

    /**
     * A world-space offset from a player's position, in that player's model space: the inverse of the
     * transform {@code LivingEntityRenderer} draws players with (scale, body yaw, flip, 0.9375 scale,
     * 1.501 drop, 16 pixels per block).
     */
    public static Vector3f fromWorld(double dx, double dy, double dz, float scale, float bodyRot) {
        Vector3f v = new Vector3f((float) dx, (float) dy, (float) dz).div(scale).rotateY((float) Math.toRadians(bodyRot - 180f));
        v.set(-v.x, -v.y, v.z).div(0.9375f);
        v.y += 1.501f;
        return v.mul(16f);
    }

    /**
     * A point in a player's model space as a world-space offset from the player's position: the
     * transform {@code LivingEntityRenderer} draws players with. The inverse of {@link #fromWorld}.
     */
    public static Vector3f toWorld(Vector3fc modelPixels, float scale, float bodyRot) {
        Vector3f v = new Vector3f(modelPixels).div(16f);
        v.y -= 1.501f;
        v.mul(0.9375f);
        v.set(-v.x, -v.y, v.z);
        return v.rotateY((float) Math.toRadians(180f - bodyRot)).mul(scale);
    }

    /** Where a shoulder ends up when the torso turns by {@code torso} about the hips and the hips slide by {@code hips}. */
    static Vector3f shoulder(Quaternionf torso, Vector3fc hips, Vector3fc rest, Vector3f out) {
        return torso.transform(out.set(rest).sub(HIP)).add(HIP).add(hips);
    }

    /**
     * Brings a shoulder that is still out of reach of its target forward along the torso's
     * {@code forward}, or one too close to it back, as far as the arm needs and at most
     * {@link #MAX_REACH}: a reaching shoulder comes forward, one whose hand plays near the body draws back.
     */
    static Vector3f reach(Vector3f shoulder, Vector3fc target, Vector3fc forward) {
        Vector3f d = new Vector3f(target).sub(shoulder);
        float beyond = d.lengthSquared() - ARM_LENGTH * ARM_LENGTH;
        if (Math.abs(beyond) < 1e-6f) return shoulder;
        // The nearest point along forward from which the target is one arm away, else the closest approach.
        float along = d.dot(forward), room = along * along - beyond;
        float move = room >= 0f ? along - (float) Math.sqrt(room) : along;
        return shoulder.fma(clamp(move, -MAX_REACH, MAX_REACH), forward);
    }

    /**
     * How far, and which way, a shoulder would have to move for the arm to just reach: toward the
     * target when it is out of reach, away from it when the hand would overshoot.
     */
    static Vector3f excess(Vector3fc shoulder, Vector3fc target, Vector3f out) {
        out.set(target).sub(shoulder);
        float distance = out.length();
        if (distance < 1e-4f) return out.zero();
        return out.mul(1f - ARM_LENGTH / distance);
    }

    /**
     * Rotations for an arm hanging from {@code shoulder} so the middle of its end, {@code handOffset}
     * pixels across from the arm's pivot line, lands on {@code target}. A target closer than the arm
     * is long is met at its height, the hand reaching past it rather than through the keys.
     */
    static PartPose aim(Vector3fc shoulder, Vector3fc target, float handOffset) {
        float xRot = 0f, yRot = 0f;
        Vector3f goal = new Vector3f();
        for (int i = 0; i < 3; i++) {
            // The arm's own X axis after yaw is (cos y, 0, -sin y); the hand's centre is offset along it.
            goal.set(target).sub(shoulder).sub(handOffset * (float) Math.cos(yRot), 0f, -handOffset * (float) Math.sin(yRot));
            float distance = goal.length();
            if (distance < 1e-4f) {
                goal.set(0f, 1f, 0f);
            } else if (distance >= ARM_LENGTH) {
                goal.div(distance);
            } else {
                float down = Math.max(-ARM_LENGTH, Math.min(ARM_LENGTH, goal.y));
                float across = (float) Math.sqrt(ARM_LENGTH * ARM_LENGTH - down * down);
                float flat = (float) Math.sqrt(goal.x * goal.x + goal.z * goal.z);
                if (flat < 1e-4f) goal.set(0f, down, -across);
                else goal.set(goal.x / flat * across, down, goal.z / flat * across);
                goal.div(ARM_LENGTH);
            }
            xRot = -(float) Math.acos(Math.max(-1f, Math.min(1f, goal.y)));
            yRot = (float) Math.atan2(-goal.x, -goal.z);
        }
        return PartPose.offsetAndRotation(shoulder.x(), shoulder.y(), shoulder.z(), xRot, yRot, 0f);
    }

    private static float asin(float v) {
        return (float) Math.asin(Math.max(-1f, Math.min(1f, v)));
    }

    private static float sin(float radians) {
        return (float) Math.sin(radians);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static float rad(float degrees) {
        return (float) Math.toRadians(degrees);
    }
}
