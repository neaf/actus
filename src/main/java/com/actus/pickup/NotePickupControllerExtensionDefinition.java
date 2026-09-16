package com.actus.pickup;

import java.util.UUID;

import com.bitwig.extension.api.PlatformType;
import com.bitwig.extension.controller.AutoDetectionMidiPortNamesList;
import com.bitwig.extension.controller.ControllerExtensionDefinition;
import com.bitwig.extension.controller.api.ControllerHost;

/**
 * Declares the Record Note Pickup extension to Bitwig Studio. Unlike
 * {@code Push2ControllerExtensionDefinition}, this has no auto-detection and no hardware device -
 * it isn't tied to one specific piece of hardware. The user adds it manually (Settings ->
 * Controllers -> Add controller manually) and points its MIDI input at whatever keyboard they
 * want this behavior on.
 */
public class NotePickupControllerExtensionDefinition extends ControllerExtensionDefinition
{
    private static final UUID EXTENSION_ID = UUID.fromString("e17dfe6a-c60e-490f-bdfd-c4cace0ff058");

    @Override
    public String getName()
    {
        return "Actus Record Note Pickup";
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
        return "Actus";
    }

    @Override
    public String getHardwareModel()
    {
        return "Record Note Pickup";
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
        return 0;
    }

    @Override
    public void listAutoDetectionMidiPortNames(final AutoDetectionMidiPortNamesList list, final PlatformType platformType)
    {
        // Deliberately empty - no auto-detection, see class doc. Assigned manually to whichever
        // keyboard's input port the user wants this behavior on.
    }

    @Override
    public NotePickupControllerExtension createInstance(final ControllerHost host)
    {
        return new NotePickupControllerExtension(this, host);
    }
}
