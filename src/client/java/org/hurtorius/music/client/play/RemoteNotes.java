package org.hurtorius.music.client.play;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.hurtorius.music.client.ClientPrefs;
import org.hurtorius.music.client.audio.PianoAudio;
import org.hurtorius.music.client.audio.Voicing;
import org.hurtorius.music.client.state.PianoClientState;
import org.hurtorius.music.client.state.PianoRuntime;
import org.hurtorius.music.net.MusicPayloads;
import org.hurtorius.music.piano.Pedal;
import org.hurtorius.music.seat.PianoSeatEntity;
import org.hurtorius.music.song.NoteEvent;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Other players' live playing. Each performer's events carry their own clock; this keeps the
 * spacing between events intact by scheduling every event at (its time + the smallest delay seen
 * for that performer + a small safety delay), so network jitter does not smear the rhythm.
 */
public final class RemoteNotes {
    /** A performer stream is forgotten after this long without events and its keys are released. */
    private static final long IDLE_NANOS = 20_000_000_000L;
    private static final Map<Stream, State> STREAMS = new HashMap<>();

    private record Stream(BlockPos piano, int player) {}

    private static final class State {
        int session;
        long offset = Long.MAX_VALUE;
        long lastArrival;
        /** Latest time anything of this stream was scheduled for; later events never go earlier. */
        long lastAt;
        final int[] sounding = new int[88];
        /**
         * Keys this performer holds as far as this client knows. A performer never strikes a key it
         * holds, so a press of one means its release never arrived here.
         */
        final boolean[] down = new boolean[88];

        State() {
            Arrays.fill(sounding, -1);
        }
    }

    private RemoteNotes() {}

    static void receive(MusicPayloads.PlayedNotes p) {
        long arrival = System.nanoTime();
        Stream key = new Stream(p.pos().immutable(), p.playerId());
        State state = STREAMS.computeIfAbsent(key, k -> new State());
        if (state.session != p.session()) {
            if (state.lastArrival != 0) stop(key, state, arrival, false);
            state.session = p.session();
            state.offset = Long.MAX_VALUE;
            state.lastAt = 0;
        } else if (state.offset != Long.MAX_VALUE) {
            // The offset is a lower envelope of (arrival - send time). Letting it rise by 200 ppm lets it
            // follow clock drift between the two machines; every packet pulls it back down at once.
            state.offset += (arrival - state.lastArrival) / 5_000L;
        }
        state.lastArrival = arrival;
        for (NoteEvent e : p.events()) state.offset = Math.min(state.offset, arrival - e.timeMs() * 1_000_000L);
        long delay = ClientPrefs.get().remoteDelayMs * 1_000_000L;
        PianoRuntime runtime = PianoClientState.get(key.piano);
        Voicing voicing = new Voicing(key.piano, key.player);
        for (NoteEvent e : p.events()) {
            long at = Math.max(Math.max(arrival, e.timeMs() * 1_000_000L + state.offset + delay), state.lastAt);
            state.lastAt = at;
            long device = PianoAudio.toDevice(at);
            switch (e.kind()) {
                case NoteEvent.NOTE_ON -> {
                    int k = e.data1();
                    int snd = Pianos.sounding(key.piano, k);
                    if (state.down[k]) {
                        // Let go of the stale hold first, so the key comes up when this press is released.
                        // A re-strike of the same sounding key replaces its voice by itself.
                        runtime.release(k, key.player, at);
                        if (state.sounding[k] >= 0 && state.sounding[k] != snd) PianoAudio.noteOff(voicing, state.sounding[k], device);
                    }
                    state.down[k] = true;
                    state.sounding[k] = snd;
                    runtime.press(k, e.data2(), key.player, at);
                    if (snd >= 0) PianoAudio.noteOn(voicing, snd, e.data2(), device);
                }
                case NoteEvent.NOTE_OFF -> {
                    int k = e.data1();
                    // A release of a key not held here (its press was missed or already let go) changes nothing.
                    if (state.down[k]) {
                        int snd = state.sounding[k];
                        state.down[k] = false;
                        state.sounding[k] = -1;
                        runtime.release(k, key.player, at);
                        if (snd >= 0) PianoAudio.noteOff(voicing, snd, device);
                    }
                }
                case NoteEvent.PEDAL -> {
                    Pedal pedal = Pianos.pedalOf(e.data1());
                    runtime.pedal(pedal, e.data2() >= 64, key.player, at);
                    PianoAudio.pedal(voicing, pedal, e.data2() >= 64, device);
                }
                default -> {
                }
            }
        }
    }

    /** Releases performers who stopped sending, stood up or left. Client thread, every tick. */
    static void tick(Minecraft mc) {
        long now = System.nanoTime();
        for (Iterator<Map.Entry<Stream, State>> it = STREAMS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Stream, State> e = it.next();
            Stream s = e.getKey();
            Entity player = mc.level == null ? null : mc.level.getEntity(s.player);
            boolean seated = player != null && player.getVehicle() instanceof PianoSeatEntity seat && seat.anchor().equals(s.piano);
            // A performer whose entity is not loaded here (far away, just teleported) keeps playing:
            // only a stream that has gone quiet is closed, so notes are never cut in the middle.
            boolean idle = now - e.getValue().lastArrival > IDLE_NANOS;
            if (idle && (player == null || !seated)) {
                stop(s, e.getValue(), now, false);
                it.remove();
            }
        }
    }

    /**
     * The piano is no longer loaded here (out of view, or gone). The server sends a piano's notes only
     * to players who have it loaded, so the releases of what its performers hold now would never
     * arrive: everything they hold is let go at once, and what was already scheduled is dropped.
     * Client thread.
     */
    static void forget(BlockPos piano) {
        long now = System.nanoTime();
        for (Iterator<Map.Entry<Stream, State>> it = STREAMS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Stream, State> e = it.next();
            if (!e.getKey().piano.equals(piano)) continue;
            stop(e.getKey(), e.getValue(), now, true);
            it.remove();
        }
    }

    /** Lets go of everything a stream holds; {@code immediate} also drops its scheduled sounds and cuts the tails short. */
    private static void stop(Stream s, State state, long now, boolean immediate) {
        PianoClientState.get(s.piano).releaseAll(s.player, now);
        PianoAudio.allNotesOff(new Voicing(s.piano, s.player), immediate);
        Arrays.fill(state.sounding, -1);
        Arrays.fill(state.down, false);
    }

    static void reset() {
        STREAMS.clear();
    }
}
