package org.hurtorius.music.client.audio;

import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;

/**
 * Entry points for the OpenAL lifecycle mixins ({@code LibraryMixin}, {@code SoundEngineMixin}):
 * Minecraft's context coming up, going down, the attribute list it creates the context with, and its
 * sound engine having tried to start.
 */
public final class AudioLifecycle {
    /** {@code ALC_MONO_SOURCES}. */
    static final int ALC_MONO_SOURCES = 0x1010;
    /**
     * Mono sources asked of OpenAL: Minecraft sizes its own pools from this (at most 255 + 8) and the
     * piano needs up to {@link AudioEngine#MAX_VOICES} more, so neither ever runs the other out of
     * sources. OpenAL Soft grants max(requested + stereo, 256).
     */
    public static final int REQUESTED_MONO_SOURCES = 255 + 8 + AudioEngine.MAX_VOICES + 1;
    /** How long a dying context waits for the audio thread; a crash path must never hang on it. */
    static final long RELEASE_TIMEOUT_MILLIS = 250;

    private AudioLifecycle() {}

    /** {@code Library#init} finished: a context is current. */
    public static void contextCreated() {
        PianoAudio.engine().contextCreated();
    }

    /** {@code Library#cleanup} is about to destroy the context and close the device. */
    public static void contextDestroying() {
        PianoAudio.engine().contextDestroying(RELEASE_TIMEOUT_MILLIS);
    }

    /**
     * {@code SoundEngine#loadLibrary} returned: Minecraft's sound engine started, or failed to (no audio
     * device, or the one it had is gone) and carries on silent.
     */
    public static void soundEngineStarted() {
        PianoAudio.engine().checkStarted();
    }

    /**
     * Adds an {@code ALC_MONO_SOURCES} request to a zero-terminated attribute list (unless it has one),
     * allocating the new list on the caller's stack frame.
     */
    public static IntBuffer withSourceRequest(IntBuffer attributes, MemoryStack stack) {
        int pairs = 0;
        for (int i = attributes.position(); i + 1 < attributes.limit() && attributes.get(i) != 0; i += 2) {
            if (attributes.get(i) == ALC_MONO_SOURCES) return attributes;
            pairs++;
        }
        IntBuffer out = stack.mallocInt(pairs * 2 + 3);
        for (int i = 0; i < pairs * 2; i++) out.put(attributes.get(attributes.position() + i));
        out.put(ALC_MONO_SOURCES).put(REQUESTED_MONO_SOURCES).put(0);
        return out.flip();
    }
}
