package org.hurtorius.music.client.audio;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.hurtorius.music.client.ClientPrefs;
import org.hurtorius.music.piano.GrandPianoBlockEntity;
import org.hurtorius.music.seat.PianoSeatEntity;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The game side of {@link AudioHost}: snapshots of the pianos being played, the volume sliders, which
 * pianos are the player's own (placed by them, or sat at) and their own notes' source, how far their
 * chunks reach, the voice limit and the listener, refreshed on the client thread every tick and read by
 * the audio thread.
 */
final class GameAudioHost implements AudioHost {
    private final Map<BlockPos, PianoSnapshot> pianos = new ConcurrentHashMap<>();
    private final Set<BlockPos> requested = ConcurrentHashMap.newKeySet();
    /** Known pianos the player placed (their owner). */
    private final Set<BlockPos> placed = ConcurrentHashMap.newKeySet();
    private volatile float records = 1f, others = 1f;
    private volatile int voiceLimit = 64;
    private volatile int loadedRadius = Integer.MAX_VALUE;
    /** The piano whose bench the player sits on, or null. */
    private volatile @Nullable BlockPos seat;
    private volatile int ownSource = NO_SOURCE;
    private volatile @Nullable Vec3 listener;

    @Override
    public @Nullable PianoSnapshot piano(BlockPos pos) {
        return pianos.get(pos);
    }

    @Override
    public void request(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || pianos.containsKey(pos) || !requested.add(pos)) return;
        mc.execute(() -> {
            requested.remove(pos);
            refresh(mc, pos);
        });
    }

    @Override
    public float recordsVolume() {
        return records;
    }

    @Override
    public float othersVolume() {
        return others;
    }

    @Override
    public boolean ownPiano(BlockPos piano) {
        return piano.equals(seat) || placed.contains(piano);
    }

    @Override
    public int ownSource() {
        return ownSource;
    }

    @Override
    public int loadedRadius() {
        return loadedRadius;
    }

    @Override
    public boolean paused() {
        Minecraft mc = Minecraft.getInstance();
        return mc != null && mc.isPaused();
    }

    @Override
    public int voiceLimit() {
        return voiceLimit;
    }

    @Override
    public @Nullable Vec3 listener() {
        return listener;
    }

    /** Makes sure a piano that is about to be played has a snapshot. Client thread. */
    void ensure(Minecraft mc, BlockPos pos) {
        if (!pianos.containsKey(pos)) refresh(mc, pos);
    }

    /** Re-reads every known piano, the sliders and the listener. Client thread, every tick. */
    void tick(Minecraft mc) {
        records = mc.options.getFinalSoundSourceVolume(SoundSource.RECORDS);
        ClientPrefs prefs = ClientPrefs.get();
        others = prefs.otherPianosVolume;
        voiceLimit = prefs.voiceLimit;
        ownSource = mc.player != null ? mc.player.getId() : NO_SOURCE;
        seat = seat(mc);
        loadedRadius = loadedRadius(mc.options.getEffectiveRenderDistance());
        listener = mc.gameRenderer.getMainCamera().isInitialized() ? mc.gameRenderer.getMainCamera().position() : null;
        for (Iterator<Map.Entry<BlockPos, PianoSnapshot>> it = pianos.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<BlockPos, PianoSnapshot> entry = it.next();
            GrandPianoBlockEntity piano = loaded(mc, entry.getKey());
            if (piano == null) {
                it.remove();
                placed.remove(entry.getKey());
                continue;
            }
            followOwner(mc, entry.getKey(), piano);
            PianoSnapshot snapshot = entry.getValue();
            if (piano.facing() != snapshot.facing()) entry.setValue(PianoSnapshot.of(entry.getKey(), piano.facing(), piano.settings()));
            else if (!piano.settings().equals(snapshot.settings())) entry.setValue(snapshot.with(piano.settings()));
        }
    }

    /**
     * How far loaded chunks reach for a view distance (in chunks): the server sends chunks out to the
     * view distance and one more ring, and never fewer than two, so nothing within this many blocks of
     * the player unloads.
     */
    static int loadedRadius(int viewDistance) {
        return 16 * (Math.max(2, viewDistance) + 1);
    }

    /**
     * Whether {@code pos} is the player's own piano as the world has it now (placed by them, or the one
     * they sit at), whether or not it has played yet. Client thread.
     */
    static boolean ownNow(Minecraft mc, BlockPos pos) {
        if (pos.equals(seat(mc))) return true;
        GrandPianoBlockEntity piano = loaded(mc, pos);
        return piano != null && placedBy(mc, piano);
    }

    /** The piano whose bench the player sits on, or null. */
    private static @Nullable BlockPos seat(Minecraft mc) {
        return mc.player != null && mc.player.getVehicle() instanceof PianoSeatEntity bench ? bench.anchor().immutable() : null;
    }

    /** True when the player placed {@code piano} (they are its owner). */
    private static boolean placedBy(Minecraft mc, GrandPianoBlockEntity piano) {
        UUID owner = piano.owner();
        return mc.player != null && owner != null && owner.equals(mc.player.getUUID());
    }

    /** The pianos currently known. */
    Collection<PianoSnapshot> snapshots() {
        return pianos.values();
    }

    /** Forgets every piano (leaving a world). */
    void clear() {
        pianos.clear();
        placed.clear();
        seat = null;
    }

    private void refresh(Minecraft mc, BlockPos pos) {
        GrandPianoBlockEntity piano = loaded(mc, pos);
        if (piano == null) {
            pianos.remove(pos);
            placed.remove(pos);
            return;
        }
        // Whose it is is known before its first note sounds.
        followOwner(mc, pos.immutable(), piano);
        pianos.put(pos.immutable(), PianoSnapshot.of(pos, piano.facing(), piano.settings()));
    }

    /** Notes whether the player placed {@code piano} (its owner may change, or the player rejoin as someone else). */
    private void followOwner(Minecraft mc, BlockPos pos, GrandPianoBlockEntity piano) {
        if (placedBy(mc, piano)) placed.add(pos);
        else placed.remove(pos);
    }

    private static @Nullable GrandPianoBlockEntity loaded(Minecraft mc, BlockPos pos) {
        return mc.level != null && mc.level.getBlockEntity(pos) instanceof GrandPianoBlockEntity piano ? piano : null;
    }
}
