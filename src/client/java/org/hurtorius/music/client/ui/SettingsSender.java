package org.hurtorius.music.client.ui;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.hurtorius.music.client.play.Pianos;
import org.hurtorius.music.net.MusicPayloads;
import org.hurtorius.music.piano.PianoSettings;
import org.jspecify.annotations.Nullable;

import java.util.function.UnaryOperator;

/**
 * Sends the piano's settings as the player changes them. Every change starts from the newest
 * settings this client knows: the server's copy in the block entity, or what was just sent while the
 * server's answer is on its way (so two quick changes never undo each other). Changes are merged and
 * sent at most every {@link #GAP_NANOS}, well inside the server's budget, plus once more whenever a
 * control settles. Client thread.
 */
final class SettingsSender {
    static final long GAP_NANOS = 100_000_000L;
    /** A change the server has not confirmed in this long was refused; the server's copy wins again. */
    static final long ECHO_NANOS = 1_500_000_000L;

    private final BlockPos piano;
    private @Nullable PianoSettings expected;
    private long expectedAt;
    private @Nullable PianoSettings pending;
    private long lastSent;

    SettingsSender(BlockPos piano) {
        this.piano = piano;
    }

    /** The settings the screen shows: the newest of what was asked for and what the server has. */
    PianoSettings current() {
        if (pending != null) return pending;
        PianoSettings server = Pianos.settings(piano);
        if (expected != null) {
            if (expected.equals(server) || System.nanoTime() - expectedAt > ECHO_NANOS) expected = null;
            else return expected;
        }
        return server;
    }

    /** Applies a change to the current settings and sends it soon. */
    void edit(UnaryOperator<PianoSettings> change) {
        PianoSettings next = change.apply(current()).sanitized();
        if (next.equals(current())) return;
        pending = next;
        flush(false);
    }

    /** Sends what is waiting: now if {@code force}, else once the gap since the last send has passed. */
    void flush(boolean force) {
        PianoSettings next = pending;
        if (next == null) return;
        long now = System.nanoTime();
        if (!force && now - lastSent < GAP_NANOS) return;
        pending = null;
        if (!ClientPlayNetworking.canSend(MusicPayloads.ChangeSettings.TYPE)) {
            Feedback.error(Component.translatable("music.screen.not_connected"));
            return;
        }
        ClientPlayNetworking.send(new MusicPayloads.ChangeSettings(piano, next));
        expected = next;
        expectedAt = now;
        lastSent = now;
    }

    /** Client thread, every tick. */
    void tick() {
        flush(false);
    }
}
