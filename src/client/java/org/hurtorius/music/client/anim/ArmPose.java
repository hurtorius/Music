package org.hurtorius.music.client.anim;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.util.Mth;

/**
 * A seated performer's pose in player model space (pixels, Y down, facing -Z): the torso's lean
 * about the hips, both arms aimed at their keys, how far the hips slid along the bench ({@code hipX},
 * toward the performer's left) and forward on it ({@code hipZ}, negative), and each leg (see
 * {@link Leg}). The legs slide along the bench with the hips and come down over its front edge, as a
 * pianist sitting on the front of the bench has the thighs over it. {@code weight} blends it over
 * whatever pose the model already has (0 = untouched, 1 = fully this pose), so it eases in after
 * sitting down. However far the player looks down at the keys, the drawn head tips down no more than
 * {@link #MAX_HEAD_PITCH}, as a pianist's does: further, the performer reads as slumped over the keys
 * with the face in the music desk. The legs come down toward the pedals, the left foot on the floor
 * and the right on the sustain pedal (see {@code ArmSolver.leftLeg} and {@code ArmSolver.rightLeg}),
 * instead of the game's riding pose, which holds them out level under the keybed with the feet in
 * mid-air; a leg on the floor keeps that pose's slight spread ({@link #SPREAD_YAW}, {@link #SPREAD_ROLL}).
 */
public record ArmPose(float weight, PartPose body, PartPose rightArm, PartPose leftArm, float hipX, float hipZ, Leg rightLeg, Leg leftLeg) {
    /** Furthest a seated performer's head tips down, in radians (15 degrees), whatever their view does. */
    public static final float MAX_HEAD_PITCH = (float) Math.toRadians(15);
    /**
     * The spread the game's riding pose gives the legs (see {@code HumanoidModel#setupAnim}), which the
     * seated legs keep: each turned out by a tenth of a half turn and tipped out a little, the right leg
     * by these, the left by as much the other way.
     */
    public static final float SPREAD_YAW = (float) (Math.PI / 10), SPREAD_ROLL = 0.07853982f;

    /**
     * One seated leg: where it hangs from ({@code z}, negative: forward of the hips, pixels) and how it
     * is turned, as {@code ModelPart} rotations: swung forward ({@code xRot}, negative), turned
     * ({@code yRot}) and tipped ({@code zRot}).
     */
    public record Leg(float z, float xRot, float yRot, float zRot) {}

    /**
     * Applies the pose to a humanoid model after its own animation. The head and everything parented
     * to the body (jacket, cape) follow the body; arms and legs are children of the root, so the arms
     * are placed directly at the leaned shoulders and the legs over the bench's front edge.
     */
    public void apply(HumanoidModel<?> model) {
        float w = Mth.clamp(weight, 0f, 1f);
        if (w <= 0f) return;
        float headDx = model.head.x - model.body.x, headDy = model.head.y - model.body.y, headDz = model.head.z - model.body.z;
        blend(model.body, body, w);
        model.head.x = model.body.x + headDx;
        model.head.y = model.body.y + headDy;
        model.head.z = model.body.z + headDz;
        model.head.xRot = Mth.lerp(w, model.head.xRot, Math.min(model.head.xRot, MAX_HEAD_PITCH));
        blend(model.rightArm, rightArm, w);
        blend(model.leftArm, leftArm, w);
        place(model.rightLeg, rightLeg, w);
        place(model.leftLeg, leftLeg, w);
    }

    private void place(ModelPart part, Leg leg, float w) {
        part.x += w * hipX;
        part.z += w * leg.z();
        part.xRot = Mth.rotLerpRad(w, part.xRot, leg.xRot());
        part.yRot = Mth.rotLerpRad(w, part.yRot, leg.yRot());
        part.zRot = Mth.rotLerpRad(w, part.zRot, leg.zRot());
    }

    private static void blend(ModelPart part, PartPose pose, float w) {
        part.x = Mth.lerp(w, part.x, pose.x());
        part.y = Mth.lerp(w, part.y, pose.y());
        part.z = Mth.lerp(w, part.z, pose.z());
        part.xRot = Mth.rotLerpRad(w, part.xRot, pose.xRot());
        part.yRot = Mth.rotLerpRad(w, part.yRot, pose.yRot());
        part.zRot = Mth.rotLerpRad(w, part.zRot, pose.zRot());
    }
}
