package org.hurtorius.music.client.play;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.hurtorius.music.client.audio.PianoAudio;
import org.hurtorius.music.client.audio.Voicing;
import org.hurtorius.music.client.state.PianoClientState;
import org.hurtorius.music.net.MusicPayloads;
import org.hurtorius.music.piano.GrandPianoBlockEntity;
import org.hurtorius.music.piano.Pedal;
import org.hurtorius.music.piano.PianoLayout;
import org.hurtorius.music.seat.PianoSeatEntity;
import org.hurtorius.music.song.NoteEvent;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The local player performing at one piano. Input can come from any thread (the screen, the
 * computer keyboard, a MIDI device): the note sounds immediately through {@link PianoAudio}, and the
 * visuals, the network message to other players, the recorder and MIDI out follow on the client
 * thread. A key held by several inputs at once sounds once.
 */
public final class Performer {
    /** Notified on the client thread for every note or pedal change the performer makes. */
    public interface Listener {
        void noteOn(int soundingKey, int velocity);

        void noteOff(int soundingKey);

        void pedal(Pedal pedal, boolean down);
    }

    private static final Object LOCK = new Object();
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    /** Client-thread work for each input, in the order the inputs changed the key state. */
    private static final ConcurrentLinkedQueue<Runnable> PENDING = new ConcurrentLinkedQueue<>();
    private static volatile @Nullable BlockPos piano;
    private static volatile boolean canPlay;
    private static volatile int transpose;
    private static volatile int playerId;
    private static volatile int session;
    private static volatile long sessionStart;
    /** When the performer last pressed or let go of a key or a pedal (System.nanoTime()); Long.MIN_VALUE before the first. */
    private static volatile long lastInput = Long.MIN_VALUE;
    private static final int[] holds = new int[88];
    private static final int[] sounding = new int[88];
    private static final boolean[] pedals = new boolean[3];

    private Performer() {}

    public static void addListener(Listener listener) {
        LISTENERS.add(listener);
    }

    /** Starts performing at the piano whose controller is {@code controller}. Client thread. */
    public static void begin(BlockPos controller, boolean allowed) {
        BlockPos current = piano;
        if (current != null && current.equals(controller)) {
            canPlay = allowed;
            return;
        }
        end();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        GrandPianoBlockEntity be = Pianos.at(controller);
        synchronized (LOCK) {
            // A session starts with nothing held, whatever became of the last one.
            Arrays.fill(holds, 0);
            Arrays.fill(pedals, false);
            transpose = be == null ? 0 : be.settings().transpose();
            playerId = mc.player.getId();
            session = (int) (System.nanoTime() & 0x7fffffff);
            sessionStart = System.nanoTime();
            canPlay = allowed;
            piano = controller.immutable();
        }
    }

    /** Lets go of everything and stops performing. Client thread. */
    public static void end() {
        synchronized (LOCK) {
            BlockPos p = piano;
            if (p == null) return;
            letGo(p, true, System.nanoTime());
            // Under the same lock as the releases: no input can take a key between them and this.
            piano = null;
        }
        drain();
    }

    public static boolean isActive() {
        return piano != null;
    }

    public static @Nullable BlockPos piano() {
        return piano;
    }

    public static boolean canPlay() {
        return piano != null && canPlay;
    }

    /**
     * When the performer last played: pressed or let go of a key or a pedal (System.nanoTime()), or
     * Long.MIN_VALUE when they have not yet. Any thread.
     */
    public static long lastInput() {
        return lastInput;
    }

    public static boolean isHeld(int key) {
        synchronized (LOCK) {
            return key >= 0 && key < 88 && holds[key] > 0;
        }
    }

    public static boolean isPedalDown(Pedal pedal) {
        synchronized (LOCK) {
            return pedals[pedal.ordinal()];
        }
    }

    /** Press a key (0..87). Any thread. */
    public static void noteOn(int key, int velocity) {
        BlockPos p = piano;
        if (p == null || !canPlay || key < 0 || key > 87) return;
        int vel = Math.max(1, Math.min(127, velocity));
        long now = System.nanoTime();
        synchronized (LOCK) {
            // The session may have ended while this input waited for the lock.
            if (piano != p) return;
            lastInput = now;
            if (holds[key]++ > 0) return;
            int id = playerId;
            int k = key + transpose;
            int snd = k >= 0 && k <= 87 ? k : -1;
            sounding[key] = snd;
            // Sound and follow-up work are issued under the same lock as the key state, so a press and
            // its release from different threads can never be handled in the wrong order.
            if (snd >= 0) PianoAudio.noteOn(new Voicing(p, id), snd, vel, 0);
            NoteEvent event = NoteEvent.on(millisSinceStart(now), key, vel);
            PENDING.add(() -> {
                PianoClientState.get(p).press(key, vel, id, now);
                send(p, event);
                if (snd >= 0) {
                    Recorder.noteOn(snd, vel, now);
                    for (Listener l : LISTENERS) l.noteOn(snd, vel);
                }
            });
        }
        drainSoon();
    }

    /** Release a key. Any thread. */
    public static void noteOff(int key) {
        BlockPos p = piano;
        if (p == null || key < 0 || key > 87) return;
        long now = System.nanoTime();
        synchronized (LOCK) {
            if (piano != p || holds[key] == 0) return;
            lastInput = now;
            if (--holds[key] > 0) return;
            releaseKey(p, key, now);
        }
        drainSoon();
    }

    /** Press or release a pedal. Any thread. */
    public static void pedal(Pedal pedal, boolean down) {
        BlockPos p = piano;
        if (p == null || !canPlay) return;
        long now = System.nanoTime();
        synchronized (LOCK) {
            if (piano != p || pedals[pedal.ordinal()] == down) return;
            lastInput = now;
            pedals[pedal.ordinal()] = down;
            changePedal(p, pedal, down, now);
        }
        drainSoon();
    }

    /**
     * Releases every held key and leaves the pedals where they are, so notes the sustain pedal holds
     * keep sounding (MIDI all notes off). Any thread.
     */
    public static void releaseKeys() {
        release(false);
    }

    /** Releases every held key and pedal (e.g. the window lost focus or the keyboard was unplugged). Any thread. */
    public static void releaseAll() {
        release(true);
    }

    private static void release(boolean pedalsToo) {
        BlockPos p = piano;
        if (p == null) return;
        synchronized (LOCK) {
            if (piano != p) return;
            letGo(p, pedalsToo, System.nanoTime());
        }
        drainSoon();
    }

    /** Lets go of every held key, and the pedals when asked. Under {@link #LOCK}. */
    private static void letGo(BlockPos p, boolean pedalsToo, long now) {
        for (int k = 0; k < 88; k++) {
            if (holds[k] == 0) continue;
            holds[k] = 0;
            releaseKey(p, k, now);
        }
        if (!pedalsToo) return;
        for (Pedal pedal : Pedal.values()) {
            if (!pedals[pedal.ordinal()]) continue;
            pedals[pedal.ordinal()] = false;
            changePedal(p, pedal, false, now);
        }
    }

    /** Sounds the release of a key nothing holds any more and queues its follow-up work. Under {@link #LOCK}. */
    private static void releaseKey(BlockPos p, int key, long now) {
        int id = playerId;
        int snd = sounding[key];
        if (snd >= 0) PianoAudio.noteOff(new Voicing(p, id), snd, 0);
        NoteEvent event = NoteEvent.off(millisSinceStart(now), key);
        PENDING.add(() -> {
            PianoClientState.get(p).release(key, id, now);
            send(p, event);
            if (snd >= 0) {
                Recorder.noteOff(snd, now);
                for (Listener l : LISTENERS) l.noteOff(snd);
            }
        });
    }

    /** Sounds a pedal change and queues its follow-up work. Under {@link #LOCK}. */
    private static void changePedal(BlockPos p, Pedal pedal, boolean down, long now) {
        int id = playerId;
        PianoAudio.pedal(new Voicing(p, id), pedal, down, 0);
        int type = Pianos.songPedalType(pedal);
        NoteEvent event = NoteEvent.pedal(millisSinceStart(now), type, down ? 127 : 0);
        PENDING.add(() -> {
            PianoClientState.get(p).pedal(pedal, down, id, now);
            send(p, event);
            Recorder.pedal(type, down ? 127 : 0, now);
            for (Listener l : LISTENERS) l.pedal(pedal, down);
        });
    }

    /** Keeps transposition current and stops when the player leaves the piano. Client thread, every tick. */
    static void tick(Minecraft mc) {
        drain();
        BlockPos p = piano;
        if (p == null) return;
        GrandPianoBlockEntity be = Pianos.at(p);
        if (be == null || mc.player == null) {
            end();
            return;
        }
        transpose = be.settings().transpose();
        boolean seatedHere = mc.player.getVehicle() instanceof PianoSeatEntity seat && seat.anchor().equals(p);
        Vec3 center = PianoLayout.center(p, be.facing());
        if (!seatedHere && mc.screen == null && mc.player.position().distanceToSqr(center) > 10 * 10) end();
    }

    private static int millisSinceStart(long now) {
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, (now - sessionStart) / 1_000_000L));
    }

    private static void send(BlockPos p, NoteEvent event) {
        if (ClientPlayNetworking.canSend(MusicPayloads.LiveNotes.TYPE)) {
            ClientPlayNetworking.send(new MusicPayloads.LiveNotes(p, session, List.of(event)));
        }
    }

    /** Runs queued follow-up work in order on the client thread (now if already there). */
    private static void drainSoon() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread()) drain();
        else mc.execute(Performer::drain);
    }

    private static void drain() {
        for (Runnable r = PENDING.poll(); r != null; r = PENDING.poll()) r.run();
    }
}
