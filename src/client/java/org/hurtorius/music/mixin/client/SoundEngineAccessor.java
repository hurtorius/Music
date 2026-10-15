package org.hurtorius.music.mixin.client;

import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundEventListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * Who hears of every sound Minecraft plays: the subtitle overlay (while subtitles are on) and any other
 * listener. The piano's notes go straight to OpenAL, so it tells them itself that a piano plays.
 */
@Mixin(SoundEngine.class)
public interface SoundEngineAccessor {
    @Accessor("listeners")
    List<SoundEventListener> music$listeners();
}
