package com.actus.push2;

import com.bitwig.extension.controller.api.MidiOut;
import com.bitwig.extension.controller.api.Transport;

/**
 * Owns Push 2's dedicated Play button (CC 85, RGB-colored - not part of the 64-pad grid).
 * Normally toggles transport play/stop, same as clicking Bitwig's own play button. But if
 * {@link Push2RecordRow} currently has anything recording/overdubbing, pressing Play instead
 * asks it to finish - see {@link Push2RecordRow#finishAll()} - and leaves the transport itself
 * untouched. (There's no other source of "currently recording" to check: per
 * {@link Push2RecordRow}'s class doc, this row is the only thing ever allowed to arm a track.)
 */
public class Push2TransportPlay
{
    static final int PLAY_CC = 85;

    private static final int COLOR_STOPPED = 1;  // dim grey - matches Push2StopRow/SceneButtons convention
    private static final int COLOR_PLAYING = 21; // green, high brightness

    private final MidiOut       midiOut;
    private final Transport     transport;
    private final Push2RecordRow recordRow;

    public Push2TransportPlay(final MidiOut midiOut, final Transport transport, final Push2RecordRow recordRow)
    {
        this.midiOut = midiOut;
        this.transport = transport;
        this.recordRow = recordRow;

        this.transport.isPlaying().addValueObserver(value -> this.redraw());
    }

    /** Called from the extension's central MIDI dispatch when the Play button is pressed. */
    public void onButtonPressed()
    {
        if (this.recordRow.hasArmedTracks())
            this.recordRow.finishAll();
        else
            this.transport.togglePlay();
    }

    public void redraw()
    {
        this.midiOut.sendMidi(0xB0, PLAY_CC, this.transport.isPlaying().get() ? COLOR_PLAYING : COLOR_STOPPED);
    }
}
