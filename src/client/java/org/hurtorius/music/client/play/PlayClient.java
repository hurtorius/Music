package org.hurtorius.music.client.play;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientBlockEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import org.hurtorius.music.client.WorldExit;
import org.hurtorius.music.net.MusicPayloads;
import org.hurtorius.music.piano.GrandPianoBlockEntity;

/** Wires the play layer into the client: packets, per-tick work, and cleanup on disconnect. */
public final class PlayClient {
    private PlayClient() {}

    public static void register() {
        Pianos.register();
        MidiBridge.register();
        ClientPlayNetworking.registerGlobalReceiver(MusicPayloads.PlayedNotes.TYPE, (p, c) -> RemoteNotes.receive(p));
        ClientPlayNetworking.registerGlobalReceiver(MusicPayloads.UploadStatus.TYPE, (p, c) -> Uploader.status(p));
        ClientBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register((be, level) -> {
            if (be instanceof GrandPianoBlockEntity) RemoteNotes.forget(be.getBlockPos());
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            Performer.tick(mc);
            RemoteNotes.tick(mc);
            SelfPlayer.tick(mc);
            Metronome.tick();
            Uploader.tick();
            MusicHush.tick(mc);
        });
        WorldExit.register(WorldExit.Step.FORGET, () -> {
            Performer.end();
            RemoteNotes.reset();
            SelfPlayer.reset();
            Metronome.reset();
            Uploader.reset();
            Recorder.cancel();
            Pianos.reset();
            MusicHush.reset();
        });
    }
}
