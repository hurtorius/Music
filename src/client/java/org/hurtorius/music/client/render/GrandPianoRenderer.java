package org.hurtorius.music.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.SheetedDecalTextureGenerator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.BlockDestructionProgress;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.hurtorius.music.client.state.PianoClientState;
import org.hurtorius.music.client.state.PianoRuntime;
import org.hurtorius.music.mixin.client.DestructionProgressAccessor;
import org.hurtorius.music.piano.GrandPianoBlockEntity;
import org.hurtorius.music.piano.Pedal;
import org.hurtorius.music.piano.PianoLayout;
import org.hurtorius.music.piano.PianoSettings;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.util.SortedSet;

/**
 * Draws the whole Hurtorius Grand and its bench from the controller block: case, keys going down
 * and coming back, the dampers lifting off the strings, pedals, lid on its prop, folding music desk
 * and the digital sheet on the desk.
 *
 * <p>Extraction reads the piano's {@link PianoRuntime} (keys, dampers, pedals, lid and desk
 * animation) and keeps the runtime's lid and desk in step with the block entity's settings. The mesh
 * comes from {@link PianoMesh} and {@link PianoDampers}; static parts are prebaked, moving parts are
 * placed per frame (the dampers only while the lid is not shut over them). Light is the brightest of
 * the cells the piano covers. While any cell is being broken, cracks are drawn over the whole
 * instrument. Rendered off screen too, since the piano spans several chunk sections
 * and must not vanish when the controller's section is culled; that also hands it every piano in
 * view distance, so pianos wholly behind the camera are skipped here (except in a shader pack's
 * shadow pass, where they may still cast shadows into view).</p>
 */
public final class GrandPianoRenderer implements BlockEntityRenderer<GrandPianoBlockEntity, PianoRenderState> {
    public static final int VIEW_DISTANCE = 96;
    /** Slack around the piano's bounding sphere, in blocks. */
    static final float CULL_MARGIN = 0.25f;

    private final PianoMesh mesh = PianoMesh.get();
    private final RenderType[] bodyTypes;
    private final Matrix4f modelToBlock = new Matrix4f();
    private final BlockPos.MutableBlockPos cell = new BlockPos.MutableBlockPos();
    /** Middle of the piano relative to the controller block, by facing ({@link Direction#get2DDataValue()}). */
    private final Vector3f[] middles = new Vector3f[4];
    /** Radius of a sphere around the middle that holds the whole piano with its lid open, in blocks. */
    private final float reach;
    private final Vector3f look = new Vector3f();

    public GrandPianoRenderer(BlockEntityRendererProvider.Context context) {
        bodyTypes = bodyTypes(mesh);
        Vector3f min = mesh.min(), max = mesh.max();
        Vector3f middle = new Vector3f(min).add(max).mul(0.5f);
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            middles[facing.get2DDataValue()] = PianoLayout.modelToBlock(facing, new Matrix4f()).transformPosition(new Vector3f(middle));
        }
        reach = max.distance(min) / 2f * PianoLayout.BLOCKS_PER_UNIT + CULL_MARGIN;
    }

    /** One cutout entity render type per kit texture, in texture index order. */
    static RenderType[] bodyTypes(PianoMesh mesh) {
        RenderType[] types = new RenderType[mesh.textureCount()];
        for (int t = 0; t < types.length; t++) types[t] = RenderTypes.entityCutout(Identifier.parse(mesh.geometry.textures.get(t)));
        return types;
    }

    @Override
    public PianoRenderState createRenderState() {
        return new PianoRenderState();
    }

    @Override
    public void extractRenderState(GrandPianoBlockEntity piano, PianoRenderState state, float partialTick, Vec3 camera,
                                   ModelFeatureRenderer.@Nullable CrumblingOverlay crumbling) {
        BlockEntityRenderer.super.extractRenderState(piano, state, partialTick, camera, crumbling);
        BlockPos pos = piano.getBlockPos();
        Direction facing = piano.facing();
        long now = System.nanoTime();
        PianoRuntime runtime = PianoClientState.get(pos);
        PianoSettings settings = piano.settings();
        runtime.setLid(settings.lidOpen(), now);
        runtime.setDesk(settings.deskUp(), now);
        runtime.update(now);

        PianoVisuals.Visual visual = PianoVisuals.get(pos, mesh);
        PianoDampers dampers = PianoDampers.current();
        visual.dampers = dampers;
        PianoPose pose = visual.pose;
        for (int key = 0; key < 88; key++) pose.key(key, runtime.keyDepth(key, now));
        for (int key = 0; key < dampers.count(); key++) pose.damper(key, runtime.damperLift(key, now));
        for (Pedal pedal : Pedal.values()) pose.pedal(pedal, runtime.pedalDepth(pedal, now));
        float lidClosed = runtime.lidClosed(now), deskFolded = runtime.deskFolded(now);
        pose.lidAndDesk(lidClosed, deskFolded);
        pose.resolve();
        visual.dampersShown = lidClosed < 1f;
        Level level = piano.getLevel();
        visual.light = level == null ? LightTexture.FULL_BRIGHT : light(level, pos, facing);
        state.breakStage = level == null ? -1 : breakStage(pos, facing);
        visual.lastExtracted = now;

        double distance = Math.sqrt(camera.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
        state.sheetVisible = visual.sheet.update(piano, lidClosed, deskFolded, pose.desk(), distance, visual.light, now);
        state.facing = facing;
        state.lightCoords = visual.light;
        state.visual = visual;
    }

    /** The brightest block light and sky light over the cells of the piano. */
    private int light(Level level, BlockPos controller, Direction facing) {
        int block = 0, sky = 0;
        for (int part = 0; part < PianoLayout.PARTS; part++) {
            PianoLayout.partPos(cell, controller, facing, part);
            int packed = LevelRenderer.getLightColor(level, cell);
            block = Math.max(block, LightTexture.block(packed));
            sky = Math.max(sky, LightTexture.sky(packed));
        }
        return LightTexture.pack(block, sky);
    }

    /** Furthest breaking stage (0..9) over the piano's cells, or -1 when nobody is breaking it. */
    private int breakStage(BlockPos controller, Direction facing) {
        Long2ObjectMap<SortedSet<BlockDestructionProgress>> progress =
            ((DestructionProgressAccessor) Minecraft.getInstance().levelRenderer).music$destructionProgress();
        if (progress.isEmpty()) return -1;
        int stage = -1;
        for (int part = 0; part < PianoLayout.PARTS; part++) {
            PianoLayout.partPos(cell, controller, facing, part);
            SortedSet<BlockDestructionProgress> breaking = progress.get(cell.asLong());
            if (breaking != null && !breaking.isEmpty()) stage = Math.max(stage, breaking.last().getProgress());
        }
        return Math.min(stage, ModelBakery.DESTROY_TYPES.size() - 1);
    }

    @Override
    public void submit(PianoRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        PianoVisuals.Visual visual = state.visual;
        if (visual == null || behindCamera(state, camera)) return;
        // Crack textures are laid out per block, from the block's own corner, as vanilla does.
        PoseStack.Pose blockSpace = state.breakStage >= 0 ? poseStack.last().copy() : null;
        poseStack.pushPose();
        poseStack.mulPose(PianoLayout.modelToBlock(state.facing, modelToBlock));
        for (int t = 0; t < bodyTypes.length; t++) collector.submitCustomGeometry(poseStack, bodyTypes[t], visual.layers[t]);
        if (state.sheetVisible) collector.submitCustomGeometry(poseStack, visual.sheet.renderType(), visual.sheet);
        if (blockSpace != null) {
            // A later order, so the cracks are blended over the finished body rather than under it.
            int light = visual.light;
            collector.order(1).submitCustomGeometry(poseStack, ModelBakery.DESTROY_TYPES.get(state.breakStage), (pose, out) -> {
                VertexConsumer cracks = new SheetedDecalTextureGenerator(out, blockSpace, 1f);
                boolean cull = !ShaderPacks.mayBeShadowPass();
                for (int t = 0; t < bodyTypes.length; t++) mesh.emit(pose, cracks, t, visual.pose, light, OverlayTexture.NO_OVERLAY, cull);
                if (visual.dampersShown) visual.dampers.emit(pose, cracks, visual.pose.damperLifts(), light, OverlayTexture.NO_OVERLAY, cull);
            });
        }
        poseStack.popPose();
    }

    /** True when no part of the piano can be in front of the camera. */
    private boolean behindCamera(PianoRenderState state, CameraRenderState camera) {
        if (!camera.initialized || ShaderPacks.mayBeShadowPass()) return false;
        Vector3f middle = middles[state.facing.get2DDataValue()];
        look.set(0f, 0f, -1f).rotate(camera.orientation);
        double dx = state.blockPos.getX() + middle.x - camera.pos.x;
        double dy = state.blockPos.getY() + middle.y - camera.pos.y;
        double dz = state.blockPos.getZ() + middle.z - camera.pos.z;
        return look.x * dx + look.y * dy + look.z * dz < -reach;
    }

    /**
     * Never a piano that was already removed: Sodium keeps handing out a section's global block
     * entities until it has rebuilt the section, which would draw a broken piano for a few more
     * frames and bring back render data its unload just gave up.
     */
    @Override
    public boolean shouldRender(GrandPianoBlockEntity piano, Vec3 camera) {
        return !piano.isRemoved() && BlockEntityRenderer.super.shouldRender(piano, camera);
    }

    @Override
    public boolean shouldRenderOffScreen() {
        return true;
    }

    @Override
    public int getViewDistance() {
        return VIEW_DISTANCE;
    }
}
