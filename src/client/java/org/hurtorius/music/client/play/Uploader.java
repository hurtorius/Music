package org.hurtorius.music.client.play;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.chat.Component;
import org.hurtorius.music.client.song.ClientSongs;
import org.hurtorius.music.net.MusicPayloads;
import org.hurtorius.music.song.Song;
import org.hurtorius.music.song.SongCodec;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;

/**
 * Adds songs to the world's library. The server takes one upload per player at a time and at most
 * one start every two seconds, so songs queue here and start spaced out; each one is sent in
 * {@link MusicPayloads#CHUNK} pieces with up to {@link #WINDOW} pieces awaiting acknowledgement.
 * A busy server is retried a few times; a silent one fails the upload after {@link #SILENCE_NANOS}.
 * Client thread.
 */
public final class Uploader {
    static final int WINDOW = 4;
    /** A little more than the server's upload cooldown. */
    static final long SPACING_NANOS = 2_100_000_000L;
    static final long RETRY_NANOS = 2_500_000_000L;
    static final int MAX_RETRIES = 3;
    static final long SILENCE_NANOS = 20_000_000_000L;
    private static final Deque<Job> QUEUE = new ArrayDeque<>();
    private static @Nullable Job current;
    private static int nextRequest = 1;
    private static long nextBeginAllowed;

    private Uploader() {}

    private static final class Job {
        final String name;
        final byte[] data;
        final String songId;
        final CompletableFuture<String> result = new CompletableFuture<>();
        int requestId;
        int sent;
        int retries;
        long lastHeard;
        long retryAt;

        Job(String name, byte[] data, String songId) {
            this.name = name;
            this.data = data;
            this.songId = songId;
        }
    }

    /** Uploads a song; completes with its id once the world has it, or with a readable error. */
    public static CompletableFuture<String> upload(Song song) {
        byte[] data = SongCodec.encode(song);
        String id = SongCodec.id(data);
        if (data.length > SongCodec.MAX_ENCODED_BYTES) {
            return CompletableFuture.failedFuture(new IOException(Component.translatable("music.message.song_too_large").getString()));
        }
        ClientSongs.put(id, song, data);
        if (!ClientPlayNetworking.canSend(MusicPayloads.UploadBegin.TYPE)) {
            return CompletableFuture.failedFuture(new IOException(Component.translatable("music.message.not_connected").getString()));
        }
        Job job = new Job(song.name(), data, id);
        QUEUE.add(job);
        return job.result;
    }

    /** True while songs are being sent or wait to be sent. */
    public static boolean busy() {
        return current != null || !QUEUE.isEmpty();
    }

    /** 0..1 progress of the song being sent now. */
    public static float progress() {
        Job job = current;
        return job == null ? 0f : job.sent / (float) Math.max(1, job.data.length);
    }

    /** Starts queued uploads when allowed, retries busy ones, and fails silent ones. Client thread, every tick. */
    static void tick() {
        long now = System.nanoTime();
        Job job = current;
        if (job != null) {
            if (job.retryAt != 0 && now >= job.retryAt) {
                job.retryAt = 0;
                begin(job, now);
            } else if (job.retryAt == 0 && now - job.lastHeard > SILENCE_NANOS) {
                fail(job, Component.translatable("music.message.upload_timeout").getString());
            }
            return;
        }
        if (!QUEUE.isEmpty() && now >= nextBeginAllowed) begin(QUEUE.poll(), now);
    }

    private static void begin(Job job, long now) {
        current = job;
        job.requestId = nextRequest++;
        job.sent = 0;
        job.lastHeard = now;
        nextBeginAllowed = now + SPACING_NANOS;
        ClientPlayNetworking.send(new MusicPayloads.UploadBegin(job.requestId, job.name, job.data.length, job.songId));
    }

    private static void fail(Job job, String message) {
        job.result.completeExceptionally(new IOException(message));
        if (current == job) current = null;
    }

    static void status(MusicPayloads.UploadStatus p) {
        Job job = current;
        if (job == null || job.requestId != p.requestId()) return;
        job.lastHeard = System.nanoTime();
        switch (p.state()) {
            case MusicPayloads.UploadStatus.READY, MusicPayloads.UploadStatus.ACK -> {
                // Keep at most WINDOW chunks beyond what the server has acknowledged.
                while (job.sent < job.data.length && job.sent - p.offset() < WINDOW * MusicPayloads.CHUNK) {
                    int end = Math.min(job.data.length, job.sent + MusicPayloads.CHUNK);
                    ClientPlayNetworking.send(new MusicPayloads.UploadChunk(job.requestId, job.sent, Arrays.copyOfRange(job.data, job.sent, end)));
                    job.sent = end;
                }
            }
            case MusicPayloads.UploadStatus.DONE -> {
                job.sent = job.data.length;
                job.result.complete(p.songId().isEmpty() ? job.songId : p.songId());
                current = null;
                ClientSongs.requestLibrary();
            }
            case MusicPayloads.UploadStatus.FAILED -> {
                if ("music.message.slow_down".equals(p.message()) && job.retries < MAX_RETRIES) {
                    job.retries++;
                    job.retryAt = System.nanoTime() + RETRY_NANOS;
                    return;
                }
                fail(job, p.message().startsWith("music.") ? Component.translatable(p.message()).getString() : p.message());
            }
            default -> {
            }
        }
    }

    static void reset() {
        if (current != null) current.result.completeExceptionally(new IOException("Disconnected"));
        for (Job job : QUEUE) job.result.completeExceptionally(new IOException("Disconnected"));
        QUEUE.clear();
        current = null;
        nextBeginAllowed = 0;
    }
}
