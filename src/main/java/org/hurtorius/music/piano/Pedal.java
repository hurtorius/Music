package org.hurtorius.music.piano;

/** The three pedals, left to right as the performer sees them. MIDI controller numbers included. */
public enum Pedal {
    SOFT(67, "pedal_soft"), SOSTENUTO(66, "pedal_sostenuto"), SUSTAIN(64, "pedal_sustain");

    private final int controller;
    private final String group;

    Pedal(int controller, String group) {
        this.controller = controller;
        this.group = group;
    }

    public int controller() {
        return controller;
    }

    /** Model group that animates for this pedal. */
    public String group() {
        return group;
    }

    public static Pedal byController(int cc) {
        for (Pedal p : values()) if (p.controller == cc) return p;
        return null;
    }
}
