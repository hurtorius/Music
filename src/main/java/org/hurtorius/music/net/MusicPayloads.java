package org.hurtorius.music.net;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.hurtorius.music.piano.PianoSettings;
import org.hurtorius.music.song.NoteEvent;
import org.hurtorius.music.song.Song;
import org.hurtorius.music.song.SongInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Every packet Music sends. Client to server packets stay under vanilla's 32767-byte serverbound
 * limit; song data moves in {@link #CHUNK} byte pieces. A player joins only with the same
 * {@link #PROTOCOL} as the server ({@link Hello}, see {@link VersionCheck}).
 */
public final class MusicPayloads {
    public static final int CHUNK = 28_000;
    /**
     * The version of everything Music sends between a game and a server: these payloads and the
     * codecs they use (PianoSettings, NoteEvent, SongInfo, Playback, PianoItemData and the order of
     * their enums). It goes up with any change to them once a version with the old ones is out
     * (PayloadFormatTest holds a fingerprint of their bytes for each protocol), so a game and a server
     * with different ones are told so as they connect rather than failing on the first piano.
     */
    public static final int PROTOCOL = 1;

    private MusicPayloads() {}

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> payloadType(String path) {
        return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("music", path));
    }

    // ---------------------------------------------------------------- client -> server

    /** Live notes from the player seated at (or standing next to) the piano at {@code pos}. */
    public record LiveNotes(BlockPos pos, int session, List<NoteEvent> events) implements CustomPacketPayload {
        public static final Type<LiveNotes> TYPE = payloadType("live_notes");
        public static final StreamCodec<RegistryFriendlyByteBuf, LiveNotes> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeBlockPos(p.pos);
            buf.writeVarInt(p.session);
            NoteEvent.LIST_CODEC.encode(buf, p.events);
        }, buf -> new LiveNotes(buf.readBlockPos(), buf.readVarInt(), NoteEvent.LIST_CODEC.decode(buf)));

        @Override
        public Type<LiveNotes> type() {
            return TYPE;
        }
    }

    /** Replace the piano's settings. */
    public record ChangeSettings(BlockPos pos, PianoSettings settings) implements CustomPacketPayload {
        public static final Type<ChangeSettings> TYPE = payloadType("change_settings");
        public static final StreamCodec<RegistryFriendlyByteBuf, ChangeSettings> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeBlockPos(p.pos);
            PianoSettings.STREAM_CODEC.encode(buf, p.settings);
        }, buf -> new ChangeSettings(buf.readBlockPos(), PianoSettings.STREAM_CODEC.decode(buf)));

        @Override
        public Type<ChangeSettings> type() {
            return TYPE;
        }
    }

    /**
     * Self-playing controls.
     *
     * @param action one of the {@code PLAY_*} constants
     * @param songId song for {@link #PLAY_SONG} / {@link #QUEUE}, else ""
     * @param value  position ms for {@link #SEEK}, playlist index for {@link #SELECT}/{@link #REMOVE}, loop ordinal for {@link #LOOP},
     *               for {@link #MOVE} the playlist index moved from and the one it goes to, as {@code (from << 8) | to}
     *               (see {@link #move})
     * @param speed  for {@link #SPEED}
     */
    public record PlaybackControl(BlockPos pos, int action, String songId, long value, float speed) implements CustomPacketPayload {
        public static final int PLAY_SONG = 0, PLAY = 1, PAUSE = 2, STOP = 3, SEEK = 4, NEXT = 5, PREVIOUS = 6,
            LOOP = 7, SPEED = 8, QUEUE = 9, REMOVE = 10, CLEAR = 11, SELECT = 12, MOVE = 13;
        public static final Type<PlaybackControl> TYPE = payloadType("playback_control");
        public static final StreamCodec<RegistryFriendlyByteBuf, PlaybackControl> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeBlockPos(p.pos);
            buf.writeVarInt(p.action);
            buf.writeUtf(p.songId, 64);
            buf.writeVarLong(Math.max(0, p.value));
            buf.writeFloat(p.speed);
        }, buf -> new PlaybackControl(buf.readBlockPos(), buf.readVarInt(), buf.readUtf(64), buf.readVarLong(), buf.readFloat()));

        public static PlaybackControl of(BlockPos pos, int action) {
            return new PlaybackControl(pos, action, "", 0, 1f);
        }

        /** Moves the playlist's song at index {@code from} to index {@code to} (both below {@link org.hurtorius.music.piano.Playback#MAX_PLAYLIST}). */
        public static PlaybackControl move(BlockPos pos, int from, int to) {
            return new PlaybackControl(pos, MOVE, "", (long) (from & 0xFF) << 8 | to & 0xFF, 1f);
        }

        /** For {@link #MOVE}: the playlist index the song is moved from. */
        public int moveFrom() {
            return (int) (value >> 8 & 0xFF);
        }

        /** For {@link #MOVE}: the playlist index the song is moved to. */
        public int moveTo() {
            return (int) (value & 0xFF);
        }

        @Override
        public Type<PlaybackControl> type() {
            return TYPE;
        }
    }

    /** Start uploading an encoded song (see {@code SongCodec}) to the world library. */
    public record UploadBegin(int requestId, String name, int size, String songId) implements CustomPacketPayload {
        public static final Type<UploadBegin> TYPE = payloadType("upload_begin");
        public static final StreamCodec<RegistryFriendlyByteBuf, UploadBegin> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeVarInt(p.requestId);
            buf.writeUtf(p.name, Song.MAX_NAME * 2);
            buf.writeVarInt(p.size);
            buf.writeUtf(p.songId, 64);
        }, buf -> new UploadBegin(buf.readVarInt(), buf.readUtf(Song.MAX_NAME * 2), buf.readVarInt(), buf.readUtf(64)));

        @Override
        public Type<UploadBegin> type() {
            return TYPE;
        }
    }

    /** One piece of an upload. Offsets must arrive in order. */
    public record UploadChunk(int requestId, int offset, byte[] data) implements CustomPacketPayload {
        public static final Type<UploadChunk> TYPE = payloadType("upload_chunk");
        public static final StreamCodec<RegistryFriendlyByteBuf, UploadChunk> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeVarInt(p.requestId);
            buf.writeVarInt(p.offset);
            buf.writeByteArray(p.data);
        }, buf -> new UploadChunk(buf.readVarInt(), buf.readVarInt(), buf.readByteArray(CHUNK)));

        @Override
        public Type<UploadChunk> type() {
            return TYPE;
        }
    }

    /** Ask for the next piece of a song this client does not have cached. */
    public record SongRequest(String songId, int offset) implements CustomPacketPayload {
        public static final Type<SongRequest> TYPE = payloadType("song_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, SongRequest> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeUtf(p.songId, 64);
            buf.writeVarInt(p.offset);
        }, buf -> new SongRequest(buf.readUtf(64), buf.readVarInt()));

        @Override
        public Type<SongRequest> type() {
            return TYPE;
        }
    }

    /** Library management: {@link #LIST}, {@link #RENAME} (text = new name) or {@link #DELETE}. */
    public record LibraryAction(int action, String songId, String text) implements CustomPacketPayload {
        public static final int LIST = 0, RENAME = 1, DELETE = 2;
        public static final Type<LibraryAction> TYPE = payloadType("library_action");
        public static final StreamCodec<RegistryFriendlyByteBuf, LibraryAction> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeVarInt(p.action);
            buf.writeUtf(p.songId, 64);
            buf.writeUtf(p.text, Song.MAX_NAME * 2);
        }, buf -> new LibraryAction(buf.readVarInt(), buf.readUtf(64), buf.readUtf(Song.MAX_NAME * 2)));

        @Override
        public Type<LibraryAction> type() {
            return TYPE;
        }
    }

    /** Leave the piano bench (the screen's "Stand up"). */
    public record StandUp(BlockPos pos) implements CustomPacketPayload {
        public static final Type<StandUp> TYPE = payloadType("stand_up");
        public static final StreamCodec<RegistryFriendlyByteBuf, StandUp> CODEC = StreamCodec.of((buf, p) -> buf.writeBlockPos(p.pos), buf -> new StandUp(buf.readBlockPos()));

        @Override
        public Type<StandUp> type() {
            return TYPE;
        }
    }

    // ---------------------------------------------------------------- server -> client

    /** Notes another player played; {@code playerId} is their entity id. */
    public record PlayedNotes(BlockPos pos, int playerId, int session, List<NoteEvent> events) implements CustomPacketPayload {
        public static final Type<PlayedNotes> TYPE = payloadType("played_notes");
        public static final StreamCodec<RegistryFriendlyByteBuf, PlayedNotes> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeBlockPos(p.pos);
            buf.writeVarInt(p.playerId);
            buf.writeVarInt(p.session);
            NoteEvent.LIST_CODEC.encode(buf, p.events);
        }, buf -> new PlayedNotes(buf.readBlockPos(), buf.readVarInt(), buf.readVarInt(), NoteEvent.LIST_CODEC.decode(buf)));

        @Override
        public Type<PlayedNotes> type() {
            return TYPE;
        }
    }

    /**
     * Server tells the client to open the piano screen after a right click.
     *
     * @param standing  {@link #SEATED} when the server seated the player on the bench, else why they stand
     * @param canPlay   whether they may play it
     * @param performer for {@link #BENCH_TAKEN}, the name of the player at the bench
     */
    public record OpenPiano(BlockPos pos, int standing, boolean canPlay, String performer) implements CustomPacketPayload {
        /**
         * Why the player stands: they sneaked; someone else sits at the bench; they ride something; the
         * bench has no room for their head (a message says how much they need); they may not play this
         * piano (a message names its owner); the bench could not take them.
         */
        public static final int SEATED = 0, SNEAKING = 1, BENCH_TAKEN = 2, RIDING = 3, NO_HEADROOM = 4, NOT_ALLOWED = 5, COULD_NOT_SIT = 6;
        public static final Type<OpenPiano> TYPE = payloadType("open_piano");
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenPiano> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeBlockPos(p.pos);
            buf.writeVarInt(p.standing);
            buf.writeBoolean(p.canPlay);
            buf.writeUtf(p.performer, 16);
        }, buf -> new OpenPiano(buf.readBlockPos(), buf.readVarInt(), buf.readBoolean(), buf.readUtf(16)));

        /** True when the server seated the player on the bench. */
        public boolean seated() {
            return standing == SEATED;
        }

        @Override
        public Type<OpenPiano> type() {
            return TYPE;
        }
    }

    /**
     * Progress of an upload: {@link #READY} (send from offset), {@link #ACK} (received up to offset),
     * {@link #DONE} (stored as songId), {@link #FAILED} (message explains).
     */
    public record UploadStatus(int requestId, int state, int offset, String songId, String message) implements CustomPacketPayload {
        public static final int READY = 0, ACK = 1, DONE = 2, FAILED = 3;
        public static final Type<UploadStatus> TYPE = payloadType("upload_status");
        public static final StreamCodec<RegistryFriendlyByteBuf, UploadStatus> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeVarInt(p.requestId);
            buf.writeVarInt(p.state);
            buf.writeVarInt(p.offset);
            buf.writeUtf(p.songId, 64);
            buf.writeUtf(p.message, 256);
        }, buf -> new UploadStatus(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readUtf(64), buf.readUtf(256)));

        @Override
        public Type<UploadStatus> type() {
            return TYPE;
        }
    }

    /** A piece of a song the client asked for. {@code total} is the full encoded size; empty data with total -1 means unavailable. */
    public record SongChunk(String songId, int offset, int total, byte[] data) implements CustomPacketPayload {
        public static final Type<SongChunk> TYPE = payloadType("song_chunk");
        public static final StreamCodec<RegistryFriendlyByteBuf, SongChunk> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeUtf(p.songId, 64);
            buf.writeVarInt(p.offset);
            buf.writeInt(p.total);
            buf.writeByteArray(p.data);
        }, buf -> new SongChunk(buf.readUtf(64), buf.readVarInt(), buf.readInt(), buf.readByteArray(CHUNK)));

        @Override
        public Type<SongChunk> type() {
            return TYPE;
        }
    }

    /** The world's song library. */
    public record Library(List<SongInfo> songs, long usedBytes, long quotaBytes) implements CustomPacketPayload {
        public static final int MAX_SONGS = 2048;
        public static final Type<Library> TYPE = payloadType("library");
        public static final StreamCodec<RegistryFriendlyByteBuf, Library> CODEC = StreamCodec.of((buf, p) -> {
            int n = Math.min(p.songs.size(), MAX_SONGS);
            buf.writeVarInt(n);
            for (int i = 0; i < n; i++) SongInfo.STREAM_CODEC.encode(buf, p.songs.get(i));
            buf.writeVarLong(p.usedBytes);
            buf.writeVarLong(p.quotaBytes);
        }, buf -> {
            int n = buf.readVarInt();
            if (n < 0 || n > MAX_SONGS) throw new IllegalArgumentException("Library too large: " + n);
            List<SongInfo> songs = new ArrayList<>(n);
            for (int i = 0; i < n; i++) songs.add(SongInfo.STREAM_CODEC.decode(buf));
            return new Library(songs, buf.readVarLong(), buf.readVarLong());
        });

        @Override
        public Type<Library> type() {
            return TYPE;
        }
    }

    // ---------------------------------------------------------------- both ways, while a player joins

    /**
     * The first thing a server with Music and a joining game with Music tell each other: the
     * {@link #PROTOCOL} they speak and the Music version they have, for the message when they differ.
     */
    public record Hello(int protocol, String version) implements CustomPacketPayload {
        public static final Type<Hello> TYPE = payloadType("hello");
        public static final StreamCodec<FriendlyByteBuf, Hello> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeVarInt(p.protocol);
            buf.writeUtf(p.version, 64);
        }, buf -> new Hello(buf.readVarInt(), buf.readUtf(64)));

        @Override
        public Type<Hello> type() {
            return TYPE;
        }
    }

    public static void register() {
        PayloadTypeRegistry.configurationS2C().register(Hello.TYPE, Hello.CODEC);
        PayloadTypeRegistry.configurationC2S().register(Hello.TYPE, Hello.CODEC);
        PayloadTypeRegistry.playC2S().register(LiveNotes.TYPE, LiveNotes.CODEC);
        PayloadTypeRegistry.playC2S().register(ChangeSettings.TYPE, ChangeSettings.CODEC);
        PayloadTypeRegistry.playC2S().register(PlaybackControl.TYPE, PlaybackControl.CODEC);
        PayloadTypeRegistry.playC2S().register(UploadBegin.TYPE, UploadBegin.CODEC);
        PayloadTypeRegistry.playC2S().register(UploadChunk.TYPE, UploadChunk.CODEC);
        PayloadTypeRegistry.playC2S().register(SongRequest.TYPE, SongRequest.CODEC);
        PayloadTypeRegistry.playC2S().register(LibraryAction.TYPE, LibraryAction.CODEC);
        PayloadTypeRegistry.playC2S().register(StandUp.TYPE, StandUp.CODEC);
        PayloadTypeRegistry.playS2C().register(PlayedNotes.TYPE, PlayedNotes.CODEC);
        PayloadTypeRegistry.playS2C().register(OpenPiano.TYPE, OpenPiano.CODEC);
        PayloadTypeRegistry.playS2C().register(UploadStatus.TYPE, UploadStatus.CODEC);
        PayloadTypeRegistry.playS2C().register(SongChunk.TYPE, SongChunk.CODEC);
        PayloadTypeRegistry.playS2C().registerLarge(Library.TYPE, Library.CODEC, 1 << 20);
    }
}
