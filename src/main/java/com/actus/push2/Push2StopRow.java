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

    // Same pulse-channel trick as Push2ClipLaunchRow - see its class doc for why a second
    // Note On on channel 14 is what makes a pad blink at all.
    private static final int CHANNEL_STATIC     = 0x90;
    private static final int CHANNEL_PULSE_FAST = 0x9E;

    private static final int COLOR_OFF  = 0;
    private static final int COLOR_STOP = 1; // dim grey - barely lit, not a loud alert color

    private final MidiOut  midiOut;
    private final Track [] tracks = new Track [NUM_TRACKS];

    private final boolean [] exists       = new boolean [NUM_TRACKS];
    private final boolean [] stopped      = new boolean [NUM_TRACKS];
    private final boolean [] queuedForStop = new boolean [NUM_TRACKS];

    public Push2StopRow(final MidiOut midiOut, final TrackBank trackBank)
    {
        this.midiOut = midiOut;

        for (int t = 0; t < NUM_TRACKS; t++)
        {
            final int col = t;
            final Track track = trackBank.getItemAt(t);
            this.tracks[t] = track;
            // A bank slot past the end of the track list (fewer real tracks than NUM_TRACKS)
            // still reports isStopped()/isQueuedForStop() values - has to be excluded
            // explicitly or that column lights up as if it were a real, stopped track.
            track.exists().addValueObserver(value -> {
                this.exists[col] = value;
                this.redrawPad(col);
            });
            track.isStopped().addValueObserver(value -> {
                this.stopped[col] = value;
                this.redrawPad(col);
            });
            track.isQueuedForStop().addValueObserver(value -> {
                this.queuedForStop[col] = value;
                this.redrawPad(col);
            });
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
        if (velocity > 0 && this.exists[column])
            this.tracks[column].stop();
    }

    private void redrawPad(final int column)
    {
        if (!this.exists[column])
        {
            this.sendSteady(column, COLOR_OFF);
            return;
        }

        if (this.stopped[column])
        {
            this.sendSteady(column, COLOR_OFF);
            return;
        }

        // Blinks toward dark (not brighter) while a press of the stop pad is waiting for the
        // next quantization boundary - reads as "fading out", matching what it's about to do.
        if (this.queuedForStop[column])
            this.sendPulsing(column, COLOR_STOP, CHANNEL_PULSE_FAST, COLOR_OFF);
        else
            this.sendSteady(column, COLOR_STOP);
    }

    private void sendPulsing(final int column, final int baseColor, final int pulseChannel, final int pulseColor)
    {
        final int note = START_NOTE + column;
        this.midiOut.sendMidi(CHANNEL_STATIC, note, baseColor);
        this.midiOut.sendMidi(pulseChannel, note, pulseColor);
    }

    private void sendSteady(final int column, final int color)
    {
        this.midiOut.sendMidi(CHANNEL_STATIC, START_NOTE + column, color);
    }
}
