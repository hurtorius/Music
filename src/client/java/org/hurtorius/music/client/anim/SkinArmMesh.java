package org.hurtorius.music.client.anim;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.Arrays;

/**
 * {@link SeatedArms} segments as quads textured with a player skin, the way the player model's arm
 * boxes are: the same skin regions, faces and texel orientation, with the arm's 12 rows shared out
 * between the upper arm (the top {@link #UPPER_ROWS}, usually the sleeve), the forearm (up to
 * {@link #WRIST_ROW}) and the hand (the last three). Each part gets its sleeve layer too, standing a
 * quarter pixel off as the player model's, except at the bends, where the upper arm meets the forearm
 * and the forearm the hand: there the sleeves stop flush with the arm and have no ends of their own,
 * which would stand out of the other part as a cuff of sleeve colour. The open ends show the arm's
 * top, the bottom of the hand, or the arm's cross section at the cut.
 */
final class SkinArmMesh {
    /** Skin rows on the upper arm. */
    static final int UPPER_ROWS = 4;
    /** The row where the forearm ends and the hand begins: the hand takes the rows from here to the end. */
    static final int WRIST_ROW = 9;
    private static final int ARM_ROWS = 12, DEPTH = 4;
    private static final float SKIN = 64f;
    private static final float SLEEVE = 0.25f;
    private static final int WHITE = 0xFFFFFFFF;
    /** Floats per vertex: x, y, z, u, v, normal x, y, z. */
    private static final int STRIDE = 8;
    private static final int BOX_FLOATS = 6 * 4 * STRIDE;

    private final int width;
    private float[] quads = new float[6 * 2 * BOX_FLOATS];
    private int size;

    SkinArmMesh(boolean slim) {
        this.width = slim ? 3 : 4;
    }

    /** Where an arm's skin region and its sleeve's start: right arm (40, 16) and (40, 32), left (32, 48) and (48, 48). */
    private static int u(boolean left, boolean sleeve) {
        return left ? (sleeve ? 48 : 32) : 40;
    }

    private static int v(boolean left, boolean sleeve) {
        return left ? 48 : (sleeve ? 32 : 16);
    }

    void upperArm(SeatedArms.Segment arm, boolean left, boolean sleeve) {
        add(arm, left, false, 0, UPPER_ROWS, true, false);
        if (sleeve) add(arm, left, true, 0, UPPER_ROWS, true, false);
    }

    void forearm(SeatedArms.Segment arm, boolean left, boolean sleeve) {
        add(arm, left, false, UPPER_ROWS, WRIST_ROW, false, false);
        if (sleeve) add(arm, left, true, UPPER_ROWS, WRIST_ROW, false, false);
    }

    void hand(SeatedArms.Segment hand, boolean left, boolean sleeve) {
        add(hand, left, false, WRIST_ROW, ARM_ROWS, false, true);
        if (sleeve) add(hand, left, true, WRIST_ROW, ARM_ROWS, false, true);
    }

    float[] quads() {
        return Arrays.copyOf(quads, size);
    }

    /**
     * One box over skin rows {@code [fromRow, toRow)} of the arm's sides. The start shows the arm's top
     * when {@code shoulder}, else the cross section at the first row; the end shows the bottom of the
     * hand when {@code hand}, else the cross section at the last row. An end that is neither is at the
     * bend: a sleeve stops there, flush with the arm and open.
     */
    private void add(SeatedArms.Segment arm, boolean left, boolean sleeve, int fromRow, int toRow, boolean shoulder, boolean hand) {
        if (size + BOX_FLOATS > quads.length) quads = Arrays.copyOf(quads, quads.length * 2);
        int u = u(left, sleeve), v = v(left, sleeve), w = width, d = DEPTH;
        float grow = sleeve ? SLEEVE : 0f;
        float length = arm.length();
        float pixelAcross = arm.width() / w, pixelThick = arm.thickness() / d, pixelAlong = length / (toRow - fromRow);
        float halfW = arm.width() * 0.5f + grow * pixelAcross, halfT = arm.thickness() * 0.5f + grow * pixelThick;
        boolean flushStart = sleeve && !shoulder, flushEnd = sleeve && !hand;
        float y0 = flushStart ? 0f : -grow * pixelAlong, y1 = length + (flushEnd ? 0f : grow * pixelAlong);
        // Arm space as the player model's: X across (the performer's left), Y along, Z from the arm's front to its back.
        Vector3fc origin = arm.start(), across = arm.across();
        Vector3f along = arm.along(), back = new Vector3f(arm.front()).negate();
        float top = v + d + fromRow, bottom = v + d + toRow;
        // Corners named as the player model's cube does: f/s = min/max X, g/t = min/max Y, h/u = min/max Z.
        // The end lies slanted by the segment's tip slant (a hand's end along the keys).
        Vector3f[] c = new Vector3f[8];
        for (int i = 0; i < 8; i++) {
            float x = (i & 1) == 0 ? -halfW : halfW, y = (i & 2) == 0 ? y0 : y1, z = (i & 4) == 0 ? -halfT : halfT;
            c[i] = new Vector3f(origin).fma(x, across).fma(y + ((i & 2) == 0 ? 0f : x * arm.tipSlant()), along).fma(z, back);
        }
        Vector3f fgh = c[0], sgh = c[1], fth = c[2], sth = c[3], fgu = c[4], sgu = c[5], ftu = c[6], stu = c[7];
        Vector3f minX = new Vector3f(across).negate(), maxX = new Vector3f(across);
        Vector3f minY = new Vector3f(along).negate(), maxY = new Vector3f(along).fma(-arm.tipSlant(), across).normalize();
        Vector3f minZ = new Vector3f(back).negate(), maxZ = new Vector3f(back);
        if (shoulder) face(minY, sgu, fgu, fgh, sgh, u + d, v, u + d + w, v + d);
        else if (!flushStart) face(minY, sgu, fgu, fgh, sgh, u + d, top, u + d + w, top + 1);
        if (hand) face(maxY, sth, fth, ftu, stu, u + d + w, v + d, u + d + 2 * w, v);
        else if (!flushEnd) face(maxY, sth, fth, ftu, stu, u + d, bottom - 1, u + d + w, bottom);
        face(minX, fgh, fgu, ftu, fth, u, top, u + d, bottom);
        face(minZ, sgh, fgh, fth, sth, u + d, top, u + d + w, bottom);
        face(maxX, sgu, sgh, sth, stu, u + d + w, top, u + 2 * d + w, bottom);
        face(maxZ, fgu, sgu, stu, ftu, u + 2 * d + w, top, u + 2 * d + 2 * w, bottom);
    }

    /** A quad with the player model's corner-to-texel order: (u2, v1), (u1, v1), (u1, v2), (u2, v2). */
    private void face(Vector3fc normal, Vector3fc a, Vector3fc b, Vector3fc c, Vector3fc d, float u1, float v1, float u2, float v2) {
        vertex(a, u2, v1, normal);
        vertex(b, u1, v1, normal);
        vertex(c, u1, v2, normal);
        vertex(d, u2, v2, normal);
    }

    private void vertex(Vector3fc p, float u, float v, Vector3fc n) {
        float[] q = quads;
        int at = size;
        q[at] = p.x();
        q[at + 1] = p.y();
        q[at + 2] = p.z();
        q[at + 3] = u / SKIN;
        q[at + 4] = v / SKIN;
        q[at + 5] = n.x();
        q[at + 6] = n.y();
        q[at + 7] = n.z();
        size = at + STRIDE;
    }

    /** Sends quads built by {@link #quads()} through {@code out}, placed by {@code pose}. */
    static void emit(PoseStack.Pose pose, VertexConsumer out, float[] quads, int light) {
        Matrix4f m = pose.pose();
        Matrix3f nm = pose.normal();
        Vector3f p = new Vector3f(), n = new Vector3f();
        for (int i = 0; i < quads.length; i += STRIDE) {
            m.transformPosition(quads[i], quads[i + 1], quads[i + 2], p);
            nm.transform(quads[i + 5], quads[i + 6], quads[i + 7], n).normalize();
            out.addVertex(p.x, p.y, p.z, WHITE, quads[i + 3], quads[i + 4], OverlayTexture.NO_OVERLAY, light, n.x, n.y, n.z);
        }
    }
}
