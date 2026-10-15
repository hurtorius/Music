package org.hurtorius.music.mixin.client;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.server.level.BlockDestructionProgress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.SortedSet;

/**
 * How far each block is being broken, keyed by packed block position. The piano renderer reads it
 * to draw cracks over the whole instrument: vanilla draws them from the block model, which the
 * piano does not have, and gives off-screen block entities no crumbling overlay.
 */
@Mixin(LevelRenderer.class)
public interface DestructionProgressAccessor {
    @Accessor("destructionProgress")
    Long2ObjectMap<SortedSet<BlockDestructionProgress>> music$destructionProgress();
}
