package org.hurtorius.music.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.client.renderer.entity.NoopRenderer;
import org.hurtorius.music.client.play.KeyClick;
import org.hurtorius.music.client.play.PlayClient;
import net.minecraft.client.renderer.special.SpecialModelRenderers;
import org.hurtorius.music.client.midi.MidiDevices;
import org.hurtorius.music.client.render.GrandPianoRenderer;
import org.hurtorius.music.client.render.PianoItemRenderer;
import org.hurtorius.music.client.render.PianoOutline;
import org.hurtorius.music.client.render.PianoVisuals;
import org.hurtorius.music.client.anim.PianoAnimation;
import org.hurtorius.music.client.audio.PianoAudio;
import org.hurtorius.music.client.song.ClientSongs;
import org.hurtorius.music.client.state.PianoClientState;
import org.hurtorius.music.client.ui.PianoUi;
import org.hurtorius.music.registry.MusicContent;

/** Client initialisation: rendering, sound, input and screens. */
public final class MusicClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        EntityRenderers.register(MusicContent.PIANO_SEAT, NoopRenderer::new);
        BlockEntityRenderers.register(MusicContent.GRAND_PIANO_ENTITY, GrandPianoRenderer::new);
        SpecialModelRenderers.ID_MAPPER.put(MusicContent.GRAND_PIANO_ID, PianoItemRenderer.Unbaked.MAP_CODEC);
        PianoItemRenderer.register();
        PianoVisuals.register();
        PianoOutline.register();
        ClientSongs.register();
        PlayClient.register();
        PianoUi.register();
        PianoClientState.register();
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            PianoClientState.tick(mc);
            ClientSongs.tick();
        });
        MidiDevices.bootstrap();
        PianoAnimation.register();
        PlacementPreview.register();
        KeyClick.register();
        VersionReply.register();
        PianoAudio.bootstrap();
        WorldExit.register(WorldExit.Step.FORGET, () -> {
            PianoClientState.clear();
            ClientSongs.reset();
        });
    }
}
