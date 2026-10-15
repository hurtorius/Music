package org.hurtorius.music.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.lwjgl.system.MemoryUtil;

/**
 * A texture for painted sheet music that stays readable at any distance: smooth (linear) filtering
 * with a full mip chain, rebuilt on the CPU by box filtering whenever the page is repainted.
 * Nearest filtering without mipmaps, as {@code DynamicTexture} has, makes thin staff lines flicker
 * and vanish a few blocks away. Render thread only.
 */
final class SheetTexture extends AbstractTexture {
    static final int LEVELS = 4;
    private final NativeImage[] levels = new NativeImage[LEVELS];

    SheetTexture(String label, int width, int height) {
        GpuDevice device = RenderSystem.getDevice();
        texture = device.createTexture(() -> label, GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, TextureFormat.RGBA8, width, height, 1, LEVELS);
        textureView = device.createTextureView(texture);
        sampler = RenderSystem.getSamplerCache().getSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, FilterMode.LINEAR, true);
        for (int i = 0; i < LEVELS; i++) levels[i] = new NativeImage(Math.max(1, width >> i), Math.max(1, height >> i), true);
    }

    /** The full-size image; paint into it, then call {@link #upload()}. */
    NativeImage image() {
        return levels[0];
    }

    /** Rebuilds every smaller level from the full-size image and sends them all to the GPU. */
    void upload() {
        for (int i = 1; i < LEVELS; i++) downsample(levels[i - 1], levels[i]);
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        for (int i = 0; i < LEVELS; i++) {
            NativeImage level = levels[i];
            encoder.writeToTexture(getTexture(), level, i, 0, 0, 0, level.getWidth(), level.getHeight(), 0, 0);
        }
    }

    /** Each target pixel is the average of the 2x2 source pixels it covers. */
    private static void downsample(NativeImage source, NativeImage target) {
        long from = source.getPointer(), to = target.getPointer();
        if (from == 0L || to == 0L) throw new IllegalStateException("Sheet texture used after it was closed");
        long stride = source.getWidth() * 4L;
        int width = target.getWidth(), height = target.getHeight();
        for (int y = 0; y < height; y++) {
            long row = from + y * 2L * stride;
            long out = to + (long) y * width * 4L;
            for (int x = 0; x < width; x++) {
                long at = row + x * 8L;
                int a = MemoryUtil.memGetInt(at), b = MemoryUtil.memGetInt(at + 4), c = MemoryUtil.memGetInt(at + stride), d = MemoryUtil.memGetInt(at + stride + 4);
                MemoryUtil.memPutInt(out + x * 4L, average(a, b, c, d));
            }
        }
    }

    private static int average(int a, int b, int c, int d) {
        int result = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            int sum = (a >>> shift & 0xFF) + (b >>> shift & 0xFF) + (c >>> shift & 0xFF) + (d >>> shift & 0xFF);
            result |= (sum + 2) / 4 << shift;
        }
        return result;
    }

    @Override
    public void close() {
        super.close();
        for (int i = 0; i < LEVELS; i++) {
            if (levels[i] != null) {
                levels[i].close();
                levels[i] = null;
            }
        }
    }
}
