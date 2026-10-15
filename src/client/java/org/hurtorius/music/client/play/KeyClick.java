package org.hurtorius.music.client.play;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.hurtorius.music.client.ClientPrefs;
import org.hurtorius.music.client.WorldExit;
import org.hurtorius.music.client.anim.CameraMode;
import org.hurtorius.music.client.anim.PianoCamera;
import org.hurtorius.music.piano.GrandPianoBlock;
import org.hurtorius.music.piano.GrandPianoBlockEntity;
import org.hurtorius.music.piano.PianoLayout;
import org.jspecify.annotations.Nullable;

/**
 * A left click by the player seated at a piano, on that piano, with its screen closed: it never
 * breaks it (the server holds to that too, see {@code PlayerMixin}). In the game's own first-person
 * view, where the crosshair shows what it is on, a click on a key plays that key for as long as the
 * button is held, at the velocity of the computer keys, and a click anywhere else on the piano says
 * that breaking it comes after standing up. In a view without the crosshair (the game's third person,
 * or a piano camera), where a click is meant for the keys in sight, the player is told how to click
 * them. Client thread.
 */
public final class KeyClick {
    /** How far a click reaches from the camera, in blocks: beyond the far end of the keyboard from the bench. */
    private static final double REACH = 4;
    /** How often the same word is said again at most, while the button is held or clicked again. */
    private static final long TOLD_NANOS = 3_000_000_000L;
    /** The key the held mouse button plays, or -1. */
    private static int held = -1;
    /** The word last said, and when. */
    private static @Nullable String told;
    private static long toldAt;

    private KeyClick() {}

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (held >= 0 && (!mc.options.keyAttack.isDown() || mc.screen != null || !Performer.isActive())) letGo();
        });
        WorldExit.register(WorldExit.Step.FORGET, () -> {
            held = -1;
            told = null;
        });
    }

    /**
     * From the start and the continuation of a block break: true when it is the seated player's click
     * on their own piano, which then plays a key or says why it does not, and breaks nothing.
     */
    public static boolean attack(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || !GrandPianoBlock.isSeatedAt(mc.player, mc.level, pos)) return false;
        if (held >= 0) return true;
        // A piano camera stands in for the game's view whichever person that is, so it is named first.
        if (PianoCamera.active()) {
            tell(mc, "music.message.keys_player_camera", CameraMode.PLAYER.displayName());
            return true;
        }
        if (!mc.options.getCameraType().isFirstPerson()) {
            tell(mc, "music.message.keys_first_person", mc.options.keyTogglePerspective.getTranslatedKeyMessage());
            return true;
        }
        BlockState state = mc.level.getBlockState(pos);
        BlockPos controller = GrandPianoBlock.controllerOf(pos, state);
        int key = controller.equals(Performer.piano()) ? keyUnderCrosshair(mc, controller, state) : -1;
        if (key < 0) {
            tell(mc, "music.message.stand_up_to_break");
        } else if (!Performer.canPlay()) {
            // Seated, but no longer allowed to play it: the owner made it theirs alone meanwhile.
            String owner = mc.level.getBlockEntity(controller) instanceof GrandPianoBlockEntity piano ? piano.ownerName() : "";
            tell(mc, "music.message.owner_only", owner);
        } else {
            Performer.noteOn(key, ClientPrefs.get().keyVelocity);
            held = key;
        }
        return true;
    }

    /** The key of the piano at {@code controller} the crosshair is on, or -1. */
    private static int keyUnderCrosshair(Minecraft mc, BlockPos controller, BlockState state) {
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 look = new Vec3(camera.forwardVector().x(), camera.forwardVector().y(), camera.forwardVector().z());
        return PianoLayout.keyAt(controller, state.getValue(GrandPianoBlock.FACING), camera.position(), look.scale(REACH));
    }

    /** Tells the player {@code key} over the hotbar, unless it was the last word said, a moment ago. */
    private static void tell(Minecraft mc, String key, Object... args) {
        long now = System.nanoTime();
        if (key.equals(told) && now - toldAt < TOLD_NANOS) return;
        told = key;
        toldAt = now;
        mc.player.displayClientMessage(Component.translatable(key, args), true);
    }

    private static void letGo() {
        Performer.noteOff(held);
        held = -1;
    }
}
