package org.hurtorius.music.client.audio;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The grand piano's sample map ({@code assets/music/instruments/grand_piano/grand_piano.json}) and the
 * math that turns a key and a velocity into a sample, a gain and a pitch. Pure: no OpenAL, no game state.
 *
 * <p>The set keeps 5 of Salamander's 16 velocity layers. A velocity plays the picked layer whose
 * {@code layerRanges} contain it, and its gain restores the loudness the full 16-layer set would have had:
 * {@code gain = ((1 - track) + track * (v/127)^2) * loudness16[trueLayer] / loudness16[pickedLayer]}.</p>
 */
public final class GrandPianoSpec {
    /** Highest MIDI velocity. */
    public static final int MAX_VELOCITY = 127;

    /**
     * One sampled note: the keys it covers, its tuning correction, one file per picked layer and, when
     * the map gives them, the loop each file ends in (none for maps without loops).
     */
    public record Region(int key, int lo, int hi, int tuneCents, boolean damper, List<String> samples, float[] loudness16, List<PcmSample.Loop> loops) {}

    /** A string-resonance sample played when a damper falls on a key in {@code lo..hi}. */
    public record Release(int key, int lo, int hi, String file, float volumeDb, float decayDbPerSecond) {}

    public final String name;
    public final float velocityTrack;
    /** 1-based Salamander layer number of each picked layer. */
    private final int[] pickedLayers;
    private final int[][] layerRanges;
    private final int[][] sfzLayerRanges;
    public final int undampedFrom;
    public final float undampedReleaseSeconds;
    public final float dampedReleaseSeconds;
    public final List<Region> regions;
    public final List<Release> releases;
    public final List<String> pedalDown;
    public final List<String> pedalUp;
    public final float pedalDownDb;
    public final float pedalUpDb;

    private GrandPianoSpec(String name, float velocityTrack, int[] pickedLayers, int[][] layerRanges, int[][] sfzLayerRanges,
                           int undampedFrom, float undampedReleaseSeconds, float dampedReleaseSeconds, List<Region> regions, List<Release> releases,
                           List<String> pedalDown, List<String> pedalUp, float pedalDownDb, float pedalUpDb) {
        this.name = name;
        this.velocityTrack = velocityTrack;
        this.pickedLayers = pickedLayers;
        this.layerRanges = layerRanges;
        this.sfzLayerRanges = sfzLayerRanges;
        this.undampedFrom = undampedFrom;
        this.undampedReleaseSeconds = undampedReleaseSeconds;
        this.dampedReleaseSeconds = dampedReleaseSeconds;
        this.regions = Collections.unmodifiableList(regions);
        this.releases = Collections.unmodifiableList(releases);
        this.pedalDown = Collections.unmodifiableList(pedalDown);
        this.pedalUp = Collections.unmodifiableList(pedalUp);
        this.pedalDownDb = pedalDownDb;
        this.pedalUpDb = pedalUpDb;
    }

    /** Reads and validates a sample map; throws {@link IllegalArgumentException} naming what is wrong. */
    public static GrandPianoSpec parse(Reader reader) {
        JsonObject root;
        try {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("not a JSON object: " + e.getMessage(), e);
        }
        try {
            int[] picked = ints(root.getAsJsonArray("pickedLayers"));
            int[][] ranges = ranges(root.getAsJsonArray("layerRanges"));
            int[][] sfzRanges = ranges(root.getAsJsonArray("sfzLayerRanges"));
            if (picked.length == 0 || picked.length != ranges.length) {
                throw new IllegalArgumentException("pickedLayers and layerRanges must have the same, non-zero length");
            }
            for (int layer : picked) {
                if (layer < 1 || layer > sfzRanges.length) throw new IllegalArgumentException("picked layer " + layer + " is outside sfzLayerRanges");
            }
            checkCoverage("layerRanges", ranges);
            checkCoverage("sfzLayerRanges", sfzRanges);
            List<Region> regions = new ArrayList<>();
            for (JsonElement e : root.getAsJsonArray("notes")) {
                JsonObject n = e.getAsJsonObject();
                List<String> samples = strings(n.getAsJsonArray("samples"));
                float[] loudness = floats(n.getAsJsonArray("loudness16"));
                int key = n.get("key").getAsInt();
                if (samples.size() != picked.length) throw new IllegalArgumentException("note " + key + " needs " + picked.length + " samples");
                if (loudness.length != sfzRanges.length) throw new IllegalArgumentException("note " + key + " needs " + sfzRanges.length + " loudness values");
                for (float l : loudness) if (!(l > 0)) throw new IllegalArgumentException("note " + key + " has a loudness that is not positive");
                regions.add(new Region(key, n.get("lo").getAsInt(), n.get("hi").getAsInt(), n.get("tuneCents").getAsInt(),
                    n.get("damper").getAsBoolean(), List.copyOf(samples), loudness, loops(n, key, samples.size())));
            }
            if (regions.isEmpty()) throw new IllegalArgumentException("no notes");
            List<Release> releases = new ArrayList<>();
            JsonArray releaseArray = root.has("release") ? root.getAsJsonArray("release") : new JsonArray();
            for (JsonElement e : releaseArray) {
                JsonObject r = e.getAsJsonObject();
                releases.add(new Release(r.get("key").getAsInt(), r.get("lo").getAsInt(), r.get("hi").getAsInt(), r.get("file").getAsString(),
                    r.get("volumeDb").getAsFloat(), r.get("decayDbPerSecond").getAsFloat()));
            }
            JsonObject pedal = root.getAsJsonObject("pedal");
            return new GrandPianoSpec(
                root.has("name") ? root.get("name").getAsString() : "grand piano",
                root.get("velocityTrack").getAsFloat(), picked, ranges, sfzRanges,
                root.get("undampedFrom").getAsInt(), root.get("undampedReleaseSeconds").getAsFloat(), root.get("dampedReleaseSeconds").getAsFloat(),
                regions, releases,
                pedal == null ? List.of() : strings(pedal.getAsJsonArray("down")),
                pedal == null ? List.of() : strings(pedal.getAsJsonArray("up")),
                pedal == null ? 0f : pedal.get("downDb").getAsFloat(),
                pedal == null ? 0f : pedal.get("upDb").getAsFloat());
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("missing or malformed field: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ lookups

    /** Number of picked velocity layers (files per region). */
    public int layers() {
        return pickedLayers.length;
    }

    /**
     * Index of the region that plays {@code midi}: the one whose {@code lo..hi} contains it, else the
     * nearest by sampled key (on a tie the lower sample, played slightly faster).
     */
    public int regionIndex(int midi) {
        int best = -1, bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < regions.size(); i++) {
            Region r = regions.get(i);
            if (midi >= r.lo() && midi <= r.hi()) return i;
            int distance = Math.abs(midi - r.key());
            if (distance < bestDistance || (distance == bestDistance && r.key() < regions.get(best).key())) {
                best = i;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** Picked layer (0-based) that plays {@code velocity}. */
    public int layer(int velocity) {
        return rangeIndex(layerRanges, clampVelocity(velocity));
    }

    /**
     * Picked layer (0-based) a note of {@code velocity} strikes at {@code tone}: its own layer at a tone of 0
     * or darker; brighter, the layer of a velocity up to {@link VoiceMix#BRIGHT_VELOCITY} higher, a harder
     * hammer (played at the loudness of {@code velocity}, see {@link #velocityGain(Region, int, int)}).
     */
    public int strikeLayer(int velocity, float tone) {
        if (!(tone > 0)) return layer(velocity);
        // The small allowance keeps a tone of exactly toneFor(velocity, layer) on that layer despite rounding.
        float harder = clampVelocity(velocity) + VoiceMix.BRIGHT_VELOCITY * Math.min(1f, tone) + 1e-3f;
        return layer((int) Math.min(MAX_VELOCITY, Math.floor(harder)));
    }

    /**
     * The tone at which a note of {@code velocity} strikes picked layer {@code layer} ({@link #strikeLayer}):
     * 0 for its own layer or a softer one, above 1 for a layer no tone reaches.
     */
    public float toneFor(int velocity, int layer) {
        int v = clampVelocity(velocity);
        if (layer <= layer(v)) return 0f;
        return (layerRanges[Math.min(layer, layerRanges.length - 1)][0] - v) / (float) VoiceMix.BRIGHT_VELOCITY;
    }

    /**
     * The brighter tones (0..1] at which a note of {@code velocity} strikes with a harder hammer, softest
     * first ({@link #toneFor} of each harder layer a tone reaches); empty for a note that strikes with the
     * hardest already. Between them, and past the last, a brighter tone only thins the lows ({@link VoiceMix#tilt}).
     */
    public List<Float> harderHammers(int velocity) {
        List<Float> tones = new ArrayList<>();
        for (int layer = layer(velocity) + 1; layer < layers(); layer++) {
            float tone = toneFor(velocity, layer);
            if (tone <= 1f) tones.add(tone);
        }
        return tones;
    }

    /** Salamander layer (0-based of 16) a full set would have played at {@code velocity}. */
    public int trueLayer(int velocity) {
        return rangeIndex(sfzLayerRanges, clampVelocity(velocity));
    }

    /** 0-based Salamander layer behind picked layer {@code layer}. */
    public int pickedSfzLayer(int layer) {
        return pickedLayers[layer] - 1;
    }

    /** SFZ-style velocity curve: {@code (1 - track) + track * (v/127)^2}. */
    public float velocityCurve(int velocity) {
        float v = clampVelocity(velocity) / (float) MAX_VELOCITY;
        return (1f - velocityTrack) + velocityTrack * v * v;
    }

    /** Linear gain for {@code velocity} on {@code region}, including the missing-layer loudness correction. */
    public float velocityGain(Region region, int velocity) {
        return velocityGain(region, velocity, layer(velocity));
    }

    /**
     * Linear gain that plays picked layer {@code layer} of {@code region} as loud as {@code velocity} is:
     * a brighter tone plays a harder layer ({@link #strikeLayer}) at the played velocity's loudness.
     */
    public float velocityGain(Region region, int velocity, int layer) {
        float[] loudness = region.loudness16();
        return velocityCurve(velocity) * loudness[trueLayer(velocity)] / loudness[pickedSfzLayer(layer)];
    }

    /** Playback rate for {@code midi} on {@code region} with A4 at {@code tuning} Hz. */
    public static double pitch(Region region, int midi, float tuning) {
        return Math.pow(2.0, (midi - region.key()) / 12.0 + region.tuneCents() / 1200.0) * tuning / 440.0;
    }

    /** True when the key has a damper (it stops when let go unless a pedal holds it). */
    public boolean damped(int midi) {
        return midi < undampedFrom;
    }

    /** Seconds a released note takes to fade to -60 dB: short for damped strings, long for undamped ones. */
    public float releaseSeconds(int midi) {
        return damped(midi) ? dampedReleaseSeconds : undampedReleaseSeconds;
    }

    /** Index of the string-resonance sample for a damper falling on {@code midi}, or -1 when there is none. */
    public int releaseIndex(int midi) {
        if (!damped(midi)) return -1;
        for (int i = 0; i < releases.size(); i++) {
            Release r = releases.get(i);
            if (midi >= r.lo() && midi <= r.hi()) return i;
        }
        return -1;
    }

    /** Resonance gain after the key was held {@code heldSeconds}: {@code volumeDb - decayDbPerSecond * held}. */
    public static float releaseGain(Release release, double heldSeconds) {
        double db = release.volumeDb() - release.decayDbPerSecond() * Math.max(0, heldSeconds);
        return (float) Math.pow(10, db / 20.0);
    }

    /** Pitch of a release sample for {@code midi}, with A4 at {@code tuning} Hz. */
    public static double releasePitch(Release release, int midi, float tuning) {
        return Math.pow(2.0, (midi - release.key()) / 12.0) * tuning / 440.0;
    }

    /** The loop the file ends in, or null when the map gives it none. */
    public PcmSample.@Nullable Loop loopOf(String file) {
        for (Region r : regions) {
            int i = r.samples().indexOf(file);
            if (i >= 0 && !r.loops().isEmpty()) return r.loops().get(i);
        }
        return null;
    }

    /** Every file the map refers to, in a stable order. */
    public List<String> files() {
        Set<String> files = new LinkedHashSet<>();
        for (Region r : regions) files.addAll(r.samples());
        for (Release r : releases) files.add(r.file());
        files.addAll(pedalDown);
        files.addAll(pedalUp);
        return List.copyOf(files);
    }

    // ------------------------------------------------------------------ parsing helpers

    /** A note's loops: all of loopStart, loopEnd and tailDbPerSecond with one value per sample, or none of them. */
    private static List<PcmSample.Loop> loops(JsonObject note, int key, int count) {
        boolean start = note.has("loopStart"), end = note.has("loopEnd"), decay = note.has("tailDbPerSecond");
        if (!start && !end && !decay) return List.of();
        if (!start || !end || !decay) throw new IllegalArgumentException("note " + key + " needs all of loopStart, loopEnd and tailDbPerSecond, or none");
        int[] starts = ints(note.getAsJsonArray("loopStart")), ends = ints(note.getAsJsonArray("loopEnd"));
        float[] decays = floats(note.getAsJsonArray("tailDbPerSecond"));
        if (starts.length != count || ends.length != count || decays.length != count) {
            throw new IllegalArgumentException("note " + key + " needs a loop for each of its " + count + " samples");
        }
        List<PcmSample.Loop> loops = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            try {
                loops.add(new PcmSample.Loop(starts[i], ends[i], decays[i]));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("note " + key + ", sample " + i + ": " + e.getMessage(), e);
            }
        }
        return List.copyOf(loops);
    }

    private static int clampVelocity(int velocity) {
        return Math.max(1, Math.min(MAX_VELOCITY, velocity));
    }

    private static int rangeIndex(int[][] ranges, int v) {
        for (int i = 0; i < ranges.length; i++) if (v >= ranges[i][0] && v <= ranges[i][1]) return i;
        return ranges.length - 1;
    }

    private static void checkCoverage(String field, int[][] ranges) {
        int next = 1;
        for (int[] range : ranges) {
            if (range[0] != next || range[1] < range[0]) throw new IllegalArgumentException(field + " must cover 1..127 in order without gaps");
            next = range[1] + 1;
        }
        if (next != MAX_VELOCITY + 1) throw new IllegalArgumentException(field + " must end at 127");
    }

    private static int[] ints(JsonArray a) {
        int[] out = new int[a.size()];
        for (int i = 0; i < out.length; i++) out[i] = a.get(i).getAsInt();
        return out;
    }

    private static float[] floats(JsonArray a) {
        float[] out = new float[a.size()];
        for (int i = 0; i < out.length; i++) out[i] = a.get(i).getAsFloat();
        return out;
    }

    private static int[][] ranges(JsonArray a) {
        int[][] out = new int[a.size()][];
        for (int i = 0; i < out.length; i++) {
            int[] pair = ints(a.get(i).getAsJsonArray());
            if (pair.length != 2) throw new IllegalArgumentException("a velocity range needs two numbers");
            out[i] = pair;
        }
        return out;
    }

    private static List<String> strings(JsonArray a) {
        List<String> out = new ArrayList<>(a.size());
        for (JsonElement e : a) out.add(e.getAsString());
        return out;
    }
}
