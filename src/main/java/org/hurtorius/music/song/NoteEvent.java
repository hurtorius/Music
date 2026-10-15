package org.hurtorius.music.song;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

import java.util.ArrayList;
import java.util.List;

/**
 * One live performance event, as sent between players.
 *
 * @param timeMs time on the performer's session clock (ms since they sat down); receivers keep the spacing
 * @param kind   {@link #NOTE_OFF}, {@link #NOTE_ON} or {@link #PEDAL}
 * @param data1  key 0..87 for notes; pedal type ({@link Song#PEDAL_SUSTAIN} etc.) for pedals
 * @param data2  velocity 1..127 for note on; 0 for note off; 0..127 for pedals (64+ = down)
 */
public record NoteEvent(int timeMs, int kind, int data1, int data2) {
    public static final int NOTE_OFF = 0, NOTE_ON = 1, PEDAL = 2;
    public static final int MAX_PER_PACKET = 256;

    public static final StreamCodec<FriendlyByteBuf, List<NoteEvent>> LIST_CODEC = StreamCodec.of((buf, list) -> {
        int n = Math.min(list.size(), MAX_PER_PACKET);
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            NoteEvent e = list.get(i);
            buf.writeVarInt(Math.max(0, e.timeMs));
            buf.writeByte(e.kind);
            buf.writeByte(e.data1);
            buf.writeByte(e.data2);
        }
    }, buf -> {
        int n = buf.readVarInt();
        if (n < 0 || n > MAX_PER_PACKET) throw new IllegalArgumentException("Too many note events: " + n);
        List<NoteEvent> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            NoteEvent e = new NoteEvent(buf.readVarInt(), buf.readUnsignedByte(), buf.readUnsignedByte(), buf.readUnsignedByte());
            if (e.valid()) list.add(e);
        }
        return list;
    });

    public boolean valid() {
        return timeMs >= 0 && switch (kind) {
            case NOTE_OFF -> data1 >= 0 && data1 <= 87;
            case NOTE_ON -> data1 >= 0 && data1 <= 87 && data2 >= 1 && data2 <= 127;
            case PEDAL -> data1 >= 0 && data1 <= 2 && data2 >= 0 && data2 <= 127;
            default -> false;
        };
    }

    public static NoteEvent on(int timeMs, int key, int velocity) {
        return new NoteEvent(timeMs, NOTE_ON, key, Math.max(1, Math.min(127, velocity)));
    }

    public static NoteEvent off(int timeMs, int key) {
        return new NoteEvent(timeMs, NOTE_OFF, key, 0);
    }

    public static NoteEvent pedal(int timeMs, int type, int value) {
        return new NoteEvent(timeMs, PEDAL, type, Math.max(0, Math.min(127, value)));
    }
}
