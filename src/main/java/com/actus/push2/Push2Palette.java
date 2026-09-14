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
 * <b_hi> <w_lo> <w_hi> F7} (each 8-bit channel split into low-7-bits/high-bit pairs; white is
 * always sent as 0 - pure RGB mix, matching this table, which has no separate white component),
 * then reload/apply once with {@code F0 00 21 1D 01 01 05 F7}.
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
            midiOut.sendSysex(String.format(
                "F0 00 21 1D 01 01 03 %02X %02X %02X %02X %02X %02X %02X 00 00 F7",
                index,
                rgb[0] % 128, rgb[0] / 128,
                rgb[1] % 128, rgb[1] / 128,
                rgb[2] % 128, rgb[2] / 128));
        }
        midiOut.sendSysex("F0 00 21 1D 01 01 05 F7");
    }
}
