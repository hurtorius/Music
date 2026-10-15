package org.hurtorius.music.piano;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/**
 * The piano's self-playing timeline, owned by the server.
 *
 * <p>{@code positionMs} is the song position at {@code anchorMs} ({@link org.hurtorius.music.MusicClock}
 * time). While playing, the position advances by {@code speed} ms per real ms. Clients receive a copy
 * whose position was re-anchored when it was sent, and anchor it to their own clock on arrival; a
 * saved copy is anchored again when it loads.</p>
 *
 * @param playlist   song ids (sha-256 hex) in play order
 * @param index      current song in the playlist, -1 when empty
 * @param playing    advancing now
 * @param positionMs song position at the anchor
 * @param anchorMs   {@link org.hurtorius.music.MusicClock} time of the anchor
 * @param speed      0.25 .. 2.0
 * @param loop       what happens at the end of a song
 * @param revision   increments on every change so clients can tell a restart from a continuation
 */
public record Playback(List<String> playlist, int index, boolean playing, long positionMs, long anchorMs, float speed, LoopMode loop, int revision) {
    public static final int MAX_PLAYLIST = 64;
    public static final float MIN_SPEED = 0.25f, MAX_SPEED = 2.0f;
    public static final Playback EMPTY = new Playback(List.of(), -1, false, 0, 0, 1f, LoopMode.OFF, 0);

    private static final Codec<Playback> FIELDS = RecordCodecBuilder.create(i -> i.group(
        Codec.STRING.listOf().optionalFieldOf("playlist", List.of()).forGetter(Playback::playlist),
        Codec.INT.optionalFieldOf("index", -1).forGetter(Playback::index),
        Codec.BOOL.optionalFieldOf("playing", false).forGetter(Playback::playing),
        Codec.LONG.optionalFieldOf("position", 0L).forGetter(Playback::positionMs),
        Codec.LONG.optionalFieldOf("anchor", 0L).forGetter(Playback::anchorMs),
        Codec.FLOAT.optionalFieldOf("speed", 1f).forGetter(Playback::speed),
        LoopMode.CODEC.optionalFieldOf("loop", LoopMode.OFF).forGetter(Playback::loop),
        Codec.INT.optionalFieldOf("revision", 0).forGetter(Playback::revision)
    ).apply(i, Playback::new));
    public static final Codec<Playback> CODEC = FIELDS.xmap(Playback::sanitized, v -> v);

    /** Network form: the position is re-anchored to "now" by the sender, so the anchor is not sent. */
    public static final StreamCodec<FriendlyByteBuf, Playback> STREAM_CODEC = StreamCodec.of((buf, p) -> {
        buf.writeVarInt(p.playlist.size());
        for (String id : p.playlist) buf.writeUtf(id, 64);
        buf.writeVarInt(p.index + 1);
        buf.writeBoolean(p.playing);
        buf.writeVarLong(Math.max(0, p.positionMs));
        buf.writeFloat(p.speed);
        buf.writeEnum(p.loop);
        buf.writeVarInt(p.revision);
    }, buf -> {
        int n = Math.min(buf.readVarInt(), MAX_PLAYLIST);
        List<String> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) list.add(buf.readUtf(64));
        return new Playback(list, buf.readVarInt() - 1, buf.readBoolean(), buf.readVarLong(), 0, buf.readFloat(), buf.readEnum(LoopMode.class), buf.readVarInt()).sanitized();
    });

    public Playback sanitized() {
        List<String> list = new ArrayList<>();
        if (playlist != null) for (String id : playlist) if (id != null && id.matches("[0-9a-f]{64}") && list.size() < MAX_PLAYLIST) list.add(id);
        int i = list.isEmpty() ? -1 : Mth.clamp(index, 0, list.size() - 1);
        float s = Float.isFinite(speed) ? Mth.clamp(speed, MIN_SPEED, MAX_SPEED) : 1f;
        return new Playback(List.copyOf(list), i, playing && i >= 0, Math.max(0, positionMs), anchorMs, s, loop == null ? LoopMode.OFF : loop, revision);
    }

    /** Song position at {@code now} on the clock the anchor is on. */
    public long positionAt(long now) {
        if (!playing) return positionMs;
        return positionMs + (long) (Math.max(0, now - anchorMs) * (double) speed);
    }

    public String currentSong() {
        return index >= 0 && index < playlist.size() ? playlist.get(index) : null;
    }

    /** Same state, re-anchored at {@code now} (used before saving or sending). */
    public Playback anchored(long now) {
        return new Playback(playlist, index, playing, positionAt(now), now, speed, loop, revision);
    }

    public Playback play(long now) {
        if (index < 0) return this;
        return new Playback(playlist, index, true, positionAt(now), now, speed, loop, revision + 1);
    }

    public Playback pause(long now) {
        return new Playback(playlist, index, false, positionAt(now), now, speed, loop, revision + 1);
    }

    public Playback stop(long now) {
        return new Playback(playlist, index, false, 0, now, speed, loop, revision + 1);
    }

    public Playback seek(long positionMs, long now) {
        return new Playback(playlist, index, playing, Math.max(0, positionMs), now, speed, loop, revision + 1);
    }

    public Playback select(int newIndex, boolean play, long now) {
        if (playlist.isEmpty()) return this;
        int i = Math.floorMod(newIndex, playlist.size());
        return new Playback(playlist, i, play, 0, now, speed, loop, revision + 1);
    }

    public Playback withSpeed(float newSpeed, long now) {
        return new Playback(playlist, index, playing, positionAt(now), now, newSpeed, loop, revision + 1).sanitized();
    }

    /** The loop mode only matters when a song ends, so changing it keeps the revision (nothing restarts). */
    public Playback withLoop(LoopMode mode) {
        return new Playback(playlist, index, playing, positionMs, anchorMs, speed, mode, revision);
    }

    public Playback withPlaylist(List<String> ids, int newIndex, boolean play, long now) {
        return new Playback(ids, newIndex, play, 0, now, speed, loop, revision + 1).sanitized();
    }

    /**
     * The playlist with the song at {@code from} moved to {@code to}, the others closing up behind it.
     * The current song stays current wherever it now stands, and goes on from where it is: playing,
     * position and revision are kept, so nothing restarts. A playlist may hold a song twice: moved past
     * itself the song playing changes only its index. This same playback when either index is outside
     * the playlist, they are the same, or the move changes nothing (a song moved past itself, neither
     * of the two playing).
     */
    public Playback moved(int from, int to) {
        int size = playlist.size();
        if (from < 0 || from >= size || to < 0 || to >= size || from == to) return this;
        List<String> list = new ArrayList<>(playlist);
        list.add(to, list.remove(from));
        int current = index;
        if (index == from) current = to;
        else if (from < index && index <= to) current = index - 1;
        else if (to <= index && index < from) current = index + 1;
        if (current == index && list.equals(playlist)) return this;
        return new Playback(List.copyOf(list), current, playing, positionMs, anchorMs, speed, loop, revision);
    }

    /**
     * Applies the end of the current song: repeat, advance, or stop. {@code durationMs} is the
     * current song's length.
     */
    public Playback afterEnd(long durationMs, long now) {
        if (!playing || index < 0) return this;
        long overshoot = Math.max(0, positionAt(now) - durationMs);
        return switch (loop) {
            case ONE -> new Playback(playlist, index, true, 0, now - (long) (overshoot / (double) speed), speed, loop, revision + 1);
            case ALL -> new Playback(playlist, (index + 1) % playlist.size(), true, 0, now, speed, loop, revision + 1);
            case OFF -> index + 1 < playlist.size()
                ? new Playback(playlist, index + 1, true, 0, now, speed, loop, revision + 1)
                : new Playback(playlist, index, false, 0, now, speed, loop, revision + 1);
        };
    }
}
