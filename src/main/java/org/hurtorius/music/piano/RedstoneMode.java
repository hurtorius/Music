package org.hurtorius.music.piano;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/** How a redstone signal into any part of the piano controls self-playing. */
public enum RedstoneMode implements StringRepresentable {
    /** Ignore redstone. */
    OFF("off"),
    /** Each rising edge toggles play / pause. */
    TOGGLE("toggle"),
    /** Plays while powered, pauses when the power goes away. */
    WHILE_POWERED("while_powered"),
    /** Each rising edge starts the next song in the playlist. */
    NEXT("next"),
    /** Each rising edge restarts the current song from the beginning. */
    RESTART("restart");

    public static final Codec<RedstoneMode> CODEC = StringRepresentable.fromEnum(RedstoneMode::values);
    private final String id;

    RedstoneMode(String id) {
        this.id = id;
    }

    @Override
    public String getSerializedName() {
        return id;
    }

    public String translationKey() {
        return "music.redstone." + id;
    }
}
