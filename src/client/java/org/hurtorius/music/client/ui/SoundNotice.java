package org.hurtorius.music.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import org.hurtorius.music.client.audio.AudioEngine;
import org.hurtorius.music.client.audio.PianoAudio;
import org.jspecify.annotations.Nullable;

/**
 * Why this player does not hear a piano, in plain words, or nothing when they should hear it: the sound
 * could not start (no audio device, no voices, the samples did not load), their Jukebox/Note Blocks
 * volume, which every piano follows for them, is at 0, or the piano is someone else's and their own
 * Other pianos volume is at 0. Shown on the piano screen's status line, on every tab and with the panel
 * put away. Client thread.
 */
public final class SoundNotice {
    private SoundNotice() {}

    /** Where the player can change what silences the piano: what clicking the notice opens. */
    public enum Remedy {
        /** Minecraft's Music & Sounds (the Jukebox/Note Blocks volume, the audio device). */
        SOUND_OPTIONS("music.sound.off.open"),
        /** The piano screen's Devices tab (the Other pianos volume). */
        DEVICES("music.sound.muted.open"),
        /** Nowhere: the log says why. */
        NONE(null);

        private final @Nullable String action;

        Remedy(@Nullable String action) {
            this.action = action;
        }

        /** What clicking the notice does, for its tooltip; null when it is not a link. */
        public @Nullable Component action() {
            return action == null ? null : Component.translatable(action);
        }
    }

    /**
     * A notice.
     *
     * @param full    the whole notice, saying where to change it
     * @param brief   the same in fewer words, for a status line too short for the whole
     * @param why     the whole notice without where to change it, for the tooltip of a brief one that is a link
     * @param warning true when no piano can be heard at all (shown in red), false for one piano muted by the player's own choice
     * @param remedy  where the player can change it
     */
    public record Notice(Component full, Component brief, Component why, boolean warning, Remedy remedy) {
        /** The notice as it fits {@code width}: in fewer words where the whole would be cut short. */
        public Component within(Font font, int width) {
            return font.width(full) <= width ? full : brief;
        }
    }

    /** Why this player can hear no piano at all now, or null. */
    public static @Nullable Notice now(Minecraft mc) {
        AudioEngine.Trouble trouble = PianoAudio.trouble();
        String key = switch (trouble) {
            case NO_DEVICE -> "music.sound.off.no_device";
            case FAILED -> "music.sound.off.failed";
            case NO_VOICES -> "music.sound.off.no_voices";
            case NO_SAMPLES -> "music.sound.off.no_samples";
            case NONE, STARTING -> null;
        };
        if (key != null) {
            Component text = Component.translatable(key);
            // The audio device is chosen in Music & Sounds; for voices or samples there is nothing to change there.
            boolean device = trouble == AudioEngine.Trouble.NO_DEVICE || trouble == AudioEngine.Trouble.FAILED;
            return new Notice(text, text, text, true, device ? Remedy.SOUND_OPTIONS : Remedy.NONE);
        }
        // With the master volume at 0 the whole game is silent, which needs no explaining.
        if (mc.options.getSoundSourceVolume(SoundSource.MASTER) > 0 && mc.options.getSoundSourceVolume(SoundSource.RECORDS) <= 0) {
            Component slider = Component.translatable("soundCategory.record");
            return new Notice(Component.translatable("music.sound.off.records", slider), Component.translatable("music.sound.off.records.brief", slider),
                Component.translatable("music.sound.off.records.why", slider), true, Remedy.SOUND_OPTIONS);
        }
        return null;
    }

    /**
     * Why this player does not hear {@code piano}, or null: no piano can be heard ({@link #now}), or it is
     * someone else's and they turned the Other pianos volume down to nothing (the notes they play there
     * themselves still sound).
     */
    public static @Nullable Notice at(Minecraft mc, BlockPos piano) {
        Notice none = now(mc);
        if (none != null) return none;
        if (!PianoAudio.mutedForListener(piano)) return null;
        return new Notice(Component.translatable("music.sound.muted"), Component.translatable("music.sound.muted.brief"),
            Component.translatable("music.sound.muted.why"), false, Remedy.DEVICES);
    }
}
