package org.hurtorius.music.piano;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/** What happens when a self-played song ends. */
public enum LoopMode implements StringRepresentable {
    /** Stop after the last song in the playlist. */
    OFF("off"),
    /** Repeat the current song. */
    ONE("one"),
    /** Repeat the whole playlist. */
    ALL("all");

    public static final Codec<LoopMode> CODEC = StringRepresentable.fromEnum(LoopMode::values);
    private final String id;

    LoopMode(String id) {
        this.id = id;
    }

    @Override
    public String getSerializedName() {
        return id;
    }

    public String translationKey() {
        return "music.loop." + id;
    }
}
