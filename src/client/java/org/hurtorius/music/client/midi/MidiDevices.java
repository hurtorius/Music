package org.hurtorius.music.client.midi;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.Minecraft;
import org.hurtorius.music.client.ClientPrefs;
import org.hurtorius.music.piano.Pedal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.ShortMessage;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The player's real MIDI instruments: a keyboard (USB, or Bluetooth where the system offers it as a
 * MIDI port; see {@link #bluetoothHint(String)}) that plays the piano, and an output (a DAW, a synth, a recorder) that receives what is
 * played at the piano. Device names come from {@link ClientPrefs#midiInput} and
 * {@link ClientPrefs#midiOutput}.
 *
 * <p>Everything that touches devices runs on one daemon thread, {@code music-midi}, which also
 * rescans every {@value #SCAN_SECONDS} seconds: unplugged devices are noticed and reported, and a
 * chosen device that is plugged in (again) connects by itself. Java's own synthesizer and
 * sequencer are never used: sound always comes from the piano's engine. On macOS Java only sees
 * devices that were connected when the game started (JDK-8139153); see
 * {@link #newDevicesNeedRestart()}.</p>
 */
public final class MidiDevices {
    private static final Logger LOG = LoggerFactory.getLogger("Music");
    private static final long SCAN_SECONDS = 2;
    private static final int OUTPUT_CHANNEL = 0;
    private static final int CC_ALL_NOTES_OFF = 123;

    private static final ScheduledExecutorService WORKER = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "music-midi");
        thread.setDaemon(true);
        // Java Sound looks its device providers up through the context class loader on every
        // listing; its own providers live in java.desktop, so spare it a search of every mod jar.
        thread.setContextClassLoader(ClassLoader.getPlatformClassLoader());
        return thread;
    });
    /** Device objects by their info, so Java Sound's sequencer and synthesizer are created once, not per scan. Worker only. */
    private static final Map<MidiDevice.Info, MidiDevice> DEVICES = new IdentityHashMap<>();
    private static final MidiPorts PORTS = new MidiPorts(MidiDevices::systemDevices, new Forwarder(), MidiDevices::shapeVelocity, MidiDevices::scanSoon);
    private static volatile MidiListener listener;
    private static volatile ClientPrefs prefs;
    private static boolean bootstrapped;

    private MidiDevices() {}

    /** From {@code MusicClient}: first scan, connect the devices in the preferences, rescan periodically, close on exit. */
    public static void bootstrap() {
        if (bootstrapped) return;
        bootstrapped = true;
        ClientPrefs p = ClientPrefs.get();
        prefs = p;
        String input = p.midiInput, output = p.midiOutput;
        submit(() -> {
            PORTS.scan();
            String in = PORTS.useInput(input).name(), out = PORTS.useOutput(output).name();
            // A choice saved under a name its device is no longer listed by (one with Linux's card
            // number, from before Music dropped it) is saved again under the name the Devices tab shows.
            if (!in.equals(input) || !out.equals(output)) Minecraft.getInstance().execute(() -> renamed(p, input, in, output, out));
            return null;
        });
        WORKER.scheduleWithFixedDelay(MidiDevices::scanLogged, SCAN_SECONDS, SCAN_SECONDS, TimeUnit.SECONDS);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> shutdown());
        if (newDevicesNeedRestart()) {
            LOG.info("MIDI: on macOS, devices connected after the game started appear only after a restart (JDK-8139153)");
        }
    }

    /** Saves the names the chosen devices are listed by now, unless the player chose others meanwhile. Client thread. */
    private static void renamed(ClientPrefs p, String oldInput, String newInput, String oldOutput, String newOutput) {
        boolean changed = false;
        if (p.midiInput.equals(oldInput) && !newInput.equals(oldInput)) {
            p.midiInput = newInput;
            changed = true;
        }
        if (p.midiOutput.equals(oldOutput) && !newOutput.equals(oldOutput)) {
            p.midiOutput = newOutput;
            changed = true;
        }
        if (changed) p.save();
    }

    /** Who receives the input's events, or null for nobody. See {@link MidiListener} for the threads. */
    public static void setListener(MidiListener newListener) {
        listener = newListener;
    }

    /** Lists the devices again now. Completes on the {@code music-midi} thread. */
    public static CompletableFuture<Void> rescan() {
        return submit(() -> {
            PORTS.scan();
            return null;
        });
    }

    /**
     * Connects the input with this name ("" disconnects). The caller stores the choice in
     * {@link ClientPrefs#midiInput}. Completes on the {@code music-midi} thread with the new state.
     */
    public static CompletableFuture<MidiPorts.State> useInput(String name) {
        return submit(() -> PORTS.useInput(name));
    }

    /** Like {@link #useInput} for the output ({@link ClientPrefs#midiOutput}). */
    public static CompletableFuture<MidiPorts.State> useOutput(String name) {
        return submit(() -> PORTS.useOutput(name));
    }

    /** Inputs found by the last scan. */
    public static List<MidiPort> inputs() {
        return PORTS.inputs();
    }

    /** Outputs found by the last scan. */
    public static List<MidiPort> outputs() {
        return PORTS.outputs();
    }

    public static MidiPorts.State inputState() {
        return PORTS.inputState();
    }

    public static MidiPorts.State outputState() {
        return PORTS.outputState();
    }

    /** Changes whenever the lists or a connection change; screens refresh when it does. */
    public static int revision() {
        return PORTS.revision();
    }

    /** Sends a key press (MIDI note 0..127, velocity 1..127) to the output. False if no output took it. */
    public static boolean noteOn(int midi, int velocity) {
        return PORTS.send(ShortMessage.NOTE_ON | OUTPUT_CHANNEL, clamp(midi, 0, 127), clamp(velocity, 1, 127));
    }

    /** Sends a key release to the output. */
    public static boolean noteOff(int midi) {
        return PORTS.send(ShortMessage.NOTE_OFF | OUTPUT_CHANNEL, clamp(midi, 0, 127), 64);
    }

    /** Sends a pedal change (0..127, 64 and above is down) to the output. */
    public static boolean pedal(Pedal pedal, int value) {
        return PORTS.send(ShortMessage.CONTROL_CHANGE | OUTPUT_CHANNEL, pedal.controller(), clamp(value, 0, 127));
    }

    /** Releases everything on the output: all notes off, then every pedal up. */
    public static boolean allNotesOff() {
        boolean sent = PORTS.send(ShortMessage.CONTROL_CHANGE | OUTPUT_CHANNEL, CC_ALL_NOTES_OFF, 0);
        for (Pedal p : Pedal.values()) sent &= pedal(p, 0);
        return sent;
    }

    /** The Devices tab's hint about Bluetooth keyboards on this system. */
    public static String bluetoothHint() {
        return bluetoothHint(System.getProperty("os.name", ""));
    }

    /**
     * The translation key of the Bluetooth hint for a system. Java Sound sees what the system offers
     * as plain MIDI ports: CoreMIDI devices on macOS (a Bluetooth keyboard becomes one once it is
     * connected in Audio MIDI Setup), WinMM ports on Windows and ALSA hardware ports on Linux, which
     * a Bluetooth MIDI keyboard does not become by itself.
     */
    public static String bluetoothHint(String osName) {
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.startsWith("mac")) return "music.screen.midi.bluetooth.mac";
        if (os.startsWith("windows")) return "music.screen.midi.bluetooth.windows";
        if (os.startsWith("linux")) return "music.screen.midi.bluetooth.linux";
        return "music.screen.midi.bluetooth.other";
    }

    /** True on macOS, where Java lists only the MIDI devices present when the game started. */
    public static boolean newDevicesNeedRestart() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac");
    }

    private static void shutdown() {
        try {
            submit(() -> {
                PORTS.close();
                return null;
            }).get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.warn("MIDI devices did not close cleanly", e);
        } finally {
            WORKER.shutdownNow();
        }
    }

    private static <T> CompletableFuture<T> submit(Supplier<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            WORKER.execute(() -> {
                try {
                    future.complete(task.get());
                } catch (Throwable t) {
                    LOG.error("MIDI device task failed", t);
                    future.completeExceptionally(t);
                }
            });
        } catch (RuntimeException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    private static void scanLogged() {
        // A periodic task that throws is cancelled silently, so nothing may escape.
        try {
            PORTS.scan();
        } catch (Throwable t) {
            LOG.error("MIDI device scan failed", t);
        }
    }

    private static void scanSoon() {
        submit(() -> {
            PORTS.scan();
            return null;
        });
    }

    /** Every device Java Sound offers. Runs on the worker. */
    private static List<MidiDevice> systemDevices() {
        MidiDevice.Info[] infos = MidiSystem.getMidiDeviceInfo();
        List<MidiDevice> devices = new ArrayList<>(infos.length);
        Map<MidiDevice.Info, MidiDevice> current = new IdentityHashMap<>();
        for (MidiDevice.Info info : infos) {
            MidiDevice device = DEVICES.get(info);
            if (device == null) {
                try {
                    device = MidiSystem.getMidiDevice(info);
                } catch (MidiUnavailableException | IllegalArgumentException e) {
                    LOG.debug("Skipping MIDI device {}: {}", info.getName(), e.toString());
                    continue;
                }
            }
            current.put(info, device);
            devices.add(device);
        }
        DEVICES.clear();
        DEVICES.putAll(current);
        return devices;
    }

    private static int shapeVelocity(int velocity) {
        ClientPrefs p = prefs;
        return p == null ? velocity : VelocityCurve.byName(p.velocityCurve).apply(velocity, p.keyVelocity);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Hands input events to whichever listener is current. */
    private static final class Forwarder implements MidiListener {
        @Override
        public void noteOn(int channel, int midi, int velocity) {
            MidiListener l = listener;
            if (l != null) l.noteOn(channel, midi, velocity);
        }

        @Override
        public void noteOff(int channel, int midi) {
            MidiListener l = listener;
            if (l != null) l.noteOff(channel, midi);
        }

        @Override
        public void pedal(int channel, Pedal pedal, int value) {
            MidiListener l = listener;
            if (l != null) l.pedal(channel, pedal, value);
        }

        @Override
        public void control(int channel, int controller, int value) {
            MidiListener l = listener;
            if (l != null) l.control(channel, controller, value);
        }

        @Override
        public void allNotesOff(int channel) {
            MidiListener l = listener;
            if (l != null) l.allNotesOff(channel);
        }

        @Override
        public void deviceLost(String name, boolean input) {
            MidiListener l = listener;
            if (l != null) l.deviceLost(name, input);
        }
    }
}
