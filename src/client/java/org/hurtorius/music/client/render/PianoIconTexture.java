package org.hurtorius.music.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.ReloadableTexture;
import net.minecraft.client.renderer.texture.TextureContents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;

/**
 * The piano's texture as its inventory icon wears it: the same image with the black lacquer lifted
 * to dark greys, so the case, the raised lid and the open inside read against a slot at sixteen
 * pixels, while the white keys stay white. Made from whatever texture the resource packs supply, and
 * remade on every resource reload (the texture manager reloads it like any other texture).
 */
final class PianoIconTexture extends ReloadableTexture {
    /** Where the icon texture is registered; nothing is loaded from this path. */
    static final Identifier ID = Identifier.fromNamespaceAndPath("music", "textures/item/grand_piano_icon.png");
    /** How much a pure black is lifted, out of 255; lighter shades are lifted less, white not at all. */
    static final float LIFT = 46f;

    private final Identifier source;

    PianoIconTexture(Identifier source) {
        super(ID);
        this.source = source;
    }

    @Override
    public TextureContents loadContents(ResourceManager resources) throws IOException {
        TextureContents contents = TextureContents.load(resources, source);
        NativeImage image = contents.image();
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) image.setPixel(x, y, lift(image.getPixel(x, y)));
        }
        return contents;
    }

    /** One ARGB colour with its darks lifted: each channel gains {@link #LIFT} times (1 - channel)². */
    static int lift(int argb) {
        int out = argb & 0xFF000000;
        for (int shift = 0; shift < 24; shift += 8) {
            float c = (argb >>> shift & 0xFF) / 255f;
            float lifted = c + LIFT / 255f * (1f - c) * (1f - c);
            out |= Math.min(255, Math.round(lifted * 255f)) << shift;
        }
        return out;
    }
}
