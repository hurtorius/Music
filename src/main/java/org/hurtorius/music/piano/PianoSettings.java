package org.hurtorius.music.piano;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/**
 * Everything about one piano that everyone hears and sees the same way. Edited at the piano,
 * stored in its block entity, validated on the server.
 *
 * @param instrument  sound set
 * @param customSound sound event id for {@link Instrument#CUSTOM}, e.g. {@code minecraft:entity.cat.ambient}
 * @param customRoot  MIDI note the custom sound plays at with pitch 1.0
 * @param volume      0..1 loudness of this piano
 * @param tone        -1 (dark) .. 1 (bright)
 * @param reverb      0..1 room amount
 * @param range       blocks at which the piano fades to silence
 * @param tuning      concert pitch of A4 in Hz
 * @param transpose   semitones added to everything played on this piano
 * @param lidOpen     main lid open (on the prop) or closed; closed sounds softer and darker
 * @param deskUp      music desk raised
 * @param sheet       what the music desk shows
 * @param redstone    how redstone controls self-playing
 * @param access      who may play and who may change the sound and settings
 */
public record PianoSettings(Instrument instrument, String customSound, int customRoot, float volume, float tone, float reverb,
                            int range, float tuning, int transpose, boolean lidOpen, boolean deskUp, SheetMode sheet,
                            RedstoneMode redstone, PlayAccess access) {
    public static final int MIN_RANGE = 8, MAX_RANGE = 96;
    public static final float MIN_TUNING = 415f, MAX_TUNING = 466f;
    public static final int MAX_SOUND_ID = 200;
    public static final PianoSettings DEFAULT = new PianoSettings(Instrument.GRAND_PIANO, "", 60, 0.8f, 0f, 0.3f, 32, 440f, 0,
        true, true, SheetMode.NOTATION, RedstoneMode.TOGGLE, PlayAccess.PLAY);

    private static final Codec<PianoSettings> FIELDS = RecordCodecBuilder.create(i -> i.group(
        Instrument.CODEC.optionalFieldOf("instrument", DEFAULT.instrument).forGetter(PianoSettings::instrument),
        Codec.STRING.optionalFieldOf("custom_sound", DEFAULT.customSound).forGetter(PianoSettings::customSound),
        Codec.INT.optionalFieldOf("custom_root", DEFAULT.customRoot).forGetter(PianoSettings::customRoot),
        Codec.FLOAT.optionalFieldOf("volume", DEFAULT.volume).forGetter(PianoSettings::volume),
        Codec.FLOAT.optionalFieldOf("tone", DEFAULT.tone).forGetter(PianoSettings::tone),
        Codec.FLOAT.optionalFieldOf("reverb", DEFAULT.reverb).forGetter(PianoSettings::reverb),
        Codec.INT.optionalFieldOf("range", DEFAULT.range).forGetter(PianoSettings::range),
        Codec.FLOAT.optionalFieldOf("tuning", DEFAULT.tuning).forGetter(PianoSettings::tuning),
        Codec.INT.optionalFieldOf("transpose", DEFAULT.transpose).forGetter(PianoSettings::transpose),
        Codec.BOOL.optionalFieldOf("lid_open", DEFAULT.lidOpen).forGetter(PianoSettings::lidOpen),
        Codec.BOOL.optionalFieldOf("desk_up", DEFAULT.deskUp).forGetter(PianoSettings::deskUp),
        SheetMode.CODEC.optionalFieldOf("sheet", DEFAULT.sheet).forGetter(PianoSettings::sheet),
        RedstoneMode.CODEC.optionalFieldOf("redstone", DEFAULT.redstone).forGetter(PianoSettings::redstone),
        PlayAccess.CODEC.optionalFieldOf("access", DEFAULT.access).forGetter(PianoSettings::access)
    ).apply(i, PianoSettings::new));
    public static final Codec<PianoSettings> CODEC = FIELDS.xmap(PianoSettings::sanitized, v -> v);

    public static final StreamCodec<FriendlyByteBuf, PianoSettings> STREAM_CODEC = StreamCodec.of(PianoSettings::write, PianoSettings::read);

    /** Clamps every field into its legal range; never throws. */
    public PianoSettings sanitized() {
        String sound = customSound == null ? "" : customSound.trim();
        if (sound.length() > MAX_SOUND_ID || (!sound.isEmpty() && Identifier.tryParse(sound) == null)) sound = "";
        return new PianoSettings(
            instrument == null ? Instrument.GRAND_PIANO : instrument, sound, Mth.clamp(customRoot, 0, 127),
            clamp01(volume), Float.isFinite(tone) ? Mth.clamp(tone, -1f, 1f) : 0f, clamp01(reverb),
            Mth.clamp(range, MIN_RANGE, MAX_RANGE), Float.isFinite(tuning) ? Mth.clamp(tuning, MIN_TUNING, MAX_TUNING) : 440f,
            Mth.clamp(transpose, -24, 24), lidOpen, deskUp,
            sheet == null ? SheetMode.NOTATION : sheet, redstone == null ? RedstoneMode.TOGGLE : redstone,
            access == null ? PlayAccess.PLAY : access);
    }

    /**
     * These settings with what a performer who may not change the piano's sound and settings still
     * changes taken from {@code asked}: the lid, the desk and what the desk shows, which belong to
     * playing it. Everything else stays as it is here.
     */
    public PianoSettings asPerformerMay(PianoSettings asked) {
        return withLidOpen(asked.lidOpen).withDeskUp(asked.deskUp).withSheet(asked.sheet == null ? sheet : asked.sheet);
    }

    private static float clamp01(float v) {
        return Float.isFinite(v) ? Mth.clamp(v, 0f, 1f) : 0f;
    }

    private static void write(FriendlyByteBuf buf, PianoSettings s) {
        buf.writeEnum(s.instrument);
        buf.writeUtf(s.customSound, MAX_SOUND_ID);
        buf.writeByte(s.customRoot);
        buf.writeFloat(s.volume);
        buf.writeFloat(s.tone);
        buf.writeFloat(s.reverb);
        buf.writeVarInt(s.range);
        buf.writeFloat(s.tuning);
        buf.writeByte(s.transpose);
        buf.writeBoolean(s.lidOpen);
        buf.writeBoolean(s.deskUp);
        buf.writeEnum(s.sheet);
        buf.writeEnum(s.redstone);
        buf.writeEnum(s.access);
    }

    private static PianoSettings read(FriendlyByteBuf buf) {
        return new PianoSettings(buf.readEnum(Instrument.class), buf.readUtf(MAX_SOUND_ID), buf.readByte(), buf.readFloat(), buf.readFloat(),
            buf.readFloat(), buf.readVarInt(), buf.readFloat(), buf.readByte(), buf.readBoolean(), buf.readBoolean(),
            buf.readEnum(SheetMode.class), buf.readEnum(RedstoneMode.class), buf.readEnum(PlayAccess.class)).sanitized();
    }

    public PianoSettings withInstrument(Instrument v) { return new PianoSettings(v, customSound, customRoot, volume, tone, reverb, range, tuning, transpose, lidOpen, deskUp, sheet, redstone, access); }
    public PianoSettings withCustomSound(String v, int root) { return new PianoSettings(instrument, v, root, volume, tone, reverb, range, tuning, transpose, lidOpen, deskUp, sheet, redstone, access).sanitized(); }
    public PianoSettings withVolume(float v) { return new PianoSettings(instrument, customSound, customRoot, v, tone, reverb, range, tuning, transpose, lidOpen, deskUp, sheet, redstone, access).sanitized(); }
    public PianoSettings withTone(float v) { return new PianoSettings(instrument, customSound, customRoot, volume, v, reverb, range, tuning, transpose, lidOpen, deskUp, sheet, redstone, access).sanitized(); }
    public PianoSettings withReverb(float v) { return new PianoSettings(instrument, customSound, customRoot, volume, tone, v, range, tuning, transpose, lidOpen, deskUp, sheet, redstone, access).sanitized(); }
    public PianoSettings withRange(int v) { return new PianoSettings(instrument, customSound, customRoot, volume, tone, reverb, v, tuning, transpose, lidOpen, deskUp, sheet, redstone, access).sanitized(); }
    public PianoSettings withTuning(float v) { return new PianoSettings(instrument, customSound, customRoot, volume, tone, reverb, range, v, transpose, lidOpen, deskUp, sheet, redstone, access).sanitized(); }
    public PianoSettings withTranspose(int v) { return new PianoSettings(instrument, customSound, customRoot, volume, tone, reverb, range, tuning, v, lidOpen, deskUp, sheet, redstone, access).sanitized(); }
    public PianoSettings withLidOpen(boolean v) { return new PianoSettings(instrument, customSound, customRoot, volume, tone, reverb, range, tuning, transpose, v, deskUp, sheet, redstone, access); }
    public PianoSettings withDeskUp(boolean v) { return new PianoSettings(instrument, customSound, customRoot, volume, tone, reverb, range, tuning, transpose, lidOpen, v, sheet, redstone, access); }
    public PianoSettings withSheet(SheetMode v) { return new PianoSettings(instrument, customSound, customRoot, volume, tone, reverb, range, tuning, transpose, lidOpen, deskUp, v, redstone, access); }
    public PianoSettings withRedstone(RedstoneMode v) { return new PianoSettings(instrument, customSound, customRoot, volume, tone, reverb, range, tuning, transpose, lidOpen, deskUp, sheet, v, access); }
    public PianoSettings withAccess(PlayAccess v) { return new PianoSettings(instrument, customSound, customRoot, volume, tone, reverb, range, tuning, transpose, lidOpen, deskUp, sheet, redstone, v); }
}
