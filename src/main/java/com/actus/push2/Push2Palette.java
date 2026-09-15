package com.actus.push2;

import com.bitwig.extension.controller.api.MidiOut;

/**
 * Writes Actus's own copy of Push 2's 128-color palette ({@link Push2Colors#PALETTE}) to the
 * device via SysEx, once, during {@code init()}.
 *
 * The palette lives in the device's volatile memory, not its firmware, so a hardware
 * power-cycle resets it; writing it on every {@code init()} makes Actus correct regardless of
 * what ran on the device before it.
 *
 * SysEx format, from DrivenByMoss's {@code ColorPaletteEntry}/{@code ColorPalette}: write one
 * entry with {@code F0 00 21 1D 01 01 03 <index> <r_lo> <r_hi> <g_lo> <g_hi> <b_lo> <b_hi>
 * <w_lo> <w_hi> F7} (each 8-bit channel split into low-7-bits/high-bit pairs), then reload/apply
 * once with {@code F0 00 21 1D 01 01 05 F7}.
 *
 * Push 2's plain white-LED buttons (Octave Up/Down, Up/Down, Shift, etc. - anything that
 * isn't an RGB pad or an RGB-colored button like Scene Launch) take a palette index like RGB
 * LEDs, resolved against that entry's separate white field, not its r/g/b fields (per Ableton's
 * published {@code Push2-map.json}/{@code AbletonPush2MIDIDisplayInterface.asc}).
 * {@link Push2Colors#PALETTE}, copied from DrivenByMoss's {@code DEFAULT_PALETTE}, has only
 * r/g/b, so {@code write()} derives white as the brightest of the three channels per entry -
 * index 0 (black) stays white=0, any index used for a monochrome button's LED gets a non-zero
 * brightness.
 */
final class Push2Palette
{
    private Push2Palette()
    {
    }

    static void write(final MidiOut midiOut)
    {
        for (int index = 0; index < Push2Colors.PALETTE.length; index++)
        {
            final int [] rgb = Push2Colors.PALETTE[index];
            final int white = Math.max(rgb[0], Math.max(rgb[1], rgb[2]));
            midiOut.sendSysex(String.format(
                "F0 00 21 1D 01 01 03 %02X %02X %02X %02X %02X %02X %02X %02X %02X F7",
                index,
                rgb[0] % 128, rgb[0] / 128,
                rgb[1] % 128, rgb[1] / 128,
                rgb[2] % 128, rgb[2] / 128,
                white % 128, white / 128));
        }
        midiOut.sendSysex("F0 00 21 1D 01 01 05 F7");
    }
}
