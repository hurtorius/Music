package org.hurtorius.music.client.anim;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.phys.Vec3;
import org.hurtorius.music.client.ClientPrefs;
import org.hurtorius.music.piano.PianoLayout;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * The local performer's own arms as they see them, drawn in the world on the keys (see
 * {@link SeatedArms} for their shape) and wearing the player's skin:
 * <ul>
 *   <li>in first person, the arms bent at the elbow and the wrist, the hands lying on the keys,
 *   instead of the game's single floating hand: the upper arms run up out of the picture toward
 *   points beside and behind the camera, so wherever the performer looks the arms never end in
 *   mid-air;</li>
 *   <li>under the keys and top cameras, which look down from just above the head, the whole arms
 *   bent at the elbow and the wrist from the leaning body's shoulders, instead of the model's rigid
 *   arms.</li>
 * </ul>
 * Near either end of the keyboard the hands stay on it as the camera drawing them sees it.
 * The hands follow the same {@link HandRig} as the third-person body, so they move to the very keys
 * being played, and they are depth tested, so the piano hides them where it should. Everyone else
 * sees the performer's ordinary third-person body.
 */
public final class FirstPersonArms {
    private FirstPersonArms() {}

    /**
     * True when the game's first-person hand must not be drawn for this player: they sit at a piano
     * and either their hands are drawn on the keys or a piano camera shows their whole body.
     */
    public static boolean hidesVanillaHands(LocalPlayer player) {
        if (PianoSeats.of(player) == null) return false;
        return ClientPrefs.get().firstPersonHands || PianoCamera.mode() != CameraMode.PLAYER;
    }

    static void register() {
        WorldRenderEvents.BEFORE_ENTITIES.register(FirstPersonArms::submit);
    }

    private static boolean firstPerson(Minecraft mc, LocalPlayer player) {
        return mc.getCameraEntity() == player
            && mc.options.getCameraType().isFirstPerson() && !mc.gameRenderer.getMainCamera().isDetached()
            && ClientPrefs.get().firstPersonHands && !player.isInvisible() && !player.isSpectator()
            && PianoSeats.of(player) != null;
    }

    private static void submit(WorldRenderContext context) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;
        if (firstPerson(mc, player)) {
            AvatarRenderer<AbstractClientPlayer> renderer = mc.getEntityRenderDispatcher().getPlayerRenderer(player);
            // The same extraction the body gets in third person, which moves the hands and attaches them.
            AvatarRenderState state = renderer.createRenderState(player, mc.gameRenderer.getMainCamera().getPartialTickTime());
            submitArms(context, state, false);
        } else if (PianoCamera.hidesPerformerHead() && !player.isInvisible()) {
            // The world drew this body without its arms (see PerformerPoses.prepareOwnView).
            AvatarRenderState state = PerformerPoses.localPerformer(context.worldState().entityRenderStates, player.getId());
            if (state != null && PerformerPoses.ownView(state)) submitArms(context, state, true);
        }
    }

    /**
     * Both arms of a posed performer, bent at the elbow and the wrist: hanging from the posed body's
     * shoulders ({@code whole}, under the keys and top cameras) or, in first person, running up out of view.
     */
    private static void submitArms(WorldRenderContext context, AvatarRenderState state, boolean whole) {
        PerformerPoses.Hands hands = state.getData(PerformerPoses.HANDS);
        ArmPose pose = state.getData(PerformerPoses.POSE);
        if (hands == null || pose == null) return;
        boolean slim = state.skin.model() == PlayerModelType.SLIM;
        KeyboardSpace keys = KeyboardSpace.get();
        SkinArmMesh mesh = new SkinArmMesh(slim);
        BlockPos controller = hands.seat().controller();
        Matrix4f modelToBlock = PianoLayout.modelToBlock(hands.seat().facing(), new Matrix4f());
        Vec3 camera = context.worldState().cameraRenderState.pos;
        Vector3f eye = PianoLayout.worldToModel(controller, hands.seat().facing(), camera);
        SeatedArms.Arm left, right;
        if (whole) {
            SeatedArms.Body body = new SeatedArms.Body(pose, state.x - controller.getX(), state.y - controller.getY(), state.z - controller.getZ(),
                state.scale, state.bodyRot, hands.seat().facing(), slim);
            left = body.arm(keys, true, hands.left(), hands.leftPress(), eye, hands.engage());
            right = body.arm(keys, false, hands.right(), hands.rightPress(), eye, hands.engage());
            mesh.upperArm(body.upperArm(true, left.forearm()), true, state.showLeftSleeve);
            mesh.upperArm(body.upperArm(false, right.forearm()), false, state.showRightSleeve);
        } else {
            left = SeatedArms.leftArm(keys, hands.left(), hands.leftPress(), eye, slim, hands.engage());
            right = SeatedArms.rightArm(keys, hands.right(), hands.rightPress(), eye, slim, hands.engage());
            // The camera's own axes, turned into the piano's model space (its transform only turns and scales).
            Matrix4f toModel = new Matrix4f(modelToBlock).invert();
            Camera view = Minecraft.getInstance().gameRenderer.getMainCamera();
            Vector3f forward = toModel.transformDirection(new Vector3f(view.forwardVector())).normalize();
            Vector3f up = toModel.transformDirection(new Vector3f(view.upVector())).normalize();
            mesh.upperArm(SeatedArms.firstPersonUpperArm(eye, forward, up, true, left.forearm()), true, state.showLeftSleeve);
            mesh.upperArm(SeatedArms.firstPersonUpperArm(eye, forward, up, false, right.forearm()), false, state.showRightSleeve);
        }
        mesh.forearm(left.forearm(), true, state.showLeftSleeve);
        mesh.forearm(right.forearm(), false, state.showRightSleeve);
        mesh.hand(left.hand(), true, state.showLeftSleeve);
        mesh.hand(right.hand(), false, state.showRightSleeve);
        float[] quads = mesh.quads();
        int light = state.lightCoords;

        PoseStack poseStack = context.matrices();
        poseStack.pushPose();
        poseStack.translate(controller.getX() - camera.x, controller.getY() - camera.y, controller.getZ() - camera.z);
        poseStack.mulPose(modelToBlock);
        context.commandQueue().submitCustomGeometry(poseStack, RenderTypes.entityTranslucent(state.skin.body().texturePath()),
            (at, out) -> SkinArmMesh.emit(at, out, quads, light));
        poseStack.popPose();
    }
}
