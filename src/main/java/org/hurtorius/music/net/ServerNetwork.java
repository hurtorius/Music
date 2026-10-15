package org.hurtorius.music.net;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.hurtorius.music.MusicClock;
import org.hurtorius.music.piano.GrandPianoBlock;
import org.hurtorius.music.piano.GrandPianoBlockEntity;
import org.hurtorius.music.piano.PianoLayout;
import org.hurtorius.music.piano.PianoSettings;
import org.hurtorius.music.seat.PianoSeatEntity;
import org.hurtorius.music.song.NoteEvent;
import org.hurtorius.music.song.Song;
import org.hurtorius.music.song.SongCodec;
import org.hurtorius.music.song.SongInfo;
import org.hurtorius.music.song.SongLibrary;
import org.hurtorius.music.song.StarterSongs;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;

/** Server side of every Music packet: validation, permissions, rate limits, library and transfers. */
public final class ServerNetwork {
    private static final Logger LOG = LoggerFactory.getLogger("Music");
    /** Live events a player may send per second before the rest are dropped. */
    static final int MAX_EVENTS_PER_SECOND = 800;
    /** Settings changes and library listings per second. */
    static final int MAX_ACTIONS_PER_SECOND = 20;
    /** Playback commands per second (each one restarts scheduling on every nearby client). */
    static final int MAX_PLAYBACK_PER_SECOND = 8;
    /** Renames and deletions per second. */
    static final int MAX_LIBRARY_CHANGES_PER_SECOND = 4;
    /** Download chunks served right away per second; more wait in a short queue. */
    static final int MAX_DOWNLOAD_CHUNKS_PER_SECOND = 60;
    static final int MAX_QUEUED_DOWNLOADS = 8;
    /** One upload may start every this many milliseconds per player. */
    static final long UPLOAD_COOLDOWN_MS = 2_000;
    /** Uploads one player may start in any minute, finished or failed. */
    static final int MAX_UPLOADS_PER_MINUTE = 6;
    /** The minute {@link #MAX_UPLOADS_PER_MINUTE} counts in. */
    static final long UPLOAD_WINDOW_MS = 60_000;
    static final long UPLOAD_TIMEOUT_MS = 60_000;
    static final int MAX_MESSAGE = 250;

    private static final Map<UUID, Budget> budgets = new HashMap<>();
    private static final Map<UUID, Upload> uploads = new HashMap<>();
    private static final Map<UUID, Deque<MusicPayloads.SongRequest>> pendingDownloads = new HashMap<>();
    /** Per performer, the keys and pedals their listeners were shown as held. */
    private static final Map<UUID, HeldNotes> held = new HashMap<>();
    /** Decoding and hashing uploaded songs happens here, one at a time, never on the server thread. */
    private static final ExecutorService VALIDATOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "music-song-validator");
        t.setDaemon(true);
        return t;
    });
    private static boolean libraryDirty;
    /** Operators told this run that the song library had to be rebuilt. */
    private static final Set<UUID> toldOfRecovery = new HashSet<>();
    /** What this server's owner set for the songs players add (config/music-server.json), read as the server starts. */
    private static SongLimits limits = SongLimits.DEFAULT;
    /** Pianos loaded on the server, so a singleplayer pause can be taken out of their timelines. */
    private static final Set<GrandPianoBlockEntity> LOADED = Collections.newSetFromMap(new IdentityHashMap<>());
    /** The game's paused time counted when the server noticed the pause going on now, or -1 while it runs. Server thread. */
    private static long pausedMark = -1;

    private ServerNetwork() {}

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(MusicPayloads.LiveNotes.TYPE, (p, c) -> liveNotes(c.player(), p));
        ServerPlayNetworking.registerGlobalReceiver(MusicPayloads.ChangeSettings.TYPE, (p, c) -> settings(c.player(), p));
        ServerPlayNetworking.registerGlobalReceiver(MusicPayloads.PlaybackControl.TYPE, (p, c) -> playback(c.player(), p));
        ServerPlayNetworking.registerGlobalReceiver(MusicPayloads.UploadBegin.TYPE, (p, c) -> uploadBegin(c.player(), p));
        ServerPlayNetworking.registerGlobalReceiver(MusicPayloads.UploadChunk.TYPE, (p, c) -> uploadChunk(c.player(), p));
        ServerPlayNetworking.registerGlobalReceiver(MusicPayloads.SongRequest.TYPE, (p, c) -> songRequest(c.player(), p));
        ServerPlayNetworking.registerGlobalReceiver(MusicPayloads.LibraryAction.TYPE, (p, c) -> library(c.player(), p));
        ServerPlayNetworking.registerGlobalReceiver(MusicPayloads.StandUp.TYPE, (p, c) -> {
            if (c.player().getVehicle() instanceof PianoSeatEntity) c.player().stopRiding();
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            // The budget stays: leaving and joining again must not refill it. Idle ones are dropped in tick.
            uploads.remove(handler.player.getUUID());
            pendingDownloads.remove(handler.player.getUUID());
        });
        ServerBlockEntityEvents.BLOCK_ENTITY_LOAD.register((be, level) -> {
            if (be instanceof GrandPianoBlockEntity piano) LOADED.add(piano);
        });
        ServerBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register((be, level) -> {
            if (be instanceof GrandPianoBlockEntity piano) LOADED.remove(piano);
        });
        ServerTickEvents.END_SERVER_TICK.register(ServerNetwork::tick);
        ServerLifecycleEvents.SERVER_STARTING.register(server -> limits = SongLimits.load(FabricLoader.getInstance().getConfigDir(), server.isDedicatedServer()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            LOADED.clear();
            pausedMark = -1;
            budgets.clear();
            uploads.clear();
            pendingDownloads.clear();
            held.clear();
            libraryDirty = false;
            toldOfRecovery.clear();
            SongLibrary.unload();
        });
    }

    // ------------------------------------------------------------------ pianos

    private static @Nullable GrandPianoBlockEntity piano(ServerPlayer player, BlockPos pos) {
        ServerLevel level = player.level();
        if (!level.isLoaded(pos)) return null;
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof GrandPianoBlock)) return null;
        GrandPianoBlockEntity piano = GrandPianoBlock.controller(level, pos, state);
        if (piano == null || !piano.isNear(player)) return null;
        return piano;
    }

    private static void liveNotes(ServerPlayer player, MusicPayloads.LiveNotes p) {
        if (p.events().isEmpty()) return;
        boolean allowed = budget(player).spendEvents(p.events().size());
        GrandPianoBlockEntity piano = allowed ? piano(player, p.pos()) : null;
        MinecraftServer server = player.level().getServer();
        HeldNotes shown = held.get(player.getUUID());
        if (piano == null || !piano.canPlay(player)) {
            // Refused; listeners still get the release of anything they were shown as held.
            if (shown != null && shown.isFor(player.level().dimension(), p.pos(), p.session())) {
                releaseFor(server, shown, shown.releasesOf(p.events()));
                if (shown.isEmpty()) held.remove(player.getUUID());
            }
            return;
        }
        BlockPos controller = piano.getBlockPos();
        if (shown == null || !shown.isFor(player.level().dimension(), controller, p.session())) {
            if (shown != null) releaseFor(server, shown, shown.releaseAll());
            shown = new HeldNotes(player.level().dimension(), controller, player.getId(), p.session());
            held.put(player.getUUID(), shown);
        }
        List<NoteEvent> releases = shown.relayed(p.events());
        if (shown.isEmpty()) held.remove(player.getUUID());
        MusicPayloads.PlayedNotes out = new MusicPayloads.PlayedNotes(controller, player.getId(), p.session(), p.events());
        MusicPayloads.PlayedNotes letGo = releases.isEmpty() ? null : new MusicPayloads.PlayedNotes(controller, player.getId(), p.session(), releases);
        Vec3 center = PianoLayout.center(controller, piano.facing());
        double reach = piano.settings().range() + 16;
        for (ServerPlayer other : PlayerLookup.tracking(player.level(), controller)) {
            if (other == player) continue;
            // Beyond reach only releases go out: the player may have seen the press before moving away.
            if (other.position().distanceToSqr(center) <= reach * reach) send(other, out);
            else if (letGo != null) send(other, letGo);
        }
    }

    /** Sends releases of what a performer's listeners were shown as held to everyone who has that piano loaded. */
    private static void releaseFor(MinecraftServer server, HeldNotes shown, List<NoteEvent> releases) {
        ServerLevel level = server.getLevel(shown.dimension);
        if (level == null || releases.isEmpty()) return;
        MusicPayloads.PlayedNotes out = new MusicPayloads.PlayedNotes(shown.piano, shown.playerId, shown.session, releases);
        for (ServerPlayer other : PlayerLookup.tracking(level, shown.piano)) {
            if (other.getId() != shown.playerId) send(other, out);
        }
    }

    /**
     * A performer who can no longer play the piano (left the game or the world, walked off, lost
     * access, or the piano is gone) lets go of everything listeners were shown as held.
     */
    private static void releaseStopped(MinecraftServer server) {
        for (Iterator<Map.Entry<UUID, HeldNotes>> it = held.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, HeldNotes> e = it.next();
            HeldNotes shown = e.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
            if (player != null && stillPlaying(player, shown)) continue;
            releaseFor(server, shown, shown.releaseAll());
            it.remove();
        }
    }

    private static boolean stillPlaying(ServerPlayer player, HeldNotes shown) {
        ServerLevel level = player.level();
        if (!level.dimension().equals(shown.dimension) || !level.isLoaded(shown.piano)) return false;
        return level.getBlockEntity(shown.piano) instanceof GrandPianoBlockEntity piano && piano.canPlay(player) && piano.isNear(player);
    }

    private static void settings(ServerPlayer player, MusicPayloads.ChangeSettings p) {
        if (!budget(player).spendAction()) return;
        GrandPianoBlockEntity piano = piano(player, p.pos());
        if (piano == null) return;
        if (!piano.canPlay(player)) {
            player.displayClientMessage(Component.translatable("music.message.owner_only", piano.ownerName()), true);
            return;
        }
        PianoSettings now = piano.settings();
        // Only the owner or an operator decides who may play it and change it. Anyone else's change keeps
        // that as it is: their screen sends the whole of the settings as it last had them, which may be
        // from before the owner changed who may.
        PianoSettings asked = (piano.isOwnerOrOperator(player) ? p.settings() : p.settings().withAccess(now.access())).sanitized();
        if (piano.canTune(player)) {
            piano.applySettings(asked, player);
            return;
        }
        // A performer who may not change the piano still opens and closes the lid and the desk, and the
        // rest of what they send stays as the piano has it: sent from a copy a moment old, it can carry
        // a sound the owner has just changed, which is no request to change it back. Only a request that
        // changes nothing of theirs asks for what is the owner's, and is told so.
        PianoSettings kept = now.asPerformerMay(asked);
        if (!kept.equals(now)) {
            piano.applySettings(kept, player);
        } else if (!asked.equals(now)) {
            player.displayClientMessage(Component.translatable("music.message.owner_tunes", piano.ownerName()), true);
        }
    }

    private static void playback(ServerPlayer player, MusicPayloads.PlaybackControl p) {
        if (!budget(player).spendPlayback()) {
            player.displayClientMessage(Component.translatable("music.message.slow_down"), true);
            return;
        }
        GrandPianoBlockEntity piano = piano(player, p.pos());
        if (piano == null) return;
        if (!piano.canPlay(player)) {
            player.displayClientMessage(Component.translatable("music.message.owner_only", piano.ownerName()), true);
            return;
        }
        String refusal = piano.control(p, SongLibrary.get(player.level().getServer()));
        if (refusal != null) player.displayClientMessage(Component.translatable(refusal), true);
    }

    // ------------------------------------------------------------------ library

    private static void library(ServerPlayer player, MusicPayloads.LibraryAction p) {
        MinecraftServer server = player.level().getServer();
        SongLibrary library = SongLibrary.get(server);
        if (p.action() == MusicPayloads.LibraryAction.LIST) {
            if (budget(player).spendAction()) sendLibrary(player, library);
            return;
        }
        if (!budget(player).spendLibraryChange()) {
            player.displayClientMessage(Component.translatable("music.message.slow_down"), true);
            return;
        }
        try {
            switch (p.action()) {
                case MusicPayloads.LibraryAction.RENAME -> {
                    if (mayManage(player, library, p.songId()) && library.rename(p.songId(), p.text())) libraryDirty = true;
                }
                case MusicPayloads.LibraryAction.DELETE -> {
                    if (mayManage(player, library, p.songId()) && library.delete(p.songId())) libraryDirty = true;
                }
                default -> {
                }
            }
        } catch (IOException e) {
            LOG.warn("Music library action {} by {} failed", p.action(), player.getGameProfile().name(), e);
            player.displayClientMessage(Component.translatable("music.message.library_error", limit(e.getMessage())), true);
        }
    }

    private static boolean isTrusted(ServerPlayer player) {
        return player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER) || player.level().getServer().isSingleplayerOwner(player.nameAndId());
    }

    /** The person who added a song, or an operator, may rename or delete it; the starter pieces belong to nobody. */
    private static boolean mayManage(ServerPlayer player, SongLibrary library, String id) {
        SongInfo info = library.info(id).orElse(null);
        if (info == null) return false;
        boolean ok = info.author().equals(player.getGameProfile().name()) || isTrusted(player);
        if (!ok) {
            String refusal = StarterSongs.isComposer(info.author()) ? "music.message.starter_song" : "music.message.not_your_song";
            player.displayClientMessage(Component.translatable(refusal), true);
        }
        return ok;
    }

    /**
     * Every operator online hears once a run that the world's song list had to be rebuilt, and how:
     * as soon as something has opened the library (a piano, the screen, an upload) and they are on. A
     * repair found while no operator was there waits in the index until one is. A library nothing
     * opened is not opened for this, so a world without a piano gets no music folder.
     */
    private static void tellOfRecovery(MinecraftServer server) {
        SongLibrary library = SongLibrary.opened(server);
        if (library == null) return;
        Optional<SongLibrary.Recovery> recovery = library.recovery();
        if (recovery.isEmpty()) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!isTrusted(player) || !toldOfRecovery.add(player.getUUID())) continue;
            player.sendSystemMessage(recovery.get().message());
            library.recoveryTold();
        }
    }

    public static void sendLibrary(ServerPlayer player, SongLibrary library) {
        send(player, new MusicPayloads.Library(library.list(), library.usedBytes(), quota(library)));
    }

    /** How much the world's library may hold: what it holds at most, or less where the server's owner set less. */
    private static long quota(SongLibrary library) {
        return limits.libraryBytes(library.quota());
    }

    /** Songs one player added to the library. */
    private static int songsBy(SongLibrary library, String author) {
        int count = 0;
        for (SongInfo song : library.list()) if (song.author().equals(author)) count++;
        return count;
    }

    // ------------------------------------------------------------------ uploads

    private static void uploadBegin(ServerPlayer player, MusicPayloads.UploadBegin p) {
        SongLibrary library = SongLibrary.get(player.level().getServer());
        String error = null;
        Upload busy = uploads.get(player.getUUID());
        boolean trusted = isTrusted(player);
        String name = player.getGameProfile().name();
        String refused = limits.whoMayAdd(trusted);
        if (refused != null) error = refused;
        else if (busy != null && busy.validating) error = "music.message.slow_down";
        else if (!budget(player).spendUpload()) error = "music.message.slow_down";
        else if (!budget(player).spendUploadThisMinute()) error = "music.message.upload_rate";
        else if (p.size() <= 0 || p.size() > SongCodec.MAX_ENCODED_BYTES) error = "music.message.song_too_large";
        else if (!SongCodec.isId(p.songId())) error = "music.message.upload_failed";
        else if (library.info(p.songId()).isPresent()) {
            send(player, new MusicPayloads.UploadStatus(p.requestId(), MusicPayloads.UploadStatus.DONE, p.size(), p.songId(), ""));
            return;
        } else error = limits.room(trusted, library.usedBytes(), library.quota(), library.bytesBy(name), songsBy(library, name), p.size());
        if (error != null) {
            send(player, new MusicPayloads.UploadStatus(p.requestId(), MusicPayloads.UploadStatus.FAILED, 0, "", error));
            return;
        }
        uploads.put(player.getUUID(), new Upload(p.requestId(), p.name(), p.size(), p.songId(), trusted));
        send(player, new MusicPayloads.UploadStatus(p.requestId(), MusicPayloads.UploadStatus.READY, 0, "", ""));
    }

    private static void uploadChunk(ServerPlayer player, MusicPayloads.UploadChunk p) {
        Upload upload = uploads.get(player.getUUID());
        if (upload == null || upload.validating || upload.requestId != p.requestId()) return;
        if (p.offset() != upload.data.size() || upload.data.size() + p.data().length > upload.size) {
            uploads.remove(player.getUUID());
            send(player, new MusicPayloads.UploadStatus(p.requestId(), MusicPayloads.UploadStatus.FAILED, 0, "", "music.message.upload_failed"));
            return;
        }
        upload.data.writeBytes(p.data());
        upload.touched = MusicClock.millis();
        if (upload.data.size() < upload.size) {
            send(player, new MusicPayloads.UploadStatus(p.requestId(), MusicPayloads.UploadStatus.ACK, upload.data.size(), "", ""));
            return;
        }
        upload.validating = true;
        byte[] bytes = upload.data.toByteArray();
        MinecraftServer server = player.level().getServer();
        UUID playerId = player.getUUID();
        String author = player.getGameProfile().name();
        CompletableFuture.supplyAsync(() -> {
            try {
                if (!SongCodec.id(bytes).equals(upload.songId)) throw new IOException("The song arrived damaged");
                return SongCodec.decode(bytes);
            } catch (IOException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, VALIDATOR).whenComplete((song, failure) -> server.execute(() -> finishUpload(server, playerId, upload, bytes, song, failure, author)));
    }

    private static void finishUpload(MinecraftServer server, UUID playerId, Upload upload, byte[] bytes, @Nullable Song song, @Nullable Throwable failure, String author) {
        if (uploads.get(playerId) == upload) uploads.remove(playerId);
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        try {
            if (failure != null) throw failure.getCause() instanceof IOException io ? io : new IOException(failure.getMessage());
            SongLibrary library = SongLibrary.get(server);
            // The limits again, now that the song is here: uploads that went on beside this one since it
            // began may have filled what the server's owner allows. A song the library has takes no room.
            String full = library.info(upload.songId).isPresent() ? null
                : limits.room(upload.trusted, library.usedBytes(), library.quota(), library.bytesBy(author), songsBy(library, author), bytes.length);
            if (full != null) {
                LOG.info("Song upload from {} had no room left when it finished: {}", author, full);
                if (player != null) send(player, new MusicPayloads.UploadStatus(upload.requestId, MusicPayloads.UploadStatus.FAILED, 0, "", full));
                return;
            }
            SongInfo info = library.add(bytes, song, upload.name, author);
            libraryDirty = true;
            if (player != null) send(player, new MusicPayloads.UploadStatus(upload.requestId, MusicPayloads.UploadStatus.DONE, bytes.length, info.id(), ""));
        } catch (IOException e) {
            LOG.info("Rejected song upload from {}: {}", author, e.getMessage());
            if (player != null) {
                String message = e.getMessage() == null ? "music.message.upload_failed" : limit(e.getMessage());
                send(player, new MusicPayloads.UploadStatus(upload.requestId, MusicPayloads.UploadStatus.FAILED, 0, "", message));
            }
        }
    }

    // ------------------------------------------------------------------ downloads

    private static void songRequest(ServerPlayer player, MusicPayloads.SongRequest p) {
        if (budget(player).spendDownload()) {
            serve(player, p);
            return;
        }
        // Over the per-second budget: keep a few requests and answer them on later ticks, so a
        // download slows down instead of stalling.
        Deque<MusicPayloads.SongRequest> queue = pendingDownloads.computeIfAbsent(player.getUUID(), id -> new ArrayDeque<>());
        if (queue.size() >= MAX_QUEUED_DOWNLOADS) queue.pollFirst();
        queue.addLast(p);
    }

    private static void serve(ServerPlayer player, MusicPayloads.SongRequest p) {
        SongLibrary library = SongLibrary.get(player.level().getServer());
        byte[] data;
        try {
            data = library.read(p.songId());
        } catch (IOException e) {
            send(player, new MusicPayloads.SongChunk(p.songId(), 0, -1, new byte[0]));
            return;
        }
        int offset = Math.max(0, Math.min(p.offset(), data.length));
        int end = Math.min(data.length, offset + MusicPayloads.CHUNK);
        send(player, new MusicPayloads.SongChunk(p.songId(), offset, data.length, Arrays.copyOfRange(data, offset, end)));
    }

    // ------------------------------------------------------------------ housekeeping

    /**
     * The singleplayer game paused (its pause menu opened): the integrated server stops ticking while
     * the songs' wall-clock timelines would keep running. Reported by the client's integrated-server
     * hook from the server's own pause flag, with {@code pausedMillis}, all the time the player's game
     * stood paused before this pause began ({@link MusicClock} milliseconds). Server thread.
     */
    public static void gamePaused(long pausedMillis) {
        pausedMark = pausedMillis;
    }

    /**
     * The pause ended: every playing timeline moves forward by the time the player's game stood paused
     * since this pause began ({@code pausedMillis} is the same count as in {@link #gamePaused}, up to
     * now), so songs go on where every listener heard them stop, however late the server noticed.
     * Only real pauses are taken out; a server that lags keeps the songs on the clock, as every
     * client does. Server thread.
     */
    public static void gameResumed(long pausedMillis) {
        if (pausedMark < 0) return;
        long paused = pausedMillis - pausedMark;
        pausedMark = -1;
        for (GrandPianoBlockEntity piano : List.copyOf(LOADED)) piano.shiftAnchor(paused);
    }

    private static void tick(MinecraftServer server) {
        releaseStopped(server);
        for (Iterator<Map.Entry<UUID, Deque<MusicPayloads.SongRequest>>> it = pendingDownloads.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Deque<MusicPayloads.SongRequest>> e = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
            if (player == null) {
                it.remove();
                continue;
            }
            Deque<MusicPayloads.SongRequest> queue = e.getValue();
            while (!queue.isEmpty() && budget(player).spendDownload()) serve(player, queue.pollFirst());
            if (queue.isEmpty()) it.remove();
        }
        if (server.getTickCount() % 20 != 0) return;
        tellOfRecovery(server);
        budgets.values().removeIf(Budget::isIdle);
        if (libraryDirty) {
            libraryDirty = false;
            SongLibrary library = SongLibrary.get(server);
            MusicPayloads.Library payload = new MusicPayloads.Library(library.list(), library.usedBytes(), quota(library));
            for (ServerPlayer player : server.getPlayerList().getPlayers()) send(player, payload);
        }
        long now = MusicClock.millis();
        uploads.entrySet().removeIf(e -> {
            if (e.getValue().validating || now - e.getValue().touched < UPLOAD_TIMEOUT_MS) return false;
            ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
            if (player != null) send(player, new MusicPayloads.UploadStatus(e.getValue().requestId, MusicPayloads.UploadStatus.FAILED, 0, "", "music.message.upload_timeout"));
            return true;
        });
    }

    private static String limit(String message) {
        if (message == null) return "";
        return message.length() <= MAX_MESSAGE ? message : message.substring(0, MAX_MESSAGE - 1) + "…";
    }

    private static void send(ServerPlayer player, CustomPacketPayload payload) {
        if (ServerPlayNetworking.canSend(player, payload.type())) ServerPlayNetworking.send(player, payload);
    }

    private static Budget budget(ServerPlayer player) {
        return budgets.computeIfAbsent(player.getUUID(), id -> new Budget());
    }

    /** Per-player counters refilled once per second, plus the upload cooldown and the uploads of the last minute. */
    static final class Budget {
        private final LongSupplier clock;
        private long second = Long.MIN_VALUE;
        private int events, actions, playback, libraryChanges, downloads;
        private long lastUpload;
        private final Deque<Long> recentUploads = new ArrayDeque<>();

        Budget() {
            this(MusicClock::millis);
        }

        /** A budget reading the time from {@code clock} (milliseconds, monotonic). */
        Budget(LongSupplier clock) {
            this.clock = clock;
            this.lastUpload = clock.getAsLong() - UPLOAD_COOLDOWN_MS;
        }

        private void roll() {
            long now = Math.floorDiv(clock.getAsLong(), 1000L);
            if (now != second) {
                second = now;
                events = actions = playback = libraryChanges = downloads = 0;
            }
        }

        boolean spendEvents(int n) {
            roll();
            events += n;
            return events <= MAX_EVENTS_PER_SECOND;
        }

        boolean spendAction() {
            roll();
            return ++actions <= MAX_ACTIONS_PER_SECOND;
        }

        boolean spendPlayback() {
            roll();
            return ++playback <= MAX_PLAYBACK_PER_SECOND;
        }

        boolean spendLibraryChange() {
            roll();
            return ++libraryChanges <= MAX_LIBRARY_CHANGES_PER_SECOND;
        }

        boolean spendDownload() {
            roll();
            return ++downloads <= MAX_DOWNLOAD_CHUNKS_PER_SECOND;
        }

        boolean spendUpload() {
            long now = clock.getAsLong();
            if (now - lastUpload < UPLOAD_COOLDOWN_MS) return false;
            lastUpload = now;
            return true;
        }

        /**
         * True when this budget limits nothing a new one would not: nothing was spent this second, the
         * upload cooldown is over and no upload counts in the last minute. Such a budget can be forgotten.
         */
        boolean isIdle() {
            long now = clock.getAsLong();
            if (Math.floorDiv(now, 1000L) == second || now - lastUpload < UPLOAD_COOLDOWN_MS) return false;
            return recentUploads.isEmpty() || now - recentUploads.peekLast() > UPLOAD_WINDOW_MS;
        }

        boolean spendUploadThisMinute() {
            long now = clock.getAsLong();
            while (!recentUploads.isEmpty() && now - recentUploads.peekFirst() > UPLOAD_WINDOW_MS) recentUploads.pollFirst();
            if (recentUploads.size() >= MAX_UPLOADS_PER_MINUTE) return false;
            recentUploads.addLast(now);
            return true;
        }
    }

    private static final class Upload {
        final int requestId;
        final String name;
        final int size;
        final String songId;
        /** Whether the player adding it counts against no player limit (an operator or the host), as they did when it began. */
        final boolean trusted;
        final ByteArrayOutputStream data = new ByteArrayOutputStream();
        long touched = MusicClock.millis();
        boolean validating;

        Upload(int requestId, String name, int size, String songId, boolean trusted) {
            this.requestId = requestId;
            this.name = name;
            this.size = size;
            this.songId = songId;
            this.trusted = trusted;
        }
    }
}
