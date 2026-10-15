package org.hurtorius.music;

import net.fabricmc.api.ModInitializer;
import org.hurtorius.music.net.MusicPayloads;
import org.hurtorius.music.net.ServerNetwork;
import org.hurtorius.music.net.VersionCheck;
import org.hurtorius.music.registry.MusicContent;

/** Music: a grand piano for Minecraft. Common (both sides) initialisation. */
public final class Music implements ModInitializer {
    @Override
    public void onInitialize() {
        MusicContent.register();
        MusicPayloads.register();
        ServerNetwork.register();
        VersionCheck.register();
    }
}
