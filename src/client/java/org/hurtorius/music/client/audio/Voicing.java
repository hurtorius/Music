package org.hurtorius.music.client.audio;

import net.minecraft.core.BlockPos;

import java.util.Objects;

/**
 * Who is playing which piano: the piano's controller position and the source id (a player's entity id,
 * or {@link org.hurtorius.music.client.state.PianoRuntime#SELF_PLAY}). Each voicing has its own keys and
 * pedals, so two players at one piano do not hold each other's notes.
 */
public record Voicing(BlockPos piano, int source) {
    public Voicing {
        piano = Objects.requireNonNull(piano, "piano").immutable();
    }
}
