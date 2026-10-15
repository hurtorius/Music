package org.hurtorius.music.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.hurtorius.music.client.audio.PianoAudio;
import org.hurtorius.music.client.audio.Voicing;
import org.hurtorius.music.client.play.Performer;
import org.hurtorius.music.client.play.Pianos;
import org.hurtorius.music.client.play.SelfPlayTrack;
import org.hurtorius.music.client.state.PianoClientState;
import org.hurtorius.music.client.state.PianoRuntime;
import org.hurtorius.music.piano.Pedal;
import org.hurtorius.music.song.Song;
import org.jspecify.annotations.Nullable;

/**
 * Plays the take waiting to be saved back to its performer alone, before they decide what to do
 * with it: on the piano's own sound and keys, on this client only. Nothing goes to the server, so
 * nobody else hears it and the world's songs stay as they are. It has a source of its own, so
 * neither the player's live notes nor the piano playing by itself cut its notes short. The take is
 * played as recorded: its keys are the ones that sounded, after the piano's transposition. At its
 * end, when asked, or when its performer leaves the piano (the screen closed, standing up, walking
 * away), it stops as a pianist does, the dampers falling, as a song playing by itself does; when the
 * piano or the world it played in is gone, nothing is left to ring and it stops at once. Client thread.
 */
final class TakePreview {
    /** Source of a take played back to its performer: neither a player's entity id nor {@link PianoRuntime#SELF_PLAY}. */
    static final int SOURCE = -2;

    private static @Nullable TakePreview current;

    private final BlockPos piano;
    private final Song take;
    private final SelfPlayTrack track;
    private long startNanos;
    private long pausedSince;

    private TakePreview(BlockPos piano, Song take, long now) {
        this.piano = piano;
        this.take = take;
        this.track = new SelfPlayTrack(new Output(piano, new Voicing(piano, SOURCE)));
        this.startNanos = now;
    }

    /** Starts playing {@code take} on {@code piano} from its beginning, instead of whatever was playing. */
    static void start(BlockPos piano, Song take) {
        stop();
        long now = System.nanoTime();
        TakePreview preview = new TakePreview(piano.immutable(), take, now);
        current = preview;
        preview.follow(now);
    }

    /** Stops the take playing, if it is: the dampers fall and the strings die away, as when a pianist stops. */
    static void stop() {
        stop(true);
    }

    /** Stops the take playing, if it is, at once: the piano or the world it played in is gone. */
    static void cut() {
        stop(false);
    }

    private static void stop(boolean ringOut) {
        TakePreview preview = current;
        if (preview == null) return;
        current = null;
        preview.track.silence(System.nanoTime(), ringOut);
    }

    /** True while a take plays. */
    static boolean playing() {
        return current != null;
    }

    /** The take playing on {@code piano}, or null. */
    static @Nullable Song song(BlockPos piano) {
        TakePreview preview = current;
        return preview != null && preview.piano.equals(piano) ? preview.take : null;
    }

    /** Where in the take the sound is now (held while the game is paused), or -1 when none plays. */
    static long position() {
        TakePreview preview = current;
        if (preview == null) return -1;
        long at = preview.pausedSince != 0 ? preview.pausedSince : System.nanoTime();
        return Math.max(0, preview.track.positionAt(at));
    }

    /** Hands out the take's notes as they come due and stops it where it should stop. Client thread, every tick. */
    static void tick(Minecraft mc) {
        TakePreview preview = current;
        if (preview == null) return;
        if (mc.level == null || Pianos.at(preview.piano) == null) {
            cut();
            return;
        }
        if (!preview.piano.equals(Performer.piano())) {
            stop();
            return;
        }
        long now = System.nanoTime();
        if (mc.isPaused()) {
            if (preview.pausedSince == 0) preview.pausedSince = now;
            return;
        }
        if (preview.pausedSince != 0) {
            // The pause did not happen for the take: it goes on where it stopped.
            long paused = now - preview.pausedSince;
            preview.pausedSince = 0;
            preview.startNanos += paused;
            preview.track.resume(paused, now);
        }
        if (preview.track.positionAt(now) >= preview.take.durationMs()) {
            stop();
            return;
        }
        preview.follow(now);
    }

    private void follow(long now) {
        // A timeline of its own that never moves: the take from its start, at its own speed.
        long elapsedMs = (now - startNanos) / 1_000_000L;
        track.follow("take", take, 0, 1f, 0, elapsedMs + SelfPlayTrack.PRESENTATION_DELAY_MS, startNanos, now);
    }

    /** The piano's keys and its sound, on this client, under {@link #SOURCE}. */
    private record Output(BlockPos piano, Voicing voicing) implements SelfPlayTrack.Output {
        @Override
        public void noteOn(int key, int velocity, long at) {
            PianoClientState.get(piano).press(key, velocity, SOURCE, at);
            PianoAudio.noteOn(voicing, key, velocity, PianoAudio.toDevice(at));
        }

        @Override
        public void noteOff(int key, long at) {
            PianoClientState.get(piano).release(key, SOURCE, at);
            PianoAudio.noteOff(voicing, key, PianoAudio.toDevice(at));
        }

        @Override
        public void pedal(Pedal pedal, boolean down, long at) {
            PianoClientState.get(piano).pedal(pedal, down, SOURCE, at);
            PianoAudio.pedal(voicing, pedal, down, PianoAudio.toDevice(at));
        }

        @Override
        public void silence(long now, boolean ringOut) {
            PianoClientState.get(piano).releaseAll(SOURCE, now);
            if (ringOut) PianoAudio.dampAll(voicing);
            else PianoAudio.allNotesOff(voicing, true);
        }
    }
}
