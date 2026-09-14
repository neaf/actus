package com.actus.push2;

import java.util.UUID;

import com.bitwig.extension.api.PlatformType;
import com.bitwig.extension.controller.AutoDetectionMidiPortNamesList;
import com.bitwig.extension.controller.ControllerExtensionDefinition;
import com.bitwig.extension.controller.api.ControllerHost;

/**
 * Declares the extension to Bitwig Studio: identity, MIDI port auto-detection and the entry
 * point for creating an instance of {@link Push2ControllerExtension}.
 */
public class Push2ControllerExtensionDefinition extends ControllerExtensionDefinition
{
    private static final UUID EXTENSION_ID = UUID.fromString("8f2c9f2a-6b1e-4b9a-9e2f-3a6b9f7c1d10");

    @Override
    public String getName()
    {
        return "Actus Push 2";
    }

    @Override
    public String getAuthor()
    {
        return "Tomasz Werbicki";
    }

    @Override
    public String getVersion()
    {
        return "0.1.0";
    }

    @Override
    public UUID getId()
    {
        return EXTENSION_ID;
    }

    @Override
    public String getHardwareVendor()
    {
        return "Ableton";
    }

    @Override
    public String getHardwareModel()
    {
        return "Push 2";
    }

    @Override
    public int getRequiredAPIVersion()
    {
        return 21;
    }

    @Override
    public int getNumMidiInPorts()
    {
        return 1;
    }

    @Override
    public int getNumMidiOutPorts()
    {
        return 1;
    }

    @Override
    public void listAutoDetectionMidiPortNames(final AutoDetectionMidiPortNamesList list, final PlatformType platformType)
    {
        switch (platformType)
        {
            case WINDOWS:
                list.add(new String[] { "MIDIIN2 (Ableton Push 2)" }, new String[] { "MIDIOUT2 (Ableton Push 2)" });
                break;

            case MAC:
                list.add(new String[] { "Ableton Push 2 Live Port" }, new String[] { "Ableton Push 2 Live Port" });
                break;

            case LINUX:
                list.add(new String[] { "Ableton Push 2 MIDI 2" }, new String[] { "Ableton Push 2 MIDI 2" });
                break;
        }
    }

    @Override
    public Push2ControllerExtension createInstance(final ControllerHost host)
    {
        return new Push2ControllerExtension(this, host);
    }
}
