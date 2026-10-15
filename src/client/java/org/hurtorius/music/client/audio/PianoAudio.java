package org.hurtorius.music.client.audio;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.reloader.ResourceReloaderKeys;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.packs.PackType;
import org.hurtorius.music.client.ClientPrefs;
import org.hurtorius.music.client.WorldExit;
import org.hurtorius.music.piano.Instrument;
import org.hurtorius.music.piano.Pedal;
import org.hurtorius.music.piano.PianoSettings;
import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The piano's sound: grand piano samples, note block instruments and custom sounds played as positioned
 * OpenAL voices in Minecraft's own audio context, with pedals, lid, tone, reverb and sample-accurate
 * scheduling.
 *
 * <p>Times are device-clock nanoseconds ({@link #deviceNanos()}, {@link #toDevice(long)}); a time of 0 or
 * in the past means now. Keys are indices 0..87 (A0..C8); transposition is the caller's. Every method
 * may be called from any thread and returns at once; the {@code music-audio} thread does the work.</p>
 */
public final class PianoAudio {
    private static final GameAudioHost HOST = new GameAudioHost();
    private static final InstrumentSounds SOUNDS = new InstrumentSounds();
    /**
     * Decodes the grand piano again for a new audio device. Its own threads: a shared pool busy with
     * other work would leave the piano silent until it got round to it.
     */
    private static final ExecutorService DECODER = Executors.newFixedThreadPool(2, new ThreadFactory() {
        private final AtomicInteger count = new AtomicInteger();

        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "music-piano-decoder-" + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    });
    private static final AudioEngine ENGINE = new AudioEngine(HOST, SOUNDS, DECODER);
    private static final PianoSubtitles SUBTITLES = new PianoSubtitles(ENGINE, HOST);
    private static boolean bootstrapped;

    private PianoAudio() {}

    /** Registers the sample reload listener, the tick, world exit and shutdown, and starts the audio thread. */
    public static void bootstrap() {
        if (bootstrapped) return;
        bootstrapped = true;
        ResourceLoader loader = ResourceLoader.get(PackType.CLIENT_RESOURCES);
        loader.registerReloader(PianoSampleReloader.ID, new PianoSampleReloader(ENGINE, SOUNDS));
        loader.addReloaderOrdering(ResourceReloaderKeys.Client.SOUNDS, PianoSampleReloader.ID);
        ClientTickEvents.END_CLIENT_TICK.register(PianoAudio::tick);
        WorldExit.register(WorldExit.Step.FORGET, () -> {
            ENGINE.stopAll();
            HOST.clear();
            SOUNDS.clearCustom();
            SUBTITLES.clear();
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> ENGINE.shutdown());
        ENGINE.start();
    }

    static AudioEngine engine() {
        return ENGINE;
    }

    private static void tick(Minecraft mc) {
        HOST.tick(mc);
        Set<String> custom = new HashSet<>();
        for (PianoSnapshot piano : HOST.snapshots()) {
            if (piano.settings().instrument() == Instrument.CUSTOM) custom.add(piano.settings().customSound());
        }
        SOUNDS.keepCustom(mc, custom);
        SUBTITLES.tick(mc);
    }

    /** True when sound can play: a live OpenAL context, voices, and the grand piano samples loaded. */
    public static boolean ready() {
        return ENGINE.ready();
    }

    /** Why the piano cannot sound now (no audio device, no voices, no samples), or {@link AudioEngine.Trouble#NONE}. */
    public static AudioEngine.Trouble trouble() {
        return ENGINE.trouble();
    }

    /** How long a note takes from being started to leaving the speakers, in milliseconds; -1 when the device does not say. */
    public static long outputDelayMillis() {
        long nanos = ENGINE.outputLatencyNanos();
        return nanos < 0 ? -1 : Math.round(nanos / 1e6);
    }

    /** The audio device's clock now (System.nanoTime() when the device has no clock). */
    public static long deviceNanos() {
        return ENGINE.deviceNanos();
    }

    /** Converts a {@link System#nanoTime()} instant to device-clock nanoseconds. */
    public static long toDevice(long systemNanos) {
        return ENGINE.toDevice(systemNanos);
    }

    public static void noteOn(Voicing v, int key, int velocity, long atDeviceNanos) {
        know(v);
        ENGINE.noteOn(v, key, velocity, atDeviceNanos);
    }

    public static void noteOff(Voicing v, int key, long atDeviceNanos) {
        ENGINE.noteOff(v, key, atDeviceNanos);
    }

    public static void pedal(Voicing v, Pedal pedal, boolean down, long atDeviceNanos) {
        know(v);
        ENGINE.pedal(v, pedal, down, atDeviceNanos);
    }

    /** Releases everything {@code v} plays; {@code immediate} fades it out in 10 ms and cancels its queued events. */
    public static void allNotesOff(Voicing v, boolean immediate) {
        ENGINE.allNotesOff(v, immediate);
    }

    /**
     * Stops {@code v} as if all its dampers fell: what it had queued never sounds, and what rings dies
     * away within a second, as when a pianist stops (a song ending, pausing or stopping).
     */
    public static void dampAll(Voicing v) {
        ENGINE.dampAll(v);
    }

    /** A metronome tick at the listener; the accent is louder and higher. */
    public static void click(boolean accent, float volume) {
        ENGINE.click(accent, volume);
    }

    /**
     * How far from a piano this player hears it: its range, but no further than the chunks they have
     * loaded reach (beyond them the server sends nothing, so the sound fades out before that).
     */
    public static int hearingRange(PianoSettings settings) {
        return Math.max(1, Math.min(settings.range(), HOST.loadedRadius()));
    }

    /**
     * The first MIDI note the grand piano samples in use let ring undamped (the top keys have no
     * dampers), as the loaded sample map says; -1 while none have loaded.
     */
    public static int undampedFrom() {
        return ENGINE.undampedFrom();
    }

    /**
     * The Brighter tones (0..1) at which the grand piano, played at {@code velocity}, strikes with a harder
     * hammer, as the loaded samples have it; empty when no harder hammer is in reach, null while no samples
     * have loaded. Between them, and past the last, Brighter only thins the lows.
     */
    public static @Nullable List<Float> harderHammers(int velocity) {
        return ENGINE.harderHammers(velocity);
    }

    public static int activeVoices() {
        return ENGINE.activeVoices();
    }

    /**
     * Piano voices this player can hear: not muted for them by a volume, not out of earshot, not died
     * away; the metronome does not count, nor does anything while the game is paused.
     */
    public static int audibleVoices() {
        return ENGINE.audibleVoices();
    }

    /**
     * True when {@code piano} is muted for this player by their own choice: it is someone else's (they did
     * not place it and do not sit at it) and their Other pianos volume is at 0, so of it they hear only the
     * notes they play there themselves. Client thread.
     */
    public static boolean mutedForListener(BlockPos piano) {
        Minecraft mc = Minecraft.getInstance();
        return ClientPrefs.get().otherPianosVolume <= 0f && !GameAudioHost.ownNow(mc, piano);
    }

    /** The engine's counters: voices, queued events, held keys, stolen and silent notes, errors. */
    public static AudioEngine.Stats stats() {
        return ENGINE.stats();
    }

    public static List<String> debugLines() {
        return ENGINE.debugLines();
    }

    /** The most recent sounds started (oldest first), for diagnostics and tests. */
    public static List<StartedNote> recentStarts() {
        return ENGINE.recentStarts();
    }

    /** On the client thread, snapshot a piano now so its first note does not wait for one. */
    private static void know(Voicing v) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.isSameThread()) HOST.ensure(mc, v.piano());
    }
}
