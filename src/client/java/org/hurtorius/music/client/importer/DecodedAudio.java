package org.hurtorius.music.client.importer;

import java.util.Objects;

/**
 * Decoded audio as one mono channel of float samples (full scale is -1..1, louder files may
 * exceed it) at {@code sampleRate} samples per second. Multi-channel sources are downmixed by
 * averaging their channels.
 *
 * @param mono       the samples; owned by this record once constructed, callers must not modify it
 * @param sampleRate samples per second, at least 1
 */
public record DecodedAudio(float[] mono, int sampleRate) {
    public DecodedAudio {
        Objects.requireNonNull(mono, "mono");
        if (sampleRate < 1) throw new IllegalArgumentException("Sample rate must be positive: " + sampleRate);
    }

    /** Number of samples. */
    public int frames() {
        return mono.length;
    }

    /** Length in seconds. */
    public double seconds() {
        return mono.length / (double) sampleRate;
    }
}
