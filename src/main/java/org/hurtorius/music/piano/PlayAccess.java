package org.hurtorius.music.piano;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/**
 * Who may play the piano and who may change it. The owner and operators always may do both. A
 * performer who may play but not change it still opens and closes the lid and the desk and picks
 * what the desk shows, which belong to playing it (see {@link PianoSettings#asPerformerMay}).
 */
public enum PlayAccess implements StringRepresentable {
    /** Everyone plays it and its songs, and changes its sound and settings. */
    EVERYONE("everyone"),
    /** Everyone plays it and its songs; only the owner and operators change its sound and settings. */
    PLAY("play"),
    /** Only the owner and operators play it or change it. */
    OWNER("owner");

    public static final Codec<PlayAccess> CODEC = StringRepresentable.fromEnum(PlayAccess::values);
    private final String id;

    PlayAccess(String id) {
        this.id = id;
    }

    @Override
    public String getSerializedName() {
        return id;
    }

    public String translationKey() {
        return "music.access." + id;
    }

    /** True when anyone may play, not only the owner and operators. */
    public boolean everyonePlays() {
        return this != OWNER;
    }

    /** True when anyone may change the sound and the settings, not only the owner and operators. */
    public boolean everyoneTunes() {
        return this == EVERYONE;
    }
}
