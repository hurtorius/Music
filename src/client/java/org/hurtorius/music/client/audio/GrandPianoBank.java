package org.hurtorius.music.client.audio;

import org.jspecify.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * The grand piano's decoded samples, laid out by {@link GrandPianoSpec}. Files that fail to load are
 * listed in {@link #failures} and their notes fall back to the nearest layer of the same note, so a
 * damaged resource pack degrades the piano instead of silencing it.
 *
 * <p>The samples let go of their PCM once OpenAL has it ({@link PcmSample#keepsPcm()}); the bank keeps
 * the files' bytes, a tenth of the size, and {@link #refill decodes them again} in place when a new audio
 * context needs the samples once more: one by one, the ones asked for first ({@link #hurry}), so a note
 * played meanwhile waits for its own sample only.</p>
 */
public final class GrandPianoBank {
    /** Reads a file named in the sample map (relative to the map). */
    @FunctionalInterface
    public interface Opener {
        InputStream open(String file) throws IOException;
    }

    public static final String MAP = "grand_piano.json";
    /** Samples decoded again at once for a new audio context. */
    public static final int REFILL_DECODERS = 2;

    public final GrandPianoSpec spec;
    /** Where the files came from, for the debug lines. */
    public final String source;
    public final List<String> failures;
    private final PcmSample[][] notes;
    private final PcmSample[] releases;
    private final List<PcmSample> pedalDown, pedalUp;
    private final List<PcmSample> all;
    /** The bytes of every file that decoded, to decode it again. */
    private final Map<String, byte[]> encoded;
    /** Guards the refill: what waits to be decoded again (next first), how many decoders work on it, whether the bank was let go of. */
    private final Object refillLock = new Object();
    private final ArrayDeque<PcmSample> toRefill = new ArrayDeque<>();
    private final Set<PcmSample> refilling = new HashSet<>();
    private final Set<PcmSample> failedAgain = ConcurrentHashMap.newKeySet();
    private int decoders;
    private boolean released;

    private GrandPianoBank(GrandPianoSpec spec, String source, Map<String, PcmSample> loaded, Map<String, byte[]> encoded, List<String> failures) {
        this.spec = spec;
        this.source = source;
        this.encoded = encoded;
        this.failures = Collections.unmodifiableList(failures);
        notes = new PcmSample[spec.regions.size()][spec.layers()];
        for (int r = 0; r < notes.length; r++) {
            List<String> files = spec.regions.get(r).samples();
            for (int l = 0; l < files.size(); l++) notes[r][l] = loaded.get(files.get(l));
        }
        releases = new PcmSample[spec.releases.size()];
        for (int i = 0; i < releases.length; i++) releases[i] = loaded.get(spec.releases.get(i).file());
        pedalDown = present(spec.pedalDown, loaded);
        pedalUp = present(spec.pedalUp, loaded);
        all = List.copyOf(loaded.values());
    }

    /**
     * Reads the sample map and decodes every file on {@code executor}, in parallel. Completes
     * exceptionally only when the map itself cannot be read.
     */
    public static CompletableFuture<GrandPianoBank> load(Opener opener, String source, Executor executor) {
        return CompletableFuture.supplyAsync(() -> {
            try (Reader reader = new InputStreamReader(opener.open(MAP), StandardCharsets.UTF_8)) {
                return GrandPianoSpec.parse(reader);
            } catch (IOException e) {
                throw new UncheckedIOException(MAP + ": " + e.getMessage(), e);
            }
        }, executor).thenCompose(spec -> decode(spec, source, spec.files(), file -> {
            try (InputStream in = opener.open(file)) {
                return in.readAllBytes();
            }
        }, executor));
    }

    /** What becomes of a sample decoded again: it is ready to upload, or could not be decoded (with why). */
    public interface Refilled {
        void ready(PcmSample sample);

        void failed(PcmSample sample, String problem);
    }

    /**
     * Decodes again, on {@code executor} ({@link #REFILL_DECODERS} at a time), those of {@code order}
     * that let go of their PCM (they went to an earlier audio context), in that order, and puts each back
     * in place as soon as it is decoded, telling {@code done}. Samples already waiting keep their place;
     * a sample asked for meanwhile ({@link #hurry}) goes first.
     */
    public void refill(List<PcmSample> order, Executor executor, Refilled done) {
        int start;
        synchronized (refillLock) {
            if (released) return;
            for (PcmSample s : order) {
                if (!s.hasPcm() && !failedAgain.contains(s) && refilling.add(s)) toRefill.addLast(s);
            }
            start = Math.min(REFILL_DECODERS - decoders, toRefill.size());
            decoders += Math.max(0, start);
        }
        for (int i = 0; i < start; i++) executor.execute(() -> decodeWaiting(done));
    }

    /** Moves {@code sample} to the front of what waits to be decoded again; nothing when it does not wait. Any thread. */
    public void hurry(PcmSample sample) {
        synchronized (refillLock) {
            if (toRefill.remove(sample)) toRefill.addFirst(sample);
        }
    }

    /** True while samples wait to be decoded again or are being decoded. */
    public boolean refilling() {
        synchronized (refillLock) {
            return !refilling.isEmpty();
        }
    }

    /** Samples still to be decoded again (waiting or being decoded). */
    public int refillsLeft() {
        synchronized (refillLock) {
            return refilling.size();
        }
    }

    /** True for a sample whose file could not be decoded again: it stays silent until the samples load anew. */
    public boolean failedAgain(PcmSample sample) {
        return failedAgain.contains(sample);
    }

    /** One decoder: takes the next waiting sample until none is left. */
    private void decodeWaiting(Refilled done) {
        while (true) {
            PcmSample next;
            synchronized (refillLock) {
                next = released ? null : toRefill.pollFirst();
                if (next == null) {
                    decoders--;
                    return;
                }
            }
            String problem = null;
            try {
                PcmSample decoded = OggDecoder.decodeForBank(next.name, new ByteArrayInputStream(encoded.get(next.name)), spec.loopOf(next.name));
                next.refillFrom(decoded);
            } catch (IOException | RuntimeException e) {
                problem = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            boolean gone;
            synchronized (refillLock) {
                refilling.remove(next);
                gone = released;
                if (!gone && problem != null) failedAgain.add(next);
            }
            // Let go of meanwhile: what was just decoded goes with the rest.
            if (gone) next.release();
            else if (problem != null) done.failed(next, problem);
            else done.ready(next);
        }
    }

    /** Frees the PCM the samples still hold and stops decoding them again: the bank has been replaced. */
    public void release() {
        synchronized (refillLock) {
            released = true;
            toRefill.clear();
            refilling.clear();
        }
        for (PcmSample s : all) s.release();
    }

    /** Reads a file's bytes. */
    @FunctionalInterface
    private interface Bytes {
        byte[] read(String file) throws IOException;
    }

    private record Decoded(PcmSample sample, byte[] bytes) {}

    private static CompletableFuture<GrandPianoBank> decode(GrandPianoSpec spec, String source, List<String> files, Bytes bytes, Executor executor) {
        Map<String, CompletableFuture<Decoded>> futures = new LinkedHashMap<>();
        for (String file : files) {
            futures.put(file, CompletableFuture.supplyAsync(() -> {
                try {
                    byte[] data = bytes.read(file);
                    return new Decoded(OggDecoder.decodeForBank(file, new ByteArrayInputStream(data), spec.loopOf(file)), data);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }, executor));
        }
        return CompletableFuture.allOf(futures.values().toArray(CompletableFuture[]::new)).handle((ignored, error) -> {
            Map<String, PcmSample> loaded = new HashMap<>();
            Map<String, byte[]> encoded = new HashMap<>();
            List<String> failures = new ArrayList<>();
            futures.forEach((file, future) -> {
                try {
                    Decoded d = future.join();
                    loaded.put(file, d.sample());
                    encoded.put(file, d.bytes());
                } catch (CompletionException e) {
                    Throwable cause = e.getCause() instanceof UncheckedIOException io ? io.getCause() : e.getCause();
                    failures.add(file + " (" + (cause.getMessage() != null ? cause.getMessage() : cause.toString()) + ")");
                }
            });
            return new GrandPianoBank(spec, source, loaded, Map.copyOf(encoded), failures);
        });
    }

    /** The sample for a region and picked layer; the nearest loaded layer when that file failed. */
    public @Nullable PcmSample note(int region, int layer) {
        PcmSample[] layers = notes[region];
        for (int d = 0; d < layers.length; d++) {
            if (layer - d >= 0 && layers[layer - d] != null) return layers[layer - d];
            if (layer + d < layers.length && layers[layer + d] != null) return layers[layer + d];
        }
        return null;
    }

    public @Nullable PcmSample release(int index) {
        return index < 0 ? null : releases[index];
    }

    /** One of the pedal noises, or null when none loaded. */
    public @Nullable PcmSample pedal(boolean down, int variant) {
        List<PcmSample> list = down ? pedalDown : pedalUp;
        return list.isEmpty() ? null : list.get(Math.floorMod(variant, list.size()));
    }

    /** Every loaded sample. */
    public List<PcmSample> all() {
        return all;
    }

    /** Bytes of PCM the samples decode to. */
    public long bytes() {
        long total = 0;
        for (PcmSample s : all) total += s.bytes();
        return total;
    }

    /** Bytes of the files kept to decode the samples again. */
    public long encodedBytes() {
        long total = 0;
        for (byte[] b : encoded.values()) total += b.length;
        return total;
    }

    /** Loaded files out of the files the map names. */
    public String coverage() {
        return all.size() + "/" + (all.size() + failures.size());
    }

    private static List<PcmSample> present(List<String> files, Map<String, PcmSample> loaded) {
        List<PcmSample> out = new ArrayList<>();
        for (String f : files) if (loaded.get(f) != null) out.add(loaded.get(f));
        return List.copyOf(out);
    }
}
