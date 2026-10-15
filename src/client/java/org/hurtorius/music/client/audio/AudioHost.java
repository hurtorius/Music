package org.hurtorius.music.client.audio;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The game state the audio thread reads. Every method is called from the audio thread and must be
 * cheap and thread-safe; the game implementation ({@link GameAudioHost}) serves values it refreshes on
 * the client thread.
 */
public interface AudioHost {
    /** The piano at {@code pos}, or null when it is not known (yet) or gone. */
    @Nullable PianoSnapshot piano(BlockPos pos);

    /** Asks for a snapshot of a piano the engine was told to play but does not know. */
    void request(BlockPos pos);

    /** The player's "Jukebox/Note Blocks" volume times master volume, 0..1. */
    float recordsVolume();

    /** How loud this player wants other people's pianos, 0..1 (their own choice, heard by nobody else). */
    float othersVolume();

    /**
     * True for a piano of this player's own: one they placed, or the one they sit at. The other pianos
     * volume leaves it alone; opening another piano's screen, to see what it plays or to stop it, does not
     * make it theirs.
     */
    boolean ownPiano(BlockPos piano);

    /**
     * The source of the notes this player plays themselves ({@link Voicing#source()}, their entity id),
     * which the other pianos volume leaves alone wherever they play them; {@link #NO_SOURCE} while there
     * is no player.
     */
    int ownSource();

    /** The own source while there is no player: no voicing has it (players use their entity id, a piano playing by itself -1). */
    int NO_SOURCE = Integer.MIN_VALUE;

    /**
     * How far, in blocks, the chunks this player has loaded reach. A piano further away goes silent when
     * its chunk unloads, so the sound must have faded out before that.
     */
    int loadedRadius();

    /** True while the game is paused; nothing may sound then. */
    boolean paused();

    /** Voice pool size wanted (clamped by the engine to 16..96). */
    int voiceLimit();

    /** Where the listener is, for skipping notes out of a piano's range; null when unknown. */
    @Nullable Vec3 listener();
}
