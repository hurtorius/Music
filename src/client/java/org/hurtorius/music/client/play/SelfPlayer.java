package org.hurtorius.music.client.play;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.hurtorius.music.client.audio.PianoAudio;
import org.hurtorius.music.client.audio.Voicing;
import org.hurtorius.music.client.song.ClientSongs;
import org.hurtorius.music.client.state.PianoClientState;
import org.hurtorius.music.client.state.PianoRuntime;
import org.hurtorius.music.piano.GrandPianoBlockEntity;
import org.hurtorius.music.piano.Pedal;
import org.hurtorius.music.piano.PianoLayout;
import org.hurtorius.music.piano.Playback;
import org.hurtorius.music.song.Song;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Plays the pianos that play by themselves. The server owns each piano's timeline
 * ({@link Playback}); every client follows it {@link SelfPlayTrack#PRESENTATION_DELAY_MS} behind,
 * which leaves time for the block entity update to arrive before the first notes of a song are due,
 * and keeps every listener on the same schedule. Each piano in earshot gets a {@link SelfPlayTrack}
 * whose notes go to the piano's keys and to the audio engine.
 */
public final class SelfPlayer {
    /** Extra listening distance beyond the piano's range before scheduling stops. */
    static final double RANGE_MARGIN = 8;

    private static final Map<BlockPos, SelfPlayTrack> TRACKS = new HashMap<>();
    private static long pausedSince;

    private SelfPlayer() {}

    /** Client thread, every tick. */
    static void tick(Minecraft mc) {
        long now = System.nanoTime();
        if (mc.isPaused()) {
            if (pausedSince == 0) pausedSince = now;
            return;
        }
        if (pausedSince != 0) {
            // The server takes the pause out of its timelines; each track does the same.
            long paused = now - pausedSince;
            for (SelfPlayTrack track : TRACKS.values()) track.resume(paused, now);
            pausedSince = 0;
        }
        for (BlockPos pos : Pianos.loaded()) {
            GrandPianoBlockEntity piano = Pianos.at(pos);
            if (piano == null) continue;
            Playback playback = piano.playback();
            String id = playback.currentSong();
            SelfPlayTrack track = TRACKS.get(pos);
            boolean audible = mc.player != null && mc.player.position().distanceToSqr(PianoLayout.center(pos, piano.facing()))
                <= Math.pow(PianoAudio.hearingRange(piano.settings()) + RANGE_MARGIN, 2);
            if (!playback.playing() || id == null || !audible) {
                if (track != null) {
                    // Stopped, paused or over: the dampers fall. Out of earshot: nothing to ring out.
                    track.silence(now, audible);
                    TRACKS.remove(pos);
                }
                continue;
            }
            Song song = track != null && track.song() != null && id.equals(track.songId()) ? track.song() : ClientSongs.get(id);
            if (song == null) continue;
            if (track == null) {
                track = new SelfPlayTrack(new PianoOutput(pos));
                TRACKS.put(pos, track);
            }
            track.follow(id, song, piano.settings().transpose(), playback.speed(), playback.revision(), piano.clientPosition(), piano.receivedAt(), now);
        }
        for (Iterator<Map.Entry<BlockPos, SelfPlayTrack>> it = TRACKS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<BlockPos, SelfPlayTrack> e = it.next();
            if (!Pianos.loaded().contains(e.getKey())) {
                e.getValue().silence(now, false);
                it.remove();
            }
        }
    }

    /** Position of the song a piano is sounding on this client, for screens and the sheet. */
    public static long position(BlockPos piano, long fallbackMs) {
        SelfPlayTrack track = TRACKS.get(piano);
        // While the game is paused the song stands where it stopped.
        return track == null || !track.sounding() ? fallbackMs : track.positionAt(pausedSince != 0 ? pausedSince : System.nanoTime());
    }

    /**
     * Where this client presents a piano's song, for the music desk: while it sounds {@code songId},
     * the clock its sound and keys follow (held while the game is paused); otherwise the server's
     * position delayed the same way ({@link SelfPlayTrack#PRESENTATION_DELAY_MS} of song time), which
     * is where the sound starts or resumes.
     *
     * @param serverPositionMs the piano's {@link GrandPianoBlockEntity#clientPosition()}
     */
    public static long presentedPosition(BlockPos piano, @Nullable String songId, long serverPositionMs) {
        SelfPlayTrack track = TRACKS.get(piano);
        if (track != null && track.sounding() && songId != null && songId.equals(track.songId())) {
            return Math.max(0, track.positionAt(pausedSince != 0 ? pausedSince : System.nanoTime()));
        }
        return Math.max(0, serverPositionMs - SelfPlayTrack.PRESENTATION_DELAY_MS);
    }

    /** True when this client is currently sounding the piano's song. */
    public static boolean isSounding(BlockPos piano) {
        SelfPlayTrack track = TRACKS.get(piano);
        return track != null && track.sounding();
    }

    /** The song a piano is sounding on this client, or null. */
    public static @Nullable Song currentSong(BlockPos piano) {
        SelfPlayTrack track = TRACKS.get(piano);
        return track == null ? null : track.song();
    }

    static void reset() {
        long now = System.nanoTime();
        for (SelfPlayTrack track : TRACKS.values()) if (track.sounding()) track.silence(now, false);
        TRACKS.clear();
        pausedSince = 0;
    }

    /** A self-playing piano's keys and its sound. */
    private record PianoOutput(BlockPos piano, Voicing voicing) implements SelfPlayTrack.Output {
        PianoOutput(BlockPos piano) {
            this(piano, new Voicing(piano, PianoRuntime.SELF_PLAY));
        }

        @Override
        public void noteOn(int key, int velocity, long at) {
            PianoClientState.get(piano).press(key, velocity, PianoRuntime.SELF_PLAY, at);
            PianoAudio.noteOn(voicing, key, velocity, PianoAudio.toDevice(at));
        }

        @Override
        public void noteOff(int key, long at) {
            PianoClientState.get(piano).release(key, PianoRuntime.SELF_PLAY, at);
            PianoAudio.noteOff(voicing, key, PianoAudio.toDevice(at));
        }

        @Override
        public void pedal(Pedal pedal, boolean down, long at) {
            PianoClientState.get(piano).pedal(pedal, down, PianoRuntime.SELF_PLAY, at);
            PianoAudio.pedal(voicing, pedal, down, PianoAudio.toDevice(at));
        }

        @Override
        public void silence(long now, boolean ringOut) {
            PianoClientState.get(piano).releaseAll(PianoRuntime.SELF_PLAY, now);
            if (ringOut) PianoAudio.dampAll(voicing);
            else PianoAudio.allNotesOff(voicing, true);
        }
    }
}
