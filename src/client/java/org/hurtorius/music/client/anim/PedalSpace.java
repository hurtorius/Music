package org.hurtorius.music.client.anim;

import org.hurtorius.music.client.render.PianoPose;
import org.hurtorius.music.piano.Pedal;
import org.hurtorius.music.piano.PianoGeometry;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * The pedals as the performer's feet meet them, in the piano's model space (see {@code PianoLayout}):
 * the top of the sustain pedal, where the right foot rests (the middle of its top, at the end of its
 * toe and at its back, where it goes into the lyre that holds it, at any depth: it turns about its
 * pivot as the renderer turns it, {@link PianoPose#pedalTurn}), the room across a foot on it has
 * between the pedals beside it, the box it fills at rest, and the boxes the parts of the lyre and its
 * other pedals fill, which the feet keep out of.
 */
public final class PedalSpace {
    private static volatile PedalSpace instance;

    /** The pedal's group at rest: its parents, its pivot and its own bind rotation. */
    private final Matrix4f rest;
    /** The middle of the pedal's top at its toe and where it goes into the lyre, in its own group's space. */
    private final Vector3f toe, back;
    /** How far it turns fully down, radians about its own x axis. */
    private final float turn;
    /** Each part of the lyre and its other pedals at rest, {min x, y, z, max x, y, z}. */
    private final List<float[]> lyre = new ArrayList<>();
    /** Across, the near sides of the pedals either side of the sustain pedal (infinite where there is none). */
    private final float low, high;
    /** The sustain pedal at rest, {min x, y, z, max x, y, z}. */
    private final float[] box;

    PedalSpace(PianoGeometry geometry, Pedal pedal) {
        int group = geometry.group(pedal.group());
        Matrix4f[] transforms = geometry.newTransforms();
        geometry.transforms(null, transforms);
        rest = new Matrix4f(transforms[group]);
        float minX = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY, top = Float.NEGATIVE_INFINITY;
        float front = Float.POSITIVE_INFINITY, end = Float.NEGATIVE_INFINITY;
        for (PianoGeometry.Element element : geometry.elements) {
            if (element.group() != group) continue;
            float[] p = element.positions();
            for (int i = 0; i < p.length; i += 3) {
                minX = Math.min(minX, p[i]);
                maxX = Math.max(maxX, p[i]);
                top = Math.max(top, p[i + 1]);
                front = Math.min(front, p[i + 2]);
                end = Math.max(end, p[i + 2]);
            }
        }
        if (minX > maxX) throw new IllegalStateException("The piano model's " + pedal.group() + " has no elements");
        // The pedal's top ends where the lyre holding it (its parent's own parts) stands over it.
        Matrix4f toPedal = new Matrix4f(rest).invert().mul(transforms[geometry.groups.get(group).parent()]);
        for (PianoGeometry.Element element : geometry.elements) {
            if (element.group() != geometry.groups.get(group).parent()) continue;
            float[] holding = bounds(element, toPedal);
            boolean over = holding[0] < maxX && holding[3] > minX && holding[1] <= top && holding[4] >= top;
            if (over && holding[2] > front && holding[2] < end) end = holding[2];
        }
        toe = new Vector3f((minX + maxX) * 0.5f, top, front);
        back = new Vector3f((minX + maxX) * 0.5f, top, end);
        turn = (float) Math.toRadians(PianoPose.pedalTurn(geometry, pedal));
        int holder = geometry.group(PianoGeometry.PEDAL_LYRE);
        for (PianoGeometry.Element element : geometry.elements) {
            if (geometry.isWithin(element.group(), holder) && element.group() != group) lyre.add(bounds(element, transforms[element.group()]));
        }
        box = geometry.bounds(pedal.group());
        float lowest = Float.NEGATIVE_INFINITY, highest = Float.POSITIVE_INFINITY;
        for (Pedal other : Pedal.values()) {
            if (other == pedal) continue;
            float[] beside = geometry.bounds(other.group());
            if (beside[0] + beside[3] > box[0] + box[3]) highest = Math.min(highest, beside[0]);
            else lowest = Math.max(lowest, beside[3]);
        }
        low = lowest;
        high = highest;
    }

    /** The box {min x, y, z, max x, y, z} {@code element} fills, its points placed by {@code transform}. */
    private static float[] bounds(PianoGeometry.Element element, Matrix4f transform) {
        float[] p = element.positions(), out = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        Vector3f at = new Vector3f();
        for (int i = 0; i < p.length; i += 3) {
            transform.transformPosition(p[i], p[i + 1], p[i + 2], at);
            for (int a = 0; a < 3; a++) {
                out[a] = Math.min(out[a], at.get(a));
                out[a + 3] = Math.max(out[a + 3], at.get(a));
            }
        }
        return out;
    }

    public static PedalSpace get() {
        PedalSpace space = instance;
        if (space == null) {
            synchronized (PedalSpace.class) {
                space = instance;
                if (space == null) instance = space = new PedalSpace(PianoGeometry.get(), Pedal.SUSTAIN);
            }
        }
        return space;
    }

    /**
     * The middle of the sustain pedal's top at {@code depth} (0 up .. 1 fully down), in the piano's
     * model space: at the end of its toe into {@code toeOut}, where it goes into the lyre into
     * {@code backOut}.
     */
    public void sustainTop(float depth, Vector3f toeOut, Vector3f backOut) {
        // The pedal's own turn is about its x axis, applied after its bind rotation (see PianoGeometry#transforms).
        Matrix4f posed = new Matrix4f(rest).rotateX(turn * Math.max(0f, Math.min(1f, depth)));
        posed.transformPosition(toe, toeOut);
        posed.transformPosition(back, backOut);
    }

    /**
     * Across (model X), the room a foot on the sustain pedal has without covering the pedals beside
     * it: {lowest, highest}, infinite on a side with no pedal.
     */
    public float[] room() {
        return new float[]{low, high};
    }

    /** The box the sustain pedal fills at rest, {min x, y, z, max x, y, z} in the piano's model space. */
    public float[] box() {
        return box.clone();
    }

    /** The boxes the parts of the lyre and its other pedals fill at rest, each {min x, y, z, max x, y, z} in the piano's model space. */
    public List<float[]> lyre() {
        List<float[]> boxes = new ArrayList<>();
        for (float[] box : lyre) boxes.add(box.clone());
        return boxes;
    }
}
