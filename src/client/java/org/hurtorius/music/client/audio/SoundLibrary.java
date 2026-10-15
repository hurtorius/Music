package org.hurtorius.music.client.audio;

import org.hurtorius.music.piano.Instrument;
import org.jspecify.annotations.Nullable;

/**
 * Where the engine gets note block and custom sounds. Called from the audio thread; implementations
 * load on other threads and answer {@link Status#LOADING} meanwhile.
 */
public interface SoundLibrary {
    enum Status { READY, LOADING, MISSING }

    /**
     * @param sound  the sound when {@code READY}
     * @param detail why it is missing (for the debug lines), or what is loading
     */
    record Lookup(Status status, @Nullable PitchedSound sound, String detail) {
        public static Lookup ready(PitchedSound sound) {
            return new Lookup(Status.READY, sound, sound.name);
        }

        public static Lookup loading(String what) {
            return new Lookup(Status.LOADING, null, what);
        }

        public static Lookup missing(String why) {
            return new Lookup(Status.MISSING, null, why);
        }
    }

    /** A note block instrument's sound. */
    Lookup instrument(Instrument instrument);

    /** Any sound event by id, for {@link Instrument#CUSTOM}. */
    Lookup custom(String soundEventId);

    /** Next sample that was replaced (resource reload) or let go of, and whose OpenAL buffer must be freed, or null. */
    @Nullable PcmSample pollRetired();

    /** One line about what is loaded, loading and missing. */
    String describe();
}
