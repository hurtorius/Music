package org.hurtorius.music.client;

import net.minecraft.client.KeyMapping;
import org.hurtorius.music.registry.MusicContent;

/** The "Music" section of the Controls screen, which holds every key the mod adds (it can be registered only once). */
public final class MusicKeys {
    public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(MusicContent.id("music"));

    private MusicKeys() {}
}
