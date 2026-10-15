package org.hurtorius.music.client.anim;

import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldExtractionContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.phys.Vec3;
import org.hurtorius.music.client.state.PianoClientState;
import org.hurtorius.music.client.state.PianoRuntime;
import org.hurtorius.music.piano.Pedal;
import org.hurtorius.music.piano.PianoLayout;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Poses every seated performer: keeps a {@link HandRig} per player, and when a player's render state
 * is extracted, works out their {@link ArmPose} from the keys they play and attaches it to the state.
 * The player model applies it after its own animation. Client thread only.
 */
public final class PerformerPoses {
    /** The pose of a performer, attached to their {@link AvatarRenderState}. */
    public static final RenderStateDataKey<ArmPose> POSE = RenderStateDataKey.create(() -> "music:performer_pose");
    /** Where a performer's hands are on the keys, attached next to {@link #POSE}. */
    public static final RenderStateDataKey<Hands> HANDS = RenderStateDataKey.create(() -> "music:performer_hands");
    /**
     * Set on the local performer while a piano camera looks down from just above their head: the head
     * is left out and the arms are drawn bent by {@link FirstPersonArms} instead of the model's own.
     */
    private static final RenderStateDataKey<Boolean> OWN_VIEW = RenderStateDataKey.create(() -> "music:performer_own_view");
    /** Rigs of performers not drawn for this long are dropped. */
    private static final long FORGET_NANOS = 10_000_000_000L;
    private static final Map<Integer, Performer> PERFORMERS = new HashMap<>();

    private PerformerPoses() {}

    /**
     * A performer's hands this frame, in the piano's model space: the middle of each hand's end as
     * {@link HandRig} places it (where the arms drawn with elbows put the hands), how far each hand's
     * fingers are down with the keys it holds (see {@link HandRig#leftPress}), where the player
     * model's rigid arms reach for them (see {@link HandRig#armTargets}), and how far the pose has
     * blended in since sitting down.
     */
    public record Hands(PianoSeats.Seat seat, Vector3fc left, Vector3fc right, float leftPress, float rightPress, Vector3fc leftArm,
                        Vector3fc rightArm, float engage) {}

    private static final class Performer {
        final int seatId;
        final BlockPos controller;
        final HandRig rig;
        final ArmSolver.Legs legs = new ArmSolver.Legs();
        /** The parts of the footing that stay while the performer sits still (see {@link #footing}); null until first drawn. */
        @Nullable Ground ground;
        long seen;

        Performer(PianoSeats.Seat seat, long now) {
            this.seatId = seat.seatId();
            this.controller = seat.controller();
            this.rig = new HandRig(KeyboardSpace.get(), now);
            this.seen = now;
        }
    }

    /** From {@code AvatarRenderer#extractRenderState}: attaches the pose when the avatar is seated at a piano. */
    public static void extract(Avatar avatar, AvatarRenderState state) {
        PianoSeats.Seat seat = PianoSeats.of(avatar);
        if (seat == null) return;
        long now = System.nanoTime();
        int id = avatar.getId();
        Performer performer = PERFORMERS.get(id);
        if (performer == null || performer.seatId != seat.seatId() || !performer.controller.equals(seat.controller())) {
            performer = new Performer(seat, now);
            PERFORMERS.put(id, performer);
        }
        performer.seen = now;

        PianoRuntime runtime = PianoClientState.find(seat.controller());
        BitSet held;
        List<PianoRuntime.Press> recent;
        if (runtime == null) {
            held = new BitSet();
            recent = List.of();
        } else {
            runtime.update(now);
            held = runtime.heldBy(id);
            recent = runtime.recentPresses(id, now - HandRig.RECENT_NANOS);
        }
        HandRig rig = performer.rig;
        rig.update(held, recent, now);
        float sustain = runtime == null ? 0f : runtime.pedalDepth(Pedal.SUSTAIN, now);

        // The model's own arms are rigid: they reach for the hands as far as they span.
        Vector3f leftArm = new Vector3f(), rightArm = new Vector3f();
        rig.armTargets(leftArm, rightArm);
        state.setData(HANDS, new Hands(seat, rig.left(new Vector3f()), rig.right(new Vector3f()), rig.leftPress(), rig.rightPress(), leftArm, rightArm,
            rig.engage()));
        Vector3f left = toPlayerModel(seat, leftArm, state);
        Vector3f right = toPlayerModel(seat, rightArm, state);
        state.setData(POSE, ArmSolver.solve(left, right, footing(performer, seat, state, sustain), state.skin.model() == PlayerModelType.SLIM, rig.engage(),
            performer.legs));
    }

    /**
     * Where this performer's legs come down over the bench and to the floor it stands on, in their own
     * model space, how much larger than the bare leg what is drawn on their legs is (boots, else
     * leggings, else the skin's trousers layer), the sustain pedal's top, {@code sustain} down (0 up
     * .. 1 down) and at rest, and the boxes the parts of the pedal lyre fill. What stays while the
     * performer sits still is worked out again only when their body moves, grows or turns (see
     * {@link Ground}), the pedal's top only while it moves, and while nothing changes the footing is
     * the same one as before, which keeps the legs placed for it (see {@link ArmSolver.Legs}).
     */
    private static ArmSolver.Footing footing(Performer performer, PianoSeats.Seat seat, AvatarRenderState state, float sustain) {
        if (performer.ground == null || !performer.ground.isFor(state)) performer.ground = new Ground(seat, state);
        float growth = HumanoidArmorLayer.shouldRender(state.feetEquipment, EquipmentSlot.FEET) ? ArmSolver.BOOTS
            : HumanoidArmorLayer.shouldRender(state.legsEquipment, EquipmentSlot.LEGS) ? ArmSolver.LEGGINGS
            : state.showLeftPants || state.showRightPants ? ArmSolver.TROUSERS : 0f;
        return performer.ground.footing(seat, state, growth, sustain);
    }

    /**
     * The parts of a performer's footing (see {@link #footing}) that stay while they sit still, in their
     * model space for the place, size and turn of the body they were worked out at: the floor, the
     * bench's edge, the sustain pedal at rest and the pedal lyre; and the footing last made from them,
     * with the sustain pedal as deep as it was then.
     */
    private static final class Ground {
        private final double x, y, z;
        private final float scale, bodyRot;
        private final float floor;
        private final Vector3f edge;
        private final ArmSolver.PedalTop pedalAtRest;
        private final List<float[]> lyre;
        private ArmSolver.@Nullable Footing last;
        private float lastSustain;

        Ground(PianoSeats.Seat seat, AvatarRenderState state) {
            x = state.x;
            y = state.y;
            z = state.z;
            scale = state.scale;
            bodyRot = state.bodyRot;
            floor = ArmSolver.fromWorld(0.0, seat.controller().getY() - state.y, 0.0, state.scale, state.bodyRot).y;
            edge = toPlayerModel(seat, new Vector3f(PianoLayout.BENCH_EDGE), state);
            pedalAtRest = pedalTop(seat, state, 0f);
            lyre = lyre(seat, state);
        }

        /** True when these were worked out for a body where {@code state}'s is, as large and turned as far. */
        boolean isFor(AvatarRenderState state) {
            return state.x == x && state.y == y && state.z == z && state.scale == scale && state.bodyRot == bodyRot;
        }

        /** The footing with {@code growth} on the legs and the sustain pedal {@code sustain} down (0 up .. 1 down). */
        ArmSolver.Footing footing(PianoSeats.Seat seat, AvatarRenderState state, float growth, float sustain) {
            if (last != null && last.growth() == growth && lastSustain == sustain) return last;
            ArmSolver.PedalTop pedal = sustain == 0f ? pedalAtRest : last != null && lastSustain == sustain ? last.pedal() : pedalTop(seat, state, sustain);
            last = new ArmSolver.Footing(floor, edge.y, edge.z, growth, pedal, pedalAtRest, lyre);
            lastSustain = sustain;
            return last;
        }
    }

    /** The sustain pedal's top at {@code depth} in this performer's model space. */
    private static ArmSolver.PedalTop pedalTop(PianoSeats.Seat seat, AvatarRenderState state, float depth) {
        Vector3f toe = new Vector3f(), back = new Vector3f();
        PedalSpace.get().sustainTop(depth, toe, back);
        float[] room = PedalSpace.get().room();
        return new ArmSolver.PedalTop(toPlayerModel(seat, toe, state), toPlayerModel(seat, back, state),
            across(seat, state, room[0], toe), across(seat, state, room[1], toe), toPlayerModel(seat, PedalSpace.get().box(), state));
    }

    /** Where {@code x} across the piano (model X) is across this performer (their model X), at the height and depth of {@code at}; infinite stays infinite. */
    private static float across(PianoSeats.Seat seat, AvatarRenderState state, float x, Vector3f at) {
        return Float.isInfinite(x) ? x : toPlayerModel(seat, new Vector3f(x, at.y, at.z), state).x;
    }

    /** The boxes the parts of the pedal lyre and its other pedals fill, in this performer's model space. */
    private static List<float[]> lyre(PianoSeats.Seat seat, AvatarRenderState state) {
        List<float[]> boxes = new ArrayList<>();
        for (float[] box : PedalSpace.get().lyre()) boxes.add(toPlayerModel(seat, box, state));
        return boxes;
    }

    /** A box {min x, y, z, max x, y, z} in the piano's model space as one in this performer's (turned square with it, it stays a box along its axes). */
    private static float[] toPlayerModel(PianoSeats.Seat seat, float[] box, AvatarRenderState state) {
        float[] out = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        for (int c = 0; c < 8; c++) {
            Vector3f corner = toPlayerModel(seat, new Vector3f(box[(c & 1) * 3], box[1 + (c >> 1 & 1) * 3], box[2 + (c >> 2 & 1) * 3]), state);
            for (int a = 0; a < 3; a++) {
                out[a] = Math.min(out[a], corner.get(a));
                out[a + 3] = Math.max(out[a + 3], corner.get(a));
            }
        }
        return out;
    }

    /**
     * After the world's render states are extracted, while a piano camera looks down from just above
     * the local performer's head: leaves their head out and marks their arms to be drawn bent instead
     * (see {@link FirstPersonArms}). Only the world's own states are touched, so the player keeps
     * head and arms in the inventory preview.
     */
    static void prepareOwnView(WorldExtractionContext context) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !PianoCamera.hidesPerformerHead()) return;
        AvatarRenderState state = localPerformer(context.worldState().entityRenderStates, mc.player.getId());
        if (state == null) return;
        state.setData(OWN_VIEW, Boolean.TRUE);
        // Whatever is worn on the head would hang in front of the camera too.
        state.headItem.clear();
        state.heldOnHead.clear();
        state.wornHeadType = null;
        state.wornHeadProfile = null;
        state.showExtraEars = false;
        // Held items hang from the model's own arms, which are not drawn.
        state.rightHandItemState.clear();
        state.leftHandItemState.clear();
    }

    /** The posed render state of the performer with entity id {@code id} among {@code states}, if any. */
    static @Nullable AvatarRenderState localPerformer(List<EntityRenderState> states, int id) {
        for (EntityRenderState entity : states) {
            if (entity instanceof AvatarRenderState state && state.id == id && state.getData(POSE) != null) return state;
        }
        return null;
    }

    /**
     * From {@code PlayerModel#setupAnim}: applies the attached pose, if any. Armour and cape models are
     * player models too, so they lean and reach with the body.
     */
    public static void apply(HumanoidModel<?> model, AvatarRenderState state) {
        ArmPose pose = state.getData(POSE);
        if (pose != null) pose.apply(model);
    }

    /**
     * True when the model's head and arms must not be drawn: the camera is where the head would be,
     * and the arms are drawn bent instead.
     */
    public static boolean ownView(AvatarRenderState state) {
        return state.getDataOrDefault(OWN_VIEW, Boolean.FALSE);
    }

    /** A point in the piano's model space, in the player model's space (pixels, Y down, facing -Z). */
    private static Vector3f toPlayerModel(PianoSeats.Seat seat, Vector3f pianoPoint, AvatarRenderState state) {
        Vec3 world = PianoLayout.modelToWorld(seat.controller(), seat.facing(), pianoPoint);
        return ArmSolver.fromWorld(world.x - state.x, world.y - state.y, world.z - state.z, state.scale, state.bodyRot);
    }

    /** Forgets performers that have not been drawn for a while. Call every client tick. */
    static void tick(Minecraft mc) {
        long now = System.nanoTime();
        PERFORMERS.values().removeIf(p -> now - p.seen > FORGET_NANOS);
    }

    static void clear() {
        PERFORMERS.clear();
    }
}
