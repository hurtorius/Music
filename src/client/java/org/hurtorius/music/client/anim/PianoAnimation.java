package org.hurtorius.music.client.anim;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import org.hurtorius.music.client.WorldExit;

/** Hands, arms and camera of seated performers: wired up from {@link org.hurtorius.music.client.MusicClient}. */
public final class PianoAnimation {
    private PianoAnimation() {}

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(PerformerPoses::tick);
        ClientTickEvents.END_CLIENT_TICK.register(SeatedView::tick);
        WorldExit.register(WorldExit.Step.FORGET, () -> {
            PerformerPoses.clear();
            SeatedView.clear();
        });
        WorldRenderEvents.END_EXTRACTION.register(PerformerPoses::prepareOwnView);
        WorldRenderEvents.BEFORE_BLOCK_OUTLINE.register((context, outline) -> !PianoSeats.atOwnPiano(outline.pos()));
        FirstPersonArms.register();
        PianoCamera.register();
    }
}
