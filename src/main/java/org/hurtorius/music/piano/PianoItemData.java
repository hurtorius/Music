package org.hurtorius.music.piano;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.codec.StreamCodec;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * What a piano keeps when it is broken to be moved: its settings and its playlist, stopped at the
 * start of the song it was at. It travels on the dropped item as the {@code music:piano} component
 * and is set on the piano the item is placed as. The owner is not kept: whoever places a piano owns
 * it. A new piano's settings and an empty playlist are not kept at all, so an untouched piano drops
 * as a plain item, the same as a newly crafted one.
 *
 * @param settings everything about the piano's sound and its body
 * @param playlist song ids in play order
 * @param index    the song in the playlist it was at, -1 when it is empty
 * @param speed    how fast it plays songs by itself
 * @param loop     what happens at the end of a song
 */
public record PianoItemData(PianoSettings settings, List<String> playlist, int index, float speed, LoopMode loop) {
    private static final Codec<PianoItemData> FIELDS = RecordCodecBuilder.create(i -> i.group(
        PianoSettings.CODEC.optionalFieldOf("settings", PianoSettings.DEFAULT).forGetter(PianoItemData::settings),
        Codec.STRING.listOf().optionalFieldOf("playlist", List.of()).forGetter(PianoItemData::playlist),
        Codec.INT.optionalFieldOf("index", -1).forGetter(PianoItemData::index),
        Codec.FLOAT.optionalFieldOf("speed", 1f).forGetter(PianoItemData::speed),
        LoopMode.CODEC.optionalFieldOf("loop", LoopMode.OFF).forGetter(PianoItemData::loop)
    ).apply(i, PianoItemData::new));
    public static final Codec<PianoItemData> CODEC = FIELDS.xmap(PianoItemData::sanitized, d -> d);

    public static final StreamCodec<FriendlyByteBuf, PianoItemData> STREAM_CODEC = StreamCodec.of((buf, d) -> {
        PianoSettings.STREAM_CODEC.encode(buf, d.settings);
        buf.writeVarInt(d.playlist.size());
        for (String id : d.playlist) buf.writeUtf(id, 64);
        buf.writeVarInt(d.index + 1);
        buf.writeFloat(d.speed);
        buf.writeEnum(d.loop);
    }, buf -> {
        PianoSettings settings = PianoSettings.STREAM_CODEC.decode(buf);
        int n = buf.readVarInt();
        if (n < 0 || n > Playback.MAX_PLAYLIST) throw new IllegalArgumentException("Playlist too long: " + n);
        List<String> playlist = new ArrayList<>(n);
        for (int i = 0; i < n; i++) playlist.add(buf.readUtf(64));
        return new PianoItemData(settings, playlist, buf.readVarInt() - 1, buf.readFloat(), buf.readEnum(LoopMode.class)).sanitized();
    });

    /** What a piano with these settings and this timeline keeps, or null when it is as a new piano is. */
    public static @Nullable PianoItemData of(PianoSettings settings, Playback playback) {
        if (settings.equals(PianoSettings.DEFAULT) && playback.playlist().isEmpty()) return null;
        return new PianoItemData(settings, playback.playlist(), playback.index(), playback.speed(), playback.loop()).sanitized();
    }

    /** Every field brought into its legal range: the settings' own, and the playlist's as a timeline keeps it. */
    public PianoItemData sanitized() {
        Playback kept = new Playback(playlist, index, false, 0, 0, speed, loop, 0).sanitized();
        return new PianoItemData(settings == null ? PianoSettings.DEFAULT : settings.sanitized(), kept.playlist(), kept.index(), kept.speed(), kept.loop());
    }

    /** The timeline a piano placed from this item starts with: its playlist, stopped at the start of the song it was at. */
    public Playback playback(long now, int revision) {
        return new Playback(playlist, index, false, 0, now, speed, loop, revision).sanitized();
    }

    /** One line for the item's tooltip: the piano's sound (a custom sound by its id) and the songs in its playlist. */
    public MutableComponent summary() {
        Component sound = settings.instrument() == Instrument.CUSTOM && !settings.customSound().isEmpty()
            ? Component.literal(settings.customSound()) : Component.translatable(settings.instrument().translationKey());
        return switch (playlist.size()) {
            case 0 -> Component.translatable("item.music.grand_piano.kept", sound);
            case 1 -> Component.translatable("item.music.grand_piano.kept.songs.one", sound);
            default -> Component.translatable("item.music.grand_piano.kept.songs", sound, playlist.size());
        };
    }
}
