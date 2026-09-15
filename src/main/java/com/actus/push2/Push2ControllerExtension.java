package com.actus.push2;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.bitwig.extension.controller.ControllerExtension;
import com.bitwig.extension.controller.api.ControllerHost;
import com.bitwig.extension.controller.api.MidiIn;
import com.bitwig.extension.controller.api.MidiOut;
import com.bitwig.extension.controller.api.TrackBank;

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
     * How many scenes are addressable without scrolling. activeScene can point anywhere in
     * this range - reachable via the Up/Down buttons ({@link Push2SceneButtons#onNavigate}) -
     * but there is no scene-bank paging yet.
     */
    static final int MAX_SCENES = 128;

    private MidiIn              midiIn;
    private MidiOut             midiOut;
    private Push2Display        display;
    private Push2ClipLaunchRow  clipLaunchRow;
    private Push2StopRow        stopRow;
    private Push2SceneButtons   sceneButtons;
    private Push2Brightness     brightness;

    /** Pad rows registered for grid dispatch - see {@link #handleMidi} and CLAUDE.md's matrix-region note. */
    private final List<PadRow> padRows = new ArrayList<>();

    /** -1 = no scene made active yet. Controller-local only - see CLAUDE.md. */
    private final AtomicInteger activeScene = new AtomicInteger(-1);
    private volatile boolean running;

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
            this.display.showText("Actus");
        }
        catch (final RuntimeException ex)
        {
            host.errorln("Could not connect to the Push 2 display: " + ex.getMessage());
        }

        final TrackBank trackBank = host.createTrackBank(NUM_TRACKS, 0, MAX_SCENES);
        this.clipLaunchRow = new Push2ClipLaunchRow(this.midiOut, trackBank, this.activeScene);
        this.stopRow = new Push2StopRow(this.midiOut, trackBank);
        this.padRows.add(this.clipLaunchRow);
        this.padRows.add(this.stopRow);
        this.sceneButtons = new Push2SceneButtons(this.midiOut, trackBank.sceneBank(), this.activeScene, () -> {
            this.clipLaunchRow.redrawAll();
            this.sceneButtons.redraw();
        });
        this.sceneButtons.bootstrapWithoutLaunching();
        this.brightness = new Push2Brightness(this.midiOut);

        this.running = true;
        if (this.display != null)
            host.scheduleTask(this::keepDisplayAlive, KEEP_ALIVE_INTERVAL_MS);

        host.showPopupNotification("Actus Push 2 initialized");
        host.println("Actus Push 2: init() complete");
    }

    @Override
    public void exit()
    {
        this.running = false;
        this.getHost().showPopupNotification("Actus Push 2 exited");
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
                this.sceneButtons.onButtonPressed();
        }
        else if (command == 0xB0 && data1 == Push2SceneButtons.STOP_ALL_CC)
        {
            if (data2 > 0 && this.sceneButtons != null)
                this.sceneButtons.onStopAllPressed();
        }
        else if (command == 0xB0 && (data1 == 46 || data1 == 47)) // Up / Down cursor buttons
        {
            if (data2 > 0 && this.sceneButtons != null)
                this.sceneButtons.onNavigate(data1 == 46 ? -1 : 1);
        }
        else if (command == 0xB0 && data1 == Push2Brightness.ENCODER_CC)
        {
            if (this.brightness != null)
                this.brightness.onEncoderTurned(data2);
        }
    }
}
