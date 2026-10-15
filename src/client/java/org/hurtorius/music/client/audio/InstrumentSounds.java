package org.hurtorius.music.client.audio;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import org.hurtorius.music.piano.Instrument;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Note block and custom sounds, decoded from whatever Minecraft's sound manager says a sound event
 * plays (so resource packs that change note block sounds change the piano too). Each note block sound
 * is measured as it is decoded and played as loud as the grand piano ({@link PitchedSound#levelled}).
 *
 * <p>Sound events are resolved on the client thread (the sound manager lives there) and decoded in
 * the background: the note block sounds on Minecraft's worker pool after each resource reload, custom
 * sounds one at a time on their own thread, so a stream of new ones cannot crowd out chunk building,
 * and only as many kept as {@link CustomSoundCache} allows. A sound event that does not exist, has no
 * sound, or cannot be decoded is {@link SoundLibrary.Status#MISSING}: it is logged and its notes are
 * silent.</p>
 */
final class InstrumentSounds implements SoundLibrary {
    /** Custom sounds longer than this are cut; a music disc as a piano sound would otherwise fill memory. */
    static final double MAX_CUSTOM_SECONDS = 30;
    /** Custom sound problems logged one by one; after that every thousandth, so piano settings cannot flood the log. */
    private static final int LOGGED_PROBLEMS = 20;
    private static final ExecutorService CUSTOM_DECODER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "music-custom-sounds");
        thread.setDaemon(true);
        return thread;
    });

    /** One note block sound being loaded or loaded. */
    private static final class Entry {
        final String label;
        volatile @Nullable PitchedSound sound;
        volatile @Nullable String problem;

        Entry(String label) {
            this.label = label;
        }

        Lookup lookup() {
            return InstrumentSounds.lookup(label, sound, problem);
        }
    }

    /** The file a sound event plays, or why there is none. */
    private record Source(@Nullable Identifier file, @Nullable Resource resource, @Nullable String problem) {
        static Source missing(String problem) {
            return new Source(null, null, problem);
        }
    }

    private final Map<Instrument, Entry> instruments = new EnumMap<>(Instrument.class);
    private final ConcurrentLinkedQueue<PcmSample> retired = new ConcurrentLinkedQueue<>();
    private final CustomSoundCache custom = new CustomSoundCache(CustomSoundCache.RECENT, CustomSoundCache.MAX_BYTES, retired::add);
    private final AtomicLong customProblems = new AtomicLong();
    private volatile int generation;

    @Override
    public Lookup instrument(Instrument instrument) {
        Entry entry;
        synchronized (instruments) {
            entry = instruments.get(instrument);
        }
        return entry == null ? Lookup.loading(instrument.getSerializedName()) : entry.lookup();
    }

    @Override
    public Lookup custom(String soundEventId) {
        if (soundEventId.isEmpty()) return Lookup.missing("no custom sound chosen");
        CustomSoundCache.Entry known = custom.get(soundEventId);
        if (known != null) return lookup(known.id, known.sound(), known.problem());
        CustomSoundCache.Entry fresh = custom.add(soundEventId);
        if (fresh != null) {
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> loadCustom(mc, fresh));
        }
        return Lookup.loading(soundEventId);
    }

    @Override
    public @Nullable PcmSample pollRetired() {
        return retired.poll();
    }

    @Override
    public String describe() {
        List<Map.Entry<String, Lookup>> all = new ArrayList<>();
        for (CustomSoundCache.Entry e : custom.entries()) all.add(Map.entry(e.id, lookup(e.id, e.sound(), e.problem())));
        synchronized (instruments) {
            for (Entry e : instruments.values()) all.add(Map.entry(e.label, e.lookup()));
        }
        int ready = 0, loading = 0;
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, Lookup> e : all) {
            switch (e.getValue().status()) {
                case READY -> ready++;
                case LOADING -> loading++;
                case MISSING -> missing.add(e.getKey() + " (" + e.getValue().detail() + ")");
            }
        }
        String text = ready + " ready, " + loading + " loading";
        if (missing.isEmpty()) return text;
        String shown = String.join(", ", missing.subList(0, Math.min(4, missing.size())));
        return text + "; missing: " + shown + (missing.size() > 4 ? " and " + (missing.size() - 4) + " more" : "");
    }

    /**
     * Drops every sound and starts decoding the note block instruments again from the current sound
     * manager. Client thread, after Minecraft's sounds have reloaded.
     */
    void reload(Minecraft mc) {
        int gen = ++generation;
        custom.clear();
        List<Entry> old = new ArrayList<>();
        synchronized (instruments) {
            old.addAll(instruments.values());
            instruments.clear();
            for (Instrument instrument : Instrument.values()) {
                if (!instrument.isNoteBlock()) continue;
                Entry entry = new Entry(instrument.getSerializedName());
                instruments.put(instrument, entry);
                loadInstrument(mc, entry, Identifier.withDefaultNamespace(instrument.soundEvent()), instrument.rootMidi(), gen);
            }
        }
        for (Entry e : old) {
            PitchedSound s = e.sound;
            if (s != null) retired.addAll(s.samples());
        }
    }

    /**
     * Starts loading the custom sounds the known pianos use, before anyone plays them, and lets go of
     * other custom sounds beyond the cache's bounds. Client thread, every tick.
     */
    void keepCustom(Minecraft mc, Set<String> inUse) {
        for (String id : inUse) {
            if (id.isEmpty() || custom.get(id) != null) continue;
            CustomSoundCache.Entry fresh = custom.add(id);
            if (fresh != null) loadCustom(mc, fresh);
        }
        custom.trim(inUse);
    }

    /** Lets go of every custom sound (leaving a world: the next one has other pianos). Client thread. */
    void clearCustom() {
        custom.clear();
    }

    private static Lookup lookup(String label, @Nullable PitchedSound sound, @Nullable String problem) {
        if (sound != null) return Lookup.ready(sound);
        return problem != null ? Lookup.missing(problem) : Lookup.loading(label);
    }

    private void loadInstrument(Minecraft mc, Entry entry, Identifier event, int lowestRoot, int gen) {
        Source source = find(mc, event);
        if (source.problem() != null) {
            failInstrument(entry, source.problem());
            return;
        }
        CompletableFuture.supplyAsync(() -> decode(source, lowestRoot, Double.POSITIVE_INFINITY, true), Util.backgroundExecutor())
            .whenComplete((built, error) -> {
                if (error != null) failInstrument(entry, "could not decode " + source.file() + ": " + cause(error).getMessage());
                else if (gen != generation) retired.addAll(built.samples());
                else entry.sound = built;
            });
    }

    /** Resolves a custom sound on this thread and decodes it on the custom sound thread, unless it is let go of first. */
    private void loadCustom(Minecraft mc, CustomSoundCache.Entry entry) {
        Identifier event = Identifier.tryParse(entry.id);
        Source source = event == null ? Source.missing("not a valid sound id") : find(mc, event);
        if (source.problem() != null) {
            failCustom(entry, source.problem());
            return;
        }
        CompletableFuture.supplyAsync(() -> entry.dropped() ? null : decode(source, 0, MAX_CUSTOM_SECONDS, false), CUSTOM_DECODER)
            .whenComplete((built, error) -> {
                if (error != null) failCustom(entry, "could not decode " + source.file() + ": " + cause(error).getMessage());
                else if (built != null) custom.complete(entry, built);
            });
    }

    /** The file a sound event plays: its first variant, so a custom sound keeps one pitch across the keyboard. Client thread. */
    private static Source find(Minecraft mc, Identifier event) {
        WeighedSoundEvents events = mc.getSoundManager().getSoundEvent(event);
        if (events == null) return Source.missing("unknown sound event");
        Sound sound = events.getSound(RandomSource.create(0L));
        if (sound == SoundManager.EMPTY_SOUND || sound == SoundManager.INTENTIONALLY_EMPTY_SOUND) {
            return Source.missing("the sound event has no sound");
        }
        Identifier file = sound.getPath();
        Optional<Resource> resource = mc.getResourceManager().getResource(file);
        return resource.map(r -> new Source(file, r, null)).orElseGet(() -> Source.missing("missing file " + file));
    }

    /** Decodes a sound; a note block instrument's ({@code levelled}) is trimmed to the grand piano's loudness. */
    private static PitchedSound decode(Source source, int lowestRoot, double maxSeconds, boolean levelled) {
        try (InputStream in = source.resource().open()) {
            PcmSample base = OggDecoder.decode(source.file().toString(), in, maxSeconds);
            return levelled ? PitchedSound.levelled(base, lowestRoot) : PitchedSound.build(base, lowestRoot);
        } catch (IOException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private static Throwable cause(Throwable error) {
        return error.getCause() != null ? error.getCause() : error;
    }

    private static void failInstrument(Entry entry, String problem) {
        entry.problem = problem;
        AudioEngine.LOG.warn("Music audio: sound {} is unavailable: {}; its notes will be silent", entry.label, problem);
    }

    private void failCustom(CustomSoundCache.Entry entry, String problem) {
        custom.fail(entry, problem);
        long n = customProblems.incrementAndGet();
        if (n <= LOGGED_PROBLEMS || n % 1000 == 0) {
            AudioEngine.LOG.warn("Music audio: sound {} is unavailable: {}; its notes will be silent ({} custom sound problems so far)",
                entry.id, problem, n);
        }
    }
}
