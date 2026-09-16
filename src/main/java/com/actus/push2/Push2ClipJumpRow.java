package com.actus.push2;

import java.util.concurrent.atomic.AtomicInteger;

import com.bitwig.extension.controller.api.ClipLauncherSlotBank;
import com.bitwig.extension.controller.api.MidiOut;
import com.bitwig.extension.controller.api.Track;
import com.bitwig.extension.controller.api.TrackBank;

/**
 * Owns the five otherwise-dark rows above the record row (notes 60-99, see CLAUDE.md's
 * matrix-region note) as a per-track "jump ahead" shortcut. Holding the bottom row of the five
 * (notes 60-67 - the trigger row) for one column arms the four rows above it, for that column
 * only, as launch shortcuts for that track's next four clips relative to {@code activeScene}:
 * the row closest to the trigger fires {@code activeScene + 1}, the topmost row fires
 * {@code activeScene + 4} - i.e. row label N (1 nearest the trigger, 4 at the top) launches
 * {@code activeScene + N}. Releasing the trigger clears that column's four pads back to dark;
 * other columns are unaffected.
 *
 * Content/color data is read straight from {@link Push2ClipLaunchRow}'s existing per-slot
 * cache ({@code hasContent}/{@code clipColor} package accessors) instead of observing the slot
 * banks a second time - this is the "third consumer" case CLAUDE.md's session-display note
 * flags for revisiting a shared cache. The preview is a static swatch of what's there or not -
 * deliberately not mirroring a clip's live recording/playing/queued state the way
 * {@code Push2ClipLaunchRow} does.
 */
public class Push2ClipJumpRow
{
    private static final int NUM_TRACKS = 8;
    private static final int NUM_SLOTS = 4;
    private static final int TRIGGER_START_NOTE = 60;
    private static final int SLOT_ROW_START_NOTE = 68; // slotIndex 0; slotIndex 1-3 follow at +8 each

    private static final int CHANNEL_STATIC = 0x90;

    private static final int COLOR_OFF          = 0;
    private static final int COLOR_TRIGGER_IDLE = 1; // dim grey - same "available but idle" treatment as Push2StopRow
    private static final int COLOR_TRIGGER_HELD = 3; // white - clear feedback the hold registered

    private final MidiOut        midiOut;
    private final Track []       tracks = new Track [NUM_TRACKS];
    private final AtomicInteger  activeScene;
    private final Push2ClipLaunchRow clipLaunchRow;

    private final boolean [] exists = new boolean [NUM_TRACKS];
    private final boolean [] held   = new boolean [NUM_TRACKS];

    public Push2ClipJumpRow(final MidiOut midiOut, final TrackBank trackBank, final AtomicInteger activeScene, final Push2ClipLaunchRow clipLaunchRow)
    {
        this.midiOut = midiOut;
        this.activeScene = activeScene;
        this.clipLaunchRow = clipLaunchRow;

        for (int t = 0; t < NUM_TRACKS; t++)
        {
            final int col = t;
            this.tracks[t] = trackBank.getItemAt(t);
            trackBank.getItemAt(t).exists().addValueObserver(value -> {
                this.exists[col] = value;
                this.redrawTrigger(col);
            });
        }
    }

    /** The trigger row (notes 60-67) - hold a column to arm its four "jump" slots above it. */
    public PadRow triggerRow()
    {
        return new PadRow()
        {
            @Override
            public int startNote()
            {
                return TRIGGER_START_NOTE;
            }

            @Override
            public void onPadPressed(final int column, final int velocity)
            {
                Push2ClipJumpRow.this.held[column] = velocity > 0;
                Push2ClipJumpRow.this.redrawTrigger(column);
                Push2ClipJumpRow.this.redrawSlots(column);
            }
        };
    }

    /**
     * One of the four slot rows (notes 68-99). {@code slotIndex} 0 is the row directly above the
     * trigger row (label 1, offset +1); {@code slotIndex} 3 is the topmost row (label 4, offset
     * +4) - see class doc.
     */
    public PadRow slotRow(final int slotIndex)
    {
        final int offset = slotIndex + 1;
        return new PadRow()
        {
            @Override
            public int startNote()
            {
                return SLOT_ROW_START_NOTE + slotIndex * 8;
            }

            @Override
            public void onPadPressed(final int column, final int velocity)
            {
                if (!Push2ClipJumpRow.this.held[column])
                    return; // dark/inactive unless this column's trigger is currently held

                final int scene = Push2ClipJumpRow.this.activeScene.get() + offset;
                if (scene < 0 || scene >= Push2ControllerExtension.MAX_SCENES)
                    return;

                final ClipLauncherSlotBank slots = Push2ClipJumpRow.this.tracks[column].clipLauncherSlotBank();
                if (velocity > 0)
                    slots.getItemAt(scene).launch();
                else
                    slots.getItemAt(scene).launchRelease();
            }
        };
    }

    /** Called when {@code activeScene} changes, to keep any currently-held column's preview in sync. */
    public void redrawAll()
    {
        for (int col = 0; col < NUM_TRACKS; col++)
            if (this.held[col])
                this.redrawSlots(col);
    }

    private void redrawTrigger(final int column)
    {
        final int color = !this.exists[column] ? COLOR_OFF : this.held[column] ? COLOR_TRIGGER_HELD : COLOR_TRIGGER_IDLE;
        this.midiOut.sendMidi(CHANNEL_STATIC, TRIGGER_START_NOTE + column, color);
    }

    private void redrawSlots(final int column)
    {
        final int scene = this.activeScene.get();
        for (int slotIndex = 0; slotIndex < NUM_SLOTS; slotIndex++)
        {
            final int note = SLOT_ROW_START_NOTE + slotIndex * 8 + column;
            final int target = scene + (slotIndex + 1);

            if (!this.held[column] || scene < 0 || target >= Push2ControllerExtension.MAX_SCENES)
            {
                this.sendSteady(note, COLOR_OFF);
                continue;
            }

            // Static preview, deliberately not mirroring the clip's live recording/playing/
            // queued state (unlike Push2ClipLaunchRow) - just what's there or not.
            this.sendSteady(note, this.clipLaunchRow.hasContent(column, target) ? this.clipLaunchRow.clipColor(column, target) : COLOR_OFF);
        }
    }

    private void sendSteady(final int note, final int color)
    {
        this.midiOut.sendMidi(CHANNEL_STATIC, note, color);
    }
}
