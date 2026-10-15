package org.hurtorius.music.client.anim;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.hurtorius.music.piano.PianoLayout;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * The local performer's own view from the bench in first person.
 *
 * <p>A grand piano is wide next to a seated performer: its keyboard spans more than two blocks just
 * beyond their knees, close enough to play. The first-person camera sits a little above the seated
 * eye and draws back a few pixels ({@link #EYE_SHIFT}), just so far that at the default field of
 * view the keyboard fills most of a wide screen's width (about 85 %) with the music desk above it:
 * the instrument looks as large from the bench as it is. On a narrower screen, or a narrower field
 * of view, the outermost keys are a small turn of the head away. In a low room the camera moves only
 * as far as keeps it {@link #CLEARANCE} from the blocks around it, as the game's own third-person
 * camera does, so it never looks out of a ceiling it has moved into. When the player sits down,
 * their view is turned once to look over the desk at the keys, with the keyboard's front edge in the
 * lower part of the screen whatever the field of view. After that the mouse alone moves the view.</p>
 *
 * <p>Client thread only.</p>
 */
public final class SeatedView {
    /** Where the first-person camera sits from the upright seated eye, in the piano's model units: 2 pixels up, 3.5 back. */
    public static final Vector3fc EYE_SHIFT = new Vector3f(0f, 2f * PianoLayout.PIXEL, -3.5f * PianoLayout.PIXEL);
    /** Where the keyboard's front edge sits below the middle of the screen, as a share of the half height. */
    public static final float KEY_EDGE_BELOW_CENTRE = 0.55f;
    /** Steepest look-down the sit-down view takes, for very narrow fields of view. */
    public static final float MAX_PITCH = 75f;
    /** How far the moved camera keeps from any block, in blocks: twice the game's near plane, as its third-person camera keeps. */
    public static final double CLEARANCE = 0.1;
    private static final int NONE = Integer.MIN_VALUE;

    private static int seatId = NONE;
    private static long seatedAt;

    private SeatedView() {}

    /** {@link #EYE_SHIFT} as a world offset in blocks for a piano facing {@code facing}, once fully eased in. */
    public static Vec3 eyeShift(Direction facing) {
        Vector3f v = PianoLayout.modelToBlock(facing, new Matrix4f()).transformDirection(new Vector3f(EYE_SHIFT));
        return new Vec3(v.x, v.y, v.z);
    }

    /**
     * The Minecraft pitch (degrees, positive down) that puts the middle of the keyboard's front edge
     * {@link #KEY_EDGE_BELOW_CENTRE} of the half height below the middle of the screen, seen from
     * {@code eye} with a vertical field of view of {@code fovDegrees}.
     */
    public static float lookPitch(Vec3 eye, BlockPos controller, Direction facing, double fovDegrees) {
        KeyboardSpace keys = KeyboardSpace.get();
        int middle = HandAssigner.MIDDLE_C;
        Vec3 edge = PianoLayout.modelToWorld(controller, facing, 0f, keys.top(middle), keys.frontZ(middle));
        Vec3 d = edge.subtract(eye);
        double down = Math.toDegrees(Math.atan2(-d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        double fov = Math.max(1.0, Math.min(179.0, fovDegrees));
        double below = Math.toDegrees(Math.atan(KEY_EDGE_BELOW_CENTRE * Math.tan(Math.toRadians(fov * 0.5))));
        return (float) Math.max(0.0, Math.min(MAX_PITCH, down - below));
    }

    /**
     * From {@code Camera#setup} in first person: where the camera goes for {@code entity}, the
     * camera entity, given the game's own placement {@code camera}; null to keep it.
     */
    public static @Nullable Vec3 firstPersonEye(Entity entity, Vec3 camera) {
        if (entity != Minecraft.getInstance().player) return null;
        PianoSeats.Seat seat = PianoSeats.of(entity);
        if (seat == null || seat.seatId() != seatId) return null;
        float t = Math.min(1f, (System.nanoTime() - seatedAt) / (HandRig.ENGAGE_SECONDS * 1e9f));
        float weight = t * t * (3f - 2f * t);
        float scale = entity instanceof LivingEntity living ? living.getScale() : 1f;
        return camera.add(clear(entity, camera, eyeShift(seat.facing()).scale(weight * scale)));
    }

    /**
     * {@code shift} from {@code eye}, shortened where a block is in the way so the camera stays
     * {@link #CLEARANCE} from it: traced from the corners of a box that far around the eye, as the
     * game's own third-person camera is.
     */
    private static Vec3 clear(Entity entity, Vec3 eye, Vec3 shift) {
        double length = shift.length();
        if (length < 1e-6) return shift;
        double reach = length;
        for (int i = 0; i < 8; i++) {
            Vec3 corner = eye.add(((i & 1) * 2 - 1) * CLEARANCE, ((i >> 1 & 1) * 2 - 1) * CLEARANCE, ((i >> 2 & 1) * 2 - 1) * CLEARANCE);
            BlockHitResult hit = entity.level().clip(new ClipContext(corner, corner.add(shift), ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, entity));
            if (hit.getType() != HitResult.Type.MISS) reach = Math.min(reach, hit.getLocation().distanceTo(corner));
        }
        return shift.scale(reach / length);
    }

    /** Notices the local player sitting down and turns their view to the keys once. Call every client tick. */
    static void tick(Minecraft mc) {
        LocalPlayer player = mc.player;
        PianoSeats.Seat seat = player == null ? null : PianoSeats.of(player);
        if (seat == null) {
            seatId = NONE;
            return;
        }
        if (seat.seatId() == seatId) return;
        seatId = seat.seatId();
        seatedAt = System.nanoTime();
        Entity bench = Objects.requireNonNull(player.getVehicle(), "a seated player rides the bench");
        // Where the camera will be once eased in, from the bench itself (the rider may not have been moved onto it yet).
        Vec3 eye = bench.getPassengerRidingPosition(player).subtract(player.getVehicleAttachmentPoint(bench))
            .add(0.0, player.getEyeHeight(), 0.0).add(eyeShift(seat.facing()).scale(player.getScale()));
        float pitch = lookPitch(eye, seat.controller(), seat.facing(), mc.options.fov().get());
        player.setXRot(pitch);
        player.xRotO = pitch;
    }

    static void clear() {
        seatId = NONE;
    }
}
