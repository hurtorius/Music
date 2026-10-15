package org.hurtorius.music.client.play;

import net.minecraft.client.Minecraft;
import org.hurtorius.music.client.ClientPrefs;
import org.hurtorius.music.client.audio.PianoAudio;

/**
 * Keeps Minecraft's own background music from starting over the piano. While the player plays (a key or
 * a pedal, {@link Performer#lastInput}) or can hear any piano ({@link PianoAudio#audibleVoices}: pianos
 * they turned down to nothing, or every piano while their Jukebox/Note Blocks volume is 0, do not
 * count), and for half a minute after (the gap between two songs of a playlist, a pause to think), the
 * game is told there is no music for the situation and its music volume is 0, as the pale garden does:
 * the music manager fades out a track already playing and starts none, and picks up its own timing
 * once the piano is quiet. Sitting at a piano or having its screen open in silence does not keep the
 * music away. Off when the player turns it off ({@link ClientPrefs#hushGameMusic}). Client thread.
 */
public final class MusicHush {
    /** Game music stays away this long after the player last played or heard a piano. */
    static final long LINGER_NANOS = 30_000_000_000L;
    private static boolean heard;
    private static long lastHeard;
    /** The performer's last key or pedal, once counted. */
    private static long countedInput = Long.MIN_VALUE;

    private MusicHush() {}

    static void tick(Minecraft mc) {
        // Out of a world nothing is played or heard: the voices of the one just left are still dying away
        // as it closes, and must not keep the title screen's music away.
        if (mc.level == null) return;
        long input = Performer.lastInput();
        if (input != countedInput) {
            countedInput = input;
            heardAt(input);
        }
        if (PianoAudio.audibleVoices() > 0) heardAt(System.nanoTime());
    }

    private static void heardAt(long at) {
        if (heard && at - lastHeard <= 0) return;
        heard = true;
        lastHeard = at;
    }

    /** True while game music should keep away. */
    public static boolean active() {
        return ClientPrefs.get().hushGameMusic && heard && System.nanoTime() - lastHeard < LINGER_NANOS;
    }

    /**
     * When the player last played or heard a piano (System.nanoTime()); game music keeps away for
     * {@link #LINGER_NANOS} from then. Long.MIN_VALUE when they have not since joining the world.
     */
    public static long lastHeard() {
        return heard ? lastHeard : Long.MIN_VALUE;
    }

    /**
     * Forgets what was heard (leaving a world): playing there keeps the music away neither on the title
     * screen nor in the next world.
     */
    static void reset() {
        heard = false;
        countedInput = Performer.lastInput();
    }
}
