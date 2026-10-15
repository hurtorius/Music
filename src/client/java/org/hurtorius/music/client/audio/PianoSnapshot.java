package org.hurtorius.music.client.audio;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.hurtorius.music.piano.Pedal;
import org.hurtorius.music.piano.PianoGeometry;
import org.hurtorius.music.piano.PianoLayout;
import org.hurtorius.music.piano.PianoSettings;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * What the audio thread knows about one piano: its settings and where each key and the pedals sound in
 * the world. Immutable; made on the client thread and replaced whenever the settings change.
 *
 * @param sounds world x, y, z where each of the 88 keys sounds (264 values; never modified): see {@link #soundWorld}
 * @param pedals world x, y, z of the pedal lyre
 */
public record PianoSnapshot(BlockPos pos, Direction facing, PianoSettings settings, float[] sounds, float[] pedals) {
    /**
     * Where a note sounds across the case, as a share of where its key is: a grand sounds from one
     * soundboard under the whole case, so its performer hears a blended image, not each key at its own
     * place two arm's lengths to either side.
     */
    public static final float SPREAD = 0.5f;
    /** Height (model units) a note sounds at: the strings over the soundboard, at the rim's inner edge. */
    public static final float STRINGS_Y = 11f;
    /** Depth (model units) a note sounds at: over the soundboard, behind the hammers, ahead of the middle of the case. */
    public static final float STRINGS_Z = 10f;

    public static PianoSnapshot of(BlockPos pos, Direction facing, PianoSettings settings) {
        float[] sounds = new float[PedalBoard.KEYS * 3];
        for (int k = 0; k < PedalBoard.KEYS; k++) {
            Vec3 v = soundWorld(pos, facing, k);
            sounds[k * 3] = (float) v.x;
            sounds[k * 3 + 1] = (float) v.y;
            sounds[k * 3 + 2] = (float) v.z;
        }
        PianoGeometry geometry = PianoGeometry.get();
        Matrix4f[] transforms = geometry.newTransforms();
        geometry.transforms(null, transforms);
        Vector3f lyre = transforms[geometry.group(Pedal.SUSTAIN.group())].transformPosition(new Vector3f());
        Vec3 p = PianoLayout.modelToWorld(pos, facing, lyre);
        return new PianoSnapshot(pos.immutable(), facing, settings, sounds, new float[]{(float) p.x, (float) p.y, (float) p.z});
    }

    /**
     * Where key {@code keyIndex} sounds in the world: on its strings inside the case, drawn half way
     * toward the middle. The hands and cameras keep using the keys themselves ({@link PianoLayout#keyWorld}).
     */
    public static Vec3 soundWorld(BlockPos controller, Direction facing, int keyIndex) {
        PianoGeometry.Key key = PianoGeometry.get().keys.get(keyIndex);
        float x = (key.from().x + key.to().x) * 0.5f * SPREAD;
        return PianoLayout.modelToWorld(controller, facing, x, STRINGS_Y, STRINGS_Z);
    }

    /** The same piano with new settings. */
    public PianoSnapshot with(PianoSettings next) {
        return new PianoSnapshot(pos, facing, next, sounds, pedals);
    }

    /** Squared distance from where a key sounds to a point. */
    public double keyDistanceSq(int key, Vec3 to) {
        double dx = sounds[key * 3] - to.x, dy = sounds[key * 3 + 1] - to.y, dz = sounds[key * 3 + 2] - to.z;
        return dx * dx + dy * dy + dz * dz;
    }
}
