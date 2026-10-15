package org.hurtorius.music.client.audio;

import org.hurtorius.music.piano.Instrument;
import org.jspecify.annotations.Nullable;

/**
 * A sound the engine started, for diagnostics and tests.
 *
 * @param kind       what kind of sound it is
 * @param voicing    who played it ({@code null} for the metronome)
 * @param key        key index 0..87, or -1 for pedal noise and the metronome
 * @param velocity   1..127 (0 for pedal noise and the metronome)
 * @param instrument the piano's sound set ({@code null} for the metronome)
 * @param sample     name of the sample that plays
 * @param gain       OpenAL source gain at the start (velocity, volume, category slider, lid, soft pedal)
 * @param pitch      OpenAL playback rate
 * @param atDevice   device-clock nanoseconds the sound starts at
 * @param alSource   the OpenAL source playing it (valid until the voice is reused)
 */
public record StartedNote(Kind kind, @Nullable Voicing voicing, int key, int velocity, @Nullable Instrument instrument, String sample,
                          float gain, float pitch, long atDevice, int alSource) {
    public enum Kind { NOTE, RESONANCE, PEDAL, CLICK }
}
