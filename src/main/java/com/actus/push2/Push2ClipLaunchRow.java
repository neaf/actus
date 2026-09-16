package com.actus.push2;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import com.bitwig.extension.controller.api.ClipLauncherSlotBank;
import com.bitwig.extension.controller.api.MidiOut;
import com.bitwig.extension.controller.api.Track;
import com.bitwig.extension.controller.api.TrackBank;

/**
 * Owns the bottom row of the pad grid (notes 36-43, one pad per track) as a clip launcher for
 * whichever scene {@code activeScene} currently points at - not the full 8x8 session grid.
 * Other rows are left to whatever claims them next (see CLAUDE.md's matrix-region note).
 *
 * A press on a track {@link Push2RecordRow} currently has armed for recording/overdub calls
 * {@link Push2RecordRow#finish} instead of the normal {@code launch()} - relaunching a clip
 * mid-overdub would reset its playhead exactly like the launch calls that row itself avoids for
 * the same reason (see its class doc), so this pad finishes the overdub in place rather than
 * restarting the clip, same as what the transport Play button does.
 *
 * Holding the Delete button (CC 118, queried live via the {@code deleteHeld} supplier passed in,
 * not cached) turns a press into {@code ClipLauncherSlot.deleteObject()} instead of a launch -
 * deletes whatever's in that track's slot for the active scene, empty or not. Neither the
 * overdub-finish check above nor a normal launch/launchRelease happens while Delete is held.
 */
public class Push2ClipLaunchRow implements PadRow
{
    private static final int NUM_TRACKS = 8;
    private static final int START_NOTE = 36;
    private static final int MAX_SCENES = Push2ControllerExtension.MAX_SCENES;

    // Push 2 pulses a pad by sending a second Note On on a different MIDI channel: 10 = slow
    // pulse, 14 = fast blink. The pad animates between the base (channel 0) color and this one.
    private static final int CHANNEL_STATIC      = 0x90;
    private static final int CHANNEL_PULSE_SLOW  = 0x9A;
    private static final int CHANNEL_PULSE_FAST  = 0x9E;

    // Colors from Push 2's default 128-entry palette (see Push2Colors / PushColorManager).
    private static final int COLOR_OFF          = 0;
    private static final int COLOR_WHITE        = 3;
    private static final int COLOR_RECORDING    = 5;  // red, high brightness
    private static final int COLOR_PLAYING_HI   = 21; // green, high brightness - matches DrivenByMoss's own convention

    private final MidiOut       midiOut;
    private final Track []      tracks = new Track [NUM_TRACKS];
    private final AtomicInteger activeScene;
    private final Push2RecordRow  recordRow;
    private final BooleanSupplier deleteHeld;

    private final boolean [] [] hasContent  = new boolean [NUM_TRACKS] [MAX_SCENES];
    private final boolean [] [] isPlaying   = new boolean [NUM_TRACKS] [MAX_SCENES];
    private final boolean [] [] isQueued    = new boolean [NUM_TRACKS] [MAX_SCENES];
    private final boolean [] [] isRecording = new boolean [NUM_TRACKS] [MAX_SCENES];
    private final int [] [] clipColor = new int [NUM_TRACKS] [MAX_SCENES];

    public Push2ClipLaunchRow(final MidiOut midiOut, final TrackBank trackBank, final AtomicInteger activeScene, final Push2RecordRow recordRow, final BooleanSupplier deleteHeld)
    {
        this.midiOut = midiOut;
        this.activeScene = activeScene;
        this.recordRow = recordRow;
        this.deleteHeld = deleteHeld;

        for (int t = 0; t < NUM_TRACKS; t++)
        {
            final int col = t;
            final Track track = trackBank.getItemAt(t);
            this.tracks[t] = track;

            final ClipLauncherSlotBank slots = track.clipLauncherSlotBank();
            slots.addHasContentObserver((slot, value) -> this.updateState(this.hasContent, col, slot, value));
            slots.addIsPlayingObserver((slot, value) -> this.updateState(this.isPlaying, col, slot, value));
            slots.addIsPlaybackQueuedObserver((slot, value) -> this.updateState(this.isQueued, col, slot, value));
            slots.addIsRecordingObserver((slot, value) -> this.updateState(this.isRecording, col, slot, value));
            slots.addColorObserver((slot, red, green, blue) -> {
                if (slot < MAX_SCENES)
                    this.clipColor[col][slot] = Push2Colors.nearest(red, green, blue);
                if (slot == this.activeScene.get())
                    this.redrawPad(col);
            });
        }
    }

    @Override
    public int startNote()
    {
        return START_NOTE;
    }

    /** Called from the extension's central MIDI dispatch for notes 36-43. */
    @Override
    public void onPadPressed(final int column, final int velocity)
    {
        final int scene = this.activeScene.get();
        if (scene < 0)
            return; // no active scene yet - nothing to launch until the scene button bootstraps one

        if (this.deleteHeld.getAsBoolean())
        {
            if (velocity > 0)
                this.tracks[column].clipLauncherSlotBank().getItemAt(scene).deleteObject();
            return; // neither a press nor a release falls through to launch while Delete is held
        }

        if (velocity > 0 && this.recordRow.isArmed(column))
        {
            this.recordRow.finish(column); // finish the overdub, don't relaunch - see class doc
            return;
        }

        final ClipLauncherSlotBank slots = this.tracks[column].clipLauncherSlotBank();
        if (velocity > 0)
            slots.getItemAt(scene).launch();
        else
            slots.getItemAt(scene).launchRelease();
    }

    /** Called when {@code activeScene} changes, to repaint the whole row against the new scene. */
    public void redrawAll()
    {
        for (int col = 0; col < NUM_TRACKS; col++)
            this.redrawPad(col);
    }

    // Read-only access to this row's per-slot cache for other consumers that need the same
    // data for a scene other than activeScene (e.g. Push2ClipJumpRow's static next-clips
    // preview) - see CLAUDE.md's session-display note on revisiting a shared cache once a
    // third consumer needs it, rather than each one observing the slot banks again
    // independently.

    boolean hasContent(final int column, final int scene)
    {
        return scene >= 0 && scene < MAX_SCENES && this.hasContent[column][scene];
    }

    int clipColor(final int column, final int scene)
    {
        return scene >= 0 && scene < MAX_SCENES ? this.clipColor[column][scene] : COLOR_OFF;
    }

    private void updateState(final boolean [] [] cache, final int column, final int scene, final boolean value)
    {
        if (scene < MAX_SCENES)
            cache[column][scene] = value;
        if (scene == this.activeScene.get())
            this.redrawPad(column);
    }

    private void redrawPad(final int column)
    {
        final int scene = this.activeScene.get();
        if (scene < 0)
        {
            this.sendSteady(column, COLOR_OFF);
            return;
        }

        final int clip = this.clipColor[column][scene];

        // Recording overrides the clip's own color - it needs to read unambiguously regardless
        // of what color the clip happens to be.
        if (this.isRecording[column][scene])
            this.sendPulsing(column, COLOR_RECORDING, CHANNEL_PULSE_FAST, COLOR_OFF);
        else if (this.isPlaying[column][scene])
            this.sendPulsing(column, clip, CHANNEL_PULSE_SLOW, COLOR_PLAYING_HI); // pulse toward a fixed green, not a hue-matched shade
        else if (this.isQueued[column][scene])
            this.sendPulsing(column, clip, CHANNEL_PULSE_FAST, COLOR_WHITE); // flash "about to launch"
        else if (this.hasContent[column][scene])
            this.sendSteady(column, clip);
        else
            this.sendSteady(column, COLOR_OFF);
    }

    /**
     * A pad only animates if a message is sent on the pulse channel (10/14) at all - sending one
     * with the pulse color equal to the base color still pulses. Steady pads must go through
     * {@link #sendSteady} instead, which never touches that channel.
     */
    private void sendPulsing(final int column, final int baseColor, final int pulseChannel, final int pulseColor)
    {
        final int note = START_NOTE + column;
        this.midiOut.sendMidi(CHANNEL_STATIC, note, baseColor);
        this.midiOut.sendMidi(pulseChannel, note, pulseColor);
    }

    /** Steady/off pad - only the base color, no pulse-channel message at all. */
    private void sendSteady(final int column, final int color)
    {
        this.midiOut.sendMidi(CHANNEL_STATIC, START_NOTE + column, color);
    }
}
