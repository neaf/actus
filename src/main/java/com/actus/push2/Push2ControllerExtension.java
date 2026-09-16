package com.actus.push2;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.bitwig.extension.controller.ControllerExtension;
import com.bitwig.extension.controller.api.ControllerHost;
import com.bitwig.extension.controller.api.MidiIn;
import com.bitwig.extension.controller.api.MidiOut;
import com.bitwig.extension.controller.api.TrackBank;
import com.bitwig.extension.controller.api.Transport;

/**
 * Extension instance created by Bitwig Studio once the Push 2 is detected. This is the
 * starting point for wiring up pads, encoders, buttons and the display — kept intentionally
 * empty here so it stays fast to read; build outward from {@link #init()}.
 */
public class Push2ControllerExtension extends ControllerExtension
{
    /**
     * Since Bitwig 3.1, flush() only fires when DAW state actually changes - it is not a
     * timer. A static display would go dark a few seconds after its last frame, so this keeps
     * asking the host for another flush call, forever, at this interval.
     */
    private static final long KEEP_ALIVE_INTERVAL_MS = 100;

    private static final int NUM_TRACKS = 8;

    /**
     * How many scenes are addressable. activeScene can point anywhere in this range - synced
     * from whichever scene Bitwig itself reports as selected, see Push2SceneButtons's class doc.
     */
    static final int MAX_SCENES = 128;

    // Push 2's Shift button - monochrome single-LED, held (not toggled) - see handleMidi. Not
    // owned by any one row since it's a cross-cutting modifier; currently only Push2MonitorRow
    // reads it.
    private static final int SHIFT_CC = 49;

    // Push 2's Delete button - monochrome single-LED, held (not toggled), same treatment as
    // Shift above. Push2ClipLaunchRow reads it: hold Delete, hit a clip pad, deletes that slot.
    private static final int DELETE_CC = 118;

    private MidiIn              midiIn;
    private MidiOut             midiOut;
    private Push2Display        display;
    private Push2SessionDisplay sessionDisplay;
    private Push2ClipLaunchRow  clipLaunchRow;
    private Push2StopRow        stopRow;
    private Push2RecordRow      recordRow;
    private Push2ClipJumpRow    clipJumpRow;
    private Push2SceneButtons   sceneButtons;
    private Push2Brightness     brightness;
    private Push2TransportPlay  transportPlay;
    private Push2MonitorRow     monitorRow;

    /** Pad rows registered for grid dispatch - see {@link #handleMidi} and CLAUDE.md's matrix-region note. */
    private final List<PadRow> padRows = new ArrayList<>();

    /** -1 = no scene made active yet. Controller-local only - see CLAUDE.md. */
    private final AtomicInteger activeScene = new AtomicInteger(-1);

    /** -1 = nothing launched yet. Separate from activeScene - see Push2SceneButtons's class doc. */
    private final AtomicInteger playingScene = new AtomicInteger(-1);
    private volatile boolean running;
    private volatile boolean shiftHeld;
    private volatile boolean deleteHeld;

    protected Push2ControllerExtension(final Push2ControllerExtensionDefinition definition, final ControllerHost host)
    {
        super(definition, host);
    }

    @Override
    public void init()
    {
        final ControllerHost host = this.getHost();

        this.midiIn = host.getMidiInPort(0);
        this.midiOut = host.getMidiOutPort(0);
        this.midiIn.setMidiCallback(this::handleMidi);
        Push2Palette.write(this.midiOut);

        try
        {
            this.display = new Push2Display(host);
        }
        catch (final RuntimeException ex)
        {
            host.errorln("Could not connect to the Push 2 display: " + ex.getMessage());
        }

        // createMainTrackBank (not createTrackBank) excludes effect tracks and the master
        // track - only audio/instrument/hybrid tracks, which is all this controller launches
        // clips on or arms for recording.
        final TrackBank trackBank = host.createMainTrackBank(NUM_TRACKS, 0, MAX_SCENES);

        // A second, otherwise-unused TrackBank windowed to exactly 1 scene - purely so its
        // bank-level indication (see Push2SceneButtons's class doc) frames exactly one row on
        // Bitwig's own Clip Launcher, kept scrolled to activeScene. Deliberately separate from
        // the main trackBank above, which stays 128-wide for direct scene addressing.
        final TrackBank indicationBank = host.createMainTrackBank(NUM_TRACKS, 0, 1);

        // Push2RecordRow manages Transport.isClipLauncherOverdubEnabled() itself, turning it on
        // only while it actually has a track armed for overdub - not set here as an always-on
        // global flag (Transport.setLauncherOverdub(boolean) throws at runtime if you're
        // tempted to reach for it instead - deprecated since API v2, same gotcha as
        // setShouldSendMidiBeatClock, see CLAUDE.md - always go through the
        // SettableBooleanValue).
        final Transport transport = host.createTransport();

        this.recordRow = new Push2RecordRow(host, this.midiOut, trackBank, this.activeScene, transport);
        this.clipLaunchRow = new Push2ClipLaunchRow(this.midiOut, trackBank, this.activeScene, this.recordRow, () -> this.deleteHeld);
        this.stopRow = new Push2StopRow(this.midiOut, trackBank);
        this.clipJumpRow = new Push2ClipJumpRow(this.midiOut, trackBank, this.activeScene, this.clipLaunchRow);
        this.padRows.add(this.clipLaunchRow);
        this.padRows.add(this.stopRow);
        this.padRows.add(this.recordRow);
        this.padRows.add(this.clipJumpRow.triggerRow());
        for (int slotIndex = 0; slotIndex < 4; slotIndex++)
            this.padRows.add(this.clipJumpRow.slotRow(slotIndex));
        if (this.display != null)
            this.sessionDisplay = new Push2SessionDisplay(host, this.display, trackBank, this.activeScene, this.playingScene);
        this.sceneButtons = new Push2SceneButtons(this.midiOut, trackBank, indicationBank, this.activeScene, this.playingScene, () -> {
            this.clipLaunchRow.redrawAll();
            this.recordRow.redrawAll();
            this.clipJumpRow.redrawAll();
            this.sceneButtons.redraw();
            if (this.sessionDisplay != null)
                this.sessionDisplay.redraw();
            if (this.transportPlay != null) // not yet constructed during the first bootstrap call below
                this.transportPlay.redraw();
        });
        this.sceneButtons.bootstrapWithoutLaunching();
        this.brightness = new Push2Brightness(this.midiOut);
        this.monitorRow = new Push2MonitorRow(this.midiOut, trackBank, () -> this.shiftHeld);
        this.midiOut.sendMidi(0xB0, SHIFT_CC, 127); // always lit, visible in the dark - same treatment as Octave Up/Down / Stop All Clips
        this.midiOut.sendMidi(0xB0, DELETE_CC, 127); // same - always lit

        this.transportPlay = new Push2TransportPlay(this.midiOut, this.sceneButtons, this.playingScene);
        this.transportPlay.redraw();

        this.running = true;
        if (this.display != null)
            host.scheduleTask(this::keepDisplayAlive, KEEP_ALIVE_INTERVAL_MS);

        host.showPopupNotification("Actus Push 2 Controller initialized");
        host.println("Actus Push 2 Controller: init() complete");
    }

    @Override
    public void exit()
    {
        this.running = false;
        if (this.display != null)
            this.display.shutdown();
        this.getHost().showPopupNotification("Actus Push 2 Controller exited");
    }

    @Override
    public void flush()
    {
        if (this.display != null)
            this.display.refresh();
    }

    private void keepDisplayAlive()
    {
        if (!this.running)
            return;

        final ControllerHost host = this.getHost();
        host.requestFlush();
        host.scheduleTask(this::keepDisplayAlive, KEEP_ALIVE_INTERVAL_MS);
    }

    /**
     * Single entry point for all raw MIDI input - Bitwig only allows one callback per MidiIn,
     * so everything (grid pads, scene buttons, and later encoders/other buttons) routes
     * through here rather than each feature registering its own callback.
     */
    private void handleMidi(final int status, final int data1, final int data2)
    {
        final int command = status & 0xF0;
        final int channel = status & 0x0F;
        if (channel != 0)
            return;

        if (command == 0x90 || command == 0x80)
        {
            final int velocity = command == 0x80 ? 0 : data2;
            for (final PadRow row : this.padRows)
            {
                if (data1 >= row.startNote() && data1 < row.startNote() + NUM_TRACKS)
                {
                    row.onPadPressed(data1 - row.startNote(), velocity);
                    break;
                }
            }
        }
        else if (command == 0xB0 && data1 == Push2SceneButtons.LAUNCH_CC)
        {
            if (data2 > 0 && this.sceneButtons != null)
            {
                this.sceneButtons.onButtonPressed();
                // Launching a scene doesn't reliably flip isRecording for an overdubbing clip
                // within it (see Push2RecordRow's class doc) - ask it to finish explicitly.
                if (this.recordRow != null)
                    this.recordRow.finishAll();
            }
        }
        else if (command == 0xB0 && data1 == Push2SceneButtons.STOP_ALL_CC)
        {
            if (data2 > 0 && this.sceneButtons != null)
                this.sceneButtons.onStopAllPressed();
        }
        else if (command == 0xB0 && data1 == Push2Brightness.ENCODER_CC)
        {
            if (this.brightness != null)
                this.brightness.onEncoderTurned(data2);
        }
        else if (command == 0xB0 && data1 == Push2TransportPlay.PLAY_CC)
        {
            if (data2 > 0 && this.transportPlay != null)
                this.transportPlay.onButtonPressed();
        }
        else if (command == 0xB0 && data1 >= Push2MonitorRow.CC_START && data1 < Push2MonitorRow.CC_START + NUM_TRACKS)
        {
            if (this.monitorRow != null)
                this.monitorRow.onButtonEvent(data1 - Push2MonitorRow.CC_START, data2 > 0);
        }
        else if (command == 0xB0 && data1 == SHIFT_CC)
        {
            // Held, not toggled - a Note On style press/release pair via CC (data2 > 0 = held).
            // LED stays always lit (see init()) so it's visible in the dark - held state is
            // tracked in shiftHeld only, not reflected on the button's own LED.
            this.shiftHeld = data2 > 0;
        }
        else if (command == 0xB0 && data1 == DELETE_CC)
        {
            // Same held-not-toggled, always-lit treatment as Shift above.
            this.deleteHeld = data2 > 0;
        }
    }
}
