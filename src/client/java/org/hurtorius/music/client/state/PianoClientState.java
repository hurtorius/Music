package org.hurtorius.music.client.state;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientBlockEntityEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.hurtorius.music.piano.GrandPianoBlockEntity;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Client-side registry of {@link PianoRuntime}s, keyed by controller block position. Client thread only. */
public final class PianoClientState {
    private static final Map<BlockPos, PianoRuntime> RUNTIMES = new HashMap<>();
    /** Runtimes of pianos that are no longer loaded are dropped after this long without use. */
    private static final long STALE_NANOS = 30_000_000_000L;

    private PianoClientState() {}

    /** Listens for pianos loading on this client: a piano loaded where one was shows as it is (see {@link PianoRuntime#placedAgain}). */
    public static void register() {
        ClientBlockEntityEvents.BLOCK_ENTITY_LOAD.register((be, level) -> {
            if (!(be instanceof GrandPianoBlockEntity)) return;
            PianoRuntime runtime = RUNTIMES.get(be.getBlockPos());
            if (runtime != null) runtime.placedAgain();
        });
    }

    public static PianoRuntime get(BlockPos controller) {
        PianoRuntime runtime = RUNTIMES.computeIfAbsent(controller.immutable(), PianoRuntime::new);
        runtime.touch();
        return runtime;
    }

    public static @Nullable PianoRuntime find(BlockPos controller) {
        return RUNTIMES.get(controller);
    }

    public static Collection<PianoRuntime> all() {
        return Collections.unmodifiableCollection(RUNTIMES.values());
    }

    /** Applies due scheduled events on every piano and forgets pianos that are gone. Call every client tick. */
    public static void tick(Minecraft mc) {
        if (mc.level == null) {
            // Out of every world: drop what a last frame or a late task created after the disconnect cleared it.
            RUNTIMES.clear();
            return;
        }
        long now = System.nanoTime();
        RUNTIMES.values().removeIf(r -> {
            r.update(now);
            boolean loaded = mc.level.getBlockEntity(r.pos) instanceof GrandPianoBlockEntity;
            return !loaded && now - r.lastTouched() > STALE_NANOS;
        });
    }

    public static void clear() {
        RUNTIMES.clear();
    }
}
