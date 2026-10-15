package org.hurtorius.music.net;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.Identifier;
import net.minecraft.server.network.ConfigurationTask;
import org.hurtorius.music.registry.MusicContent;
import org.jspecify.annotations.Nullable;

import java.util.function.Consumer;

/**
 * Turns away, with a message that says why, a player whose game has another Music than the server,
 * while they join and before anything of Music's crosses between them: the server sends its
 * {@link MusicPayloads.Hello} as the first task of the joining, the game answers with its own, and
 * the player goes on only when both speak the same {@link MusicPayloads#PROTOCOL}. A game that cannot
 * take the hello has no Music at all, and is told which one to install. This runs before the game's
 * registries are compared, whose own message would name Fabric API, or unknown entries, instead.
 */
public final class VersionCheck implements ConfigurationTask {
    public static final ConfigurationTask.Type TYPE = new ConfigurationTask.Type(MusicContent.MOD_ID + ":version");
    /**
     * Ahead of every other listener, so this check is the first task a joining game is given. Named
     * here rather than through {@code MusicContent.id}, which would load the game's blocks with it.
     */
    private static final Identifier FIRST = Identifier.fromNamespaceAndPath(MusicContent.MOD_ID, "version_check");

    private final String version;

    private VersionCheck(String version) {
        this.version = version;
    }

    public static void register() {
        ServerConfigurationConnectionEvents.BEFORE_CONFIGURE.addPhaseOrdering(FIRST, Event.DEFAULT_PHASE);
        ServerConfigurationConnectionEvents.BEFORE_CONFIGURE.register(FIRST, (handler, server) -> {
            if (ServerConfigurationNetworking.canSend(handler, MusicPayloads.Hello.TYPE)) handler.addTask(new VersionCheck(version()));
            else handler.disconnect(missing(version()));
        });
        ServerConfigurationNetworking.registerGlobalReceiver(MusicPayloads.Hello.TYPE, (hello, context) -> {
            Component refusal = refusal(hello, version());
            if (refusal == null) context.networkHandler().completeTask(TYPE);
            else context.networkHandler().disconnect(refusal);
        });
    }

    /** This Music's version, as its mod metadata gives it. */
    public static String version() {
        return FabricLoader.getInstance().getModContainer(MusicContent.MOD_ID)
            .map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("?");
    }

    /**
     * Null when a game that said {@code hello} may join a server with Music {@code serverVersion}; else
     * what it is told, in its own language when its Music has the words, in English when it does not.
     */
    static @Nullable Component refusal(MusicPayloads.Hello hello, String serverVersion) {
        if (hello.protocol() == MusicPayloads.PROTOCOL) return null;
        return Component.translatableWithFallback("music.disconnect.version",
            "This server has Music " + serverVersion + " and you have Music " + hello.version() + ". Join with the same Music version as the server",
            serverVersion, hello.version());
    }

    /** What a game without Music is told: in English, since it has none of Music's words. */
    static Component missing(String serverVersion) {
        return Component.literal("This server has the Music mod " + serverVersion + ". Install Fabric Loader and Music " + serverVersion + " to join");
    }

    @Override
    public void start(Consumer<Packet<?>> sender) {
        sender.accept(ServerConfigurationNetworking.createS2CPacket(new MusicPayloads.Hello(MusicPayloads.PROTOCOL, version)));
    }

    @Override
    public ConfigurationTask.Type type() {
        return TYPE;
    }
}
