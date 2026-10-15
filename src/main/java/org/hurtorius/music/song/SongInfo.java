package org.hurtorius.music.song;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * One song in a world's library.
 *
 * @param id        sha-256 hex of the encoded song
 * @param name      display name
 * @param author    name of the player who added it
 * @param durationMs length
 * @param notes     note count
 * @param bytes     encoded size
 * @param addedAt   wall-clock time it was added (ms)
 */
public record SongInfo(String id, String name, String author, long durationMs, int notes, int bytes, long addedAt) {
    public static final Codec<SongInfo> CODEC = RecordCodecBuilder.create(i -> i.group(
        Codec.STRING.fieldOf("id").forGetter(SongInfo::id),
        Codec.STRING.fieldOf("name").forGetter(SongInfo::name),
        Codec.STRING.optionalFieldOf("author", "").forGetter(SongInfo::author),
        Codec.LONG.fieldOf("duration").forGetter(SongInfo::durationMs),
        Codec.INT.fieldOf("notes").forGetter(SongInfo::notes),
        Codec.INT.fieldOf("bytes").forGetter(SongInfo::bytes),
        Codec.LONG.optionalFieldOf("added", 0L).forGetter(SongInfo::addedAt)
    ).apply(i, SongInfo::new));

    public static final StreamCodec<FriendlyByteBuf, SongInfo> STREAM_CODEC = StreamCodec.of((buf, s) -> {
        buf.writeUtf(s.id, 64);
        buf.writeUtf(s.name, Song.MAX_NAME * 2);
        buf.writeUtf(s.author, 64);
        buf.writeVarLong(s.durationMs);
        buf.writeVarInt(s.notes);
        buf.writeVarInt(s.bytes);
        buf.writeLong(s.addedAt);
    }, buf -> new SongInfo(buf.readUtf(64), buf.readUtf(Song.MAX_NAME * 2), buf.readUtf(64), buf.readVarLong(), buf.readVarInt(), buf.readVarInt(), buf.readLong()));

    public SongInfo renamed(String newName) {
        return new SongInfo(id, newName, author, durationMs, notes, bytes, addedAt);
    }
}
