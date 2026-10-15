package org.hurtorius.music.client.audio;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.sounds.SoundEventListener;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.hurtorius.music.mixin.client.SoundEngineAccessor;
import org.hurtorius.music.mixin.client.SoundManagerAccessor;
import org.hurtorius.music.piano.PianoLayout;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Subtitles for the piano. Its notes go straight to OpenAL, past Minecraft's sound engine, so the
 * subtitle overlay (and any other sound listener, an accessibility mod's for one) would never hear of
 * them. Each piano that started a note this player can hear is told to the sound engine's listeners as
 * one "Piano plays" sound at the piano, at most every half second: the overlay keeps a single line for
 * it, its arrow pointing at the piano, for as long as it plays. The overlay listens only while the
 * Subtitles option is on, so the option is respected; a muted piano still shows, as vanilla's sounds do.
 * The sound is resolved against the sound manager first, as Minecraft's own are before its listeners
 * hear of them, so a listener that asks what it plays gets an answer (the empty sound). A listener that
 * fails is logged once and left out from then on, so it cannot stop the game's tick. Client thread.
 */
final class PianoSubtitles {
    static final Identifier EVENT = Identifier.fromNamespaceAndPath("music", "block.grand_piano.play");
    static final String KEY = "subtitles.music.grand_piano.play";
    /** A piano playing on is told again this often, which keeps its line alive and its arrow current. */
    static final long REPEAT_NANOS = 500_000_000L;
    private static final WeighedSoundEvents SUBTITLE = new WeighedSoundEvents(EVENT, KEY);
    private static final SoundEvent SOUND = SoundEvent.createVariableRangeEvent(EVENT);

    private final AudioEngine engine;
    private final AudioHost host;
    private final Map<BlockPos, Long> told = new HashMap<>();
    /** Listeners that threw when told of the piano; they are not told again. */
    private final Set<SoundEventListener> failed = Collections.newSetFromMap(new IdentityHashMap<>());
    private long checkedAt = System.nanoTime();

    PianoSubtitles(AudioEngine engine, AudioHost host) {
        this.engine = engine;
        this.host = host;
    }

    void tick(Minecraft mc) {
        long now = System.nanoTime();
        Map<BlockPos, Long> sounded = engine.soundedSince(checkedAt);
        checkedAt = now;
        told.values().removeIf(at -> now - at >= REPEAT_NANOS);
        if (sounded.isEmpty()) return;
        List<SoundEventListener> listeners = List.copyOf(((SoundEngineAccessor) ((SoundManagerAccessor) mc.getSoundManager()).music$soundEngine()).music$listeners());
        if (listeners.isEmpty()) return;
        for (BlockPos pos : sounded.keySet()) {
            PianoSnapshot piano = host.piano(pos);
            if (piano == null || told.containsKey(pos)) continue;
            told.put(pos, now);
            Vec3 at = PianoLayout.center(pos, piano.facing());
            SimpleSoundInstance sound = new SimpleSoundInstance(SOUND, SoundSource.RECORDS, 1f, 1f, RandomSource.create(), at.x, at.y, at.z);
            sound.resolve(mc.getSoundManager());
            float range = Math.min(piano.settings().range(), host.loadedRadius());
            for (SoundEventListener listener : listeners) {
                if (failed.contains(listener)) continue;
                try {
                    listener.onPlaySound(sound, SUBTITLE, range);
                } catch (RuntimeException e) {
                    failed.add(listener);
                    AudioEngine.LOG.error("Music audio: sound listener {} failed on the piano's subtitle; it is not told of the piano again",
                        listener.getClass().getName(), e);
                }
            }
        }
    }

    /** Forgets every piano (leaving a world). */
    void clear() {
        told.clear();
    }
}
