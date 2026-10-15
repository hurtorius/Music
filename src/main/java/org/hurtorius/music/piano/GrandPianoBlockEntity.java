package org.hurtorius.music.piano;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.hurtorius.music.MusicClock;
import org.hurtorius.music.net.MusicPayloads;
import org.hurtorius.music.registry.MusicContent;
import org.hurtorius.music.seat.PianoSeatEntity;
import org.hurtorius.music.song.SongInfo;
import org.hurtorius.music.song.SongLibrary;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * State of one piano, held by its controller block. The server owns settings and the self-playing
 * timeline; clients receive them through the block entity update packet. A piano broken to be moved
 * gives its settings and its playlist to the item it drops ({@link PianoItemData}), and a piano placed
 * from such an item takes them back.
 */
public class GrandPianoBlockEntity extends BlockEntity {
    private static final Logger LOG = LoggerFactory.getLogger("Music");
    /** A song counts as finished this long after its last note, so tails ring out. */
    public static final long END_TAIL_MS = 1500;
    /**
     * How high a player is drawn over their feet, the hat layer included, in blocks at scale 1 (the
     * player model's 32.5 pixels): seated on the bench their head needs that much room.
     */
    public static final double SEATED_HEAD = 32.5 * 0.9375 / 16;

    private @Nullable UUID owner;
    private String ownerName = "";
    private PianoSettings settings = PianoSettings.DEFAULT;
    /**
     * Whether the players want the lid up: what they last asked for, or what the piano was placed with.
     * The lid in {@link #settings} is down where it has no room even when they want it up; it rises
     * once the room is back, and a piano broken to be moved keeps what they want, not what the room allowed.
     */
    private boolean lidWanted = PianoSettings.DEFAULT.lidOpen();
    private Playback playback = Playback.EMPTY;
    private boolean powered;
    private int comparator;
    private @Nullable UUID seat;
    /** {@link MusicClock} time at which the current playback copy arrived or was loaded; its anchor. */
    private long receivedAt;
    /** Client: {@link System#nanoTime()} at which the current playback copy arrived. */
    private long receivedNanos;

    public GrandPianoBlockEntity(BlockPos pos, BlockState state) {
        super(MusicContent.GRAND_PIANO_ENTITY, pos, state);
    }

    public Direction facing() {
        return getBlockState().getValue(GrandPianoBlock.FACING);
    }

    public PianoSettings settings() {
        return settings;
    }

    public Playback playback() {
        return playback;
    }

    public String ownerName() {
        return ownerName;
    }

    public @Nullable UUID owner() {
        return owner;
    }

    /** Song position now, valid on both sides. */
    public long playbackPosition() {
        return playback.positionAt(MusicClock.millis());
    }

    public void setOwner(UUID id, String name) {
        owner = id;
        ownerName = name == null ? "" : name;
        changed();
    }

    // ------------------------------------------------------------------ server

    /** True when the player may play the piano and its songs. */
    public boolean canPlay(ServerPlayer player) {
        return settings.access().everyonePlays() || isOwnerOrOperator(player);
    }

    /**
     * True when the player may change the piano's sound and settings. A performer who may not still
     * opens the lid and the desk (see {@link PianoSettings#asPerformerMay}).
     */
    public boolean canTune(ServerPlayer player) {
        return settings.access().everyoneTunes() || isOwnerOrOperator(player);
    }

    public boolean isOwnerOrOperator(ServerPlayer player) {
        return owner == null || owner.equals(player.getUUID()) || player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    /** True when the player is close enough to play this piano. */
    public boolean isNear(ServerPlayer player) {
        Vec3 center = PianoLayout.center(worldPosition, facing());
        return player.position().distanceToSqr(center) <= 8 * 8 || seatedPlayer() == player;
    }

    /**
     * A right click on the piano: seats the player on its bench when they may play it, are not
     * sneaking or riding, the bench is free and their head has room, and opens its screen, which is
     * told why they stand when they do (see {@link MusicPayloads.OpenPiano}).
     */
    public void use(ServerPlayer player) {
        if (!(level instanceof ServerLevel serverLevel)) return;
        boolean canPlay = canPlay(player);
        int standing;
        String performer = "";
        String headroom = null;
        Entity current = seatEntity(serverLevel);
        // Sneaking is the reason given only when nothing else keeps the player off the bench, so the
        // advice to right-click without sneaking always seats them.
        if (current != null && current.hasPassenger(player)) {
            standing = MusicPayloads.OpenPiano.SEATED;
        } else if (!canPlay) {
            standing = MusicPayloads.OpenPiano.NOT_ALLOWED;
        } else if (current != null && current.isVehicle()) {
            standing = MusicPayloads.OpenPiano.BENCH_TAKEN;
            ServerPlayer seatedNow = seatedPlayer();
            if (seatedNow != null) performer = seatedNow.getGameProfile().name();
        } else if (player.getVehicle() != null) {
            standing = MusicPayloads.OpenPiano.RIDING;
        } else if (player.isShiftKeyDown()) {
            standing = MusicPayloads.OpenPiano.SNEAKING;
        } else {
            if (current != null) current.discard();
            standing = MusicPayloads.OpenPiano.COULD_NOT_SIT;
            PianoSeatEntity chair = MusicContent.PIANO_SEAT.create(serverLevel, EntitySpawnReason.TRIGGERED);
            if (chair != null) {
                Vec3 at = PianoLayout.seatWorld(worldPosition, facing());
                float yaw = PianoLayout.performerYaw(facing());
                chair.setAnchor(worldPosition);
                chair.snapTo(at.x, at.y, at.z, yaw, 0f);
                headroom = missingHeadroom(serverLevel, chair, player);
                if (headroom == null) serverLevel.addFreshEntity(chair);
                // Forced past the game's wait after leaving a seat, so a player who just stood up sits down
                // again at once (sneaking and a taken bench were looked at above).
                if (headroom == null && player.startRiding(chair, true, true)) {
                    seat = chair.getUUID();
                    standing = MusicPayloads.OpenPiano.SEATED;
                    // A creative player who flew to the bench sits down: flying widens the view and shrinks the piano in it.
                    if (player.getAbilities().flying) {
                        player.getAbilities().flying = false;
                        player.onUpdateAbilities();
                    }
                    // Face the keys; the pitch is left to the client, which looks down at them once
                    // from where its camera really is (SeatedView).
                    player.forceSetRotation(yaw, false, 0f, true);
                    changed();
                } else {
                    if (headroom != null) standing = MusicPayloads.OpenPiano.NO_HEADROOM;
                    else LOG.warn("{} could not sit at the piano at {}", player.getGameProfile().name(), worldPosition.toShortString());
                    chair.discard();
                }
            } else {
                LOG.warn("No bench seat could be made for the piano at {}", worldPosition.toShortString());
            }
        }
        if (ServerPlayNetworking.canSend(player, MusicPayloads.OpenPiano.TYPE)) {
            ServerPlayNetworking.send(player, new MusicPayloads.OpenPiano(worldPosition, standing, canPlay, performer));
        }
        // After the screen opens, so these reach its status line.
        if (!canPlay) player.displayClientMessage(Component.translatable("music.message.owner_only", ownerName), true);
        if (headroom != null) player.displayClientMessage(Component.translatable("music.message.no_room_to_sit", headroom), true);
    }

    /**
     * Null when {@code player} fits seated on {@code chair} (not yet in the world): nothing solid from
     * the bench top up to the top of their head as it is drawn, hat included. Otherwise the clear
     * height over the floor sitting needs, in blocks, as the player is told it (rounded up to a tenth).
     */
    private @Nullable String missingHeadroom(ServerLevel level, PianoSeatEntity chair, ServerPlayer player) {
        Vec3 feet = chair.position().subtract(player.getVehicleAttachmentPoint(chair));
        double half = player.getBbWidth() * 0.5, top = feet.y + SEATED_HEAD * player.getScale();
        AABB body = new AABB(feet.x - half, chair.getY() + 1e-3, feet.z - half, feet.x + half, top, feet.z + half);
        if (level.noBlockCollision(player, body)) return null;
        return String.format(Locale.ROOT, "%.1f", Math.ceil((top - worldPosition.getY()) * 10) / 10);
    }

    /**
     * The bench's chair, if one exists. The remembered id is checked against the chair's own anchor;
     * when it is stale (the performer relogged, or this block entity's data was copied) the chair
     * is found by place instead.
     */
    public @Nullable Entity seatEntity(ServerLevel level) {
        if (seat != null && level.getEntity(seat) instanceof PianoSeatEntity chair && chair.isAlive() && chair.anchor().equals(worldPosition)) {
            return chair;
        }
        Vec3 at = PianoLayout.seatWorld(worldPosition, facing());
        List<PianoSeatEntity> found = level.getEntitiesOfClass(PianoSeatEntity.class, new AABB(at, at).inflate(0.75),
            s -> s.isAlive() && s.anchor().equals(worldPosition));
        PianoSeatEntity chair = found.isEmpty() ? null : found.get(0);
        seat = chair == null ? null : chair.getUUID();
        return chair;
    }

    public @Nullable ServerPlayer seatedPlayer() {
        if (!(level instanceof ServerLevel serverLevel)) return null;
        Entity chair = seatEntity(serverLevel);
        if (chair == null) return null;
        for (Entity passenger : chair.getPassengers()) if (passenger instanceof ServerPlayer p) return p;
        return null;
    }

    /**
     * Applies new settings. The lid only rises into room it has (see
     * {@link GrandPianoBlock#hasLidRoom}): asked to open where a block is in its way, it stays closed,
     * and rises once the room is back. Settings that leave the lid as it is leave what the players want
     * of it too, so a change of sound made while the lid is held down for want of room keeps it wanted up.
     */
    public void applySettings(PianoSettings next) {
        PianoSettings wanted = next.sanitized();
        if (wanted.lidOpen() != settings.lidOpen()) lidWanted = wanted.lidOpen();
        boolean opening = wanted.lidOpen() && !settings.lidOpen();
        settings = opening && level != null && !GrandPianoBlock.hasLidRoom(level, worldPosition, facing()) ? wanted.withLidOpen(false) : wanted;
        changed();
    }

    /**
     * Lowers the lid where it has no room to stand, leaving it wanted up: it rises again once the room
     * is back, and the item a piano broken meanwhile drops keeps it up.
     */
    public void shutLid() {
        if (!settings.lidOpen()) return;
        settings = settings.withLidOpen(false);
        changed();
    }

    /** Applies settings a player asked for, telling them when the lid had no room to open. */
    public void applySettings(PianoSettings next, ServerPlayer by) {
        applySettings(next);
        if (next.lidOpen() && !settings.lidOpen()) {
            by.displayClientMessage(Component.translatable("music.message.lid_no_room", GrandPianoBlock.LID_HEIGHT), true);
        }
    }

    /** Runs a playback command; returns a message key when the command was refused, else null. */
    public @Nullable String control(MusicPayloads.PlaybackControl c, SongLibrary library) {
        long now = MusicClock.millis();
        Playback p = playback;
        switch (c.action()) {
            case MusicPayloads.PlaybackControl.PLAY_SONG -> {
                if (library.info(c.songId()).isEmpty()) return "music.message.song_missing";
                List<String> list = new ArrayList<>(p.playlist());
                int at = list.indexOf(c.songId());
                if (at < 0) {
                    if (list.size() >= Playback.MAX_PLAYLIST) list.remove(0);
                    list.add(c.songId());
                    at = list.size() - 1;
                }
                p = p.withPlaylist(list, at, true, now);
            }
            case MusicPayloads.PlaybackControl.QUEUE -> {
                if (library.info(c.songId()).isEmpty()) return "music.message.song_missing";
                if (p.playlist().size() >= Playback.MAX_PLAYLIST) return "music.message.playlist_full";
                List<String> list = new ArrayList<>(p.playlist());
                list.add(c.songId());
                // Adding to the playlist does not move the timeline, so nothing restarts.
                p = new Playback(list, p.index() < 0 ? 0 : p.index(), p.playing(), p.positionMs(), p.anchorMs(), p.speed(), p.loop(),
                    p.index() < 0 ? p.revision() + 1 : p.revision()).sanitized();
            }
            case MusicPayloads.PlaybackControl.PLAY -> {
                if (p.index() < 0) return "music.message.nothing_to_play";
                p = p.play(now);
            }
            case MusicPayloads.PlaybackControl.PAUSE -> p = p.pause(now);
            case MusicPayloads.PlaybackControl.STOP -> p = p.stop(now);
            case MusicPayloads.PlaybackControl.SEEK -> p = p.seek(Math.min(c.value(), durationOf(p.currentSong(), library)), now);
            case MusicPayloads.PlaybackControl.NEXT -> p = p.select(p.index() + 1, p.playing() || p.index() >= 0, now);
            case MusicPayloads.PlaybackControl.PREVIOUS -> p = p.positionAt(now) > 3000 ? p.seek(0, now) : p.select(p.index() - 1, p.playing(), now);
            case MusicPayloads.PlaybackControl.SELECT -> p = p.select((int) c.value(), true, now);
            case MusicPayloads.PlaybackControl.LOOP -> {
                LoopMode[] modes = LoopMode.values();
                p = p.withLoop(modes[(int) Math.floorMod(c.value(), modes.length)]);
            }
            case MusicPayloads.PlaybackControl.SPEED -> p = p.withSpeed(c.speed(), now);
            case MusicPayloads.PlaybackControl.REMOVE -> {
                int i = (int) c.value();
                if (i < 0 || i >= p.playlist().size()) return null;
                List<String> list = new ArrayList<>(p.playlist());
                list.remove(i);
                int index = p.index();
                boolean playing = p.playing();
                long position = p.positionAt(now);
                int revision = p.revision();
                if (i < index) index--;
                else if (i == index) {
                    position = 0;
                    revision++;
                    if (index >= list.size()) {
                        index = list.size() - 1;
                        playing = false;
                    }
                }
                p = new Playback(list, index, playing, position, now, p.speed(), p.loop(), revision).sanitized();
            }
            case MusicPayloads.PlaybackControl.CLEAR -> p = new Playback(List.of(), -1, false, 0, now, p.speed(), p.loop(), p.revision() + 1);
            case MusicPayloads.PlaybackControl.MOVE -> {
                // The song playing goes on where it is, wherever it now stands in the playlist.
                Playback moved = p.moved(c.moveFrom(), c.moveTo());
                if (moved == p) return null;
                p = moved;
            }
            default -> {
                return null;
            }
        }
        playback = p.sanitized();
        changed();
        updateComparator();
        return null;
    }

    private static long durationOf(@Nullable String id, SongLibrary library) {
        if (id == null) return 0;
        return library.info(id).map(SongInfo::durationMs).orElse(0L);
    }

    /** Drops songs that were deleted from the library. */
    public void forgetSong(String id) {
        if (!playback.playlist().contains(id)) return;
        long now = MusicClock.millis();
        boolean wasCurrent = id.equals(playback.currentSong());
        int removedBefore = 0;
        for (int k = 0; k < playback.index(); k++) if (id.equals(playback.playlist().get(k))) removedBefore++;
        int index = playback.index() - removedBefore;
        List<String> list = new ArrayList<>(playback.playlist());
        list.removeIf(id::equals);
        playback = wasCurrent
            ? new Playback(list, Math.min(index, list.size() - 1), false, 0, now, playback.speed(), playback.loop(), playback.revision() + 1).sanitized()
            : new Playback(list, index, playback.playing(), playback.positionAt(now), now, playback.speed(), playback.loop(), playback.revision()).sanitized();
        changed();
        updateComparator();
    }

    /**
     * Moves the timeline's anchor forward by {@code ms}, as if no time had passed. Used when a
     * singleplayer game was paused, so a song continues where it stopped.
     */
    public void shiftAnchor(long ms) {
        if (!playback.playing() || ms <= 0) return;
        playback = new Playback(playback.playlist(), playback.index(), true, playback.positionMs(), playback.anchorMs() + ms,
            playback.speed(), playback.loop(), playback.revision());
        changed();
    }

    public void serverTick(ServerLevel level) {
        if (level.getGameTime() % 5 != 0) return;
        if (seat != null && seatEntity(level) == null) seat = null;
        // The lid follows its room: a block put where the raised lid stands closes it, rather than leaving
        // the lid drawn through the block, and a lid wanted up rises once the block is gone.
        if (lidWanted) {
            boolean room = GrandPianoBlock.hasLidRoom(level, worldPosition, facing());
            if (settings.lidOpen() && !room) {
                shutLid();
            } else if (!settings.lidOpen() && room) {
                settings = settings.withLidOpen(true);
                changed();
            }
        }
        SongLibrary library = SongLibrary.get(level.getServer());
        if (level.getGameTime() % 100 == 0) {
            for (String id : List.copyOf(playback.playlist())) if (library.info(id).isEmpty()) forgetSong(id);
        }
        if (!playback.playing()) return;
        // Keep the saved position current so a restart or chunk reload resumes where the song is.
        if (level.getGameTime() % 20 == 0) setChanged();
        String id = playback.currentSong();
        Optional<SongInfo> info = id == null ? Optional.empty() : library.info(id);
        long now = MusicClock.millis();
        if (info.isEmpty()) {
            playback = playback.stop(now);
            changed();
        } else if (playback.positionAt(now) >= info.get().durationMs() + END_TAIL_MS) {
            playback = playback.afterEnd(info.get().durationMs() + END_TAIL_MS, now);
            changed();
        }
        updateComparator();
    }

    public void updateRedstone() {
        if (!(level instanceof ServerLevel serverLevel)) return;
        boolean now = false;
        for (int part = 0; part < PianoLayout.PARTS && !now; part++) {
            now = serverLevel.hasNeighborSignal(PianoLayout.partPos(worldPosition, facing(), part));
        }
        if (now == powered) return;
        powered = now;
        long t = MusicClock.millis();
        Playback p = playback;
        switch (settings.redstone()) {
            case TOGGLE -> {
                if (now && p.index() >= 0) p = p.playing() ? p.pause(t) : p.play(t);
            }
            case WHILE_POWERED -> {
                if (p.index() >= 0) p = now ? (p.playing() ? p : p.play(t)) : (p.playing() ? p.pause(t) : p);
            }
            case NEXT -> {
                if (now && p.index() >= 0) p = p.select(p.index() + 1, true, t);
            }
            case RESTART -> {
                if (now && p.index() >= 0) p = p.seek(0, t).play(t);
            }
            case OFF -> {
            }
        }
        if (p != playback) {
            playback = p;
            changed();
            updateComparator();
        } else {
            // Only the remembered power changed: saved, but no client shows it, so nothing is sent.
            setChanged();
        }
    }

    /** 0 when idle; 1..15 tracks progress through the current song while it plays or is paused mid-song. */
    public int comparatorSignal() {
        return comparator;
    }

    private void updateComparator() {
        if (!(level instanceof ServerLevel serverLevel)) return;
        int signal = 0;
        String id = playback.currentSong();
        if (id != null && (playback.playing() || playback.positionMs() > 0)) {
            long duration = SongLibrary.get(serverLevel.getServer()).info(id).map(SongInfo::durationMs).orElse(0L);
            double progress = duration <= 0 ? 0 : Math.min(1, playback.positionAt(MusicClock.millis()) / (double) duration);
            signal = 1 + (int) Math.floor(progress * 14);
        }
        if (signal != comparator) {
            comparator = signal;
            for (int part = 0; part < PianoLayout.PARTS; part++) {
                BlockPos pos = PianoLayout.partPos(worldPosition, facing(), part);
                serverLevel.updateNeighbourForOutputSignal(pos, getBlockState().getBlock());
            }
        }
    }

    private void changed() {
        setChanged();
        if (level != null && !level.isClientSide()) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
    }

    // ------------------------------------------------------------------ the item it drops and is placed from

    /**
     * Its settings and playlist go on the item it drops (the loot table copies {@code music:piano}),
     * with the lid as the players want it: a lid held down for want of room is no setup of theirs.
     */
    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        PianoItemData kept = PianoItemData.of(settings.withLidOpen(lidWanted), playback);
        if (kept != null) components.set(MusicContent.PIANO_DATA, kept);
    }

    /** Placed from an item that kept a piano's setup: its settings, and its playlist stopped. The placer still becomes the owner. */
    @Override
    protected void applyImplicitComponents(DataComponentGetter components) {
        super.applyImplicitComponents(components);
        PianoItemData kept = components.get(MusicContent.PIANO_DATA);
        if (kept == null) return;
        settings = kept.settings();
        lidWanted = settings.lidOpen();
        playback = kept.playback(MusicClock.millis(), playback.revision() + 1);
    }

    /**
     * What a creative player's copy of the piano (picked with its data) leaves out of its block entity
     * data: the setup travels as {@code music:piano} instead, the owner is whoever places the copy, and
     * a copy never starts in the middle of the original's song or with its redstone state.
     */
    @Override
    public void removeComponentsFromTag(ValueOutput output) {
        super.removeComponentsFromTag(output);
        for (String key : new String[]{"Owner", "OwnerName", "Settings", "LidWanted", "Playback", "Powered", "Comparator"}) output.discard(key);
    }

    // ------------------------------------------------------------------ persistence and sync

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        if (owner != null) output.store("Owner", UUIDUtil.CODEC, owner);
        output.putString("OwnerName", ownerName);
        output.store("Settings", PianoSettings.CODEC, settings);
        output.putBoolean("LidWanted", lidWanted);
        output.store("Playback", Playback.CODEC, playback.anchored(MusicClock.millis()));
        output.putBoolean("Powered", powered);
        output.putInt("Comparator", comparator);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        owner = input.read("Owner", UUIDUtil.CODEC).orElse(null);
        ownerName = input.getStringOr("OwnerName", "");
        settings = input.read("Settings", PianoSettings.CODEC).orElse(PianoSettings.DEFAULT);
        // A piano saved before the lid kept what was wanted of it wants it as it stood.
        lidWanted = input.getBooleanOr("LidWanted", settings.lidOpen()) || settings.lidOpen();
        Playback loaded = input.read("Playback", Playback.CODEC).orElse(Playback.EMPTY);
        // Position resumes from where it was saved or sent; the anchor becomes "now" on this side.
        receivedAt = MusicClock.millis();
        receivedNanos = System.nanoTime();
        playback = new Playback(loaded.playlist(), loaded.index(), loaded.playing(), loaded.positionMs(), receivedAt, loaded.speed(), loaded.loop(), loaded.revision());
        powered = input.getBooleanOr("Powered", false);
        comparator = input.getIntOr("Comparator", 0);
    }

    /** {@link MusicClock} time when the current playback copy arrived; a newer copy has another. */
    public long receivedAt() {
        return receivedAt;
    }

    /** Client: song position now, measured with the monotonic clock so wall-clock adjustments cannot jump it. */
    public long clientPosition() {
        if (!playback.playing()) return playback.positionMs();
        return playback.positionMs() + (long) (Math.max(0, System.nanoTime() - receivedNanos) / 1_000_000.0 * playback.speed());
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveCustomOnly(registries);
    }
}
