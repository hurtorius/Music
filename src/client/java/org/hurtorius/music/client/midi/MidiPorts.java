package org.hurtorius.music.client.midi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Receiver;
import javax.sound.midi.Sequencer;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Synthesizer;
import javax.sound.midi.Transmitter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The MIDI devices available from a source, plus the one input and one output in use.
 *
 * <p>Inputs are devices with transmitters, outputs devices with receivers; Java's own sequencer and
 * synthesizer are never listed or opened. Devices alike are told apart as "Keystation (2)", and each
 * keeps its name while it stays plugged in, so the choice stays with the device it was made for when
 * another alike comes or goes. A chosen device that is missing is connected as soon as
 * a scan finds it; a connected device that disappears from a scan, or an output that stops
 * accepting messages, is closed, reported through {@link MidiListener#deviceLost} and reconnected
 * when it comes back. Whenever an input closes (switched, set to none, lost), the keys and pedals it
 * still held are first reported released. Failures to open (a device busy in another program) are kept in the
 * {@link State} for the screen to show and retried on every scan.</p>
 *
 * <p>Threads: {@link #scan}, {@link #useInput}, {@link #useOutput} and {@link #close} belong to one
 * management thread and must never run on the MIDI input thread (closing an input waits for that
 * thread). {@link #send} and the getters may be called from any thread.</p>
 */
public final class MidiPorts {
    private static final Logger LOG = LoggerFactory.getLogger("Music");
    /** The ALSA address Java appends to a port's name on Linux: card, device and (for MIDI) subdevice. */
    private static final Pattern ALSA_PORT = Pattern.compile("\\s*\\[(?:plug)?hw:\\d+,(\\d+)(?:,(\\d+))?]$");

    /** Where the input or output stands. */
    public enum Status {
        /** Nothing chosen. */
        NONE,
        /** Open and working. */
        CONNECTED,
        /** Chosen but not present; it connects when it appears. */
        MISSING,
        /** Present but could not be opened (often: in use by another program); retried on each scan. */
        UNAVAILABLE,
        /** Was connected, then went away; it reconnects when it comes back. */
        LOST;

        public String translationKey() {
            return "music.midi.status." + name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * @param name   the chosen device, or "" for none
     * @param status where it stands
     * @param detail the driver's error message for {@link Status#UNAVAILABLE}/{@link Status#LOST}, else ""
     */
    public record State(String name, Status status, String detail) {
        static final State NONE = new State("", Status.NONE, "");
    }

    private final Supplier<List<MidiDevice>> source;
    private final MidiListener listener;
    private final IntUnaryOperator velocity;
    private final Runnable onOutputFailure;
    private final Endpoint input = new Endpoint(true), output = new Endpoint(false);
    private final AtomicInteger revision = new AtomicInteger();
    private volatile List<MidiPort> inputs = List.of(), outputs = List.of();
    private Map<String, MidiDevice> inputDevices = Map.of(), outputDevices = Map.of();
    /** The listed name of each device by the name the system gives it (the first, where several share one). */
    private Map<String, String> inputsBySystemName = Map.of(), outputsBySystemName = Map.of();
    private boolean scanned;
    private volatile Receiver outputReceiver;
    private volatile Receiver failedReceiver;

    /**
     * @param source          lists the system's devices (called on the management thread)
     * @param listener        receives input events and lost devices
     * @param velocity        shapes note-on velocities from the input
     * @param onOutputFailure called (on the sending thread) the first time the output rejects a
     *                        message, so the owner can scan soon
     */
    public MidiPorts(Supplier<List<MidiDevice>> source, MidiListener listener, IntUnaryOperator velocity, Runnable onOutputFailure) {
        this.source = source;
        this.listener = listener;
        this.velocity = velocity;
        this.onOutputFailure = onOutputFailure;
    }

    public List<MidiPort> inputs() {
        return inputs;
    }

    public List<MidiPort> outputs() {
        return outputs;
    }

    public State inputState() {
        return input.state;
    }

    public State outputState() {
        return output.state;
    }

    /** Changes whenever the device lists or a connection state change, for screens to refresh. */
    public int revision() {
        return revision.get();
    }

    /** Lists the devices again, then closes what went away and connects what was waiting for its device. */
    public synchronized void scan() {
        List<MidiDevice> devices;
        try {
            devices = source.get();
        } catch (RuntimeException e) {
            LOG.warn("Could not list MIDI devices", e);
            return;
        }
        List<MidiDevice> in = new ArrayList<>(), out = new ArrayList<>();
        for (MidiDevice device : devices) {
            if (device instanceof Sequencer || device instanceof Synthesizer) continue;
            if (device.getMaxTransmitters() != 0) in.add(device);
            if (device.getMaxReceivers() != 0) out.add(device);
        }
        Map<String, MidiDevice> ins = named(in, inputDevices), outs = named(out, outputDevices);
        List<MidiPort> inList = ports(ins), outList = ports(outs);
        boolean changed = !inList.equals(inputs) || !outList.equals(outputs);
        inputDevices = ins;
        outputDevices = outs;
        inputsBySystemName = bySystemName(ins);
        outputsBySystemName = bySystemName(outs);
        inputs = List.copyOf(inList);
        outputs = List.copyOf(outList);
        if (changed || !scanned) {
            LOG.info("MIDI inputs: {}; MIDI outputs: {}", names(inList), names(outList));
            revision.incrementAndGet();
        }
        scanned = true;
        reconcile(input, ins);
        reconcile(output, outs);
    }

    /**
     * Uses the input with this name ("" for none). Returns the resulting state, named as the device is
     * listed: a choice saved under the name the system gives the device is listed by another name
     * ("Keystation [hw:2,0,0]" is "Keystation", or "Keystation (2)" next to another alike), which the
     * caller saves from then on.
     */
    public synchronized State useInput(String name) {
        return use(input, name);
    }

    /** Sends what is played to the output with this name ("" for none). Returns the resulting state, named as {@link #useInput} says. */
    public synchronized State useOutput(String name) {
        return use(output, name);
    }

    /** Closes the input and the output; the choices are kept for a later scan. */
    public synchronized void close() {
        release(input);
        release(output);
        input.state = input.wanted.isEmpty() ? State.NONE : new State(input.wanted, Status.MISSING, "");
        output.state = output.wanted.isEmpty() ? State.NONE : new State(output.wanted, Status.MISSING, "");
        revision.incrementAndGet();
    }

    /**
     * Sends a channel message to the output. Returns false when no output is connected or it
     * rejected the message (it is then reported lost by the next scan).
     *
     * @throws IllegalArgumentException if the message itself is invalid
     */
    public boolean send(int status, int data1, int data2) {
        Receiver receiver = outputReceiver;
        if (receiver == null) return false;
        ShortMessage message;
        try {
            message = new ShortMessage(status, data1, data2);
        } catch (InvalidMidiDataException e) {
            throw new IllegalArgumentException("Invalid MIDI message " + status + " " + data1 + " " + data2, e);
        }
        try {
            receiver.send(message, -1);
            return true;
        } catch (RuntimeException e) {
            if (failedReceiver != receiver) {
                failedReceiver = receiver;
                LOG.warn("MIDI output {} stopped accepting messages", output.wanted, e);
                onOutputFailure.run();
            }
            return false;
        }
    }

    private State use(Endpoint end, String name) {
        if (!scanned) scan();
        String wanted = name == null || name.isBlank() ? "" : listedName(end, name.strip());
        if (wanted.equals(end.wanted) && end.device != null) return end.state;
        release(end);
        end.wanted = wanted;
        end.failureLogged = false;
        if (wanted.isEmpty()) {
            end.state = State.NONE;
        } else {
            MidiDevice device = (end.input ? inputDevices : outputDevices).get(wanted);
            if (device == null) end.state = new State(wanted, Status.MISSING, "");
            else open(end, device);
        }
        revision.incrementAndGet();
        return end.state;
    }

    private void reconcile(Endpoint end, Map<String, MidiDevice> available) {
        if (end.device != null) {
            boolean broken = !end.input && failedReceiver != null && failedReceiver == end.receiver;
            boolean gone = available.get(end.wanted) != end.device;
            if (!broken && !gone) return;
            String detail = broken ? "It stopped accepting messages" : "It was disconnected";
            release(end);
            end.state = new State(end.wanted, Status.LOST, detail);
            revision.incrementAndGet();
            LOG.warn("MIDI {} {} lost: {}", end.input ? "input" : "output", end.wanted, detail);
            try {
                listener.deviceLost(end.wanted, end.input);
            } catch (RuntimeException e) {
                LOG.error("MIDI listener failed on a lost device", e);
            }
            if (broken) return;
        }
        if (end.wanted.isEmpty()) return;
        MidiDevice device = available.get(end.wanted);
        if (device != null) open(end, device);
        else if (end.state.status() != Status.LOST && end.state.status() != Status.MISSING) {
            end.state = new State(end.wanted, Status.MISSING, "");
            revision.incrementAndGet();
        }
    }

    private void open(Endpoint end, MidiDevice device) {
        boolean openedHere = false;
        try {
            if (!device.isOpen()) {
                device.open();
                openedHere = true;
            }
            if (end.input) {
                MidiDispatch dispatch = new MidiDispatch(listener, velocity);
                Transmitter transmitter = device.getTransmitter();
                transmitter.setReceiver(dispatch);
                end.transmitter = transmitter;
                end.dispatch = dispatch;
            } else {
                Receiver receiver = device.getReceiver();
                end.receiver = receiver;
                outputReceiver = receiver;
            }
            end.device = device;
            end.state = new State(end.wanted, Status.CONNECTED, "");
            end.failureLogged = false;
            revision.incrementAndGet();
            LOG.info("MIDI {} connected: {}", end.input ? "input" : "output", end.wanted);
        } catch (MidiUnavailableException | RuntimeException e) {
            if (openedHere && !sharedWithOther(end, device)) closeQuietly(device);
            String detail = e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
            State failed = new State(end.wanted, Status.UNAVAILABLE, detail);
            if (!failed.equals(end.state)) revision.incrementAndGet();
            end.state = failed;
            if (!end.failureLogged) {
                LOG.warn("MIDI {} {} could not be opened: {}", end.input ? "input" : "output", end.wanted, detail);
                end.failureLogged = true;
            }
        }
    }

    /** Closes what the endpoint holds; the device itself stays open if the other endpoint uses it too. */
    private void release(Endpoint end) {
        if (end.dispatch != null) end.dispatch.close();
        if (end.transmitter != null) {
            try {
                end.transmitter.close();
            } catch (RuntimeException e) {
                LOG.debug("Closing a MIDI transmitter failed", e);
            }
        }
        if (end.receiver != null) {
            if (outputReceiver == end.receiver) outputReceiver = null;
            try {
                end.receiver.close();
            } catch (RuntimeException e) {
                LOG.debug("Closing a MIDI receiver failed", e);
            }
        }
        if (end.device != null && !sharedWithOther(end, end.device)) closeQuietly(end.device);
        end.device = null;
        end.transmitter = null;
        end.dispatch = null;
        end.receiver = null;
    }

    private boolean sharedWithOther(Endpoint end, MidiDevice device) {
        return (end == input ? output : input).device == device;
    }

    private static void closeQuietly(MidiDevice device) {
        try {
            device.close();
        } catch (RuntimeException e) {
            LOG.debug("Closing MIDI device {} failed", device.getDeviceInfo().getName(), e);
        }
    }

    /**
     * Names {@code devices}, in their order: a device keeps the name it had in the last scan
     * ({@code before}) for as long as it stays listed, so unplugging one of two alike, or plugging in
     * another, never moves a name, and with it the player's choice, from one to the other. A device
     * new to the list takes its own name, or the first of "(2)", "(3)" ... after it that is free. So
     * one plugged back in gets back the name it left while the other alike stays plugged in; once both
     * were unplugged nothing tells them apart (the system gives a device plugged back in a new port),
     * and the first one back takes the first name.
     */
    private static Map<String, MidiDevice> named(List<MidiDevice> devices, Map<String, MidiDevice> before) {
        Map<MidiDevice, String> kept = new IdentityHashMap<>();
        before.forEach((name, device) -> kept.put(device, name));
        Set<String> taken = new HashSet<>();
        for (MidiDevice device : devices) {
            String name = kept.get(device);
            if (name != null) taken.add(name);
        }
        Map<String, MidiDevice> named = new LinkedHashMap<>();
        for (MidiDevice device : devices) {
            String name = kept.get(device);
            if (name == null) {
                String base = cleanName(device.getDeviceInfo().getName());
                name = base;
                for (int n = 2; taken.contains(name); n++) name = base + " (" + n + ")";
                taken.add(name);
            }
            named.put(name, device);
        }
        return named;
    }

    private static List<MidiPort> ports(Map<String, MidiDevice> named) {
        List<MidiPort> ports = new ArrayList<>(named.size());
        named.forEach((name, device) -> ports.add(new MidiPort(name, detail(device.getDeviceInfo()))));
        return ports;
    }

    /** The listed name of each device by the name the system gives it (the first, where several share one). */
    private static Map<String, String> bySystemName(Map<String, MidiDevice> named) {
        Map<String, String> listed = new HashMap<>();
        named.forEach((name, device) -> listed.putIfAbsent(systemName(device.getDeviceInfo().getName()), name));
        return listed;
    }

    /**
     * The listed name a choice stands for: itself when a device is listed by it; the device whose system
     * name it is, when it was saved as one (which tells two alike apart by their card numbers); else
     * the choice without the parts of a system name that change from one session to the next.
     */
    private String listedName(Endpoint end, String chosen) {
        if ((end.input ? inputDevices : outputDevices).containsKey(chosen)) return chosen;
        String listed = (end.input ? inputsBySystemName : outputsBySystemName).get(chosen);
        return listed != null ? listed : stableName(chosen);
    }

    private static String systemName(String raw) {
        return raw == null ? "" : raw.replaceAll("\\p{Cntrl}", "").strip();
    }

    private static String cleanName(String raw) {
        String name = stableName(systemName(raw));
        return name.isEmpty() ? "MIDI device" : name;
    }

    /**
     * A port's name without the part that changes from one session to the next. Linux names a port
     * "card [hw:C,D,S]", and C is the order the sound cards were found in, so a headset plugged in
     * first moves a keyboard to another number; the name keeps the card and, past its first port,
     * which port it is.
     */
    private static String stableName(String name) {
        Matcher m = ALSA_PORT.matcher(name);
        if (!m.find()) return name;
        String device = m.group(1), subdevice = m.group(2) == null ? "0" : m.group(2);
        String card = name.substring(0, m.start()).strip();
        return device.equals("0") && subdevice.equals("0") ? card : card + " (port " + device + "." + subdevice + ")";
    }

    private static String detail(MidiDevice.Info info) {
        List<String> parts = new ArrayList<>();
        String vendor = info.getVendor() == null ? "" : info.getVendor().strip();
        String description = info.getDescription() == null ? "" : info.getDescription().strip();
        if (!vendor.isEmpty() && !vendor.equalsIgnoreCase("Unknown vendor")) parts.add(vendor);
        if (!description.isEmpty() && !description.equalsIgnoreCase("No details available")) parts.add(description);
        return String.join(", ", parts);
    }

    private static List<String> names(List<MidiPort> ports) {
        return ports.stream().map(MidiPort::name).toList();
    }

    /** The input or the output: what was chosen and what is open for it. */
    private static final class Endpoint {
        final boolean input;
        String wanted = "";
        volatile State state = State.NONE;
        MidiDevice device;
        Transmitter transmitter;
        MidiDispatch dispatch;
        Receiver receiver;
        boolean failureLogged;

        Endpoint(boolean input) {
            this.input = input;
        }
    }
}
