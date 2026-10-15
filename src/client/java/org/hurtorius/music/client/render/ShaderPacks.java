package org.hurtorius.music.client.render;

import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * What a shader pack loader is doing, for drawing decisions that depend on it. Iris is optional, so
 * its public API ({@code net.irisshaders.iris.api.v0.IrisApi}) is looked up once by reflection.
 * Without Iris there is no shader pack and never a shadow pass; with Iris but no usable API, the
 * cautious answer is given (a pack may be on, any pass may be a shadow pass).
 */
final class ShaderPacks {
    private static final Logger LOG = LoggerFactory.getLogger("Music");
    private static final boolean IRIS = FabricLoader.getInstance().isModLoaded("iris");
    private static final @Nullable MethodHandle SHADOW_PASS = IRIS ? query("isRenderingShadowPass") : null;
    private static final @Nullable MethodHandle PACK_IN_USE = IRIS ? query("isShaderPackInUse") : null;
    private static boolean warned;

    private ShaderPacks() {}

    /**
     * True when the current pass may be a shader pack's shadow pass, which draws the world from the
     * sun: what is behind the player can cast shadows in front of them.
     */
    static boolean mayBeShadowPass() {
        return IRIS && ask(SHADOW_PASS);
    }

    /** True when a shader pack may be drawing the world (it lights things its own way). */
    static boolean mayBeInUse() {
        return IRIS && ask(PACK_IN_USE);
    }

    private static boolean ask(@Nullable MethodHandle question) {
        if (question == null) return true;
        try {
            return (boolean) question.invokeExact();
        } catch (Throwable t) {
            if (!warned) LOG.warn("Music: Iris did not answer about its shader pack; pianos are drawn as if one were on", t);
            warned = true;
            return true;
        }
    }

    private static @Nullable MethodHandle query(String name) {
        try {
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            Object instance = lookup.findStatic(api, "getInstance", MethodType.methodType(api)).invoke();
            return lookup.findVirtual(api, name, MethodType.methodType(boolean.class)).bindTo(instance);
        } catch (Throwable t) {
            LOG.warn("Music: Iris is installed but its API has no {}; pianos are drawn as if a shader pack were on", name, t);
            return null;
        }
    }
}
