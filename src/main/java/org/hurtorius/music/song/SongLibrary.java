package org.hurtorius.music.song;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.stream.Stream;

/**
 * The songs that belong to a world. Each song is stored once, named by its id, under
 * {@code <world>/music/songs/<id>.song}; {@code <world>/music/library.json} holds names and
 * metadata, and which {@link StarterSongs starter pieces} it has had. Songs travel with the
 * world folder, so maps can ship with music.
 *
 * <p>A new library is given the starter pieces: one that holds no song and never had them (no index
 * yet, or an empty one from before Music had them). A library that holds songs is never added to, so a
 * map keeps the list its maker left, even once its players have emptied it, and in one that was given
 * them a piece its players deleted stays deleted. Before the first piece's file is written, an index
 * listing the pieces as still being given ({@code giving}) is on disk, so a first open cut short
 * finishes giving them the next time (and does not take them for songs its index lost).</p>
 *
 * <p>The song files are what the library holds; the index only names them. Every write of the index
 * goes to disk before it replaces the old one, which stays as {@code library.json.bak} (unless it
 * was one that could not be read, or the backup cannot be replaced: it is a second copy, so saving
 * goes on without it). An index that cannot be read is kept as {@code library.json.broken-<time>}
 * and the library is rebuilt from the backup and the song files, so no song whose file is still
 * there drops out of the library (pianos forget every playlist entry the library no longer lists).
 * An index with entries that cannot be read is kept as such a copy too, and written again at once
 * without them. What was repaired waits in the index ({@code recovery}) until an operator has been
 * told. A change whose index cannot be written is undone, except a delete: the song went with its
 * file.</p>
 *
 * <p>When neither the index nor its backup can be read, a starter piece found from its file is still
 * its composer's (it is known by its notes, whatever title the version of Music that gave it used),
 * and the world is taken to have had them all, so one that is not there was deleted and stays
 * deleted.</p>
 */
public final class SongLibrary {
    private static final Logger LOG = LoggerFactory.getLogger("Music");
    public static final long DEFAULT_QUOTA = 256L * 1024 * 1024;
    public static final int MAX_SONGS = 2048;
    private static final String INDEX = "library.json", BACKUP = "library.json.bak", SONGS = "songs", SUFFIX = ".song";
    private static final long MAX_INDEX_BYTES = 8L * 1024 * 1024;
    /** The name songs are compared under when only their notes matter. */
    private static final String ANY_NAME = "Untitled";
    private static SongLibrary current;

    /** Verified song bytes kept in memory for downloads, bounded by {@link #CACHE_BYTES}. */
    static final long CACHE_BYTES = 32L * 1024 * 1024;

    private final Path root;
    /** Milliseconds since the epoch, for when a song was added and for the names of kept copies. */
    private final LongSupplier clock;
    private final Map<String, SongInfo> songs = new LinkedHashMap<>();
    /**
     * The starter pieces the library has had ({@code starters} in the index): given to it, or passed
     * over because it already held songs. A library that has had them is never given them again.
     */
    private final Set<String> offered = new LinkedHashSet<>();
    /** The starter pieces a new library is still being given ({@code giving} in the index). */
    private final Set<String> giving = new LinkedHashSet<>();
    private final long quota;
    private final LinkedHashMap<String, byte[]> cache = new LinkedHashMap<>(16, 0.75f, true);
    private long cacheBytes;
    private @Nullable Recovery recovery;
    /** Whether an operator has been told of {@link #recovery}; until then the index keeps it. */
    private boolean recoveryTold;
    /** False while {@code library.json} on disk is one that could not be read: it never becomes the backup. */
    private boolean indexReadable = true;

    /**
     * What opening a library had to repair, in this run or in an earlier one no operator was told of.
     *
     * @param songs   songs the library holds that its index did not list (all of them when the index
     *                was missing or unreadable)
     * @param damaged whether the index was there but could not be read
     * @param keptAs  the name the unreadable index was kept under in the music folder, or null when
     *                there was none (or it could not be moved)
     */
    public record Recovery(int songs, boolean damaged, @Nullable String keptAs) {
        /** What operators are told when they join. The game's translations have no plurals, so one song has its own text. */
        public Component message() {
            boolean kept = damaged && keptAs != null;
            String key = !damaged ? "music.message.library_found" : kept ? "music.message.library_rebuilt_kept" : "music.message.library_rebuilt";
            List<Object> args = new ArrayList<>();
            if (songs != 1) args.add(songs);
            if (kept) args.add(keptAs);
            return Component.translatable(songs == 1 ? key + ".one" : key, args.toArray());
        }
    }

    private SongLibrary(Path root, long quota, LongSupplier clock) {
        this.root = root;
        this.quota = quota;
        this.clock = clock;
    }

    /**
     * The library of the world the server is running, opened on first use (by a piano, the screen or
     * an upload), which writes the music folder of a world that had none.
     */
    public static synchronized SongLibrary get(MinecraftServer server) {
        Path root = rootOf(server);
        if (current == null || !current.root.equals(root)) current = open(root, StarterSongs.all());
        return current;
    }

    /**
     * The library of the world the server is running if something has opened it, else null: nothing
     * is read or written, so asking writes no music folder into a world without a piano.
     */
    public static synchronized @Nullable SongLibrary opened(MinecraftServer server) {
        return current != null && current.root.equals(rootOf(server)) ? current : null;
    }

    private static Path rootOf(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("music").toAbsolutePath().normalize();
    }

    /**
     * Opens the library in {@code root}: reads its index, finds the songs whose files the index lost,
     * and gives a new library the starter pieces.
     */
    static SongLibrary open(Path root, List<StarterSongs.Starter> starters) {
        return open(root, starters, System::currentTimeMillis);
    }

    /** As {@link #open(Path, List)}, telling the time by {@code clock}. */
    static SongLibrary open(Path root, List<StarterSongs.Starter> starters, LongSupplier clock) {
        SongLibrary library = new SongLibrary(root, DEFAULT_QUOTA, clock);
        boolean changed = library.load(starters);
        changed |= library.give(starters);
        if (changed) {
            try {
                library.saveIndex();
            } catch (IOException e) {
                LOG.error("Could not write the Music song library index in {}", root, e);
            }
        }
        return library;
    }

    /** Forget the cached library when a server stops. */
    public static synchronized void unload() {
        current = null;
    }

    /** What opening this library, or an earlier opening no operator was told of, had to repair, if anything. */
    public synchronized Optional<Recovery> recovery() {
        return Optional.ofNullable(recovery);
    }

    /**
     * Notes that an operator has been told of {@link #recovery()}: the index stops keeping it for the
     * next run. Operators joining later in this run are still told.
     */
    public synchronized void recoveryTold() {
        if (recovery == null || recoveryTold) return;
        recoveryTold = true;
        try {
            saveIndex();
        } catch (IOException e) {
            recoveryTold = false;
            LOG.warn("Could not write the Music song library index in {}; operators will be told of its repair again next time", root, e);
        }
    }

    /** Every song, in the order the Songs tab lists them ({@link SongOrder#LIBRARY}). */
    public synchronized List<SongInfo> list() {
        List<SongInfo> list = new ArrayList<>(songs.values());
        list.sort(SongOrder.LIBRARY);
        return list;
    }

    public synchronized Optional<SongInfo> info(String id) {
        return Optional.ofNullable(id == null ? null : songs.get(id));
    }

    public synchronized long usedBytes() {
        long used = 0;
        for (SongInfo s : songs.values()) used += s.bytes();
        return used;
    }

    public long quota() {
        return quota;
    }

    /** Encoded bytes of a stored song, verified against its id the first time they are read. */
    public synchronized byte[] read(String id) throws IOException {
        if (!SongCodec.isId(id) || info(id).isEmpty()) throw new IOException("No such song");
        byte[] cached = cache.get(id);
        if (cached != null) return cached;
        byte[] data = Files.readAllBytes(songFile(id));
        if (!SongCodec.id(data).equals(id)) throw new IOException("Stored song " + id + " is damaged");
        cache.put(id, data);
        cacheBytes += data.length;
        for (var it = cache.entrySet().iterator(); cacheBytes > CACHE_BYTES && it.hasNext(); ) {
            var eldest = it.next();
            if (eldest.getKey().equals(id)) continue;
            cacheBytes -= eldest.getValue().length;
            it.remove();
        }
        return data;
    }

    /** Space used by songs one player added. */
    public synchronized long bytesBy(String author) {
        long used = 0;
        for (SongInfo s : songs.values()) if (s.author().equals(author)) used += s.bytes();
        return used;
    }

    /**
     * Validates and stores an encoded song. If an identical song is already stored, its entry is
     * returned unchanged.
     */
    public synchronized SongInfo add(byte[] encoded, String name, String author) throws IOException {
        return add(encoded, SongCodec.decode(encoded), name, author);
    }

    /**
     * As {@link #add(byte[], String, String)}, for a song the caller already decoded from {@code encoded}.
     * When the index cannot be written the song is not kept: its file would bring it back after a
     * restart, owned by nobody, although its uploader was told it failed.
     */
    public synchronized SongInfo add(byte[] encoded, Song song, String name, String author) throws IOException {
        SongInfo existing = songs.get(SongCodec.id(encoded));
        if (existing != null) return existing;
        SongInfo info = store(encoded, song, name, author);
        try {
            saveIndex();
        } catch (IOException e) {
            songs.remove(info.id());
            Path file = songFile(info.id());
            try {
                Files.deleteIfExists(file);
            } catch (IOException left) {
                LOG.error("Could not take back the Music song file {} after its index entry failed; it will be listed, owned by nobody, when the library opens again",
                    file, left);
            }
            throw e;
        }
        return info;
    }

    /** Renames a song; when the index cannot be written the old name stays, as the player is told. */
    public synchronized boolean rename(String id, String name) throws IOException {
        SongInfo info = songs.get(id);
        if (info == null) return false;
        String clean = new Song.Builder(name).build().name();
        if (clean.equals(info.name())) return false;
        songs.put(id, info.renamed(clean));
        try {
            saveIndex();
        } catch (IOException e) {
            songs.put(id, info);
            throw e;
        }
        return true;
    }

    /**
     * Deletes a song with its file. Once the file is gone the song is: an index that cannot be written
     * then still lists it, but a listed song without its file is skipped when the library opens.
     */
    public synchronized boolean delete(String id) throws IOException {
        if (!songs.containsKey(id)) return false;
        // The file goes first: a song file still on disk would come back the next time the library opens.
        Files.deleteIfExists(songFile(id));
        songs.remove(id);
        byte[] cached = cache.remove(id);
        if (cached != null) cacheBytes -= cached.length;
        try {
            saveIndex();
        } catch (IOException e) {
            LOG.error("Deleted the Music song {}, but could not write the song library index in {}", id, root, e);
        }
        return true;
    }

    /** Writes the song's file and lists it, without saving the index. */
    private SongInfo store(byte[] encoded, Song song, String name, String author) throws IOException {
        String id = SongCodec.id(encoded);
        if (songs.size() >= MAX_SONGS) throw new IOException("The library is full (" + MAX_SONGS + " songs)");
        if (usedBytes() + encoded.length > quota) throw new IOException("The library has no room left");
        String cleanName = new Song.Builder(name == null || name.isBlank() ? song.name() : name).build().name();
        SongInfo info = new SongInfo(id, cleanName, cleanAuthor(author), song.durationMs(), song.noteCount(), encoded.length, clock.getAsLong());
        writeAtomic(songFile(id), encoded, null);
        songs.put(id, info);
        return info;
    }

    /**
     * Gives a new library the starter pieces, and finishes giving them where a first open was cut
     * short. Returns whether the index needs writing again.
     *
     * <p>A library that holds songs but has no record of the pieces is no new one: from before Music
     * had them, it keeps the list its players or maker left; with its index and backup lost, it had
     * them, and those not there were deleted. Either way it is recorded as having had them, so it is
     * not taken for a new library once its players have deleted every song.</p>
     *
     * <p>Before the first piece's file is written, an index that lists the pieces as still being given
     * goes to disk: otherwise a first open cut short (the game killed, a full disk) would leave a few of
     * them looking like a library whose index was lost, which is no new library and is not given the
     * rest.</p>
     */
    private boolean give(List<StarterSongs.Starter> starters) {
        if (giving.isEmpty()) {
            if (!offered.isEmpty()) return false;
            if (!songs.isEmpty()) {
                starters.forEach(piece -> offered.add(piece.id()));
                return !offered.isEmpty();
            }
            starters.forEach(piece -> giving.add(piece.id()));
            if (giving.isEmpty()) return false;
            try {
                saveIndex();
            } catch (IOException e) {
                // Still new: they are given when it opens again, or after the next save that works.
                LOG.error("Could not write the Music song library index in {}, so the starter songs were not added; they will be when the world opens again", root, e);
                return false;
            }
        }
        boolean changed = false;
        for (StarterSongs.Starter piece : starters) {
            if (!giving.contains(piece.id())) continue;
            try {
                if (!holds(piece)) store(piece.encoded(), piece.song(), piece.name(), piece.composer());
                giving.remove(piece.id());
                offered.add(piece.id());
                changed = true;
            } catch (IOException e) {
                LOG.warn("Could not add the starter song {} to the library in {}: {}", piece.name(), root, e.getMessage());
            }
        }
        return changed;
    }

    /** Whether the library lists this piece's song (given, or added by a player who imported the same file). */
    private boolean holds(StarterSongs.Starter piece) {
        return songs.containsKey(SongCodec.id(piece.encoded()));
    }

    private static String cleanAuthor(String author) {
        if (author == null) return "";
        String a = author.replaceAll("[\\p{Cntrl}]", "").strip();
        return a.length() > 32 ? a.substring(0, 32) : a;
    }

    private Path songFile(String id) throws IOException {
        if (!SongCodec.isId(id)) throw new IOException("Bad song id");
        return root.resolve(SONGS).resolve(id + SUFFIX);
    }

    /**
     * Reads the index, or its backup when it is missing or unreadable, then lists every song file the
     * index did not. Returns whether the index needs writing again.
     */
    private boolean load(List<StarterSongs.Starter> starters) {
        Path index = root.resolve(INDEX);
        boolean read = false;
        String keptAs = null;
        boolean damaged = false;
        IndexRead fromIndex = null;
        if (Files.exists(index, LinkOption.NOFOLLOW_LINKS)) {
            try {
                fromIndex = readIndex(index);
                read = true;
            } catch (Exception e) {
                damaged = true;
                indexReadable = false;
                forget();
                // Keep the unreadable index aside so nothing overwrites it; the songs stay on disk.
                Path aside = asideName(index);
                LOG.error("Could not read the Music song library at {}; keeping it as {} and rebuilding the library from its backup and song files",
                    index, aside.getFileName(), e);
                try {
                    Files.move(index, aside);
                    keptAs = aside.getFileName().toString();
                } catch (IOException moveFailure) {
                    // It stays in its place until a save replaces it, and never becomes the backup.
                    LOG.error("Could not move {} aside", index, moveFailure);
                }
            }
        }
        int damagedEntries = fromIndex == null ? 0 : fromIndex.damagedEntries();
        Path backup = root.resolve(BACKUP);
        if (!read && Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
            try {
                // A repair the backup records is not told on its own: this rebuild counts every song.
                damagedEntries = readIndex(backup).damagedEntries();
                LOG.warn("Read the Music song library from its backup {}", backup);
            } catch (Exception e) {
                forget();
                LOG.error("Could not read the backup of the Music song library at {} either", backup, e);
            }
        }
        Recovery untold = fromIndex == null ? null : fromIndex.untold();
        int found = findSongFiles(starters);
        int recovered = read ? found : songs.size();
        if (recovered > 0 || damaged) {
            recovery = untold == null ? new Recovery(recovered, damaged, keptAs)
                : new Recovery(untold.songs() + recovered, untold.damaged(), untold.keptAs());
            LOG.warn("The Music song library in {} was rebuilt: {} songs recovered from their files", root, recovered);
        } else {
            recovery = untold;
        }
        // Entries that could not be read leave the index now, once (readIndex kept its original):
        // left in, they would have it copied aside again at every start.
        return recovered > 0 || damaged || damagedEntries > 0;
    }

    /** Forgets what a list that could not be read in full put into the library. */
    private void forget() {
        songs.clear();
        offered.clear();
        giving.clear();
    }

    /**
     * What an index held besides its songs.
     *
     * @param untold         the repair it says no operator was told of yet, or null
     * @param damagedEntries how many of its entries could not be read
     */
    private record IndexRead(@Nullable Recovery untold, int damagedEntries) {}

    /**
     * Reads an index file into the library (songs whose files are missing are left out). An index with
     * entries that cannot be read is first copied aside, as the next save writes it without them.
     */
    private IndexRead readIndex(Path file) throws IOException {
        if (Files.isSymbolicLink(file) || Files.size(file) > MAX_INDEX_BYTES) throw new IOException(file.getFileName() + " is not a regular file of acceptable size");
        JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        List<SongInfo> list = new ArrayList<>();
        int damaged = 0;
        for (JsonElement entry : json.getAsJsonArray("songs")) {
            var parsed = SongInfo.CODEC.parse(JsonOps.INSTANCE, entry).resultOrPartial(message -> LOG.warn("Damaged Music library entry: {}", message));
            if (parsed.isPresent()) list.add(parsed.get());
            else damaged++;
        }
        List<String> starters = ids(json, "starters"), pending = ids(json, "giving");
        Recovery untold = untold(file, json.get("recovery"));
        if (damaged > 0) {
            // Keep a copy of the original before the next save rewrites it without the damaged entries.
            Path copy = asideName(file);
            try {
                Files.copy(file, copy);
                LOG.warn("{} has {} damaged entries; its original is kept as {}", file, damaged, copy.getFileName());
            } catch (IOException e) {
                LOG.error("Could not keep a copy of {}, which has {} damaged entries, as {}", file, damaged, copy.getFileName(), e);
            }
        }
        for (SongInfo info : list) {
            if (songs.size() >= MAX_SONGS) break;
            if (SongCodec.isId(info.id()) && Files.isRegularFile(songFile(info.id()), LinkOption.NOFOLLOW_LINKS)) {
                // Names come from disk; keep them within what the network codec accepts.
                songs.put(info.id(), new SongInfo(info.id(), new Song.Builder(info.name()).build().name(), cleanAuthor(info.author()),
                    Math.max(0, info.durationMs()), Math.max(0, info.notes()), Math.max(0, info.bytes()), info.addedAt()));
            } else {
                LOG.warn("Music library entry {} has no song file; skipping it", info.id());
            }
        }
        offered.addAll(starters);
        giving.addAll(pending);
        giving.removeAll(offered);
        return new IndexRead(untold, damaged);
    }

    /** The ids in a list of the index, or none when it has no such list (one written before Music kept it). */
    private static List<String> ids(JsonObject json, String list) {
        List<String> ids = new ArrayList<>();
        JsonElement element = json.get(list);
        if (element != null) for (JsonElement id : element.getAsJsonArray()) ids.add(id.getAsString());
        return ids;
    }

    /** The repair an index says no operator was told of, or null; one it cannot make out is dropped (it is only a notice). */
    private static @Nullable Recovery untold(Path file, @Nullable JsonElement element) {
        if (element == null) return null;
        try {
            JsonObject json = element.getAsJsonObject();
            JsonElement keptAs = json.get("keptAs");
            return new Recovery(Math.max(0, json.get("songs").getAsInt()), json.get("damaged").getAsBoolean(),
                keptAs == null || keptAs.isJsonNull() ? null : keptAs.getAsString());
        } catch (RuntimeException e) {
            LOG.warn("{} holds a notice of an earlier repair that cannot be read; operators are not told of it", file.getFileName(), e);
            return null;
        }
    }

    private static JsonObject toJson(Recovery recovery) {
        JsonObject json = new JsonObject();
        json.addProperty("songs", recovery.songs());
        json.addProperty("damaged", recovery.damaged());
        if (recovery.keptAs() != null) json.addProperty("keptAs", recovery.keptAs());
        return json;
    }

    /**
     * Lists every song file the index did not, named and timed from the song itself; nobody owns
     * it (a starter piece is its composer's), so only operators can rename or delete it. A starter
     * piece is known by its notes, so one a version of Music gave under another title is its
     * composer's too. Files that are not the song their name says are left alone. Returns how many
     * were found, leaving out the starter pieces the list says were still being given: the library
     * was adding those when it last stopped, so they are no song its index lost.
     */
    private int findSongFiles(List<StarterSongs.Starter> starters) {
        Path folder = root.resolve(SONGS);
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) return 0;
        List<Path> files;
        // A listing reports a failure part way through as an unchecked exception.
        try (Stream<Path> listing = Files.list(folder)) {
            files = listing.filter(p -> p.getFileName().toString().endsWith(SUFFIX)).sorted().toList();
        } catch (IOException | UncheckedIOException e) {
            LOG.error("Could not list the Music song files in {}", folder, e);
            return 0;
        }
        Map<String, StarterSongs.Starter> pieces = new HashMap<>(), piecesByNotes = new HashMap<>();
        for (StarterSongs.Starter piece : starters) pieces.put(SongCodec.id(piece.encoded()), piece);
        int found = 0;
        for (Path file : files) {
            String name = file.getFileName().toString();
            String id = name.substring(0, name.length() - SUFFIX.length());
            if (!SongCodec.isId(id) || songs.containsKey(id)) continue;
            if (songs.size() >= MAX_SONGS) {
                LOG.warn("The Music song library in {} is full; {} and the song files after it were left out", root, name);
                break;
            }
            try {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > SongCodec.MAX_ENCODED_BYTES) {
                    throw new IOException("not a regular file of acceptable size");
                }
                byte[] data = Files.readAllBytes(file);
                if (!SongCodec.id(data).equals(id)) throw new IOException("its content is not the song its name says");
                Song song = SongCodec.decode(data);
                StarterSongs.Starter piece = pieces.get(id);
                if (piece == null && !starters.isEmpty()) {
                    if (piecesByNotes.isEmpty()) for (StarterSongs.Starter p : starters) piecesByNotes.put(notesId(p.song()), p);
                    piece = piecesByNotes.get(notesId(song));
                }
                String author = piece == null ? "" : cleanAuthor(piece.composer());
                songs.put(id, new SongInfo(id, song.name(), author, song.durationMs(), song.noteCount(), data.length, Files.getLastModifiedTime(file).toMillis()));
                if (piece == null || !giving.contains(piece.id())) found++;
            } catch (IOException e) {
                LOG.warn("Music song file {} cannot be listed ({}); leaving it as it is", file, e.getMessage());
            }
        }
        return found;
    }

    /** The id the song would have under {@link #ANY_NAME}: songs with the same notes share it, whatever they are called. */
    private static String notesId(Song song) {
        return SongCodec.id(SongCodec.encode(song.toBuilder().name(ANY_NAME).build()));
    }

    private void saveIndex() throws IOException {
        List<SongInfo> list = new ArrayList<>(songs.values());
        JsonElement songsJson = SongInfo.CODEC.listOf().encodeStart(JsonOps.INSTANCE, list).getOrThrow(IOException::new);
        JsonObject json = new JsonObject();
        json.addProperty("format", 1);
        json.add("songs", songsJson);
        json.add("starters", array(offered));
        if (!giving.isEmpty()) json.add("giving", array(giving));
        if (recovery != null && !recoveryTold) json.add("recovery", toJson(recovery));
        byte[] data = new GsonBuilder().setPrettyPrinting().create().toJson(json).getBytes(StandardCharsets.UTF_8);
        writeAtomic(root.resolve(INDEX), data, indexReadable ? root.resolve(BACKUP) : null);
        indexReadable = true;
    }

    private static JsonArray array(Set<String> ids) {
        JsonArray array = new JsonArray();
        for (String id : ids) array.add(id);
        return array;
    }

    /** Where to keep a copy of {@code file} aside: {@code <name>.broken-<time>}, numbered on when that is taken. */
    private Path asideName(Path file) {
        String name = file.getFileName() + ".broken-" + clock.getAsLong();
        Path aside = file.resolveSibling(name);
        for (int n = 2; Files.exists(aside, LinkOption.NOFOLLOW_LINKS); n++) aside = file.resolveSibling(name + "-" + n);
        return aside;
    }

    /**
     * Writes a file through a temporary one that reaches the disk before it takes the file's place,
     * so a crash leaves the old file or the new one, never half of either. The file it replaces is
     * copied to {@code previous} first, when that is given; that copy is a second one, so when it
     * cannot be made (a read-only or held backup, a folder in its place) the file is written anyway.
     */
    private static void writeAtomic(Path file, byte[] data, @Nullable Path previous) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(data);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        if (previous != null && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.copy(file, previous, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                LOG.warn("Could not keep the previous {} as {}; writing the new one without that backup", file.getFileName(), previous.getFileName(), e);
            }
        }
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
