package org.hurtorius.music.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * The mod's work for a player leaving a world, run on the client thread at the start of
 * {@code Minecraft.disconnect} (see {@code MinecraftMixin}), which every way out of a world passes
 * through: quitting, being kicked, the server going away, closing the game.
 *
 * <p>Fabric's disconnect event comes from the network thread when the connection closes, and work
 * handed from there to the client thread with {@code Minecraft.execute} can be thrown away by that
 * same disconnect ({@code dropAllTasks}): an unsaved take would be lost and the old world's pianos,
 * keys and sounds could reach the next one. Here nothing is queued. What the player made is kept
 * first ({@link Step#KEEP}), then the world's state is forgotten ({@link Step#FORGET}). A failing
 * task is logged and does not stop the others. Client thread only.</p>
 */
public final class WorldExit {
    private static final Logger LOG = LoggerFactory.getLogger("Music");

    /** When a task runs: everything in {@code KEEP} before anything in {@code FORGET}. */
    public enum Step { KEEP, FORGET }

    private static final List<Runnable> KEEP = new ArrayList<>();
    private static final List<Runnable> FORGET = new ArrayList<>();

    private WorldExit() {}

    public static void register(Step step, Runnable task) {
        (step == Step.KEEP ? KEEP : FORGET).add(task);
    }

    /** The player is leaving the world. */
    public static void run() {
        for (Runnable task : KEEP) runSafely(task);
        for (Runnable task : FORGET) runSafely(task);
    }

    private static void runSafely(Runnable task) {
        try {
            task.run();
        } catch (RuntimeException e) {
            LOG.error("Music: a task for leaving the world failed", e);
        }
    }
}
