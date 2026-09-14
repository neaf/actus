package com.actus.push2;

import com.bitwig.extension.controller.ControllerExtension;
import com.bitwig.extension.controller.api.ControllerHost;
import com.bitwig.extension.controller.api.MidiIn;
import com.bitwig.extension.controller.api.MidiOut;

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

    private MidiIn       midiIn;
    private MidiOut      midiOut;
    private Push2Display display;
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

        try
        {
            this.display = new Push2Display(host);
            this.display.showText("Actus");
        }
        catch (final RuntimeException ex)
        {
            host.errorln("Could not connect to the Push 2 display: " + ex.getMessage());
        }

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
}
