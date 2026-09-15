package com.actus.push2;

/**
 * A consumer that owns one row of the 8x8 pad grid (8 notes, one per track column) - see
 * CLAUDE.md's matrix-region note. {@link Push2ControllerExtension} dispatches grid Note
 * On/Off messages to whichever registered row's note range they fall in.
 */
interface PadRow
{
    /** First note (column 0) of this row; columns 1-7 follow consecutively. */
    int startNote();

    /** velocity 0 = release (Note Off or Note On velocity 0), matching the grid's convention. */
    void onPadPressed(int column, int velocity);
}
