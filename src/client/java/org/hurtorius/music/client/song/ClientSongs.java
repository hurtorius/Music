package org.hurtorius.music.client.song;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import org.hurtorius.music.net.MusicPayloads;
import org.hurtorius.music.song.Song;
import org.hurtorius.music.song.SongCodec;
import org.hurtorius.music.song.SongInfo;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Songs on this client: a memory cache, a disk cache shared by every world and server
 * ({@code .minecraft/music/cache/<id>.song}, verified by hash), on-demand downloads from the server,
 * and the latest copy of the world's library listing. Client thread only.
 */
public final class ClientSongs {
    private static final Logger LOG = LoggerFactory.getLogger("Music");
    private static final int MEMORY_SONGS = 64;
    private static final long RETRY_NANOS = 10_000_000_000L;
    /** A download with no new piece for this long asks again for the same piece. */
    private static final long STALL_NANOS = 5_000_000_000L;
    private static final int MAX_STALLS = 3;
    /** The shared disk cache is trimmed (oldest first) to stay under this size. */
    static final long DISK_CACHE_BYTES = 256L * 1024 * 1024;

    private static final Map<String, Song> MEMORY = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Song> eldest) {
            return size() > MEMORY_SONGS;
        }
    };
    private static final Map<String, Download> DOWNLOADS = new HashMap<>();
    private static final Map<String, Long> FAILED = new HashMap<>();
    private static final List<Consumer<String>> ARRIVAL_LISTENERS = new ArrayList<>();
    private static List<SongInfo> library = List.of();
    private static long libraryUsed, libraryQuota;
    private static int libraryRevision;

    private ClientSongs() {}

    private static final class Download {
        final ByteArrayOutputStream data = new ByteArrayOutputStream();
        final List<CompletableFuture<Song>> waiters = new ArrayList<>();
        int total = -1;
        long lastProgress = System.nanoTime();
        int stalls;
    }

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(MusicPayloads.SongChunk.TYPE, (p, c) -> chunk(p));
        ClientPlayNetworking.registerGlobalReceiver(MusicPayloads.Library.TYPE, (p, c) -> {
            library = List.copyOf(p.songs());
            libraryUsed = p.usedBytes();
            libraryQuota = p.quotaBytes();
            libraryRevision++;
        });
    }

    /** Cached song, or null while it is being fetched (a download starts automatically). */
    public static @Nullable Song get(String id) {
        if (!SongCodec.isId(id)) return null;
        Song song = MEMORY.get(id);
        if (song != null) return song;
        song = fromDisk(id);
        if (song != null) {
            MEMORY.put(id, song);
            return song;
        }
        request(id);
        return null;
    }

    /** Completes when the song is available (or fails if the server does not have it). */
    public static CompletableFuture<Song> fetch(String id) {
        Song song = get(id);
        if (song != null) return CompletableFuture.completedFuture(song);
        Download d = DOWNLOADS.get(id);
        // A download that failed a moment ago is not asked for again yet (players see this text).
        if (d == null) return CompletableFuture.failedFuture(new IOException("The server has not sent this song yet. Try again in a moment"));
        CompletableFuture<Song> f = new CompletableFuture<>();
        d.waiters.add(f);
        return f;
    }

    /** Adds a song this client produced (recording, import) so it is available without a download. */
    public static void put(String id, Song song, byte[] encoded) {
        MEMORY.put(id, song);
        writeDisk(id, encoded);
        FAILED.remove(id);
    }

    /** Called with a song id whenever a download finishes. */
    public static void onArrival(Consumer<String> listener) {
        ARRIVAL_LISTENERS.add(listener);
    }

    public static List<SongInfo> library() {
        return library;
    }

    public static @Nullable SongInfo info(String id) {
        for (SongInfo s : library) if (s.id().equals(id)) return s;
        return null;
    }

    public static long libraryUsedBytes() {
        return libraryUsed;
    }

    public static long libraryQuotaBytes() {
        return libraryQuota;
    }

    /** Changes every time a new listing arrives, so screens can refresh lazily. */
    public static int libraryRevision() {
        return libraryRevision;
    }

    public static void requestLibrary() {
        if (ClientPlayNetworking.canSend(MusicPayloads.LibraryAction.TYPE)) {
            ClientPlayNetworking.send(new MusicPayloads.LibraryAction(MusicPayloads.LibraryAction.LIST, "", ""));
        }
    }

    /** Re-asks for stalled downloads (the server may have been busy) and gives up after a few tries. Client thread, every tick. */
    public static void tick() {
        long now = System.nanoTime();
        for (var it = DOWNLOADS.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            Download d = e.getValue();
            if (now - d.lastProgress < STALL_NANOS) continue;
            if (++d.stalls > MAX_STALLS || !ClientPlayNetworking.canSend(MusicPayloads.SongRequest.TYPE)) {
                it.remove();
                FAILED.put(e.getKey(), now);
                LOG.warn("Gave up downloading song {} after {} stalls", e.getKey(), MAX_STALLS);
                d.waiters.forEach(w -> w.completeExceptionally(new IOException("The server did not send the song")));
                continue;
            }
            d.lastProgress = now;
            ClientPlayNetworking.send(new MusicPayloads.SongRequest(e.getKey(), d.data.size()));
        }
    }

    /** Forget per-server state when leaving a world. Disk cache stays. */
    public static void reset() {
        DOWNLOADS.values().forEach(d -> d.waiters.forEach(w -> w.completeExceptionally(new IOException("Disconnected"))));
        DOWNLOADS.clear();
        FAILED.clear();
        library = List.of();
        libraryRevision++;
    }

    private static void request(String id) {
        if (DOWNLOADS.containsKey(id)) return;
        Long failedAt = FAILED.get(id);
        if (failedAt != null && System.nanoTime() - failedAt < RETRY_NANOS) return;
        if (!ClientPlayNetworking.canSend(MusicPayloads.SongRequest.TYPE)) return;
        DOWNLOADS.put(id, new Download());
        ClientPlayNetworking.send(new MusicPayloads.SongRequest(id, 0));
    }

    private static void chunk(MusicPayloads.SongChunk p) {
        Download d = DOWNLOADS.get(p.songId());
        if (d == null) return;
        Piece piece = piece(d.data.size(), p.offset(), p.total());
        if (piece == Piece.REFUSED) {
            fail(p.songId(), d, "The server could not send the song");
            return;
        }
        if (piece == Piece.STALE) return;
        d.total = p.total();
        d.data.writeBytes(p.data());
        d.lastProgress = System.nanoTime();
        d.stalls = 0;
        if (d.data.size() < d.total) {
            if (p.data().length == 0) {
                fail(p.songId(), d, "The song stopped arriving from the server");
                return;
            }
            ClientPlayNetworking.send(new MusicPayloads.SongRequest(p.songId(), d.data.size()));
            return;
        }
        DOWNLOADS.remove(p.songId());
        byte[] bytes = d.data.toByteArray();
        try {
            if (!SongCodec.id(bytes).equals(p.songId())) throw new IOException("checksum mismatch");
            Song song = SongCodec.decode(bytes);
            MEMORY.put(p.songId(), song);
            writeDisk(p.songId(), bytes);
            d.waiters.forEach(w -> w.complete(song));
            for (Consumer<String> l : List.copyOf(ARRIVAL_LISTENERS)) l.accept(p.songId());
        } catch (IOException e) {
            fail(p.songId(), d, "The song arrived damaged (" + e.getMessage() + ")");
        }
    }

    /** What a piece of a download is. */
    enum Piece {
        /** The bytes that come next. */
        NEXT,
        /**
         * Bytes from anywhere else: a late answer to an earlier request, such as the first answer to a
         * piece asked for again after a stall. Pieces are only asked for where a download ends, so
         * this is never lost data; it is ignored and a real loss is still caught by the stall timer.
         */
        STALE,
        /** The server does not have the song, or announced an impossible size. */
        REFUSED
    }

    /** Classifies a piece at {@code offset} of a {@code total}-byte song for a download holding {@code received} bytes. */
    static Piece piece(int received, int offset, int total) {
        if (total < 0 || total > SongCodec.MAX_ENCODED_BYTES) return Piece.REFUSED;
        return offset == received ? Piece.NEXT : Piece.STALE;
    }

    /** Gives up a download; {@code message} is for players (it can reach the screen), the log adds the song's id. */
    private static void fail(String id, Download d, String message) {
        LOG.warn("Song {}: {}", id, message);
        DOWNLOADS.remove(id);
        FAILED.put(id, System.nanoTime());
        d.waiters.forEach(w -> w.completeExceptionally(new IOException(message)));
    }

    public static Path cacheDir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("music").resolve("cache");
    }

    private static @Nullable Song fromDisk(String id) {
        Path file = cacheDir().resolve(id + ".song");
        if (!Files.isRegularFile(file)) return null;
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (!SongCodec.id(bytes).equals(id)) {
                Files.deleteIfExists(file);
                return null;
            }
            Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
            return SongCodec.decode(bytes);
        } catch (IOException e) {
            LOG.warn("Ignoring damaged cached song {}", file, e);
            return null;
        }
    }

    private static void writeDisk(String id, byte[] bytes) {
        try {
            Path dir = cacheDir();
            Files.createDirectories(dir);
            Path file = dir.resolve(id + ".song");
            if (Files.isRegularFile(file)) return;
            Path temp = dir.resolve(id + ".part");
            Files.write(temp, bytes);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            trimDisk(dir, file);
        } catch (IOException e) {
            LOG.warn("Could not cache song {}", id, e);
        }
    }

    /** Deletes the least recently used cached songs until the cache fits {@link #DISK_CACHE_BYTES}. */
    private static void trimDisk(Path dir, Path keep) throws IOException {
        List<Path> files = new ArrayList<>();
        long total = 0;
        try (var stream = Files.list(dir)) {
            for (Path f : (Iterable<Path>) stream::iterator) {
                if (!f.getFileName().toString().endsWith(".song") || !Files.isRegularFile(f)) continue;
                files.add(f);
                total += Files.size(f);
            }
        }
        if (total <= DISK_CACHE_BYTES) return;
        files.sort(java.util.Comparator.comparing(f -> {
            try {
                return Files.getLastModifiedTime(f).toMillis();
            } catch (IOException e) {
                return 0L;
            }
        }));
        for (Path f : files) {
            if (total <= DISK_CACHE_BYTES) break;
            if (f.equals(keep)) continue;
            long size = Files.size(f);
            Files.deleteIfExists(f);
            total -= size;
        }
    }
}
