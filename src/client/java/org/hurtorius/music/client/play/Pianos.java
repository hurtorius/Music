package org.hurtorius.music.client.play;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientBlockEntityEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.hurtorius.music.piano.GrandPianoBlockEntity;
import org.hurtorius.music.piano.Pedal;
import org.hurtorius.music.piano.PianoSettings;
import org.hurtorius.music.song.Song;
import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Client-side lookups shared by the play classes: loaded pianos, settings, transposition. */
public final class Pianos {
    private static final Set<BlockPos> LOADED = new LinkedHashSet<>();

    private Pianos() {}

    static void register() {
        ClientBlockEntityEvents.BLOCK_ENTITY_LOAD.register((be, level) -> {
            if (be instanceof GrandPianoBlockEntity) LOADED.add(be.getBlockPos().immutable());
        });
        ClientBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register((be, level) -> {
            if (be instanceof GrandPianoBlockEntity) LOADED.remove(be.getBlockPos());
        });
    }

    /** Controller positions of pianos loaded on this client. */
    public static Set<BlockPos> loaded() {
        return Collections.unmodifiableSet(LOADED);
    }

    static void reset() {
        LOADED.clear();
    }

    public static @Nullable GrandPianoBlockEntity at(BlockPos controller) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        return mc.level.getBlockEntity(controller) instanceof GrandPianoBlockEntity piano ? piano : null;
    }

    public static PianoSettings settings(BlockPos controller) {
        GrandPianoBlockEntity piano = at(controller);
        return piano == null ? PianoSettings.DEFAULT : piano.settings();
    }

    /** The key that sounds when {@code key} is played on this piano, or -1 when transposed off the keyboard. */
    public static int sounding(BlockPos controller, int key) {
        int k = key + settings(controller).transpose();
        return k >= 0 && k <= 87 ? k : -1;
    }

    public static int localPlayerId() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? Integer.MIN_VALUE : mc.player.getId();
    }

    public static Pedal pedalOf(int songPedalType) {
        return switch (songPedalType) {
            case Song.PEDAL_SOSTENUTO -> Pedal.SOSTENUTO;
            case Song.PEDAL_SOFT -> Pedal.SOFT;
            default -> Pedal.SUSTAIN;
        };
    }

    public static int songPedalType(Pedal pedal) {
        return switch (pedal) {
            case SOSTENUTO -> Song.PEDAL_SOSTENUTO;
            case SOFT -> Song.PEDAL_SOFT;
            case SUSTAIN -> Song.PEDAL_SUSTAIN;
        };
    }
}
