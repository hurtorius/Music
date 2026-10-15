package org.hurtorius.music.client.audio;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/**
 * Loads the grand piano samples on every resource reload, through the resource manager so resource
 * packs can replace them. Decoding happens in the preparation stage on the background executor; the
 * apply stage (ordered after Minecraft's sounds) hands the bank to the engine and reloads the note
 * block sounds from the freshly loaded sound events.
 */
final class PianoSampleReloader implements PreparableReloadListener {
    static final Identifier ID = Identifier.fromNamespaceAndPath("music", "piano_audio");
    private static final String FOLDER = "instruments/grand_piano/";

    private record Result(@Nullable GrandPianoBank bank, @Nullable String problem) {}

    private final AudioEngine engine;
    private final InstrumentSounds sounds;

    PianoSampleReloader(AudioEngine engine, InstrumentSounds sounds) {
        this.engine = engine;
        this.sounds = sounds;
    }

    private static Identifier file(String name) {
        return Identifier.fromNamespaceAndPath("music", FOLDER + name);
    }

    @Override
    public CompletableFuture<Void> reload(SharedState state, Executor prepare, PreparationBarrier barrier, Executor apply) {
        ResourceManager resources = state.resourceManager();
        String source = resources.getResource(file(GrandPianoBank.MAP)).map(Resource::sourcePackId).orElse("no pack");
        // Minecraft's sound engine restarts during the reload: these samples, not the old ones, are what it gets.
        engine.expectBank(true);
        return GrandPianoBank.load(name -> resources.open(file(name)), source, prepare)
            .handle((bank, error) -> error == null ? new Result(bank, null) : new Result(null, describe(error)))
            .thenCompose(barrier::wait)
            .thenAcceptAsync(this::apply, apply)
            .whenComplete((done, error) -> {
                if (error != null) engine.expectBank(false);
            });
    }

    private void apply(Result result) {
        GrandPianoBank bank = result.bank();
        if (bank != null) {
            engine.setBank(bank);
            AudioEngine.LOG.info("Music audio: grand piano {} samples ({} MB of PCM) from {}", bank.coverage(),
                Math.round(bank.bytes() / 1e6), bank.source);
            if (!bank.failures.isEmpty()) AudioEngine.LOG.warn("Music audio: grand piano samples that failed to load: {}", bank.failures);
        } else {
            engine.bankFailed(result.problem());
            AudioEngine.LOG.error("Music audio: the grand piano samples could not be loaded: {}", result.problem());
        }
        engine.resetWarnings();
        sounds.reload(Minecraft.getInstance());
    }

    private static String describe(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && (cause.getMessage() == null || cause instanceof CompletionException)) cause = cause.getCause();
        return cause.getMessage() != null ? cause.getMessage() : cause.toString();
    }

    @Override
    public String getName() {
        return "Music piano samples";
    }
}
