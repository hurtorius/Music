package org.hurtorius.music.piano;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/**
 * What the piano sounds like. The grand piano uses the bundled multi-velocity samples; note-block
 * instruments reuse Minecraft's own note block sounds across the whole keyboard; CUSTOM plays any
 * sound event the player names, tuned so {@code customRoot} is the sample's own pitch.
 */
public enum Instrument implements StringRepresentable {
    GRAND_PIANO("grand_piano", null, 60, true),
    HARP("harp", "block.note_block.harp", 66, true),
    BASS("bass", "block.note_block.bass", 42, true),
    GUITAR("guitar", "block.note_block.guitar", 54, true),
    BANJO("banjo", "block.note_block.banjo", 66, true),
    BELL("bell", "block.note_block.bell", 90, true),
    CHIME("chime", "block.note_block.chime", 90, true),
    FLUTE("flute", "block.note_block.flute", 78, true),
    XYLOPHONE("xylophone", "block.note_block.xylophone", 90, true),
    IRON_XYLOPHONE("iron_xylophone", "block.note_block.iron_xylophone", 66, true),
    COW_BELL("cow_bell", "block.note_block.cow_bell", 78, true),
    DIDGERIDOO("didgeridoo", "block.note_block.didgeridoo", 42, true),
    BIT("bit", "block.note_block.bit", 66, true),
    PLING("pling", "block.note_block.pling", 66, true),
    BASEDRUM("basedrum", "block.note_block.basedrum", 66, false),
    SNARE("snare", "block.note_block.snare", 66, false),
    HAT("hat", "block.note_block.hat", 66, false),
    CUSTOM("custom", null, 60, true);

    public static final Codec<Instrument> CODEC = StringRepresentable.fromEnum(Instrument::values);

    private final String id;
    private final String soundEvent;
    private final int rootMidi;
    private final boolean pitched;

    Instrument(String id, String soundEvent, int rootMidi, boolean pitched) {
        this.id = id;
        this.soundEvent = soundEvent;
        this.rootMidi = rootMidi;
        this.pitched = pitched;
    }

    @Override
    public String getSerializedName() {
        return id;
    }

    /** Vanilla sound event path (minecraft namespace) for note-block instruments, else null. */
    public String soundEvent() {
        return soundEvent;
    }

    /** MIDI note the sample sounds at with pitch 1.0. */
    public int rootMidi() {
        return rootMidi;
    }

    /** Percussion follows the keyboard's pitch too, like a note block does, but has no musical root. */
    public boolean pitched() {
        return pitched;
    }

    public boolean isNoteBlock() {
        return soundEvent != null;
    }

    public String translationKey() {
        return "music.instrument." + id;
    }

    public static Instrument byId(String id) {
        for (Instrument i : values()) if (i.id.equals(id)) return i;
        return GRAND_PIANO;
    }
}
