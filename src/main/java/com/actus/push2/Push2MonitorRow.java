package com.actus.push2;

import java.util.function.BooleanSupplier;

import com.bitwig.extension.controller.api.MidiOut;
import com.bitwig.extension.controller.api.Track;
import com.bitwig.extension.controller.api.TrackBank;

/**
 * Owns the row of 8 buttons directly below the screen (CC 20-27, "Lower Row 1-8" in Ableton's
 * own Push 2 spec, RGB-colored, one per track column left to right - not the "Upper Row 1-8" at
 * CC 102-109, and not part of the 64-pad grid) as a per-track input-monitoring toggle,
 * independent of {@link Push2RecordRow}'s arm state.
 *
 * {@code Track.monitorMode()} is a {@code SettableEnumValue}; the string values ("OFF"/"ON"/
 * "AUTO") are undocumented in the Controller API and confirmed via DrivenByMoss's
 * {@code TrackImpl}. This row only ever uses "OFF"/"ON" - "AUTO" (monitor only while armed or
 * selected-and-stopped) is not exposed here.
 *
 * A press normally means "monitor only this track": every other track is turned off and this
 * one on, except that pressing the track that's already the sole one monitoring turns it off
 * instead. A press is additive (toggles just that track, independent of the rest) when either
 * the hardware Shift button (CC 49, queried live via the {@code shiftHeld} supplier) or another
 * track's monitor button (this row's own CC 20-27) is currently held.
 */
public class Push2MonitorRow
{
    private static final int NUM_TRACKS = 8;
    static final int CC_START = 20;

    private static final String MODE_ON  = "ON";
    private static final String MODE_OFF = "OFF";

    private static final int COLOR_OFF = 0;
    private static final int COLOR_ON  = 36; // light blue

    private final MidiOut         midiOut;
    private final Track []        tracks = new Track [NUM_TRACKS];
    private final boolean []      monitoring = new boolean [NUM_TRACKS];
    private final boolean []      held = new boolean [NUM_TRACKS];
    private final BooleanSupplier shiftHeld;

    public Push2MonitorRow(final MidiOut midiOut, final TrackBank trackBank, final BooleanSupplier shiftHeld)
    {
        this.midiOut = midiOut;
        this.shiftHeld = shiftHeld;

        for (int t = 0; t < NUM_TRACKS; t++)
        {
            final int col = t;
            final Track track = trackBank.getItemAt(t);
            this.tracks[t] = track;
            track.monitorMode().addValueObserver(value -> {
                this.monitoring[col] = MODE_ON.equalsIgnoreCase(value);
                this.redraw(col);
            });
        }
    }

    /** Called from the extension's central MIDI dispatch for CC 20-27, on press and release. */
    public void onButtonEvent(final int column, final boolean pressed)
    {
        if (!pressed)
        {
            this.held[column] = false;
            return;
        }

        if (this.shiftHeld.getAsBoolean() || this.anyOtherHeld(column))
        {
            this.tracks[column].monitorMode().set(this.monitoring[column] ? MODE_OFF : MODE_ON);
        }
        else if (this.monitoring[column] && this.isOnlyOneMonitoring(column))
        {
            this.tracks[column].monitorMode().set(MODE_OFF);
        }
        else
        {
            for (int col = 0; col < NUM_TRACKS; col++)
                if (col != column)
                    this.tracks[col].monitorMode().set(MODE_OFF);
            this.tracks[column].monitorMode().set(MODE_ON);
        }

        this.held[column] = true;
    }

    private boolean anyOtherHeld(final int column)
    {
        for (int col = 0; col < NUM_TRACKS; col++)
            if (col != column && this.held[col])
                return true;
        return false;
    }

    private boolean isOnlyOneMonitoring(final int column)
    {
        for (int col = 0; col < NUM_TRACKS; col++)
            if (col != column && this.monitoring[col])
                return false;
        return true;
    }

    private void redraw(final int column)
    {
        this.midiOut.sendMidi(0xB0, CC_START + column, this.monitoring[column] ? COLOR_ON : COLOR_OFF);
    }
}
