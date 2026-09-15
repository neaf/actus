package com.actus.push2;

import com.bitwig.extension.controller.api.MidiOut;

/**
 * Push 2's global pad LED brightness, controlled by the dedicated small encoder above the Tap
 * Tempo button (CC 15, {@code PUSH_SMALL_KNOB2} in DrivenByMoss's {@code PushControlSurface}).
 * Same SysEx command Ableton Live's own Push 2 integration and DrivenByMoss use for this setting
 * (DrivenByMoss's {@code PushControlSurface.sendLEDBrightness()}).
 */
public class Push2Brightness
{
    static final int ENCODER_CC = 15;

    private static final int DEFAULT_PERCENT = 100;

    // Below 10%, Push 2's hardware itself glitches - buttons disappearing entirely, pads
    // showing the wrong color outright - reproduced with DrivenByMoss's own script too, so
    // this is a real hardware floor, not something to work around in software.
    private static final int MIN_PERCENT = 10;

    // Push 2's encoders send relative deltas as two's complement 7-bit values: 1-63 = positive
    // steps, 65-127 = negative steps (127 = -1, 66 = -62, ...). Confirmed via DrivenByMoss's
    // PushControllerSetup, which constructs `new TwosComplementValueChanger(...)` for Push.
    private static final int TWOS_COMPLEMENT_WRAP = 128;

    private final MidiOut midiOut;
    private int           percent = DEFAULT_PERCENT;

    public Push2Brightness(final MidiOut midiOut)
    {
        this.midiOut = midiOut;
        this.send();
    }

    /** Called from the extension's central MIDI dispatch for CC 15. */
    public void onEncoderTurned(final int value)
    {
        final int delta = value < 64 ? value : value - TWOS_COMPLEMENT_WRAP;
        this.percent = Math.max(MIN_PERCENT, Math.min(100, this.percent + delta));
        this.send();
    }

    private void send()
    {
        final int brightness = this.percent * 127 / 100;
        this.midiOut.sendSysex(String.format("F0 00 21 1D 01 01 06 %02X F7", brightness));
    }
}
