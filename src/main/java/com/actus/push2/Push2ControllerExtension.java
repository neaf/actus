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
    private MidiIn  midiIn;
    private MidiOut midiOut;

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

        host.showPopupNotification("Actus Push 2 initialized");
        host.println("Actus Push 2: init() complete");
    }

    @Override
    public void exit()
    {
        this.getHost().showPopupNotification("Actus Push 2 exited");
    }

    @Override
    public void flush()
    {
        // Called after every document/host update. Push LED and display state here.
    }
}
