package org.hurtorius.music.client.midi;

import java.util.Locale;

/**
 * How a MIDI keyboard's velocities are shaped before they reach the piano
 * ({@code ClientPrefs.velocityCurve}). Soft makes loud notes easier to reach on a stiff keyboard,
 * hard does the opposite, fixed plays every note at the player's chosen velocity.
 */
public enum VelocityCurve {
    SOFT(0.6), NORMAL(1.0), HARD(1.6), FIXED(1.0);

    private final double exponent;

    VelocityCurve(double exponent) {
        this.exponent = exponent;
    }

    /** The curve named in the preferences; unknown names are {@link #NORMAL}. */
    public static VelocityCurve byName(String name) {
        if (name != null) {
            for (VelocityCurve c : values()) if (c.name().equalsIgnoreCase(name.strip())) return c;
        }
        return NORMAL;
    }

    /** Lowercase name as stored in the preferences. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String translationKey() {
        return "music.velocity_curve." + id();
    }

    /** Shapes a device velocity 1..127; {@code fixed} is the velocity used by {@link #FIXED}. */
    public int apply(int velocity, int fixed) {
        if (this == FIXED) return Math.max(1, Math.min(127, fixed));
        int v = Math.max(1, Math.min(127, velocity));
        if (this == NORMAL) return v;
        return (int) Math.max(1, Math.min(127, Math.round(127 * Math.pow(v / 127.0, exponent))));
    }
}
