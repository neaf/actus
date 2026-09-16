package com.actus.push2;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import com.bitwig.extension.controller.api.ClipLauncherSlotBank;
import com.bitwig.extension.controller.api.ControllerHost;
import com.bitwig.extension.controller.api.MidiOut;
import com.bitwig.extension.controller.api.Track;
import com.bitwig.extension.controller.api.TrackBank;
import com.bitwig.extension.controller.api.Transport;

/**
 * Owns the row above the stop row (notes 52-59, one pad per track) as a "schedule
 * recording/overdub" button for whichever scene {@code activeScene} points at - scene-relative
 * like {@link Push2ClipLaunchRow}, not track-only like {@link Push2StopRow}.
 *
 * A press arms the track ({@code Track.arm().set(true)}) and branches on whether the slot has a
 * clip at all, not on whether it's currently playing. Truly empty: {@code ClipLauncherSlot
 * .record()} starts a fresh scheduled recording. Has a clip, playing or not: overdubs into it -
 * {@code record()} is never used for that, since it ignores {@code Transport
 * .isClipLauncherOverdubEnabled()} and hard-records, restarting the clip and extending its
 * length. With the overdub flag on, an armed track overdubs into whatever's playing on it the
 * moment playback starts, regardless of whether that start was already in progress or happens
 * because of this press: if already playing, nothing else is called ({@code launch()} and
 * {@code launchWithOptions()} both reset the playhead; arming alone is the trigger); if stopped,
 * {@code ClipLauncherSlot.launch()} is required to start playback, and since the track is armed
 * with overdub enabled before the launch, playback starts in overdub mode rather than
 * hard-recording. The overdub-enabled flag is managed by this row's own
 * {@link #activeOverdubCount}, not left on globally.
 *
 * Pressing the pad again for a track this row already has armed depends on the session type.
 * Overdub: toggles it off (see {@link #finish}) - a looping clip being overdubbed has no natural
 * "stop" signal, so a track left armed without this toggle stays armed indefinitely and
 * overdubs any future note played into it. Fresh recording still in progress
 * ({@link #freshRecording} true): cancels (see {@link #cancel}) - deletes the partial take and
 * does nothing else; the track ends up disarmed as a side effect of the delete. A second press
 * then lands as an ordinary record-into-empty-slot, giving cancel and cancel-then-restart as two
 * separate gestures.
 *
 * Finishing is quantized to the next bar, not immediate, via two different mechanisms since
 * fresh recording and overdub have no finishing primitive in common. Fresh recording: {@code
 * ClipLauncherSlot.launchWithOptions("default", "default")} - a second mouse click on Bitwig's
 * own record circle launches the clip quantized to the bar; it does not call {@code record()}
 * again (which would hard-record a second pass). The track stays armed until the
 * {@code isRecording} observer (reliable for a genuine {@code record()} session) reports the
 * take finished. Overdub: {@code Track.arm()} has no native quantization hook and there is no
 * Bitwig-tracked recording session to toggle off, so {@link #scheduleDisarmAtNextBar} computes
 * the delay from {@code Transport.playPosition()}, {@code Transport.tempo()}, and the project's
 * time signature, and disarms via {@code ControllerHost.scheduleTask} - not sample-accurate,
 * bounded by scheduler jitter. This quantized finishing applies only to {@link #finish}/
 * {@link #finishAll} - the safety nets that disarm because the underlying state changed (the
 * armed slot's {@code isPlaying} going false) and {@link #disarmAllImmediately} (used when the
 * transport stops) are immediate.
 *
 * Arm state is fully owned by this row: every track stays disarmed except for the window this
 * row has it recording/overdubbing. An {@code arm()} observer on every track vetoes any
 * arm-true transition this row didn't itself request (tracked via {@link #armedByUs}).
 * Disarming is not driven off {@code ClipLauncherSlot.isRecording()} for the overdub path - that
 * flag does not go true for arm-triggered overdub. This row tracks its own state
 * ({@link #armedByUs}, {@link #armedScene}, {@link #freshRecording}) and disarms on the armed
 * slot's {@code isPlaying} going false, the transport stopping ({@link #disarmAllImmediately}),
 * or an explicit finish (see {@link #finishAll()}, called by {@link Push2TransportPlay}'s Play
 * button and the Scene Launch button).
 *
 * Input monitoring is a separate concern this row does not touch - see {@link Push2MonitorRow}.
 */
public class Push2RecordRow implements PadRow
{
    private static final int NUM_TRACKS = 8;
    private static final int START_NOTE = 52;
    private static final int MAX_SCENES = Push2ControllerExtension.MAX_SCENES;

    // Same pulse-channel trick as Push2ClipLaunchRow - see its class doc.
    private static final int CHANNEL_STATIC     = 0x90;
    private static final int CHANNEL_PULSE_FAST = 0x9E;

    private static final int COLOR_OFF           = 0;
    private static final int COLOR_ARM_DIM       = 6; // dark red - "ready to record/overdub"
    private static final int COLOR_RECORDING_HI  = 5; // full red - "recording now" / "about to start"

    private final ControllerHost host;
    private final MidiOut        midiOut;
    private final Track []       tracks = new Track [NUM_TRACKS];
    private final AtomicInteger  activeScene;
    private final Transport      transport;

    private final boolean [] exists = new boolean [NUM_TRACKS];

    private final boolean [] [] hasContent        = new boolean [NUM_TRACKS] [MAX_SCENES];
    private final boolean [] [] isPlaying         = new boolean [NUM_TRACKS] [MAX_SCENES];
    private final boolean [] [] isRecording       = new boolean [NUM_TRACKS] [MAX_SCENES];
    private final boolean [] [] isRecordingQueued = new boolean [NUM_TRACKS] [MAX_SCENES];

    // armedScene is only meaningful while armedByUs is true. freshRecording distinguishes
    // record()-into-empty-slot from overdub-into-existing-clip - see class doc.
    private final boolean [] armedByUs      = new boolean [NUM_TRACKS];
    private final int []     armedScene     = new int [NUM_TRACKS];
    private final boolean [] freshRecording = new boolean [NUM_TRACKS];

    // Count of tracks currently armed for overdub specifically (not fresh recording, which
    // doesn't need the flag). Transport.isClipLauncherOverdubEnabled() is a single global flag,
    // so it's set on the 0->1 transition and cleared on the 1->0 transition.
    private int activeOverdubCount;

    public Push2RecordRow(final ControllerHost host, final MidiOut midiOut, final TrackBank trackBank, final AtomicInteger activeScene, final Transport transport)
    {
        this.host = host;
        this.midiOut = midiOut;
        this.activeScene = activeScene;
        this.transport = transport;
        Arrays.fill(this.armedScene, -1);

        // Read synchronously in msUntilNextBar() - requires markInterested().
        this.transport.playPosition().markInterested();
        this.transport.tempo().markInterested();
        this.transport.timeSignature().numerator().markInterested();
        this.transport.timeSignature().denominator().markInterested();

        for (int t = 0; t < NUM_TRACKS; t++)
        {
            final int col = t;
            final Track track = trackBank.getItemAt(t);
            this.tracks[t] = track;

            // A bank slot past the end of the track list (fewer real tracks than NUM_TRACKS)
            // still reports slot/queue state - has to be excluded explicitly or that column
            // lights up dark red as if it were a real, armable track.
            track.exists().addValueObserver(value -> {
                this.exists[col] = value;
                this.redrawPad(col);
            });

            final ClipLauncherSlotBank slots = track.clipLauncherSlotBank();
            slots.addHasContentObserver((slot, value) -> this.updateState(this.hasContent, col, slot, value));
            slots.addIsPlayingObserver((slot, value) -> {
                this.updateState(this.isPlaying, col, slot, value);
                if (!value && this.armedByUs[col] && slot == this.armedScene[col])
                    this.disarm(col); // clip stopped - nothing left to overdub into
            });
            slots.addIsRecordingObserver((slot, value) -> {
                this.updateState(this.isRecording, col, slot, value);
                if (!value && this.armedByUs[col] && slot == this.armedScene[col])
                    this.disarm(col);
            });
            slots.addIsRecordingQueuedObserver((slot, value) -> this.updateState(this.isRecordingQueued, col, slot, value));

            // Veto any arm-true this row didn't itself request - see class doc.
            track.arm().addValueObserver(value -> {
                if (value && !this.armedByUs[col])
                    track.arm().set(false);
            });
        }

        // Immediate, not quantized - nothing to wait for once playback has halted.
        transport.isPlaying().addValueObserver(playing -> {
            if (!playing)
                this.disarmAllImmediately();
        });
    }

    @Override
    public int startNote()
    {
        return START_NOTE;
    }

    /** Only presses matter - recording is one-shot, there's no "release" behavior. */
    @Override
    public void onPadPressed(final int column, final int velocity)
    {
        if (velocity == 0)
            return;

        if (!this.exists[column])
            return;

        if (this.armedByUs[column])
        {
            if (this.freshRecording[column])
                this.cancel(column);
            else
                this.finish(column);
            return;
        }

        final int scene = this.activeScene.get();
        if (scene < 0)
            return; // no active scene yet

        final Track track = this.tracks[column];
        final boolean hasClip = this.hasContent[column][scene];
        final boolean alreadyPlaying = this.isPlaying[column][scene];

        this.armedByUs[column] = true;
        this.armedScene[column] = scene;
        this.freshRecording[column] = !hasClip;
        track.arm().set(true);

        if (hasClip)
        {
            if (this.activeOverdubCount++ == 0)
                this.transport.isClipLauncherOverdubEnabled().set(true);

            if (!alreadyPlaying)
                track.clipLauncherSlotBank().getItemAt(scene).launch(); // start playback so arming has something to overdub into
        }
        else
        {
            track.clipLauncherSlotBank().getItemAt(scene).record();
        }

        this.redrawPad(column); // don't wait on a Bitwig observer - weAreRecording is already true
    }

    /** Whether this row currently has any track armed for its own recording/overdub. */
    public boolean hasArmedTracks()
    {
        for (final boolean armed : this.armedByUs)
            if (armed)
                return true;
        return false;
    }

    /** Whether this row currently has this specific track armed for its own recording/overdub. */
    public boolean isArmed(final int column)
    {
        return this.armedByUs[column];
    }

    /**
     * Ends whatever recording/overdub this row has going on every track, quantized to the next
     * bar - called by other controls (the transport Play button, Scene Launch) that should also
     * finish it.
     */
    public void finishAll()
    {
        for (int col = 0; col < NUM_TRACKS; col++)
            this.finish(col);
    }

    /**
     * Ends this row's recording/overdub on one track, if it has any going, waiting for the end
     * of the current bar rather than cutting off immediately. No-op otherwise. Also called by
     * {@link Push2ClipLaunchRow} when its own pad is pressed on a track this row has armed, since
     * relaunching a clip mid-overdub would reset its playhead.
     */
    public void finish(final int column)
    {
        if (!this.armedByUs[column])
            return;

        if (this.freshRecording[column])
        {
            // Quantized launch, not a second record() call - see class doc. The isRecording
            // observer registered in the constructor disarms the track once Bitwig actually
            // finishes the take.
            this.tracks[column].clipLauncherSlotBank().getItemAt(this.armedScene[column]).launchWithOptions("default", "default");
        }
        else
        {
            this.scheduleDisarmAtNextBar(column);
        }
    }

    /**
     * Called instead of {@link #finish} when the record pad is pressed again while still
     * actively recording a brand-new clip. Deletes the partial take and does nothing else. The
     * track ends up disarmed as a side effect - deleting a clip that's actively recording stops
     * the recording, which the {@code isPlaying}/{@code isRecording} safety nets react to. A
     * second press on the now-empty slot starts a fresh recording through the normal path in
     * {@link #onPadPressed}.
     */
    private void cancel(final int column)
    {
        this.tracks[column].clipLauncherSlotBank().getItemAt(this.armedScene[column]).deleteObject();
    }

    /**
     * Disarms every track this row currently has armed, immediately, no quantization - used when
     * the transport stops. Explicit finish actions go through {@link #finishAll()}/
     * {@link #finish(int)} instead.
     */
    public void disarmAllImmediately()
    {
        for (int col = 0; col < NUM_TRACKS; col++)
            if (this.armedByUs[col])
                this.disarm(col);
    }

    /** Schedules {@link #disarm} for the given track at the next bar boundary. */
    private void scheduleDisarmAtNextBar(final int column)
    {
        this.host.scheduleTask(() -> this.disarm(column), this.msUntilNextBar());
    }

    /**
     * Milliseconds from now until the next bar boundary, from the transport's current position,
     * tempo, and time signature.
     */
    private long msUntilNextBar()
    {
        final double beat = this.transport.playPosition().get();
        final double bpm = this.transport.tempo().getRaw();
        final int numerator = this.transport.timeSignature().numerator().get();
        final int denominator = this.transport.timeSignature().denominator().get();
        final double beatsPerBar = numerator * 4.0 / denominator;
        final double beatsIntoBar = beat % beatsPerBar;
        final double beatsRemaining = beatsPerBar - beatsIntoBar;
        return Math.round(beatsRemaining * 60_000.0 / bpm);
    }

    /** Called when {@code activeScene} changes, to repaint the whole row against the new scene. */
    public void redrawAll()
    {
        for (int col = 0; col < NUM_TRACKS; col++)
            this.redrawPad(col);
    }

    private void disarm(final int column)
    {
        // Guards against a scheduled disarm firing after the track was already disarmed some
        // other way in the meantime.
        if (!this.armedByUs[column])
            return;

        if (!this.freshRecording[column] && --this.activeOverdubCount == 0)
            this.transport.isClipLauncherOverdubEnabled().set(false);

        this.armedByUs[column] = false;
        this.armedScene[column] = -1;
        this.tracks[column].arm().set(false);
        this.redrawPad(column);
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
        if (!this.exists[column])
        {
            this.sendSteady(column, COLOR_OFF);
            return;
        }

        final int scene = this.activeScene.get();
        if (scene < 0)
        {
            this.sendSteady(column, COLOR_OFF);
            return;
        }

        // Driven off armedByUs/armedScene, not isRecording - that flag doesn't reflect
        // arm-triggered overdub, see class doc.
        final boolean weAreRecording = this.armedByUs[column] && this.armedScene[column] == scene;
        if (weAreRecording || this.isRecording[column][scene])
            this.sendSteady(column, COLOR_RECORDING_HI);
        else if (this.isRecordingQueued[column][scene])
            this.sendPulsing(column, COLOR_ARM_DIM, CHANNEL_PULSE_FAST, COLOR_RECORDING_HI);
        else
            this.sendSteady(column, COLOR_ARM_DIM);
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

    private void sendSteady(final int column, final int color)
    {
        this.midiOut.sendMidi(CHANNEL_STATIC, START_NOTE + column, color);
    }
}
