package org.hurtorius.music.client.render;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientBlockEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import org.hurtorius.music.piano.GrandPianoBlockEntity;
import org.hurtorius.music.piano.PianoGeometry;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Render data kept between frames for each piano on screen: its pose arrays (keys, dampers, pedals,
 * lid and desk), its light and its desk sheet. Entries go away (and give their sheet texture back)
 * when the piano unloads, or when it has not been drawn for a while (out of view distance, another
 * dimension). Client thread only.
 */
public final class PianoVisuals {
    private static final long FORGET_NANOS = 10_000_000_000L;
    private static final Map<BlockPos, Visual> VISUALS = new HashMap<>();

    private PianoVisuals() {}

    /** One piano's render data. The geometry callbacks read the fields set by the latest extraction. */
    static final class Visual {
        final PianoPose pose = new PianoPose(PianoGeometry.get());
        final DeskSheet sheet = new DeskSheet();
        final SubmitNodeCollector.CustomGeometryRenderer[] layers;
        int light;
        /** The dampers of the samples in use ({@link PianoDampers#current()}). */
        PianoDampers dampers = PianoDampers.get();
        /** False while the lid is shut, when nothing can see the dampers. */
        boolean dampersShown = true;
        long lastExtracted;

        Visual(PianoMesh mesh) {
            layers = new SubmitNodeCollector.CustomGeometryRenderer[mesh.textureCount()];
            for (int t = 0; t < layers.length; t++) {
                int texture = t;
                layers[t] = (target, out) -> {
                    boolean cull = !ShaderPacks.mayBeShadowPass();
                    mesh.emit(target, out, texture, pose, light, OverlayTexture.NO_OVERLAY, cull);
                    if (texture == PianoDampers.TEXTURE && dampersShown) dampers.emit(target, out, pose.damperLifts(), light, OverlayTexture.NO_OVERLAY, cull);
                };
            }
        }
    }

    static Visual get(BlockPos pos, PianoMesh mesh) {
        Visual visual = VISUALS.get(pos);
        if (visual == null) {
            visual = new Visual(mesh);
            VISUALS.put(pos.immutable(), visual);
        }
        return visual;
    }

    /** Number of pianos with render data right now. */
    public static int count() {
        return VISUALS.size();
    }

    /** Number of desk sheets holding a texture right now. */
    public static int sheetTextures() {
        int n = 0;
        for (Visual v : VISUALS.values()) if (v.sheet.renderType() != null) n++;
        return n;
    }

    /**
     * The instant ({@link System#nanoTime()}) the piano at {@code pos} was last posed for a frame: its
     * keys, dampers and pedals were drawn as they stood then. -1 when it has not been drawn.
     */
    public static long lastDrawn(BlockPos pos) {
        Visual visual = VISUALS.get(pos);
        return visual == null ? -1 : visual.lastExtracted;
    }

    /** Song position the desk sheet of the piano at {@code pos} showed last, or -1 when it shows none. */
    public static long sheetPosition(BlockPos pos) {
        Visual visual = VISUALS.get(pos);
        return visual == null || visual.sheet.renderType() == null ? -1 : visual.sheet.shownPosition();
    }

    public static void register() {
        ClientBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register((blockEntity, level) -> {
            if (blockEntity instanceof GrandPianoBlockEntity) forget(blockEntity.getBlockPos());
        });
        ClientTickEvents.END_CLIENT_TICK.register(PianoVisuals::tick);
    }

    private static void forget(BlockPos pos) {
        Visual visual = VISUALS.remove(pos);
        if (visual != null) visual.sheet.release();
    }

    private static void tick(Minecraft minecraft) {
        if (VISUALS.isEmpty()) return;
        long now = System.nanoTime();
        for (Iterator<Visual> it = VISUALS.values().iterator(); it.hasNext(); ) {
            Visual visual = it.next();
            if (now - visual.lastExtracted > FORGET_NANOS) {
                visual.sheet.release();
                it.remove();
            } else {
                visual.sheet.releaseIfIdle(now);
            }
        }
    }
}
