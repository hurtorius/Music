package org.hurtorius.music.client.midi;

/**
 * A MIDI input or output the player can choose.
 *
 * @param name   unique within its list ("Keyboard", "Keyboard (2)" for a second identical one); this
 *               is what {@code ClientPrefs.midiInput}/{@code midiOutput} store
 * @param detail vendor and description from the driver, or "" when it gives none
 */
public record MidiPort(String name, String detail) {}
