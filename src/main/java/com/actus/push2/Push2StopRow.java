package com.actus.push2;

import com.bitwig.extension.controller.api.MidiOut;
import com.bitwig.extension.controller.api.Track;
import com.bitwig.extension.controller.api.TrackBank;

/**
 * Owns the row above the clip launcher (notes 44-51, one pad per track) as a per-track stop
 * button - independent of which scene is active. {@code Track.isStopped()}/{@code stop()}
 * operate on whatever clip is currently playing/recording/queued on that track, regardless of
 * scene, so unlike {@link Push2ClipLaunchRow} this row needs no per-scene state at all.
 */
public class Push2StopRow implements PadRow
{
    private static final int NUM_TRACKS = 8;
    private static final int START_NOTE = 44;

    private static final int COLOR_OFF  = 0;
    private static final int COLOR_STOP = 1; // dim grey - barely lit, not a loud alert color

    private final MidiOut  midiOut;
    private final Track [] tracks = new Track [NUM_TRACKS];

    public Push2StopRow(final MidiOut midiOut, final TrackBank trackBank)
    {
        this.midiOut = midiOut;

        for (int t = 0; t < NUM_TRACKS; t++)
        {
            final int col = t;
            final Track track = trackBank.getItemAt(t);
            this.tracks[t] = track;
            track.isStopped().addValueObserver(stopped -> this.redrawPad(col, stopped));
        }
    }

    @Override
    public int startNote()
    {
        return START_NOTE;
    }

    /** Only presses matter here - a bare stop has no "release" behavior to speak of. */
    @Override
    public void onPadPressed(final int column, final int velocity)
    {
        if (velocity > 0)
            this.tracks[column].stop();
    }

    private void redrawPad(final int column, final boolean stopped)
    {
        this.midiOut.sendMidi(0x90, START_NOTE + column, stopped ? COLOR_OFF : COLOR_STOP);
    }
}
