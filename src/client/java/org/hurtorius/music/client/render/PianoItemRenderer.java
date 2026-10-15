package org.hurtorius.music.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.serialization.MapCodec;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.special.NoDataSpecialModelRenderer;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemDisplayContext;
import org.hurtorius.music.piano.PianoGeometry;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.function.Consumer;

/**
 * The grand piano item: the same mesh as the placed piano, at rest (lid open, desk up), without its
 * bench, centred in the item's unit cube and scaled so its longest side fits. The display transforms
 * in {@code models/item/grand_piano.json} then size and turn it for the inventory, hands, frames,
 * shelves and the ground. In inventory slots it wears {@link PianoIconTexture}, so the black piano
 * reads against the slot, and leaves out the dampers, which would be smaller than a pixel there;
 * everywhere else it has them and is lit by the world like the placed piano.
 */
public final class PianoItemRenderer implements NoDataSpecialModelRenderer {
    /** Group of the bench, which the item leaves out. */
    static final String BENCH = "hurtorius_bench";

    private final PianoMesh mesh;
    private final RenderType[] types;
    private final RenderType[] iconTypes;
    private final boolean[] drawn;
    private final PianoPose rest;
    private final Matrix4f fit;
    private final Vector3f[] corners = new Vector3f[8];

    private PianoItemRenderer(PianoMesh mesh) {
        this.mesh = mesh;
        this.types = GrandPianoRenderer.bodyTypes(mesh);
        this.iconTypes = types.clone();
        iconTypes[0] = RenderTypes.entityCutout(PianoIconTexture.ID);
        this.rest = new PianoPose(mesh.geometry);
        this.drawn = pianoTextures(mesh.geometry);
        Vector3f min = new Vector3f(Float.POSITIVE_INFINITY), max = new Vector3f(Float.NEGATIVE_INFINITY);
        bounds(mesh.geometry, rest.resolve(), drawn, min, max);
        this.fit = fit(min, max);
        for (int i = 0; i < 8; i++) {
            corners[i] = fit.transformPosition(new Vector3f((i & 1) == 0 ? min.x : max.x, (i & 2) == 0 ? min.y : max.y, (i & 4) == 0 ? min.z : max.z));
        }
    }

    /** Registers the icon texture with the game's texture manager once the client has started; it reloads with resources. */
    public static void register() {
        ClientLifecycleEvents.CLIENT_STARTED.register(mc -> mc.getTextureManager().registerAndLoad(PianoIconTexture.ID,
            new PianoIconTexture(Identifier.parse(PianoMesh.get().geometry.textures.get(0)))));
    }

    /**
     * The textures the item draws: every texture used by some part other than the bench. (The kit
     * gives the bench a texture of its own, so leaving that texture out leaves out the bench.)
     */
    static boolean[] pianoTextures(PianoGeometry geometry) {
        int bench = geometry.hasGroup(BENCH) ? geometry.group(BENCH) : -1;
        boolean[] used = new boolean[Math.max(1, geometry.textures.size())];
        for (PianoGeometry.Element element : geometry.elements) {
            if (bench < 0 || !geometry.isWithin(element.group(), bench)) used[element.texture()] = true;
        }
        return used;
    }

    /** Model-space bounds of every element drawn with the given textures, in the pose {@code transforms}. */
    static void bounds(PianoGeometry geometry, Matrix4f[] transforms, boolean[] textures, Vector3f min, Vector3f max) {
        Vector3f v = new Vector3f();
        for (PianoGeometry.Element element : geometry.elements) {
            if (!textures[element.texture()]) continue;
            float[] p = element.positions();
            Matrix4f m = transforms[element.group()];
            for (int i = 0; i < p.length; i += 3) {
                m.transformPosition(v.set(p[i], p[i + 1], p[i + 2]));
                min.min(v);
                max.max(v);
            }
        }
    }

    /** Model units to the item's unit cube: centred on (0.5, 0.5, 0.5), longest side 1. */
    public static Matrix4f fit(Vector3f min, Vector3f max) {
        float size = Math.max(max.x - min.x, Math.max(max.y - min.y, max.z - min.z));
        return new Matrix4f().translation(0.5f, 0.5f, 0.5f).scale(1f / size)
            .translate(-(min.x + max.x) / 2f, -(min.y + max.y) / 2f, -(min.z + max.z) / 2f);
    }

    @Override
    public void submit(ItemDisplayContext context, PoseStack poseStack, SubmitNodeCollector collector, int light, int overlay, boolean foil, int outline) {
        RenderType[] wear = context == ItemDisplayContext.GUI ? iconTypes : types;
        poseStack.pushPose();
        poseStack.mulPose(fit);
        for (int t = 0; t < wear.length; t++) {
            if (!drawn[t]) continue;
            int texture = t;
            collector.submitCustomGeometry(poseStack, wear[t], (pose, out) -> {
                mesh.emit(pose, out, texture, rest, light, overlay, false);
                if (texture == PianoDampers.TEXTURE && context != ItemDisplayContext.GUI) PianoDampers.current().emit(pose, out, rest.damperLifts(), light, overlay, false);
            });
        }
        poseStack.popPose();
    }

    @Override
    public void getExtents(Consumer<Vector3fc> output) {
        for (Vector3f corner : corners) output.accept(corner);
    }

    /** The {@code music:grand_piano} special model type named by {@code assets/music/items/grand_piano.json}. */
    public record Unbaked() implements SpecialModelRenderer.Unbaked {
        public static final MapCodec<Unbaked> MAP_CODEC = MapCodec.unit(new Unbaked());

        @Override
        public SpecialModelRenderer<?> bake(SpecialModelRenderer.BakingContext context) {
            return new PianoItemRenderer(PianoMesh.get());
        }

        @Override
        public MapCodec<Unbaked> type() {
            return MAP_CODEC;
        }
    }
}
