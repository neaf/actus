package com.actus.push2;

import com.bitwig.extension.controller.api.MidiOut;

/**
 * Writes Actus's own copy of Push 2's 128-color palette ({@link Push2Colors#PALETTE}) to the
 * device via SysEx, once, during {@code init()}.
 *
 * Necessary because the palette lives in the device's volatile memory, not its firmware -
 * confirmed the hard way: after a hardware power-cycle (Bitwig left running the whole time,
 * nothing changed on our side), pad/button colors that had been correct came back completely
 * different. They'd only ever been right because some earlier script (DrivenByMoss) had written
 * this same table into the device previously; relying on "whatever's already loaded" only
 * worked by accident. Writing it ourselves on every init() makes Actus correct regardless of
 * what ran on the device before it, or whether it was just power-cycled.
 *
 * SysEx format, verbatim from DrivenByMoss's {@code ColorPaletteEntry}/{@code ColorPalette}:
 * write one entry with {@code F0 00 21 1D 01 01 03 <index> <r_lo> <r_hi> <g_lo> <g_hi> <b_lo>
 * <b_hi> <w_lo> <w_hi> F7} (each 8-bit channel split into low-7-bits/high-bit pairs), then
 * reload/apply once with {@code F0 00 21 1D 01 01 05 F7}.
 *
 * <p>Gotcha, found the hard way: Push 2's plain white-LED buttons (Octave Up/Down, Up/Down,
 * Shift, etc. - anything that isn't an RGB pad or an RGB-colored button like Scene Launch) don't
 * take a raw brightness value; per Ableton's own MIDI spec (confirmed against their published
 * {@code Push2-map.json}/{@code AbletonPush2MIDIDisplayInterface.asc}), the CC/note velocity
 * sent to one of them is a palette index exactly like for RGB LEDs, just resolved against that
 * entry's separate <b>white</b> field instead of its r/g/b fields. An earlier version of this
 * method sent white as a flat 0 for every entry (this table, copied from DrivenByMoss's
 * DEFAULT_PALETTE, only has r/g/b to begin with) - which silently made every monochrome button on
 * the device permanently unlightable, since every index it could possibly be told to use had a
 * zeroed-out white channel. DrivenByMoss avoids this by reading each entry's existing white value
 * off the device before writing (preserving Push 2's factory default); we don't have that
 * read-back machinery, so instead we derive a reasonable white value from the entry's own r/g/b
 * (the brightest of the three channels) - index 0 (black) still comes out white=0 (off), and any
 * index actually used for a monochrome button's LED gets a sensible non-zero brightness.
 */
final class Push2Palette
{
    private Push2Palette()
    {
        // Static utility.
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
