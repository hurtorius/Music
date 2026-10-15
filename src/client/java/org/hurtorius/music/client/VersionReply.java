package org.hurtorius.music.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import org.hurtorius.music.net.MusicPayloads;
import org.hurtorius.music.net.VersionCheck;

/**
 * Answers a server's {@link MusicPayloads.Hello} while joining it with this game's own: the server
 * compares the two and tells the player, before anything else of Music's crosses, when their Music
 * is not the one it has (see {@link VersionCheck}).
 */
public final class VersionReply {
    private VersionReply() {}

    public static void register() {
        ClientConfigurationNetworking.registerGlobalReceiver(MusicPayloads.Hello.TYPE, (hello, context) ->
            context.responseSender().sendPacket(new MusicPayloads.Hello(MusicPayloads.PROTOCOL, VersionCheck.version())));
    }
}
