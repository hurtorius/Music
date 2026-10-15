package org.hurtorius.music.piano;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/** What the music desk shows while a song plays. */
public enum SheetMode implements StringRepresentable {
    OFF("off"), NOTATION("notation"), ROLL("roll");

    public static final Codec<SheetMode> CODEC = StringRepresentable.fromEnum(SheetMode::values);
    private final String id;

    SheetMode(String id) {
        this.id = id;
    }

    @Override
    public String getSerializedName() {
        return id;
    }

    public String translationKey() {
        return "music.sheet." + id;
    }
}
