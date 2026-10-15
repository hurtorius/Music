package org.hurtorius.music.client.audio;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.hurtorius.music.piano.Instrument;
import org.hurtorius.music.piano.Pedal;
import org.hurtorius.music.piano.PianoSettings;
import org.jspecify.annotations.Nullable;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALC11;
import org.lwjgl.openal.EXTEfx;
import org.lwjgl.openal.SOFTDeviceClock;
import org.lwjgl.openal.SOFTLoopPoints;
import org.lwjgl.openal.SOFTSourceStartDelay;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ShortBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * The piano's sound engine: a pool of OpenAL sources in Minecraft's own context, driven by one
 * {@code music-audio} thread.
 *
 * <p>Producers (any thread) submit timestamped events; the audio thread orders them in time, keeps each
 * {@link Voicing}'s keys and pedals in a {@link PedalBoard}, starts voices sample-accurately with
 * {@code alSourcePlayAtTimeSOFT} (or on time from the thread when that extension is missing), runs the
 * release and fade envelopes, follows each piano's settings while voices sound, and gives voices up by
 * {@link StealPolicy} so the pool size is never exceeded. A grand piano note held past its recording
 * repeats the loop it ends in and fades at the string's own rate.</p>
 *
 * <p>Every OpenAL call the engine makes happens under one lock. Minecraft destroys its context from
 * other threads (resource reload, device change, crash); {@link #contextDestroying(long)} takes the lock
 * with a timeout and deletes every source, buffer and effect first, and {@link #contextCreated()} makes
 * the thread build them again in the new context. The grand piano's samples let go of their PCM once
 * uploaded, so a new context gets them decoded again from the bytes the bank keeps, one by one, a sample
 * a note waits for first: the piano plays on while the rest follow.</p>
 *
 * <p>{@link #step()} is one iteration of the audio thread and can be driven directly (offline tests).</p>
 */
public final class AudioEngine {
    static final Logger LOG = LoggerFactory.getLogger("Music");
    public static final int MIN_VOICES = 16, MAX_VOICES = 96;
    /** Free voices kept in hand by fading the least important one early, so new notes never cut a voice. */
    static final int RESERVE = 2;
    /** With start-delay support, events are handed to OpenAL this long before they are due. */
    static final long LEAD_NANOS = 25_000_000L;
    /** Events of a voicing wait this long for a piano snapshot or a loading sound before playing silently. */
    static final long WAIT_NANOS = 250_000_000L;
    /**
     * While the grand piano's samples are decoded again for a new audio device, events wait this long: a
     * note's own sample comes first, in tens of milliseconds, but a slow computer may take a few times that.
     */
    static final long REFILL_WAIT_NANOS = 1_000_000_000L;
    /** Events further ahead than this are a clock mix-up and are refused. */
    static final long MAX_AHEAD_NANOS = 10_000_000_000L;
    static final long STEAL_FADE_NANOS = 10_000_000L, KILL_FADE_NANOS = 10_000_000L;
    static final long RESTRIKE_FADE_NANOS = 80_000_000L, GONE_FADE_NANOS = 80_000_000L;
    static final float NOTE_BLOCK_RELEASE_SECONDS = 0.2f;
    /** When a voicing's dampers all fall at once ({@link #dampAll}), nothing rings longer than this, the undamped top strings included. */
    static final float DAMP_ALL_SECONDS = 1.0f;
    /** A looped sample played where OpenAL cannot loop fades out over its last half second instead. */
    static final long UNLOOPED_FADE_NANOS = 500_000_000L;
    /** Velocity tracking for note block and custom sounds (the grand piano uses its sample map's value). */
    static final float SOUND_VELOCITY_TRACK = 0.73f;
    /** Soft and sostenuto pedals make a quieter thump than the sustain pedal's damper lift. */
    static final float SMALL_PEDAL_DB = -6f;
    static final long LID_SWING_NANOS = 1_000_000_000L;
    /** A piano moving into or out of the listener's "other pianos" volume gets there over this long. */
    static final long LISTENER_RAMP_NANOS = 200_000_000L;
    static final long STATE_CHECK_NANOS = 15_000_000L;
    /** How often the output delay is read from the device. */
    static final long LATENCY_CHECK_NANOS = 500_000_000L;
    static final int UPLOAD_BUDGET_BYTES = 2 << 20;
    static final int RECENT_LIMIT = 256;
    /** Assumed natural decay of a note while its recording plays, for judging which sounding voice is quietest. */
    static final double SUSTAIN_DECAY_DB_PER_SECOND = 3.0;
    /** A voice is over once its envelope is this far down (-60 dB). */
    static final float SILENT = 0.001f;
    private static final AtomicInteger GENERATIONS = new AtomicInteger();

    private enum Kind { NOTE_ON, NOTE_OFF, PEDAL, ALL_OFF, DAMP_ALL, CLICK, STOP_ALL }

    /** A submitted event; {@code at} is device-clock nanoseconds (0 = now), {@code born} is System.nanoTime(). */
    private record Event(Kind kind, @Nullable Voicing voicing, int key, int velocity, @Nullable Pedal pedal, boolean flag, float volume,
                         long at, long born) {}

    private enum Wait { DONE, WAIT }

    /** Why the piano cannot sound, as far as the engine can tell; {@link #NONE} when it can. */
    public enum Trouble {
        /** It can sound. */
        NONE,
        /** It is starting up or loading its samples. */
        STARTING,
        /** Minecraft's sound engine did not start: there is no audio device. */
        NO_DEVICE,
        /** The engine could not start in Minecraft's sound engine. */
        FAILED,
        /** OpenAL had no sources left for the piano. */
        NO_VOICES,
        /** The grand piano's samples could not be loaded. */
        NO_SAMPLES
    }

    private final AudioHost host;
    private final SoundLibrary sounds;
    private final Executor decoder;
    private final ReentrantLock lock = new ReentrantLock();
    private final ConcurrentLinkedQueue<Event> incoming = new ConcurrentLinkedQueue<>();
    private final ClockSync clock = new ClockSync();
    private final Object signal = new Object();
    private volatile boolean signalled;

    // ---- context (guarded by lock)
    private volatile boolean contextPending;
    /** True while the audio thread builds the context Minecraft announced (between {@link #contextPending} and {@link #live}). */
    private volatile boolean building;
    private volatile boolean live;
    /** Bumped whenever Minecraft starts destroying its context, so a build racing it never goes live. */
    private volatile int epoch;
    private int generation;
    private long device;
    private final LongSupplier deviceClockReader = () -> SOFTDeviceClock.alcGetInteger64vSOFT(device, SOFTDeviceClock.ALC_DEVICE_CLOCK_SOFT);
    private volatile boolean startDelay, deviceClock, efx, loopPoints;
    private int reverbEffect, reverbSlot, directFilter, sendFilter;
    private final List<Voice> voices = new ArrayList<>();
    private final Set<PcmSample> uploaded = new LinkedHashSet<>();
    private final Deque<PcmSample> uploadQueue = new ArrayDeque<>();
    private final EventQueue<Event> queue = new EventQueue<>();
    private final Map<Voicing, PedalBoard> boards = new HashMap<>();
    private final Map<BlockPos, Float> lids = new HashMap<>();
    private @Nullable GrandPianoBank activeBank;
    /** Whether the active bank has been checked for samples this context can no longer upload. */
    private boolean bankChecked;
    /** Grand piano samples decoded again for this context, to upload (filled by the decoders). */
    private final ConcurrentLinkedQueue<PcmSample> refilled = new ConcurrentLinkedQueue<>();
    /** When the samples began to be decoded again for this context (System.nanoTime()), 0 when they are not. */
    private long refillStarted;
    private boolean paused;
    private long pausedAt, lastStep, stepNow, lastStateCheck, latencyReadAt;
    private int pedalVariant;
    private boolean deferred;
    private long nextWaitNanos = 100_000_000L;
    private final PcmSample clickAccent = Woodblock.sample(true), clickTick = Woodblock.sample(false);

    // ---- shared state for other threads
    private final AtomicReference<@Nullable GrandPianoBank> bank = new AtomicReference<>();
    private volatile @Nullable String bankProblem;
    private volatile boolean bankReady, bankExpected;
    private volatile Trouble trouble = Trouble.STARTING;
    private volatile String status = "waiting for Minecraft's sound engine";
    private volatile String deviceName = "", alVersion = "", effects = "off";
    private volatile int activeCount, audibleCount, poolSize, queuedCount, holdingCount;
    private volatile long outputLatencyNanos = -1;
    private final AtomicLong stolen = new AtomicLong(), dropped = new AtomicLong(), alErrors = new AtomicLong(), stepFailures = new AtomicLong();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private final ArrayDeque<StartedNote> recent = new ArrayDeque<>();
    /** When each piano last started a note this listener can hear (System.nanoTime()), for subtitles. */
    private final Map<BlockPos, Long> sounded = new ConcurrentHashMap<>();
    private @Nullable Thread thread;
    private volatile boolean running;

    /** An engine that decodes the grand piano again, when a new context needs it, on the common pool (offline use). */
    public AudioEngine(AudioHost host, SoundLibrary sounds) {
        this(host, sounds, ForkJoinPool.commonPool());
    }

    /** An engine that decodes the grand piano again, when a new context needs it, on {@code decoder}. */
    public AudioEngine(AudioHost host, SoundLibrary sounds, Executor decoder) {
        this.host = host;
        this.sounds = sounds;
        this.decoder = decoder;
    }

    // =================================================================== producers (any thread)

    public void noteOn(Voicing voicing, int key, int velocity, long atDevice) {
        Objects.requireNonNull(voicing, "voicing");
        if (!validKey(key)) return;
        if (velocity <= 0) {
            noteOff(voicing, key, atDevice);
            return;
        }
        submit(new Event(Kind.NOTE_ON, voicing, key, Math.min(GrandPianoSpec.MAX_VELOCITY, velocity), null, false, 0f, atDevice, System.nanoTime()));
    }

    public void noteOff(Voicing voicing, int key, long atDevice) {
        Objects.requireNonNull(voicing, "voicing");
        if (!validKey(key)) return;
        submit(new Event(Kind.NOTE_OFF, voicing, key, 0, null, false, 0f, atDevice, System.nanoTime()));
    }

    public void pedal(Voicing voicing, Pedal pedal, boolean down, long atDevice) {
        Objects.requireNonNull(voicing, "voicing");
        Objects.requireNonNull(pedal, "pedal");
        submit(new Event(Kind.PEDAL, voicing, -1, 0, pedal, down, 0f, atDevice, System.nanoTime()));
    }

    /** Lets go of everything a voicing plays; {@code immediate} also cuts sound (10 ms) and drops its queued events. */
    public void allNotesOff(Voicing voicing, boolean immediate) {
        Objects.requireNonNull(voicing, "voicing");
        submit(new Event(Kind.ALL_OFF, voicing, -1, 0, null, immediate, 0f, 0L, System.nanoTime()));
    }

    /**
     * Stops a voicing as if every damper fell at once: its queued events are dropped and notes not
     * started yet never start, while what sounds dies away as a damped string does (within
     * {@link #DAMP_ALL_SECONDS}, the undamped top strings too), with the dampers' string resonance.
     */
    public void dampAll(Voicing voicing) {
        Objects.requireNonNull(voicing, "voicing");
        submit(new Event(Kind.DAMP_ALL, voicing, -1, 0, null, false, 0f, 0L, System.nanoTime()));
    }

    /** A metronome click at the listener, now. */
    public void click(boolean accent, float volume) {
        float v = Float.isFinite(volume) ? Math.max(0f, Math.min(1f, volume)) : 0f;
        submit(new Event(Kind.CLICK, null, -1, 0, null, accent, v, 0L, System.nanoTime()));
    }

    /** Silences everything quickly and forgets all keys, pedals and queued events (leaving a world). */
    public void stopAll() {
        submit(new Event(Kind.STOP_ALL, null, -1, 0, null, true, 0f, 0L, System.nanoTime()));
    }

    /**
     * Whether a resource reload is loading new grand piano samples: until they come (or the reload
     * ends without them) the samples in use are not decoded again for a new context.
     */
    public void expectBank(boolean expected) {
        bankExpected = expected;
        if (!expected) wake();
    }

    /** Installs a newly loaded grand piano sample bank (replacing the previous one). */
    public void setBank(GrandPianoBank next) {
        bankProblem = next.all().isEmpty() ? "no grand piano sample could be decoded" : null;
        bank.set(next);
        bankExpected = false;
        wake();
    }

    /** The first MIDI note the grand piano samples in use let ring undamped, or -1 while none have loaded. */
    public int undampedFrom() {
        GrandPianoBank b = bank.get();
        return b == null ? -1 : b.spec.undampedFrom;
    }

    /**
     * The brighter tones at which the grand piano samples in use strike a note of {@code velocity} with a
     * harder hammer ({@link GrandPianoSpec#harderHammers}); null while none have loaded.
     */
    public @Nullable List<Float> harderHammers(int velocity) {
        GrandPianoBank b = bank.get();
        return b == null ? null : b.spec.harderHammers(velocity);
    }

    /** Records that the grand piano samples could not be loaded at all; the previous bank keeps playing. */
    public void bankFailed(String problem) {
        bankProblem = problem;
        bankExpected = false;
        wake();
    }

    private static boolean validKey(int key) {
        return key >= 0 && key < PedalBoard.KEYS;
    }

    private void submit(Event e) {
        incoming.add(e);
        wake();
    }

    private void wake() {
        synchronized (signal) {
            signalled = true;
            signal.notifyAll();
        }
    }

    // =================================================================== clock and status (any thread)

    /** Device clock now, estimated from System.nanoTime(); System.nanoTime() when there is no device clock. */
    public long deviceNanos() {
        return toDevice(System.nanoTime());
    }

    public long toDevice(long systemNanos) {
        return deviceClockValid() ? clock.toDevice(systemNanos) : systemNanos;
    }

    private boolean deviceClockValid() {
        return live && clock.valid();
    }

    /** True when the engine runs in a live OpenAL context with voices and the grand piano is loaded. */
    public boolean ready() {
        return live && bankReady && poolSize > 0;
    }

    /** Why the piano cannot sound now, or {@link Trouble#NONE}. */
    public Trouble trouble() {
        if (ready()) return Trouble.NONE;
        if (!live) return trouble;
        if (poolSize == 0) return Trouble.NO_VOICES;
        GrandPianoBank b = bank.get();
        return bankProblem != null && (b == null || b.all().isEmpty()) ? Trouble.NO_SAMPLES : Trouble.STARTING;
    }

    /**
     * How long a sound takes from being started to leaving the speakers (OpenAL's mixing buffer), in
     * nanoseconds; -1 when the device does not say.
     */
    public long outputLatencyNanos() {
        return live && deviceClock ? outputLatencyNanos : -1;
    }

    public int activeVoices() {
        return activeCount;
    }

    /**
     * Piano voices the listener can hear: louder at the listener than -60 dB, counting volumes (theirs and
     * the piano's), distance and how far the note has died away. A piano muted for them, or out of earshot,
     * has none; the metronome's clicks are not a piano, and nothing is heard while the game is paused.
     */
    public int audibleVoices() {
        return audibleCount;
    }

    /**
     * Pianos that started a note the listener can hear at or after {@code sinceNanos} (System.nanoTime()),
     * with when they last did.
     */
    public Map<BlockPos, Long> soundedSince(long sinceNanos) {
        Map<BlockPos, Long> out = new HashMap<>();
        for (Map.Entry<BlockPos, Long> e : sounded.entrySet()) if (e.getValue() >= sinceNanos) out.put(e.getKey(), e.getValue());
        sounded.values().removeIf(t -> t < sinceNanos);
        return out;
    }

    /**
     * Engine counters.
     *
     * @param active  voices in use
     * @param pool    OpenAL sources the engine owns
     * @param queued  events waiting for their time
     * @param stolen  voices given up early to make room
     * @param silent  notes that could not sound (no piano, no sample, out of range, paused, no context)
     * @param alErrors OpenAL errors seen after the engine's calls
     * @param stepErrors exceptions in the audio thread
     * @param holding  voicings (a player at a piano, or a piano playing by itself) with keys or pedals still down
     */
    public record Stats(int active, int pool, int queued, long stolen, long silent, long alErrors, long stepErrors, int holding) {}

    public Stats stats() {
        return new Stats(activeCount, poolSize, queuedCount, stolen.get(), dropped.get(), alErrors.get(), stepFailures.get(), holdingCount);
    }

    public List<StartedNote> recentStarts() {
        synchronized (recent) {
            return List.copyOf(recent);
        }
    }

    public List<String> debugLines() {
        List<String> lines = new ArrayList<>();
        Stats stats = stats();
        if (live) {
            long latency = outputLatencyNanos();
            lines.add(String.format(Locale.ROOT, "Music audio: on, %s on %s, output delay %s", alVersion, deviceName,
                latency < 0 ? "unknown" : String.format(Locale.ROOT, "%.0f ms", latency / 1e6)));
            lines.add(String.format(Locale.ROOT, "Voices: %d/%d in use, %d queued, %d holding keys or pedals; scheduled starts: %s; device clock: %s; EFX: %s; loops: %s",
                stats.active(), stats.pool(), stats.queued(), stats.holding(), startDelay ? "sample-accurate" : "timed by the audio thread",
                deviceClock ? "yes" : "no (system clock)", effects, loopPoints ? "yes" : "no (looped notes fade at their end)"));
        } else {
            lines.add("Music audio: off, " + status);
        }
        GrandPianoBank b = bank.get();
        String problem = bankProblem;
        if (b != null) {
            lines.add(String.format(Locale.ROOT, "Grand piano \"%s\": %s samples, %.1f MB of PCM (sent to OpenAL, then let go of), %.1f MB kept to decode again, from %s",
                b.spec.name, b.coverage(), b.bytes() / 1e6, b.encodedBytes() / 1e6, b.source));
            int left = b.refillsLeft();
            if (left > 0) lines.add("Grand piano: decoding " + left + " samples again for this sound device");
            if (!b.failures.isEmpty()) lines.add("Grand piano samples that failed: " + String.join(", ", b.failures));
        }
        if (problem != null) lines.add("Grand piano problem: " + problem);
        if (b == null && problem == null) lines.add("Grand piano: loading");
        lines.add("Sounds: " + sounds.describe());
        lines.add(String.format(Locale.ROOT, "Stolen voices: %d, silent notes: %d, OpenAL errors: %d, engine errors: %d",
            stats.stolen(), stats.silent(), stats.alErrors(), stats.stepErrors()));
        return lines;
    }

    // =================================================================== lifecycle (Minecraft's threads)

    /** Minecraft created a context ({@code Library#init} finished): build sources, effects and buffers in it. */
    public void contextCreated() {
        contextPending = true;
        status = "starting";
        trouble = Trouble.STARTING;
        wake();
    }

    /**
     * Minecraft is about to destroy its context ({@code Library#cleanup}). Stops and deletes everything
     * the engine made in it, waiting at most {@code timeoutMillis} for the audio thread.
     */
    public void contextDestroying(long timeoutMillis) {
        epoch++;
        live = false;
        contextPending = false;
        stopped();
        boolean locked;
        try {
            locked = lock.tryLock(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            locked = false;
        }
        if (!locked) {
            LOG.error("Music audio: the audio thread did not let go within {} ms; OpenAL objects are left to the closing device", timeoutMillis);
            return;
        }
        try {
            releaseContext();
            // Again: a build that was finishing as the context went could have said it was on.
            stopped();
        } catch (Throwable t) {
            LOG.error("Music audio: failed to release OpenAL objects before the context closed", t);
        } finally {
            lock.unlock();
        }
    }

    /** No context until Minecraft makes a new one; should none come, {@link #checkStarted} says there is no device. */
    private void stopped() {
        status = "Minecraft's sound engine stopped (restarting, or waiting for an audio device)";
        trouble = Trouble.STARTING;
    }

    /**
     * Called once Minecraft has tried to start its sound engine (at launch, on a resource reload, when the
     * audio device changes or is lost). When that produced no context there is no usable audio device:
     * say so instead of failing silently. A context that came but could not be used keeps its own reason.
     */
    public void checkStarted() {
        if (contextPending || building || live || trouble != Trouble.STARTING) return;
        status = "no audio device (Minecraft's sound engine did not start)";
        trouble = Trouble.NO_DEVICE;
        LOG.warn("Music audio: Minecraft's sound engine is not running (no audio device?); the piano stays silent");
    }

    /** Starts the {@code music-audio} thread. */
    public synchronized void start() {
        if (thread != null) return;
        running = true;
        Thread t = new Thread(this::run, "music-audio");
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY);
        t.setUncaughtExceptionHandler((th, e) -> LOG.error("Music audio thread died", e));
        thread = t;
        t.start();
    }

    /** Stops the thread (the game is closing); OpenAL objects are released by {@link #contextDestroying}. */
    public synchronized void shutdown() {
        Thread t = thread;
        if (t == null) return;
        running = false;
        wake();
        try {
            t.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        thread = null;
    }

    private void run() {
        while (running) {
            long wait;
            try {
                step();
                wait = nextWaitNanos;
            } catch (Throwable t) {
                long n = stepFailures.incrementAndGet();
                if (n <= 10 || n % 1000 == 0) LOG.error("Music audio: engine step failed ({} so far)", n, t);
                wait = 50_000_000L;
            }
            await(wait);
        }
    }

    private void await(long nanos) {
        if (nanos <= 0) return;
        long deadline = System.nanoTime() + nanos;
        if (nanos > 1_500_000L) {
            synchronized (signal) {
                if (!signalled) {
                    try {
                        signal.wait(Math.max(1, (nanos - 1_000_000L) / 1_000_000L));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        running = false;
                    }
                }
                signalled = false;
            }
        }
        // The last stretch is spun so on-time starts without the start-delay extension stay within a millisecond.
        while (!startDelay && System.nanoTime() < deadline && !signalled && running) Thread.onSpinWait();
    }

    // =================================================================== the audio thread

    /** One iteration: (re)build the context if needed, then handle events, envelopes, settings and uploads. */
    public void step() {
        lock.lock();
        try {
            if (contextPending) setupContext();
            syncBank();
            if (!live) {
                discardIncoming();
                nextWaitNanos = 200_000_000L;
                return;
            }
            long now = now();
            stepNow = now;
            retireReplaced();
            refillIfLetGo();
            takeRefilled();
            resizePool(now);
            followPause(now);
            drainIncoming(now);
            deferred = false;
            processDue(now);
            if (!paused) updateVoices(now);
            keepReserve(now);
            uploadSome();
            readLatency(now);
            alOk("audio step");
            lastStep = now;
            Vec3 listener = host.listener();
            int used = 0, audible = 0;
            for (Voice v : voices) {
                if (!v.used) continue;
                used++;
                // A piano's own voices (not the metronome), and none while the game is paused: nothing sounds then.
                if (!paused && v.piano != null && v.levelAt(now, listener) >= SILENT) audible++;
            }
            activeCount = used;
            audibleCount = audible;
            poolSize = voices.size();
            queuedCount = queue.size();
            holdingCount = boards.size();
            nextWaitNanos = idleNanos(now);
        } finally {
            lock.unlock();
        }
    }

    private long now() {
        if (!deviceClock) return System.nanoTime();
        return clock.read(deviceClockReader);
    }

    private long idleNanos(long now) {
        long wait = 100_000_000L;
        boolean any = false, moving = false, fading = false;
        for (Voice v : voices) {
            if (!v.used) continue;
            any = true;
            if (v.fadeAt != Long.MAX_VALUE) fading = true;
            if (v.releaseAt != Long.MAX_VALUE) moving = true;
        }
        // OpenAL takes up property changes once per mixer update (several milliseconds), so stepping more
        // often only adds device clock reads, and those spin while the mixer is busy.
        if (any) wait = 15_000_000L;
        if (moving) wait = 10_000_000L;
        if (fading) wait = 5_000_000L;
        for (Map.Entry<BlockPos, Float> lid : lids.entrySet()) {
            PianoSnapshot s = host.piano(lid.getKey());
            if (s != null && lid.getValue() != (s.settings().lidOpen() ? 0f : 1f)) wait = Math.min(wait, 10_000_000L);
        }
        if (!uploadQueue.isEmpty()) wait = Math.min(wait, 1_000_000L);
        long next = queue.nextTime();
        if (next != Long.MAX_VALUE) {
            long untilNext = next - (startDelay ? LEAD_NANOS : 0) - now;
            // Held-back events are due already; look again shortly instead of spinning on them.
            wait = Math.min(wait, deferred ? Math.max(untilNext, 5_000_000L) : untilNext);
        }
        return wait;
    }

    // ------------------------------------------------------------------ context

    private void setupContext() {
        building = true;
        contextPending = false;
        try {
            if (device != 0) {
                // A new context without a cleanup in between: the old objects died with the old context.
                forgetContext();
            }
            buildContext();
        } catch (RuntimeException e) {
            LOG.error("Music audio: could not start in Minecraft's OpenAL context; the piano stays silent", e);
            status = "failed to start: " + e;
            trouble = Trouble.FAILED;
            live = false;
            forgetContext();
        } finally {
            building = false;
        }
    }

    private void buildContext() {
        int startedIn = epoch;
        long context = ALC10.alcGetCurrentContext();
        long dev = context == 0 ? 0 : ALC10.alcGetContextsDevice(context);
        if (dev == 0) {
            status = "no OpenAL context";
            trouble = Trouble.NO_DEVICE;
            LOG.warn("Music audio: Minecraft reported a new sound context but none is current; the piano stays silent");
            return;
        }
        device = dev;
        generation = GENERATIONS.incrementAndGet();
        AL10.alGetError();
        startDelay = AL.getCapabilities().AL_SOFT_source_start_delay;
        loopPoints = AL.getCapabilities().AL_SOFT_loop_points;
        deviceClock = ALC10.alcIsExtensionPresent(dev, "ALC_SOFT_device_clock");
        efx = ALC10.alcIsExtensionPresent(dev, "ALC_EXT_EFX") && setupEfx();
        effects = !efx ? "off" : reverbEffect != 0 ? "tone and reverb" : "tone only";
        String name = ALC10.alcGetString(dev, ALC11.ALC_ALL_DEVICES_SPECIFIER);
        if (name == null) name = ALC10.alcGetString(dev, ALC10.ALC_DEVICE_SPECIFIER);
        deviceName = name == null ? "unknown device" : name;
        String version = AL10.alGetString(AL10.AL_VERSION);
        alVersion = "OpenAL " + (version == null ? "?" : version);
        clock.reset();
        paused = false;
        int wanted = wantedVoices();
        while (voices.size() < wanted && addVoice()) {
            // grows the pool one source at a time until it is full or the device has no more
        }
        poolSize = voices.size();
        if (voices.isEmpty()) {
            status = "OpenAL has no free sources for the piano";
            trouble = Trouble.NO_VOICES;
            LOG.error("Music audio: could not create any OpenAL source; the piano stays silent");
            return;
        }
        if (voices.size() < wanted) LOG.warn("Music audio: only {} of {} voices could be created", voices.size(), wanted);
        // A new device starts its clock again (usually far below the old one): drop every old-clock time,
        // or the stopped-source check waits until the new clock catches up and finished voices stay in use.
        lastStep = lastStateCheck = pausedAt = stepNow = now();
        latencyReadAt = lastStep - LATENCY_CHECK_NANOS;
        outputLatencyNanos = -1;
        if (activeBank != null) queueUploads(activeBank);
        bankChecked = false;
        if (startedIn != epoch) {
            // Minecraft began tearing this context down while it was being built; its cleanup deletes what was made.
            return;
        }
        live = true;
        status = "on";
        trouble = Trouble.NONE;
        alOk("context setup");
        LOG.info("Music audio started on {} ({}): {} voices, scheduled starts {}, device clock {}, EFX {}, loop points {}", deviceName, alVersion, voices.size(),
            startDelay ? "sample-accurate" : "by thread", deviceClock ? "on" : "off", efx ? "on" : "off", loopPoints ? "on" : "off");
    }

    private boolean setupEfx() {
        AL10.alGetError();
        reverbEffect = EXTEfx.alGenEffects();
        EXTEfx.alEffecti(reverbEffect, EXTEfx.AL_EFFECT_TYPE, EXTEfx.AL_EFFECT_EAXREVERB);
        if (AL10.alGetError() == AL10.AL_NO_ERROR) {
            setReverbParameters(reverbEffect);
        } else {
            EXTEfx.alEffecti(reverbEffect, EXTEfx.AL_EFFECT_TYPE, EXTEfx.AL_EFFECT_REVERB);
            if (AL10.alGetError() == AL10.AL_NO_ERROR) {
                EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_REVERB_DECAY_TIME, 2.2f);
                EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_REVERB_GAIN, 0.32f);
            } else {
                EXTEfx.alDeleteEffects(reverbEffect);
                reverbEffect = 0;
                LOG.warn("Music audio: this OpenAL has no reverb effect; reverb is off");
            }
        }
        if (reverbEffect != 0) {
            reverbSlot = EXTEfx.alGenAuxiliaryEffectSlots();
            EXTEfx.alAuxiliaryEffectSloti(reverbSlot, EXTEfx.AL_EFFECTSLOT_EFFECT, reverbEffect);
        }
        directFilter = EXTEfx.alGenFilters();
        EXTEfx.alFilteri(directFilter, EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_BANDPASS);
        sendFilter = EXTEfx.alGenFilters();
        EXTEfx.alFilteri(sendFilter, EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_LOWPASS);
        int error = AL10.alGetError();
        if (error == AL10.AL_NO_ERROR) return true;
        LOG.warn("Music audio: EFX setup failed ({}); tone and reverb are off", errorName(error));
        deleteEfx();
        return false;
    }

    /** A warm small hall: about 2.2 s, soft early reflections, darker tail. */
    private static void setReverbParameters(int effect) {
        EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_DENSITY, 1.0f);
        EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_DIFFUSION, 1.0f);
        EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_GAIN, 0.32f);
        EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_GAINHF, 0.6f);
        EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_GAINLF, 1.0f);
        EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_DECAY_TIME, 2.2f);
        EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_DECAY_HFRATIO, 0.75f);
        EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_DECAY_LFRATIO, 1.0f);
        EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_REFLECTIONS_GAIN, 0.25f);
        EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_REFLECTIONS_DELAY, 0.018f);
        EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_LATE_REVERB_GAIN, 1.0f);
        EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_LATE_REVERB_DELAY, 0.025f);
        EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_AIR_ABSORPTION_GAINHF, 0.994f);
        EXTEfx.alEffecti(effect, EXTEfx.AL_EAXREVERB_DECAY_HFLIMIT, 1);
    }

    private void deleteEfx() {
        if (reverbSlot != 0) {
            EXTEfx.alAuxiliaryEffectSloti(reverbSlot, EXTEfx.AL_EFFECTSLOT_EFFECT, EXTEfx.AL_EFFECT_NULL);
            EXTEfx.alDeleteAuxiliaryEffectSlots(reverbSlot);
        }
        if (reverbEffect != 0) EXTEfx.alDeleteEffects(reverbEffect);
        if (directFilter != 0) EXTEfx.alDeleteFilters(directFilter);
        if (sendFilter != 0) EXTEfx.alDeleteFilters(sendFilter);
        reverbSlot = reverbEffect = directFilter = sendFilter = 0;
    }

    /** Deletes every source, effect and buffer of the current context. Lock held. */
    private void releaseContext() {
        if (device == 0) return;
        for (Voice v : voices) {
            AL10.alSourceStop(v.source);
            AL10.alSourcei(v.source, AL10.AL_BUFFER, 0);
            AL10.alDeleteSources(v.source);
        }
        if (efx) deleteEfx();
        for (PcmSample s : uploaded) {
            if (s.generation == generation && s.buffer != 0) AL10.alDeleteBuffers(s.buffer);
        }
        alOk("releasing the context");
        forgetContext();
    }

    /**
     * Drops every handle into the current context and all playing state, without OpenAL calls. The keys
     * and pedals each voicing holds stay (a sustain pedal held through a device change still holds),
     * moved on by the pedal and key-up events that were queued. Lock held.
     */
    private void forgetContext() {
        live = false;
        voices.clear();
        efx = false;
        effects = "off";
        reverbSlot = reverbEffect = directFilter = sendFilter = 0;
        for (PcmSample s : uploaded) s.buffer = 0;
        uploaded.clear();
        uploadQueue.clear();
        device = 0;
        for (Event e : queue.pollUntil(Long.MAX_VALUE)) follow(e);
        lids.clear();
        paused = false;
        activeCount = 0;
        audibleCount = 0;
        poolSize = 0;
        queuedCount = 0;
        holdingCount = 0;
        outputLatencyNanos = -1;
    }

    private int wantedVoices() {
        return Math.max(MIN_VOICES, Math.min(MAX_VOICES, host.voiceLimit()));
    }

    private boolean addVoice() {
        AL10.alGetError();
        int source = AL10.alGenSources();
        if (AL10.alGetError() != AL10.AL_NO_ERROR || source == 0) return false;
        voices.add(new Voice(source));
        return true;
    }

    /** Grows or shrinks the pool to the player's voice limit without cutting sounding voices. */
    private void resizePool(long now) {
        int wanted = wantedVoices();
        if (voices.size() < wanted) {
            while (voices.size() < wanted && addVoice()) {
                // grows one source at a time
            }
            return;
        }
        for (Iterator<Voice> it = voices.iterator(); it.hasNext() && voices.size() > wanted; ) {
            Voice v = it.next();
            if (v.used) continue;
            AL10.alDeleteSources(v.source);
            it.remove();
        }
        int going = 0;
        for (Voice v : voices) if (v.used && v.fadeAt != Long.MAX_VALUE) going++;
        int over = voices.size() - wanted - going;
        while (over-- > 0) {
            int victim = victim(now, false);
            if (victim < 0) break;
            fade(voices.get(victim), now, STEAL_FADE_NANOS);
        }
    }

    /** Reads how far behind the speakers OpenAL mixes, every half second. */
    private void readLatency(long now) {
        if (!deviceClock || now - latencyReadAt < LATENCY_CHECK_NANOS) return;
        latencyReadAt = now;
        long latency = SOFTDeviceClock.alcGetInteger64vSOFT(device, SOFTDeviceClock.ALC_DEVICE_LATENCY_SOFT);
        int error = ALC10.alcGetError(device);
        outputLatencyNanos = error == ALC10.ALC_NO_ERROR && latency >= 0 ? latency : -1;
    }

    // ------------------------------------------------------------------ bank and uploads

    /** Takes up a bank that arrived; frees the samples and the PCM of the one it replaces. Runs with or without a context. */
    private void syncBank() {
        GrandPianoBank next = bank.get();
        if (next == activeBank) return;
        GrandPianoBank old = activeBank;
        if (old != null) {
            for (PcmSample s : old.all()) retire(s);
            old.release();
        }
        activeBank = next;
        refilled.clear();
        refillStarted = 0;
        bankChecked = false;
        uploadQueue.clear();
        if (next != null && live) queueUploads(next);
        bankReady = next != null && !next.all().isEmpty();
    }

    /**
     * Samples that went to an earlier context let go of their PCM: when this context still needs them,
     * the bank decodes them again (off this thread), in the order they are uploaded, mid velocities
     * first, unless a reload brings a new bank first. Each is uploaded as it comes; a note waits only
     * for its own ({@link #resolve}).
     */
    private void refillIfLetGo() {
        GrandPianoBank b = activeBank;
        if (bankChecked || b == null || bankExpected) return;
        bankChecked = true;
        List<PcmSample> lost = new ArrayList<>();
        for (PcmSample s : uploadOrder(b)) if (!isUploaded(s) && !s.hasPcm()) lost.add(s);
        if (lost.isEmpty()) return;
        LOG.info("Music audio: decoding {} grand piano samples again for this sound device", lost.size());
        refillStarted = System.nanoTime();
        b.refill(lost, decoder, new GrandPianoBank.Refilled() {
            @Override
            public void ready(PcmSample sample) {
                refilled.add(sample);
                wake();
            }

            @Override
            public void failed(PcmSample sample, String problem) {
                LOG.error("Music audio: could not decode {} again ({}); its notes stay silent until resources reload", sample.name, problem);
                bankProblem = "could not decode " + sample.name + " again: " + problem;
                wake();
            }
        });
    }

    /** Samples decoded again go to OpenAL first, before the rest of the upload queue; the piano is ready once none is left. */
    private void takeRefilled() {
        List<PcmSample> ready = new ArrayList<>();
        for (PcmSample s = refilled.poll(); s != null; s = refilled.poll()) ready.add(s);
        for (int i = ready.size() - 1; i >= 0; i--) uploadQueue.addFirst(ready.get(i));
        GrandPianoBank b = activeBank;
        boolean readyNow = b != null && !b.all().isEmpty() && !b.refilling();
        if (readyNow && refillStarted != 0) {
            LOG.info("Music audio: decoded the grand piano samples again in {} ms", (System.nanoTime() - refillStarted) / 1_000_000L);
            refillStarted = 0;
        }
        bankReady = readyNow;
    }

    private void queueUploads(GrandPianoBank b) {
        uploadQueue.clear();
        uploadQueue.addAll(uploadOrder(b));
    }

    /** Every sample of a bank: the notes first, mid velocities before the others, so what is played first is ready first. */
    private static List<PcmSample> uploadOrder(GrandPianoBank b) {
        Set<PcmSample> order = new LinkedHashSet<>();
        int layers = b.spec.layers();
        int[] layerOrder = new int[layers];
        int middle = layers / 2;
        for (int i = 0, n = 0; n < layers; i++) {
            int up = middle + i, down = middle - i - 1;
            if (up < layers) layerOrder[n++] = up;
            if (down >= 0 && n < layers) layerOrder[n++] = down;
        }
        for (int layer : layerOrder) {
            for (int r = 0; r < b.spec.regions.size(); r++) {
                PcmSample s = b.note(r, layer);
                if (s != null) order.add(s);
            }
        }
        order.addAll(b.all());
        return List.copyOf(order);
    }

    private void uploadSome() {
        int budget = UPLOAD_BUDGET_BYTES;
        while (budget > 0 && !uploadQueue.isEmpty()) {
            PcmSample s = uploadQueue.poll();
            if (isUploaded(s) || !s.hasPcm()) continue;
            if (!upload(s)) return;
            budget -= (int) Math.min(Integer.MAX_VALUE, s.bytes());
        }
    }

    private boolean isUploaded(PcmSample s) {
        return s.generation == generation && s.buffer != 0;
    }

    /** Sends a sample to OpenAL (with its loop), then lets go of its PCM unless it keeps it. False when it cannot be sent. */
    private boolean upload(PcmSample s) {
        if (isUploaded(s)) return true;
        ShortBuffer pcm = s.uploadView();
        // Let go of after going to an earlier context: the bank is being decoded again.
        if (pcm == null) return false;
        AL10.alGetError();
        int buffer = AL10.alGenBuffers();
        if (!alOk("creating a buffer for " + s.name)) return false;
        AL10.alBufferData(buffer, AL10.AL_FORMAT_MONO16, pcm, s.sampleRate);
        boolean ok = alOk("uploading " + s.name);
        PcmSample.Loop loop = s.loop();
        if (ok && loop != null && loopPoints) {
            // Loop points can only be set while no source uses the buffer: right after its data.
            AL11.alBufferiv(buffer, SOFTLoopPoints.AL_LOOP_POINTS_SOFT, new int[]{loop.start(), loop.end()});
            ok = alOk("setting the loop of " + s.name);
        }
        if (!ok) {
            AL10.alDeleteBuffers(buffer);
            AL10.alGetError();
            return false;
        }
        s.buffer = buffer;
        s.generation = generation;
        uploaded.add(s);
        if (!s.keepsPcm()) s.release();
        return true;
    }

    /** Frees the buffer of a sample that is no longer used (bank or sound replaced), stopping its voices. */
    private void retire(PcmSample s) {
        if (!isUploaded(s)) return;
        for (Voice v : voices) if (v.used && v.sample == s) free(v);
        AL10.alDeleteBuffers(s.buffer);
        s.buffer = 0;
        uploaded.remove(s);
        alOk("deleting " + s.name);
    }

    private void retireReplaced() {
        for (PcmSample s = sounds.pollRetired(); s != null; s = sounds.pollRetired()) {
            retire(s);
            s.release();
        }
    }

    // ------------------------------------------------------------------ events

    /** Without a context nothing sounds, but keys still come up and pedals still move: the boards follow them. */
    private void discardIncoming() {
        for (Event e = incoming.poll(); e != null; e = incoming.poll()) {
            if (e.kind == Kind.NOTE_ON || e.kind == Kind.CLICK) dropped.incrementAndGet();
            else follow(e);
        }
    }

    /** What an event does to the keys and pedals held, without sounding anything. */
    private void follow(Event e) {
        switch (e.kind) {
            case NOTE_OFF -> {
                PedalBoard board = boards.get(e.voicing);
                if (board != null) {
                    board.release(e.key);
                    if (board.idle()) boards.remove(e.voicing);
                }
            }
            case PEDAL -> {
                PedalBoard board = boards.computeIfAbsent(e.voicing, k -> new PedalBoard());
                board.pedal(e.pedal, e.flag);
                if (board.idle()) boards.remove(e.voicing);
            }
            case ALL_OFF, DAMP_ALL -> boards.remove(e.voicing);
            case STOP_ALL -> boards.clear();
            case NOTE_ON, CLICK -> {
                // Notes that never sounded leave no key down: their key-up finds nothing to release.
            }
        }
    }

    private void drainIncoming(long now) {
        for (Event e = incoming.poll(); e != null; e = incoming.poll()) {
            switch (e.kind) {
                case ALL_OFF -> {
                    if (e.flag) {
                        Voicing v = e.voicing;
                        queue.removeIf(q -> v.equals(q.voicing));
                        for (Voice voice : voices) if (voice.used && v.equals(voice.voicing)) fade(voice, now, KILL_FADE_NANOS);
                        boards.remove(v);
                    } else {
                        queue.add(now, e);
                    }
                }
                case DAMP_ALL -> dampAllNow(e.voicing, now);
                case STOP_ALL -> {
                    queue.clear();
                    boards.clear();
                    for (Voice voice : voices) if (voice.used) fade(voice, now, KILL_FADE_NANOS);
                }
                default -> {
                    long at = e.at <= now ? now : e.at;
                    if (at - now > MAX_AHEAD_NANOS) {
                        warnOnce("ahead", "Music audio: refused an event {} ms in the future (clock mix-up?)", (at - now) / 1_000_000L);
                        if (e.kind == Kind.NOTE_ON) dropped.incrementAndGet();
                        continue;
                    }
                    queue.add(at, e);
                }
            }
        }
    }

    /**
     * Every damper of a voicing falls now. Done as the event arrives, not queued: what the voicing is
     * told next (a new song's pedals) comes after it.
     */
    private void dampAllNow(Voicing v, long now) {
        queue.removeIf(q -> v.equals(q.voicing));
        PianoSnapshot piano = host.piano(v.piano());
        PedalBoard board = boards.remove(v);
        if (board != null) {
            BitSet falls = board.reset();
            for (int k = falls.nextSetBit(0); k >= 0; k = falls.nextSetBit(k + 1)) damp(v, k, now, piano);
        }
        for (Voice voice : voices) {
            if (!voice.used || !v.equals(voice.voicing)) continue;
            if (voice.startAt > now) {
                // Handed to OpenAL ahead of its time and not heard yet.
                fade(voice, now, KILL_FADE_NANOS);
            } else if (voice.kind == StartedNote.Kind.NOTE) {
                if (voice.releaseAt == Long.MAX_VALUE) release(voice, now, Math.min(releaseSeconds(voice), DAMP_ALL_SECONDS));
                else if (voice.releaseSeconds > DAMP_ALL_SECONDS) fade(voice, now, (long) (DAMP_ALL_SECONDS * 1e9));
            }
        }
    }

    private void processDue(long now) {
        long horizon = now + (startDelay ? LEAD_NANOS : 0);
        List<Event> due = queue.pollUntil(horizon);
        if (due.isEmpty()) return;
        Set<Voicing> blocked = new HashSet<>();
        List<Event> later = new ArrayList<>();
        for (Event e : due) {
            if (e.voicing != null && blocked.contains(e.voicing)) {
                later.add(e);
                continue;
            }
            long t = Math.max(e.at, now);
            boolean mayWait = System.nanoTime() - e.born < (refillStarted != 0 ? REFILL_WAIT_NANOS : WAIT_NANOS);
            if (apply(e, t, now, mayWait) == Wait.WAIT) {
                blocked.add(e.voicing);
                later.add(e);
            }
        }
        for (Event e : later) queue.add(e.at, e);
        deferred = !later.isEmpty();
    }

    /** Applies one event at device time {@code t}. Returns WAIT to hold the voicing's events a little longer. */
    private Wait apply(Event e, long t, long now, boolean mayWait) {
        if (e.kind == Kind.CLICK) {
            startClick(e, t);
            return Wait.DONE;
        }
        Voicing v = e.voicing;
        PianoSnapshot piano = host.piano(v.piano());
        if (piano == null && mayWait) {
            host.request(v.piano());
            return Wait.WAIT;
        }
        switch (e.kind) {
            case NOTE_ON -> {
                return noteOn(e, v, piano, t, now, mayWait);
            }
            case NOTE_OFF -> {
                PedalBoard board = boards.get(v);
                if (board == null) return Wait.DONE;
                if (board.release(e.key)) damp(v, e.key, t, piano);
                if (board.idle()) boards.remove(v);
            }
            case PEDAL -> pedalChange(v, e.pedal, e.flag, t, piano);
            case ALL_OFF -> {
                PedalBoard board = boards.remove(v);
                if (board != null) {
                    BitSet falls = board.reset();
                    for (int k = falls.nextSetBit(0); k >= 0; k = falls.nextSetBit(k + 1)) damp(v, k, t, piano);
                }
                for (Voice voice : voices) {
                    if (voice.used && voice.kind == StartedNote.Kind.NOTE && v.equals(voice.voicing) && voice.releaseAt == Long.MAX_VALUE) {
                        release(voice, t, releaseSeconds(voice));
                    }
                }
            }
            default -> throw new IllegalStateException("unexpected event " + e.kind);
        }
        return Wait.DONE;
    }

    private Wait noteOn(Event e, Voicing v, @Nullable PianoSnapshot piano, long t, long now, boolean mayWait) {
        int midi = e.key + 21;
        Sound sound = null;
        if (piano != null) {
            sound = resolve(piano.settings(), midi, e.velocity);
            if (sound.waiting && mayWait) return Wait.WAIT;
        }
        PedalBoard board = boards.computeIfAbsent(v, k -> new PedalBoard());
        board.press(e.key);
        for (Voice voice : voices) {
            if (voice.used && voice.kind == StartedNote.Kind.NOTE && voice.key == e.key && v.equals(voice.voicing) && voice.fadeAt == Long.MAX_VALUE) {
                fade(voice, t, RESTRIKE_FADE_NANOS);
            }
        }
        if (piano == null || sound == null || sound.sample == null || paused) {
            dropped.incrementAndGet();
            return Wait.DONE;
        }
        Vec3 listener = host.listener();
        int range = range(piano.settings());
        if (listener != null && piano.keyDistanceSq(e.key, listener) > (double) range * range) {
            dropped.incrementAndGet();
            return Wait.DONE;
        }
        Voice voice = claim(now, sound.sample);
        if (voice == null) return Wait.DONE;
        voice.kind = StartedNote.Kind.NOTE;
        voice.voicing = v;
        voice.key = e.key;
        voice.velocity = e.velocity;
        voice.instrument = piano.settings().instrument();
        voice.layer = sound.layer;
        if (!start(voice, now, t, (float) sound.pitch440, true, sound.gain, piano, e.key * 3, false, board.isDown(Pedal.SOFT))) return Wait.DONE;
        sounded.put(piano.pos(), System.nanoTime());
        record(voice, piano.settings());
        return Wait.DONE;
    }

    /** How far a piano is heard here: its range, but never past the chunks the listener has loaded. */
    private int range(PianoSettings settings) {
        return Math.max(1, Math.min(settings.range(), host.loadedRadius()));
    }

    /** What a key on a piano sounds like right now; {@code layer} is the grand piano's picked layer, -1 for other sounds. */
    private record Sound(@Nullable PcmSample sample, double pitch440, float gain, int layer, boolean waiting) {}

    private Sound resolve(PianoSettings settings, int midi, int velocity) {
        Instrument instrument = settings.instrument();
        if (instrument == Instrument.GRAND_PIANO) {
            GrandPianoBank b = activeBank;
            if (b == null) {
                if (bankProblem != null) warnOnce("bank", "Music audio: grand piano samples unavailable ({}); notes are silent", bankProblem);
                return new Sound(null, 1, 0, -1, bankProblem == null);
            }
            GrandPianoSpec spec = b.spec;
            int region = spec.regionIndex(midi);
            GrandPianoSpec.Region r = spec.regions.get(region);
            // A brighter tone strikes with a harder hammer: a louder layer's sound at this velocity's loudness.
            int layer = spec.strikeLayer(velocity, settings.tone());
            PcmSample sample = b.note(region, layer);
            if (sample != null && !isUploaded(sample) && !sample.hasPcm() && !b.failedAgain(sample)) {
                // Being decoded again for this audio device: this one goes first, and the note waits for it.
                b.hurry(sample);
                return new Sound(null, 1, 0, -1, true);
            }
            return new Sound(sample, GrandPianoSpec.pitch(r, midi, 440f), spec.velocityGain(r, velocity, layer), layer, false);
        }
        SoundLibrary.Lookup lookup;
        int root;
        if (instrument == Instrument.CUSTOM) {
            lookup = sounds.custom(settings.customSound());
            root = settings.customRoot();
        } else {
            lookup = sounds.instrument(instrument);
            root = instrument.rootMidi();
        }
        if (lookup.status() != SoundLibrary.Status.READY) return new Sound(null, 1, 0, -1, lookup.status() == SoundLibrary.Status.LOADING);
        PitchedSound pitched = lookup.sound();
        PitchedSound.Choice choice = pitched.choose(midi, root, settings.tuning());
        float v = velocity / (float) GrandPianoSpec.MAX_VELOCITY;
        float gain = ((1f - SOUND_VELOCITY_TRACK) + SOUND_VELOCITY_TRACK * v * v) * pitched.trim;
        return new Sound(choice.sample(), choice.pitch() * 440.0 / settings.tuning(), gain, -1, false);
    }

    private void pedalChange(Voicing v, Pedal pedal, boolean down, long t, @Nullable PianoSnapshot piano) {
        PedalBoard board = boards.get(v);
        if (board == null) {
            // Lifting a pedal nobody holds (its board went with a stop) changes nothing and must leave nothing behind.
            if (!down) return;
            board = new PedalBoard();
            boards.put(v, board);
        }
        if (board.isDown(pedal) == down) return;
        BitSet falls = board.pedal(pedal, down);
        for (int k = falls.nextSetBit(0); k >= 0; k = falls.nextSetBit(k + 1)) damp(v, k, t, piano);
        if (board.idle()) boards.remove(v);
        GrandPianoBank b = activeBank;
        if (paused || piano == null || b == null || piano.settings().instrument() != Instrument.GRAND_PIANO) return;
        PcmSample noise = b.pedal(down, pedalVariant++);
        if (noise == null) return;
        float db = (down ? b.spec.pedalDownDb : b.spec.pedalUpDb) + (pedal == Pedal.SUSTAIN ? 0f : SMALL_PEDAL_DB);
        Voice voice = claim(stepNow, noise);
        if (voice == null) return;
        voice.kind = StartedNote.Kind.PEDAL;
        voice.voicing = v;
        voice.instrument = Instrument.GRAND_PIANO;
        if (!start(voice, stepNow, t, 1f, false, VoiceMix.dbToGain(db), piano, -1, false, false)) return;
        record(voice, piano.settings());
    }

    /** A damper falls on {@code key}: its notes release, and a damped grand piano string adds its resonance. */
    private void damp(Voicing v, int key, long t, @Nullable PianoSnapshot piano) {
        Voice latest = null;
        for (Voice voice : voices) {
            if (!voice.used || voice.kind != StartedNote.Kind.NOTE || voice.key != key || !v.equals(voice.voicing)) continue;
            if (voice.releaseAt != Long.MAX_VALUE || voice.fadeAt != Long.MAX_VALUE) continue;
            release(voice, t, releaseSeconds(voice));
            if (latest == null || voice.startAt > latest.startAt) latest = voice;
        }
        GrandPianoBank b = activeBank;
        if (latest == null || paused || piano == null || b == null || latest.instrument != Instrument.GRAND_PIANO) return;
        int midi = key + 21;
        int index = b.spec.releaseIndex(midi);
        PcmSample resonance = b.release(index);
        if (resonance == null) return;
        GrandPianoSpec.Release r = b.spec.releases.get(index);
        double held = Math.max(0, t - latest.startAt) / 1e9;
        float gain = b.spec.velocityCurve(latest.velocity) * GrandPianoSpec.releaseGain(r, held);
        Voice voice = claim(stepNow, resonance);
        if (voice == null) return;
        voice.kind = StartedNote.Kind.RESONANCE;
        voice.voicing = v;
        voice.key = key;
        voice.velocity = latest.velocity;
        voice.instrument = Instrument.GRAND_PIANO;
        // The string rings on as its note did, brightened as much.
        voice.layer = latest.layer;
        if (!start(voice, stepNow, t, (float) GrandPianoSpec.releasePitch(r, midi, 440f), true, gain, piano, key * 3, false, latest.soft)) return;
        record(voice, piano.settings());
    }

    private float releaseSeconds(Voice voice) {
        GrandPianoBank b = activeBank;
        if (voice.instrument == Instrument.GRAND_PIANO && b != null) return b.spec.releaseSeconds(voice.key + 21);
        return NOTE_BLOCK_RELEASE_SECONDS;
    }

    private void startClick(Event e, long t) {
        if (paused) return;
        Voice voice = claim(stepNow, e.flag ? clickAccent : clickTick);
        if (voice == null) return;
        voice.kind = StartedNote.Kind.CLICK;
        if (!start(voice, stepNow, t, 1f, false, e.volume, null, -1, true, false)) return;
        record(voice, null);
    }

    // ------------------------------------------------------------------ voices

    /**
     * A voice for {@code sample}, a free one or the least important sound's, with the sample sent to OpenAL;
     * null when it cannot be. The caller says what the voice plays (kind, voicing, key, velocity, instrument,
     * layer), which its settings depend on, and then {@link #start}s it.
     */
    private @Nullable Voice claim(long now, PcmSample sample) {
        if (!upload(sample)) {
            // A pedal noise or string resonance still being decoded again for this device goes next.
            GrandPianoBank b = activeBank;
            if (b != null && !sample.hasPcm()) b.hurry(sample);
            dropped.incrementAndGet();
            return null;
        }
        Voice v = allocate(now);
        v.reset();
        v.used = true;
        v.sample = sample;
        return v;
    }

    /**
     * Configures a {@link #claim}ed voice's source and starts it at {@code t}. A sample that ends in a loop
     * repeats it, fading at its rate, until the voice is released or has died away. False, with the voice
     * freed, when OpenAL refuses.
     *
     * @param position index into the piano's sound positions, or -1 for the pedals (ignored for relative sounds)
     */
    private boolean start(Voice v, long now, long t, float pitch440, boolean followsTuning, float baseGain,
                          @Nullable PianoSnapshot piano, int position, boolean relative, boolean soft) {
        PcmSample sample = Objects.requireNonNull(v.sample, "claimed voice without a sample");
        v.pitch440 = pitch440;
        v.followsTuning = followsTuning;
        v.baseGain = baseGain;
        v.soft = soft;
        v.relative = relative;
        v.startAt = t;
        v.piano = piano == null ? null : piano.pos();
        PcmSample.Loop loop = sample.loop();
        int s = v.source;
        AL10.alSourcei(s, AL10.AL_BUFFER, sample.buffer);
        AL10.alSourcei(s, AL10.AL_LOOPING, loop != null && loopPoints ? AL10.AL_TRUE : AL10.AL_FALSE);
        if (relative || piano == null) {
            AL10.alSourcei(s, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
            AL10.alSource3f(s, AL10.AL_POSITION, 0f, 0f, 0f);
            AL10.alSourcei(s, AL10.AL_DISTANCE_MODEL, AL10.AL_NONE);
            AL10.alSourcef(s, AL10.AL_ROLLOFF_FACTOR, 0f);
        } else {
            float[] at = position < 0 ? piano.pedals() : piano.sounds();
            int i = Math.max(0, position);
            v.positional = true;
            v.x = at[i];
            v.y = at[i + 1];
            v.z = at[i + 2];
            AL10.alSourcei(s, AL10.AL_SOURCE_RELATIVE, AL10.AL_FALSE);
            AL10.alSource3f(s, AL10.AL_POSITION, v.x, v.y, v.z);
            AL10.alSourcei(s, AL10.AL_DISTANCE_MODEL, AL11.AL_LINEAR_DISTANCE);
            AL10.alSourcef(s, AL10.AL_REFERENCE_DISTANCE, 0f);
            AL10.alSourcef(s, AL10.AL_ROLLOFF_FACTOR, 1f);
            if (efx) {
                // The reverb send fades over distance as the direct sound does (by default it would not),
                // and a little treble goes with distance, as in air.
                AL10.alSourcef(s, EXTEfx.AL_ROOM_ROLLOFF_FACTOR, 1f);
                AL10.alSourcef(s, EXTEfx.AL_AIR_ABSORPTION_FACTOR, 1f);
            }
        }
        v.lidAffected = !relative && position >= 0;
        applySettings(v, piano, t, true);
        if (loop != null) {
            // In wall-clock time at the voice's playback rate (a later change of tuning moves it only a little).
            double framesPerNano = sample.sampleRate * v.appliedPitch / 1e9;
            v.tailAfter = (long) (loop.start() / framesPerNano);
            v.tailDbPerSecond = loop.decayDbPerSecond();
            if (!loopPoints) v.fadeOutAfter = Math.max(0, (long) (loop.end() / framesPerNano) - UNLOOPED_FADE_NANOS);
        }
        if (startDelay && t > now) SOFTSourceStartDelay.alSourcePlayAtTimeSOFT(s, t);
        else AL10.alSourcePlay(s);
        if (!alOk("starting " + sample.name)) {
            free(v);
            dropped.incrementAndGet();
            return false;
        }
        return true;
    }

    /** A free voice, or one taken from the least important sound (cut at once; rare thanks to {@link #RESERVE}). */
    private Voice allocate(long now) {
        for (Voice v : voices) if (!v.used) return v;
        int victim = victim(now, true);
        Voice v = voices.get(victim);
        free(v);
        stolen.incrementAndGet();
        return v;
    }

    /** Fades a voice out early whenever fewer than {@link #RESERVE} voices are free. */
    private void keepReserve(long now) {
        int free = 0;
        for (Voice v : voices) if (!v.used || v.fadeAt != Long.MAX_VALUE) free++;
        while (free < RESERVE) {
            int victim = victim(now, false);
            if (victim < 0) return;
            fade(voices.get(victim), now, STEAL_FADE_NANOS);
            stolen.incrementAndGet();
            free++;
        }
    }

    private int victim(long now, boolean includeFading) {
        Vec3 listener = host.listener();
        List<StealPolicy.Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < voices.size(); i++) {
            Voice v = voices.get(i);
            if (!v.used) continue;
            StealPolicy.Stage stage = v.stage(now);
            if (stage == StealPolicy.Stage.FADING && !includeFading) continue;
            long since = switch (stage) {
                case FADING -> v.fadeAt;
                case RELEASED -> v.releaseAt != Long.MAX_VALUE ? v.releaseAt : v.startAt;
                case SOUNDING, SCHEDULED -> v.startAt;
            };
            candidates.add(new StealPolicy.Candidate(i, stage, since, v.levelAt(now, listener)));
        }
        return StealPolicy.victim(candidates);
    }

    private void fade(Voice v, long at, long nanos) {
        if (v.fadeAt != Long.MAX_VALUE && v.fadeAt + v.fadeNanos <= at + nanos) return;
        v.fadeAt = at;
        v.fadeNanos = nanos;
    }

    private static void release(Voice v, long at, float seconds) {
        v.releaseAt = at;
        v.releaseSeconds = seconds;
    }

    private void free(Voice v) {
        AL10.alSourceStop(v.source);
        AL10.alSourcei(v.source, AL10.AL_BUFFER, 0);
        v.reset();
    }

    /** Envelopes, live settings (volume, tone, lid, reverb, range, tuning), pianos that disappeared, ended voices. */
    private void updateVoices(long now) {
        rampLids(now);
        boolean checkState = now - lastStateCheck >= STATE_CHECK_NANOS;
        if (checkState) lastStateCheck = now;
        for (Voice v : voices) {
            if (!v.used) continue;
            PianoSnapshot piano = v.piano == null ? null : host.piano(v.piano);
            if (v.piano != null && piano == null && v.fadeAt == Long.MAX_VALUE) fade(v, now, GONE_FADE_NANOS);
            if (v.finished(now)) {
                free(v);
                continue;
            }
            if (checkState && v.startAt <= now - 5_000_000L && AL10.alGetSourcei(v.source, AL10.AL_SOURCE_STATE) == AL10.AL_STOPPED) {
                free(v);
                continue;
            }
            applySettings(v, piano, now, false);
        }
    }

    /** The lid sound follows the lid's swing: one second from open to closed. */
    private void rampLids(long now) {
        long dt = Math.max(0, now - lastStep);
        Set<BlockPos> sounding = new HashSet<>();
        for (Voice v : voices) if (v.used && v.piano != null) sounding.add(v.piano);
        lids.keySet().retainAll(sounding);
        for (BlockPos pos : sounding) {
            PianoSnapshot s = host.piano(pos);
            if (s == null) continue;
            float target = s.settings().lidOpen() ? 0f : 1f;
            Float current = lids.get(pos);
            if (current == null) {
                lids.put(pos, target);
                continue;
            }
            float step = dt / (float) LID_SWING_NANOS;
            lids.put(pos, current < target ? Math.min(target, current + step) : Math.max(target, current - step));
        }
    }

    private float lidClosed(PianoSnapshot piano) {
        Float lid = lids.get(piano.pos());
        return lid != null ? lid : (piano.settings().lidOpen() ? 0f : 1f);
    }

    /** Pushes gain, pitch, filters, send and range to OpenAL when they changed. */
    private void applySettings(Voice v, @Nullable PianoSnapshot piano, long now, boolean force) {
        float records = Math.max(0f, Math.min(1f, host.recordsVolume()));
        float envelope = v.envelope(now);
        float gain;
        float lf = 1f, hf = 1f, send = 0f, pitch = v.pitch440;
        int range = 0;
        if (piano != null) {
            PianoSettings s = piano.settings();
            float lid = v.lidAffected ? lidClosed(piano) : 0f;
            boolean grand = v.instrument == Instrument.GRAND_PIANO;
            GrandPianoBank b = activeBank;
            // The part of a brighter tone past the one that struck this note's layer, followed while it sounds.
            float beyond = grand && v.layer >= 0 && b != null ? s.tone() - b.spec.toneFor(v.velocity, v.layer) : 0f;
            float tilt = VoiceMix.tilt(s.tone(), grand, beyond);
            followListenerVolume(v, piano, now, force);
            v.pianoGain = s.volume() * v.listenerVolume * VoiceMix.colourGain(tilt, lid, v.soft);
            gain = v.baseGain * records * v.pianoGain * envelope;
            lf = VoiceMix.lowShelf(tilt);
            hf = VoiceMix.highShelf(s.tone(), lid, v.soft);
            send = reverbSlot != 0 ? s.reverb() : 0f;
            range = range(s);
            if (v.followsTuning) pitch = v.pitch440 * s.tuning() / 440f;
        } else {
            // A piano that went away (broken, unloaded, the world left) fades out as it was last heard: muted stays muted.
            gain = v.baseGain * records * v.pianoGain * envelope;
        }
        // A gain above 1 is left to OpenAL, which holds a source to AL_MAX_GAIN (1) after its distance attenuation.
        int s = v.source;
        if (force || Math.abs(gain - v.appliedGain) > Math.max(1e-5f, v.appliedGain * 0.004f)) {
            AL10.alSourcef(s, AL10.AL_GAIN, gain);
            v.appliedGain = gain;
        }
        if (force || Math.abs(pitch - v.appliedPitch) > 1e-5f) {
            AL10.alSourcef(s, AL10.AL_PITCH, pitch);
            v.appliedPitch = pitch;
        }
        if (range > 0 && (force || range != v.appliedRange)) {
            AL10.alSourcef(s, AL10.AL_MAX_DISTANCE, range);
            v.appliedRange = range;
        }
        if (!efx || (piano == null && !v.relative)) return;
        if (force || Math.abs(lf - v.appliedLf) > 0.003f || Math.abs(hf - v.appliedHf) > 0.003f) {
            if (piano == null) {
                AL10.alSourcei(s, EXTEfx.AL_DIRECT_FILTER, EXTEfx.AL_FILTER_NULL);
            } else {
                EXTEfx.alFilterf(directFilter, EXTEfx.AL_BANDPASS_GAIN, 1f);
                EXTEfx.alFilterf(directFilter, EXTEfx.AL_BANDPASS_GAINLF, lf);
                EXTEfx.alFilterf(directFilter, EXTEfx.AL_BANDPASS_GAINHF, hf);
                AL10.alSourcei(s, EXTEfx.AL_DIRECT_FILTER, directFilter);
            }
            v.appliedLf = lf;
            v.appliedHf = hf;
        }
        if (force || Math.abs(send - v.appliedSend) > 0.003f) {
            if (send > 0.001f) {
                EXTEfx.alFilterf(sendFilter, EXTEfx.AL_LOWPASS_GAIN, send);
                EXTEfx.alFilterf(sendFilter, EXTEfx.AL_LOWPASS_GAINHF, Math.min(1f, hf * 0.8f));
                AL11.alSource3i(s, EXTEfx.AL_AUXILIARY_SEND_FILTER, reverbSlot, 0, sendFilter);
            } else {
                AL11.alSource3i(s, EXTEfx.AL_AUXILIARY_SEND_FILTER, EXTEfx.AL_EFFECTSLOT_NULL, 0, EXTEfx.AL_FILTER_NULL);
            }
            v.appliedSend = send;
        }
    }

    /**
     * The listener's own volume for a voice: 1 at their own pianos (placed by them, or sat at) and for
     * the notes they play themselves, their "other pianos" volume for the rest. A voice moves to a new one
     * over {@link #LISTENER_RAMP_NANOS} (sitting down at a piano, leaving its bench, moving the slider),
     * not at once.
     */
    private void followListenerVolume(Voice v, PianoSnapshot piano, long now, boolean force) {
        boolean own = host.ownPiano(piano.pos()) || v.voicing != null && v.voicing.source() == host.ownSource();
        float wanted = own ? 1f : Math.max(0f, Math.min(1f, host.othersVolume()));
        float step = force ? 1f : Math.max(0, now - v.settingsAt) / (float) LISTENER_RAMP_NANOS;
        v.listenerVolume += Math.max(-step, Math.min(step, wanted - v.listenerVolume));
        v.settingsAt = now;
    }

    // ------------------------------------------------------------------ pause

    /** Pauses sounding voices while the game is paused (and drops ones not started yet); resumes them after. */
    private void followPause(long now) {
        boolean want = host.paused();
        if (want == paused) return;
        if (want) {
            paused = true;
            pausedAt = now;
            for (Voice v : voices) {
                if (!v.used) continue;
                if (v.startAt > now) {
                    free(v);
                } else if (AL10.alGetSourcei(v.source, AL10.AL_SOURCE_STATE) == AL10.AL_PLAYING) {
                    AL10.alSourcePause(v.source);
                    v.pausedByEngine = true;
                }
            }
            alOk("pausing");
        } else {
            paused = false;
            long shift = now - pausedAt;
            for (Voice v : voices) {
                if (!v.used) continue;
                v.startAt += shift;
                if (v.releaseAt != Long.MAX_VALUE) v.releaseAt = v.releaseAt < pausedAt ? v.releaseAt + shift : now;
                if (v.fadeAt != Long.MAX_VALUE) v.fadeAt = v.fadeAt < pausedAt ? v.fadeAt + shift : now;
                if (v.pausedByEngine) {
                    AL10.alSourcePlay(v.source);
                    v.pausedByEngine = false;
                }
            }
            alOk("resuming");
        }
    }

    // ------------------------------------------------------------------ diagnostics

    private void record(Voice v, @Nullable PianoSettings settings) {
        StartedNote note = new StartedNote(v.kind, v.voicing, v.key, v.velocity, settings == null ? null : v.instrument, v.sample.name,
            v.appliedGain, v.appliedPitch, v.startAt, v.source);
        synchronized (recent) {
            recent.addLast(note);
            while (recent.size() > RECENT_LIMIT) recent.removeFirst();
        }
    }

    private boolean alOk(String what) {
        int error = AL10.alGetError();
        if (error == AL10.AL_NO_ERROR) return true;
        long n = alErrors.incrementAndGet();
        if (n <= 20 || n % 1000 == 0) LOG.error("Music audio: OpenAL error {} after {} ({} so far)", errorName(error), what, n);
        return false;
    }

    private void warnOnce(String key, String message, Object arg) {
        if (warned.add(key)) LOG.warn(message, arg);
    }

    /** Forgets which one-time warnings were given (after a resource reload the situation may differ). */
    public void resetWarnings() {
        warned.clear();
    }

    private static String errorName(int error) {
        return switch (error) {
            case AL10.AL_INVALID_NAME -> "invalid name";
            case AL10.AL_INVALID_ENUM -> "invalid enum";
            case AL10.AL_INVALID_VALUE -> "invalid value";
            case AL10.AL_INVALID_OPERATION -> "invalid operation";
            case AL10.AL_OUT_OF_MEMORY -> "out of memory";
            default -> "0x" + Integer.toHexString(error);
        };
    }

    /** One OpenAL source and what it is playing. Audio thread only. */
    private static final class Voice {
        final int source;
        boolean used;
        StartedNote.Kind kind = StartedNote.Kind.NOTE;
        @Nullable Voicing voicing;
        @Nullable BlockPos piano;
        int key = -1, velocity;
        /** The grand piano's picked layer this note (or the string it rings on) was struck with; -1 for other sounds. */
        int layer = -1;
        @Nullable Instrument instrument;
        @Nullable PcmSample sample;
        float baseGain, pitch440;
        boolean followsTuning, soft, relative, lidAffected, pausedByEngine, positional;
        /** Where it sounds in the world, when {@link #positional}. */
        float x, y, z;
        long startAt;
        long releaseAt = Long.MAX_VALUE;
        float releaseSeconds;
        long fadeAt = Long.MAX_VALUE, fadeNanos;
        /** Nanoseconds after the start when the sample's loop begins and the note fades at {@link #tailDbPerSecond}. */
        long tailAfter = Long.MAX_VALUE;
        float tailDbPerSecond;
        /** Nanoseconds after the start when a looped sample that cannot loop starts fading out. */
        long fadeOutAfter = Long.MAX_VALUE;
        float appliedGain, appliedPitch, appliedLf, appliedHf, appliedSend;
        int appliedRange;
        /** The listener's own volume for it ({@link AudioEngine#followListenerVolume}), and when its settings were last followed. */
        float listenerVolume = 1f;
        long settingsAt;
        /**
         * What its piano last made of its gain (the piano's volume, the listener's own volume for it, tone,
         * lid and soft pedal), kept for the fade once the piano is gone; 1 for sounds of no piano.
         */
        float pianoGain = 1f;

        Voice(int source) {
            this.source = source;
        }

        void reset() {
            used = false;
            kind = StartedNote.Kind.NOTE;
            voicing = null;
            piano = null;
            key = -1;
            velocity = 0;
            layer = -1;
            instrument = null;
            sample = null;
            baseGain = 0;
            pitch440 = 1;
            followsTuning = soft = relative = lidAffected = pausedByEngine = positional = false;
            x = y = z = 0;
            startAt = 0;
            releaseAt = Long.MAX_VALUE;
            releaseSeconds = 0;
            fadeAt = Long.MAX_VALUE;
            fadeNanos = 0;
            tailAfter = Long.MAX_VALUE;
            tailDbPerSecond = 0;
            fadeOutAfter = Long.MAX_VALUE;
            appliedGain = appliedPitch = appliedLf = appliedHf = appliedSend = -1;
            appliedRange = -1;
            listenerVolume = 1f;
            settingsAt = 0;
            pianoGain = 1f;
        }

        float envelope(long now) {
            float e = 1f;
            long played = now - startAt;
            if (tailAfter != Long.MAX_VALUE) e *= VoiceMix.tail(played - tailAfter, tailDbPerSecond);
            if (fadeOutAfter != Long.MAX_VALUE) e *= VoiceMix.fade(played - fadeOutAfter, UNLOOPED_FADE_NANOS);
            if (releaseAt != Long.MAX_VALUE) e *= VoiceMix.release(now - releaseAt, releaseSeconds);
            if (fadeAt != Long.MAX_VALUE) e *= VoiceMix.fade(now - fadeAt, fadeNanos);
            return e;
        }

        boolean finished(long now) {
            if (fadeAt != Long.MAX_VALUE && now - fadeAt >= fadeNanos) return true;
            if (fadeOutAfter != Long.MAX_VALUE && now - startAt - fadeOutAfter >= UNLOOPED_FADE_NANOS) return true;
            boolean decaying = releaseAt != Long.MAX_VALUE && now > releaseAt || tailAfter != Long.MAX_VALUE && now - startAt > tailAfter;
            return decaying && envelope(now) < SILENT;
        }

        StealPolicy.Stage stage(long now) {
            if (fadeAt != Long.MAX_VALUE) return StealPolicy.Stage.FADING;
            if (startAt > now) return StealPolicy.Stage.SCHEDULED;
            if (kind != StartedNote.Kind.NOTE || releaseAt != Long.MAX_VALUE) return StealPolicy.Stage.RELEASED;
            return StealPolicy.Stage.SOUNDING;
        }

        /**
         * How loud it is at the listener now: the gain OpenAL plays it at (velocity, volumes, tone, lid,
         * envelope) after the distance loss, held to OpenAL's gain limit of 1 as OpenAL holds it, and the
         * decay of the recording itself while it plays (the loop's fade is in the envelope).
         */
        float levelAt(long now, @Nullable Vec3 listener) {
            double distance = 1;
            if (positional && listener != null && appliedRange > 0) {
                double dx = x - listener.x, dy = y - listener.y, dz = z - listener.z;
                distance = Math.max(0, 1 - Math.sqrt(dx * dx + dy * dy + dz * dz) / appliedRange);
            }
            double played = Math.max(0, Math.min(now - startAt, tailAfter)) / 1e9;
            return (float) (Math.min(1, Math.max(0, appliedGain) * distance) * Math.pow(10, -SUSTAIN_DECAY_DB_PER_SECOND * played / 20));
        }
    }
}
