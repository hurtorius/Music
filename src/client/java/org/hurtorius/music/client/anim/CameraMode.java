package org.hurtorius.music.client.anim;

import net.minecraft.network.chat.Component;

/** How the camera frames a seated performer. Stored in {@code ClientPrefs.camera} by {@link #id()}. */
public enum CameraMode {
    /** The game's own view (first or third person). */
    PLAYER("player"),
    /** Behind and above the shoulders, looking down at the keyboard. */
    KEYS("keys"),
    /** Concert view from the treble side. */
    SIDE("side"),
    /** Straight down onto the keyboard. */
    TOP("top");

    private final String id;

    CameraMode(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public Component displayName() {
        return Component.translatable("music.camera." + id);
    }

    /** The next mode, wrapping around; for cycling through them. */
    public CameraMode next() {
        CameraMode[] modes = values();
        return modes[(ordinal() + 1) % modes.length];
    }

    /** The mode with this id, or {@link #PLAYER} for anything unknown. */
    public static CameraMode byId(String id) {
        for (CameraMode mode : values()) if (mode.id.equals(id)) return mode;
        return PLAYER;
    }
}
