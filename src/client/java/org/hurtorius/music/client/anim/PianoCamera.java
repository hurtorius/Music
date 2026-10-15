package org.hurtorius.music.client.anim;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.hurtorius.music.client.ClientPrefs;
import org.hurtorius.music.client.MusicKeys;
import org.hurtorius.music.piano.GrandPianoBlockEntity;
import org.hurtorius.music.piano.PianoGeometry;
import org.hurtorius.music.piano.PianoLayout;
import org.hurtorius.music.piano.PianoShapes;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The seated performer's camera. In {@link CameraMode#PLAYER} the game's own camera is used; the
 * other modes place the camera relative to the piano (visual only: aiming and the player's own view
 * direction are untouched) and show the performer's whole body. The choice is this player's own and
 * is kept in {@link ClientPrefs#camera}.
 *
 * <p>The piano screen changes it with {@link #setMode}; a key binding in the Music section of the
 * controls (unbound by default: nearly every key is a note on the piano screen) cycles it while the
 * player sits at a piano with the screen closed.
 * The game's own front view (see {@link #front}) swings around the performer, to the open side of the
 * raised lid first, when it would otherwise look at them through the case or the lid.</p>
 *
 * <p>Every camera here keeps clear of blocks as the game's own third-person camera does: a box
 * {@link #WALL_MARGIN} out on every side of it stays out of them (see {@link #stop}). Under a low
 * ceiling a piano camera first comes down below it, where it is across, and the front view looks down
 * no further than keeps it under the ceiling, so in a room as low as the piano they still frame the
 * performer at the instrument from where they would. The top camera looks straight down only while
 * it still frames the keyboard (see {@link #framedTop}): brought lower, it would see just the middle
 * of the keyboard, and drawn back toward the performer to see more, their back would hide the middle.
 * There (in a room as low as the piano) the player is shown the view over the keys instead and told
 * so, with the clear height the view from above needs (see {@link #topTooLow}). Only a camera a wall
 * stops is pulled in toward the performer's head.</p>
 */
public final class PianoCamera {
    /** Where the performer's head is, in piano model units: 17 pixels over the seat. Cameras are pulled in toward it when blocked. */
    private static final Vector3fc HEAD = new Vector3f(PianoLayout.SEAT).add(0f, 17f * PianoLayout.PIXEL, 0f);
    /**
     * Above the performer's shoulders (23 pixels over the seat, 4.5 toward the keys), looking down
     * over their hands; their head is not drawn in this view, and their arms are drawn bent so they
     * lie below the keys rather than across them.
     */
    private static final Vector3fc KEYS_EYE = new Vector3f(PianoLayout.SEAT).add(0f, 23f * PianoLayout.PIXEL, 4.5f * PianoLayout.PIXEL);
    /** The keys camera looks at the middle of the keyboard, level with the black keys' tops and 2.2 units behind the keybed's front. */
    private static final Vector3fc KEYS_LOOK = new Vector3f(0f, KeyboardSpace.get().highestTop(), 2.2f);
    /**
     * The concert view frames the instrument and the performer at it, so it is placed in the piano's
     * own units: from 19 units over the bench top, looking at 7.5 over it.
     */
    private static final Vector3fc SIDE_EYE = new Vector3f(-46f, PianoLayout.SEAT.y() + 19f, -10f);
    private static final Vector3fc SIDE_LOOK = new Vector3f(0f, PianoLayout.SEAT.y() + 7.5f, -2.5f);
    /**
     * Straight over the keys, 16 units over the black keys' tops: low enough that most of the raised
     * lid is above the view, the performer's shoulders at the bottom edge (their head is not drawn).
     */
    private static final Vector3fc TOP_EYE = new Vector3f(0f, KeyboardSpace.get().highestTop() + 16f, 1.5f);
    /** The keyboard with its cheeks, {min x, y, z, max x, y, z} in model units: the top camera keeps its top corners in the picture. */
    private static final float[] KEYBED = PianoGeometry.get().bounds(PianoGeometry.KEYBED_AND_CHEEKS);
    /** Steps of the search for the clear height the view from above needs: to well under a hundredth of a block. */
    private static final int HEADROOM_STEPS = 20;
    /** Share of the picture's half width and height left free around the keyboard the top camera frames. */
    private static final double FRAME_MARGIN = 0.02;
    /** How far a camera keeps from blocks on every side, in blocks (the game's own keeps {@link #GAME_MARGIN}). */
    private static final double WALL_MARGIN = 0.15;
    /** How far out the game's own third-person camera tries its line from (see {@code Camera#getMaxZoom}), in blocks. */
    private static final double GAME_MARGIN = 0.1;
    /**
     * Points of the seated performer the front view must see, in blocks from their eyes: up, across
     * (toward their right) and toward the keys, which the body leans to. The eyes, the top of the head,
     * both shoulders and the waist: the performer from the waist up.
     */
    private static final double[][] FRONT_SEEN = {{0, 0, 0.1}, {0.25, 0, 0.1}, {-0.45, -0.3, 0.05}, {-0.45, 0.3, 0.05}, {-0.85, 0, 0}};
    /**
     * How far down at the performer the front view looks when it swings around them, in degrees: what
     * the player looks, kept to this range, and under a ceiling no further than keeps it below it.
     */
    private static final float FRONT_LOW = 20f, FRONT_HIGH = 35f;
    /** Steps, in degrees, by which the front view swings around the performer, and the furthest it swings either way. */
    private static final float FRONT_SWING_STEP = 5f, FRONT_SWING = 120f;
    /** Steps, in degrees, by which the front view rises over the piano when no swing sees past it, and the highest it goes. */
    private static final float FRONT_STEP = 2f, FRONT_HIGHEST = 85f;

    /** A camera placement: world position and Minecraft yaw/pitch in degrees. */
    public record Shot(Vec3 position, float yaw, float pitch) {}

    /**
     * What the lines a camera is placed by meet: the block hit first on the way from one point to
     * another, as the game's {@code BlockGetter#clip} finds it, or a miss where nothing is in the way.
     */
    @FunctionalInterface
    public interface Sight {
        BlockHitResult clip(Vec3 from, Vec3 to);

        /** The blocks of {@code entity}'s level by their visual shapes, as the game's own third-person camera meets them. */
        static Sight of(Entity entity) {
            return (from, to) -> entity.level().clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, entity));
        }
    }

    /** How far a camera can go along a line (see {@link #stop}), and the face of the block that stopped it, or null when none did. */
    private record Stop(double distance, @Nullable Direction face) {}

    /** Whether the latest frame of the top camera showed the view over the keys instead (see {@link #topTooLow}); client thread. */
    private static boolean topFellBack;
    /** Whether the player has been told so since it last came to be (client thread). */
    private static boolean toldTopTooLow;

    private PianoCamera() {}

    /** The chosen mode (whether or not the player is seated). */
    public static CameraMode mode() {
        return CameraMode.byId(ClientPrefs.get().camera);
    }

    /** Chooses the camera mode and saves it. */
    public static void setMode(CameraMode mode) {
        ClientPrefs prefs = ClientPrefs.get();
        if (mode.id().equals(prefs.camera)) return;
        prefs.camera = mode.id();
        prefs.save();
    }

    /** Switches to the next mode and returns it. */
    public static CameraMode cycle() {
        CameraMode next = mode().next();
        setMode(next);
        return next;
    }

    /** True while the local player sits at a piano and a piano camera replaces their view. */
    public static boolean active() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.getCameraEntity() == mc.player && mode() != CameraMode.PLAYER && PianoSeats.of(mc.player) != null;
    }

    /**
     * True while a camera looks down from just above the local performer's head (keys and top): the
     * head, which would fill the view, is left out as in first person. The concert view keeps it.
     */
    public static boolean hidesPerformerHead() {
        CameraMode mode = mode();
        return (mode == CameraMode.KEYS || mode == CameraMode.TOP) && active();
    }

    /**
     * From {@code Camera#setup}: the placement for the camera entity, or null to keep the game's own.
     * The top camera, where a ceiling keeps it from framing the keyboard, gives the view over the keys
     * (see {@link #topTooLow}).
     */
    public static @Nullable Shot shot(Entity cameraEntity) {
        CameraMode mode = mode();
        topFellBack = false;
        if (mode == CameraMode.PLAYER || cameraEntity != Minecraft.getInstance().player) return null;
        PianoSeats.Seat seat = PianoSeats.of(cameraEntity);
        if (seat == null) return null;
        Minecraft mc = Minecraft.getInstance();
        Sight sight = Sight.of(cameraEntity);
        double fov = mc.options.fov().get(), aspect = aspect(mc);
        Shot shot = placed(sight, seat.controller(), seat.facing(), mode, fov, aspect);
        if (shot != null) return shot;
        topFellBack = true;
        return placed(sight, seat.controller(), seat.facing(), CameraMode.KEYS, fov, aspect);
    }

    /**
     * True when the latest frame of the top camera showed the view over the keys instead: under a
     * ceiling too low for it to frame the keyboard from above (see {@link #topHeadroom}). The player is
     * told so once each time it comes to be (see {@link #register}).
     */
    public static boolean topTooLow() {
        return topFellBack && mode() == CameraMode.TOP && active();
    }

    /**
     * A mode's camera for the performer at the piano at {@code controller}, kept clear of the blocks
     * {@code sight} meets. Null for the top camera where it is kept so low, or pulled in so far, that
     * it no longer frames the keyboard from above at a vertical field of view of {@code fov} degrees in
     * a picture {@code aspect} times as wide as high (see {@link #framedTop}).
     */
    public static @Nullable Shot placed(Sight sight, BlockPos controller, Direction facing, CameraMode mode, double fov, double aspect) {
        Shot shot = shot(mode, controller, facing);
        Vec3 head = PianoLayout.modelToWorld(controller, facing, HEAD.x(), HEAD.y(), HEAD.z());
        Vec3 to = shot.position();
        Stop stop = stop(sight, head, to);
        if (stop.face() == null) return shot;
        if (stop.face() == Direction.DOWN) {
            // A low ceiling: the camera comes down below it, where it is across, but no lower than the head.
            double under = head.add(to.subtract(head).normalize().scale(stop.distance())).y;
            to = new Vec3(to.x, Math.max(head.y, Math.min(to.y, under)), to.z);
            stop = stop(sight, head, to);
        }
        // Moved (under a ceiling, or pulled in toward the head by a wall), the camera aims again at what
        // the mode looks at: kept as it was, it would look past the keys at the performer's own back.
        Vec3 at = stop.face() == null ? to : head.add(to.subtract(head).normalize().scale(stop.distance()));
        // The top camera only where it still frames the keyboard from there, however it was moved: brought
        // down, its box ends on the ceiling's face, and whether the line checked again ends a hair short of
        // the ceiling or in it (the game finds it there) is rounding, which must not decide what is shown.
        return mode == CameraMode.TOP ? framedTop(controller, facing, at, fov, aspect) : aim(mode, controller, facing, at);
    }

    /**
     * The top camera moved to {@code at} (brought down under a ceiling, or pulled in toward the head by
     * a block in its way), looking straight down, while it still frames the keyboard at a vertical
     * field of view of {@code fov} degrees in a picture {@code aspect} times as wide as high: the
     * keyboard's cheeks {@link #FRAME_MARGIN} inside the picture's edges (see {@link #keyboardExtent}),
     * or as far in as the top camera has them where nothing is in its way (at a narrow field of view it
     * shows less than the whole keyboard there too). Null where it does not.
     */
    private static @Nullable Shot framedTop(BlockPos controller, Direction facing, Vec3 at, double fov, double aspect) {
        double goal = Math.max(1.0 - FRAME_MARGIN, keyboardExtent(shot(CameraMode.TOP, controller, facing), controller, facing, fov, aspect));
        Shot straight = aim(CameraMode.TOP, controller, facing, at);
        return keyboardExtent(straight, controller, facing, fov, aspect) <= goal ? straight : null;
    }

    /**
     * The clear height over the floor of the piano at {@code controller} the top camera needs to frame
     * the keyboard from above, in blocks, at a vertical field of view of {@code fov} degrees in a
     * picture {@code aspect} times as wide as high: the lowest it can come down and still frame it (see
     * {@link #framedTop}), and the room it keeps from the ceiling ({@link #WALL_MARGIN}).
     */
    public static double topHeadroom(BlockPos controller, Direction facing, double fov, double aspect) {
        Vec3 free = shot(CameraMode.TOP, controller, facing).position();
        double low = PianoLayout.modelToWorld(controller, facing, HEAD.x(), HEAD.y(), HEAD.z()).y, high = free.y;
        for (int i = 0; i < HEADROOM_STEPS; i++) {
            double mid = (low + high) * 0.5;
            if (framedTop(controller, facing, new Vec3(free.x, mid, free.z), fov, aspect) != null) high = mid;
            else low = mid;
        }
        return high + WALL_MARGIN - controller.getY();
    }

    /** The shape of the game's picture: its width over its height. */
    private static double aspect(Minecraft mc) {
        return (double) mc.getWindow().getWidth() / mc.getWindow().getHeight();
    }

    /**
     * How far out the keyboard's cheeks reach in {@code shot}, at a vertical field of view of
     * {@code fov} degrees in a picture {@code aspect} times as wide as high: of the top corners of the
     * keybed and its cheeks, the one furthest toward an edge of the picture, as a share of the way
     * from its middle to that edge (1 on the edge; infinite for a corner behind the camera).
     */
    private static double keyboardExtent(Shot shot, BlockPos controller, Direction facing, double fov, double aspect) {
        Vec3 forward = Vec3.directionFromRotation(shot.pitch(), shot.yaw()), up = Vec3.directionFromRotation(shot.pitch() - 90f, shot.yaw());
        Vec3 right = forward.cross(up);
        double tan = Math.tan(Math.toRadians(fov) * 0.5), furthest = 0.0;
        for (int c = 0; c < 4; c++) {
            Vec3 corner = PianoLayout.modelToWorld(controller, facing, KEYBED[(c & 1) * 3], KEYBED[4], KEYBED[2 + (c >> 1) * 3]).subtract(shot.position());
            double depth = corner.dot(forward);
            if (depth <= 0) return Double.POSITIVE_INFINITY;
            furthest = Math.max(furthest, Math.max(Math.abs(corner.dot(right) / depth) / (tan * aspect), Math.abs(corner.dot(up) / depth) / tan));
        }
        return furthest;
    }

    /** The unobstructed placement of a mode for a piano. {@link CameraMode#PLAYER} has none. */
    public static Shot shot(CameraMode mode, BlockPos controller, Direction facing) {
        return aim(mode, controller, facing, PianoLayout.modelToWorld(controller, facing, eye(mode)));
    }

    /** A mode's camera at {@code from}, turned to what that mode looks at. */
    public static Shot aim(CameraMode mode, BlockPos controller, Direction facing, Vec3 from) {
        return switch (mode) {
            case KEYS -> look(from, PianoLayout.modelToWorld(controller, facing, new Vector3f(KEYS_LOOK)));
            case SIDE -> look(from, PianoLayout.modelToWorld(controller, facing, new Vector3f(SIDE_LOOK)));
            case TOP -> new Shot(from, PianoLayout.performerYaw(facing), 90f);
            case PLAYER -> throw new IllegalArgumentException("The player camera is the game's own");
        };
    }

    /** Where a mode's camera is in the piano's model units when nothing is in its way. */
    private static Vector3f eye(CameraMode mode) {
        return switch (mode) {
            case KEYS -> new Vector3f(KEYS_EYE);
            case SIDE -> new Vector3f(SIDE_EYE);
            case TOP -> new Vector3f(TOP_EYE);
            case PLAYER -> throw new IllegalArgumentException("The player camera is the game's own");
        };
    }

    private static Shot look(Vec3 from, Vec3 target) {
        Vec3 d = target.subtract(from);
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        return new Shot(from, yaw, pitch);
    }

    /**
     * From {@code Camera#setup} in the game's front view (the second third-person view): the camera
     * for a performer seated at a piano, or null to keep the game's. The game puts it at its usual
     * distance in front of the eyes along the look, which at a piano is inside the case or under the
     * raised lid. Where the game's own camera cannot see the performer from the waist up past the
     * instrument (case, desk, and the lid with the room it swings through), this one keeps that
     * distance and looks down at them from {@link #FRONT_LOW} to {@link #FRONT_HIGH} degrees (under a
     * ceiling, no further than keeps it below the ceiling at that distance), swung around them as
     * little as sees them: to their right first, the side the lid opens to, then to their left. Only
     * where no swing sees past the piano does it rise over it. Like the game's own, it stops short of
     * any block in the way.
     */
    public static @Nullable Shot front(Entity entity, float partialTick) {
        if (mode() != CameraMode.PLAYER || entity != Minecraft.getInstance().player) return null;
        PianoSeats.Seat seat = PianoSeats.of(entity);
        if (seat == null || !(entity.level().getBlockEntity(seat.controller()) instanceof GrandPianoBlockEntity piano)) return null;
        boolean lidOpen = piano.settings().lidOpen();
        Vec3 eye = entity.getEyePosition(partialTick);
        float yaw = entity.getViewYRot(partialTick), pitch = entity.getViewXRot(partialTick);
        double scale = entity instanceof LivingEntity living ? living.getScale() : 1.0;
        double distance = entity instanceof LivingEntity living ? scale * living.getAttributeValue(Attributes.CAMERA_DISTANCE) : 4.0;
        List<Vector3f> seen = frontSeen(seat.controller(), seat.facing(), eye, scale);
        Sight sight = Sight.of(entity);
        if (sees(seat, seen, gameCamera(sight, eye, eye.add(Vec3.directionFromRotation(pitch, yaw).scale(distance))), lidOpen)) return null;
        float down = Mth.clamp(-pitch, FRONT_LOW, FRONT_HIGH);
        Stop up = stop(sight, eye, eye.add(0, distance, 0));
        if (up.face() != null) down = Math.min(down, (float) Math.toDegrees(Math.asin(Mth.clamp(up.distance() / distance, 0.0, 1.0))));
        for (float swing = 0f; swing <= FRONT_SWING; swing += FRONT_SWING_STEP) {
            for (float turn : swing == 0f ? new float[]{0f} : new float[]{swing, -swing}) {
                Vec3 at = stopShort(sight, eye, eye.add(Vec3.directionFromRotation(-down, yaw + turn).scale(distance)));
                if (sees(seat, seen, at, lidOpen)) return new Shot(at, yaw + turn + 180f, down);
            }
        }
        for (float rise = -down; rise >= -FRONT_HIGHEST; rise -= FRONT_STEP) {
            Vec3 at = stopShort(sight, eye, eye.add(Vec3.directionFromRotation(rise, yaw).scale(distance)));
            if (sees(seat, seen, at, lidOpen)) return new Shot(at, yaw + 180f, -rise);
        }
        return null;
    }

    /**
     * The points of a performer the front view must see (see {@link #FRONT_SEEN}), in the piano's
     * model space, for a performer of render scale {@code scale} whose eyes are at {@code eye}.
     */
    public static List<Vector3f> frontSeen(BlockPos controller, Direction facing, Vec3 eye, double scale) {
        Vec3 right = Vec3.atLowerCornerOf(PianoLayout.right(facing).getUnitVec3i());
        Vec3 toKeys = Vec3.atLowerCornerOf(PianoLayout.back(facing).getUnitVec3i());
        List<Vector3f> points = new ArrayList<>();
        for (double[] p : FRONT_SEEN) {
            Vec3 at = eye.add(right.scale(p[1] * scale)).add(toKeys.scale(p[2] * scale)).add(0, p[0] * scale, 0);
            points.add(PianoLayout.worldToModel(controller, facing, at));
        }
        return points;
    }

    /** True when the lines from every one of {@code seen} to a camera at {@code at} pass nothing the piano draws. */
    private static boolean sees(PianoSeats.Seat seat, List<Vector3f> seen, Vec3 at, boolean lidOpen) {
        Vector3f camera = PianoLayout.worldToModel(seat.controller(), seat.facing(), at);
        for (Vector3f point : seen) if (PianoShapes.clip(point, camera, lidOpen) < 1f) return false;
        return true;
    }

    /** The camera on the way from {@code eye} to {@code end}, stopped where it would come within {@link #WALL_MARGIN} of a block (see {@link #stop}). */
    private static Vec3 stopShort(Sight sight, Vec3 eye, Vec3 end) {
        Stop stop = stop(sight, eye, end);
        return stop.face() == null ? end : eye.add(end.subtract(eye).normalize().scale(stop.distance()));
    }

    /**
     * Where the game's own third-person camera stands on the line from {@code eye} to {@code end}, as
     * {@code Camera#getMaxZoom} finds it: the line followed from each corner of a box
     * {@link #GAME_MARGIN} out on every side, the camera as far from the eye as the nearest block any
     * of them meets is from it.
     */
    private static Vec3 gameCamera(Sight sight, Vec3 eye, Vec3 end) {
        double reach = eye.distanceTo(end);
        for (int i = 0; i < 8; i++) {
            Vec3 corner = new Vec3(((i & 1) * 2 - 1) * GAME_MARGIN, ((i >> 1 & 1) * 2 - 1) * GAME_MARGIN, ((i >> 2 & 1) * 2 - 1) * GAME_MARGIN);
            BlockHitResult hit = sight.clip(eye.add(corner), end.add(corner));
            if (hit.getType() != HitResult.Type.MISS) reach = Math.min(reach, hit.getLocation().distanceTo(eye));
        }
        return eye.add(end.subtract(eye).normalize().scale(reach));
    }

    /**
     * How far a camera can go along the line from {@code from} to {@code to}, as the game's own
     * third-person camera finds it: the line is followed from each corner of a box
     * {@link #WALL_MARGIN} out on every side, and the camera stops where the first of them meets a
     * block (with that block's face), so it keeps that far from walls, floors and ceilings alike.
     */
    private static Stop stop(Sight sight, Vec3 from, Vec3 to) {
        double distance = from.distanceTo(to);
        Direction face = null;
        for (int i = 0; i < 8; i++) {
            Vec3 corner = new Vec3(((i & 1) * 2 - 1) * WALL_MARGIN, ((i >> 1 & 1) * 2 - 1) * WALL_MARGIN, ((i >> 2 & 1) * 2 - 1) * WALL_MARGIN);
            Vec3 start = from.add(corner);
            BlockHitResult hit = sight.clip(start, to.add(corner));
            if (hit.getType() == HitResult.Type.MISS) continue;
            double reach = hit.getLocation().distanceTo(start);
            if (reach < distance) {
                distance = reach;
                face = hit.getDirection();
            }
        }
        return new Stop(distance, face);
    }

    static void register() {
        KeyMapping key = KeyBindingHelper.registerKeyBinding(
            new KeyMapping("key.music.camera", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), MusicKeys.CATEGORY));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (key.consumeClick()) {
                // Away from a piano the key means nothing, so it may share a key with something else.
                if (mc.player == null || PianoSeats.of(mc.player) == null) continue;
                CameraMode mode = cycle();
                if (mc.player != null) mc.player.displayClientMessage(Component.translatable("music.camera.now", mode.displayName()), true);
            }
            // Shown the view over the keys for the view from above: said once, with what it needs.
            boolean tooLow = topTooLow();
            PianoSeats.Seat seat = mc.player == null ? null : PianoSeats.of(mc.player);
            if (tooLow && !toldTopTooLow && seat != null) {
                double needs = topHeadroom(seat.controller(), seat.facing(), mc.options.fov().get(), aspect(mc));
                mc.player.displayClientMessage(Component.translatable("music.camera.top_too_low", CameraMode.TOP.displayName(),
                    String.format(Locale.ROOT, "%.1f", Math.ceil(needs * 10.0 - 1e-6) / 10.0), CameraMode.KEYS.displayName()), true);
            }
            toldTopTooLow = tooLow;
        });
        // The crosshair marks where the player's own view points, which a piano camera does not show.
        HudElementRegistry.replaceElement(VanillaHudElements.CROSSHAIR, crosshair -> (graphics, delta) -> {
            if (!active()) crosshair.render(graphics, delta);
        });
    }
}
