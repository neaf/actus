package com.actus.pickup;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.bitwig.extension.controller.ControllerExtension;
import com.bitwig.extension.controller.api.Clip;
import com.bitwig.extension.controller.api.ClipLauncherSlotBank;
import com.bitwig.extension.controller.api.ControllerHost;
import com.bitwig.extension.controller.api.MidiIn;
import com.bitwig.extension.controller.api.NoteInput;
import com.bitwig.extension.controller.api.Track;
import com.bitwig.extension.controller.api.TrackBank;
import com.bitwig.extension.controller.api.Transport;

/**
 * Bitwig has no "retrospective record" - a note already held when recording starts produces no
 * Note On event, so it's missing from the clip. Two fixes, one per recording type.
 *
 * <p>Arranger recording: live retrigger - resend a fresh Note On the instant {@code Transport
 * .isArrangerRecordEnabled()} flips true. Measured ~40ms late on real hardware, invariant across
 * audio buffer sizes - a fixed Bitwig controller-script dispatch floor, not audio latency, not
 * fixable in script code. Accepted (arranger has no queued/stopped signal to do better with).
 *
 * <p>Clip launcher recording: writes the note directly into the clip's data via {@link
 * Clip#setStep} at beat 0 instead of resending anything audible - no flam, no drop-risk. A
 * placeholder ({@link #INITIAL_PLACEHOLDER_BEATS}) is written the instant recording starts (see
 * {@link #startPickup}) - that's what triggers {@code showInEditor()} (a one-time, deliberate UI
 * focus switch to the recording clip) and the {@link #CURSOR_SETTLE_DELAY_MS} queueing handshake
 * (Bitwig's cursor-follow isn't guaranteed to have landed by the next script tick). Writing the
 * placeholder immediately, not at the first real event, means later corrections almost always
 * land as fast direct writes instead of racing the settle delay near the end of a take.
 * {@link #finalizeNote} then corrects the duration once it's known: on release ({@link
 * #handleMidi}), predicted just before a quantized stop boundary ({@link #schedulePredictiveWrite},
 * using {@code isStopQueued()} for advance notice - firing early only makes a stored note's end
 * slightly off, unlike a live MIDI resend where early means silently dropped), or as a fallback
 * once recording actually stops ({@link #finishPickup}) - idempotent per pitch via {@link
 * #pickupFinalizedPitches}. {@link #extensionTick}, a persistent ~1/16-note heartbeat (same
 * pattern as {@code Push2ControllerExtension.keepDisplayAlive}), continuously re-extends any
 * still-unfinalized note as a safety net for stops with no predictive signal at all.
 *
 * <p>Automatic and targeted at the exact recording slot - no manual step - and additive
 * ({@code setStep} only touches one cell), so it coexists with everything else already recorded.
 *
 * <p>Pickup state is global, not per-slot - only one in-flight pickup is tracked at a time.
 */
public class NotePickupControllerExtension extends ControllerExtension
{
    /** Large enough to cover any real project without paging - same convention as Push2's MAX_SCENES. */
    private static final int MAX_TRACKS = 128;
    private static final int MAX_SCENES = 128;

    private static final long CURSOR_SETTLE_DELAY_MS = 150;

    /** How far before the predicted stop boundary to finalize a still-held note - "the last 1/16 note". */
    private static final double PREDICTIVE_WRITE_MARGIN_BEATS = 0.25;

    /** Nominal duration written at record-start, before any note's real final duration is known. */
    private static final double INITIAL_PLACEHOLDER_BEATS = 1.0;

    /** Floor on the extension-tick interval so an extreme tempo can't spin it into a tight loop. */
    private static final long MIN_TICK_INTERVAL_MS = 20;

    private record PendingNote(int channel, int pitch, int velocity, double durationBeats)
    {
    }

    /** pitch -> [channel, velocity] for notes currently held down. */
    private final Map<Integer, int[]> heldNotes = new HashMap<>();

    /** Rising/falling-edge detection for observers that fire once immediately with current state. */
    private final boolean[][] wasRecording = new boolean[MAX_TRACKS][MAX_SCENES];
    private final boolean[][] recordingKnown = new boolean[MAX_TRACKS][MAX_SCENES];
    private final boolean[][] wasStopQueued = new boolean[MAX_TRACKS][MAX_SCENES];
    private final boolean[][] stopQueuedKnown = new boolean[MAX_TRACKS][MAX_SCENES];

    private final ClipLauncherSlotBank[] slotBanksByTrack = new ClipLauncherSlotBank[MAX_TRACKS];

    private boolean pickupActive;
    private int pickupTrack;
    private int pickupScene;
    private double pickupRecordStartBeats;
    private Map<Integer, int[]> pickupHeldNotes = new HashMap<>();
    /** Pitches whose FINAL duration has been written - guards against a stale correction re-firing. */
    private final Set<Integer> pickupFinalizedPitches = new HashSet<>();
    private final List<PendingNote> pickupPendingWrites = new ArrayList<>();
    private boolean pickupEditorShown;
    private boolean pickupEditorSettled;

    private NoteInput noteInput;
    private Transport transport;
    private Clip launcherCursorClip;
    private volatile boolean running;

    protected NotePickupControllerExtension(final NotePickupControllerExtensionDefinition definition, final ControllerHost host)
    {
        super(definition, host);
    }

    @Override
    public void init()
    {
        final ControllerHost host = this.getHost();
        final MidiIn midiIn = host.getMidiInPort(0);

        this.noteInput = midiIn.createNoteInput("Record Note Pickup", "80????", "90????");
        // Keep receiving every note event ourselves too, alongside Bitwig's automatic routing.
        this.noteInput.setShouldConsumeEvents(false);
        midiIn.setMidiCallback(this::handleMidi);

        this.transport = host.createTransport();
        this.transport.isArrangerRecordEnabled().addValueObserver(isRecording -> {
            if (isRecording)
                this.retriggerHeldNotes();
        });
        // Read on-demand (not observed) - must markInterested().
        this.transport.getPosition().markInterested();
        this.transport.tempo().markInterested();
        this.transport.defaultLaunchQuantization().markInterested();

        // Width is irrelevant - setStep is only ever called at x=0. Height 128 covers the full
        // MIDI pitch range so no key-scrolling is ever needed.
        this.launcherCursorClip = host.createLauncherCursorClip(16, 128);

        // createMainTrackBank (not createTrackBank): only audio/instrument/hybrid tracks, same
        // reasoning as Push2ControllerExtension.
        final TrackBank trackBank = host.createMainTrackBank(MAX_TRACKS, 0, MAX_SCENES);
        for (int t = 0; t < MAX_TRACKS; t++)
        {
            final Track track = trackBank.getItemAt(t);
            final ClipLauncherSlotBank slots = track.clipLauncherSlotBank();
            this.slotBanksByTrack[t] = slots;
            final int trackIndex = t;
            slots.addIsRecordingObserver((scene, isRecording) -> this.onSlotRecordingChanged(trackIndex, scene, isRecording));
            slots.addIsStopQueuedObserver((scene, isStopQueued) -> this.onSlotStopQueuedChanged(trackIndex, scene, isStopQueued));
        }

        this.running = true;
        host.scheduleTask(this::extensionTick, 100);

        host.showPopupNotification("Actus Record Note Pickup initialized");
        host.println("Actus Record Note Pickup: init() complete");
    }

    @Override
    public void exit()
    {
        this.running = false;
        this.getHost().showPopupNotification("Actus Record Note Pickup exited");
    }

    @Override
    public void flush()
    {
    }

    private void onSlotRecordingChanged(final int track, final int scene, final boolean isRecording)
    {
        final boolean isNewRecording = isRecording && this.recordingKnown[track][scene] && !this.wasRecording[track][scene];
        final boolean isNewlyStopped = !isRecording && this.recordingKnown[track][scene] && this.wasRecording[track][scene];
        this.recordingKnown[track][scene] = true;
        this.wasRecording[track][scene] = isRecording;

        if (isNewRecording)
            this.startPickup(track, scene);
        else if (isNewlyStopped && this.pickupActive && track == this.pickupTrack && scene == this.pickupScene)
            this.finishPickup();
    }

    private void onSlotStopQueuedChanged(final int track, final int scene, final boolean isStopQueued)
    {
        final boolean isNewlyQueued = isStopQueued && this.stopQueuedKnown[track][scene] && !this.wasStopQueued[track][scene];
        this.stopQueuedKnown[track][scene] = true;
        this.wasStopQueued[track][scene] = isStopQueued;

        if (isNewlyQueued && this.pickupActive && track == this.pickupTrack && scene == this.pickupScene)
            this.schedulePredictiveWrite();
    }

    private void startPickup(final int track, final int scene)
    {
        if (this.heldNotes.isEmpty())
        {
            this.pickupActive = false;
            return;
        }

        this.pickupActive = true;
        this.pickupTrack = track;
        this.pickupScene = scene;
        this.pickupRecordStartBeats = this.transport.getPosition().get();
        this.pickupHeldNotes = new HashMap<>(this.heldNotes);
        this.pickupFinalizedPitches.clear();
        this.pickupPendingWrites.clear();
        this.pickupEditorShown = false;
        this.pickupEditorSettled = false;
        this.getHost().println("Note Pickup: recording started on track " + track + " scene " + scene
                + " with " + this.pickupHeldNotes.size() + " held note(s)");

        // Placeholder for every held note right away - see class doc for why immediately, not later.
        for (final Map.Entry<Integer, int[]> entry : this.pickupHeldNotes.entrySet())
            this.writeStep(track, scene, entry.getKey(), entry.getValue()[0], entry.getValue()[1], INITIAL_PLACEHOLDER_BEATS);
    }

    /** Fallback: finalizes any note the release/predictive paths didn't already finalize. */
    private void finishPickup()
    {
        final double stopBeats = this.transport.getPosition().get();
        final int track = this.pickupTrack;
        final int scene = this.pickupScene;
        for (final Map.Entry<Integer, int[]> entry : this.pickupHeldNotes.entrySet())
        {
            final int pitch = entry.getKey();
            if (this.pickupFinalizedPitches.contains(pitch))
                continue;
            final double duration = stopBeats - this.pickupRecordStartBeats;
            if (duration > 0)
                this.finalizeNote(track, scene, pitch, entry.getValue()[0], entry.getValue()[1], duration);
        }
        this.pickupActive = false;
    }

    /** Schedules a correction for every still-held note, timed just before the predicted stop boundary. */
    private void schedulePredictiveWrite()
    {
        final double intervalBeats = parseQuantizationToBeats(this.transport.defaultLaunchQuantization().get());
        if (intervalBeats <= 0)
            return; // unparseable/no quantization - finishPickup()'s fallback covers it

        final double currentBeats = this.transport.getPosition().get();
        final double boundaryBeats = Math.ceil(currentBeats / intervalBeats) * intervalBeats;
        final double predictedDurationBeats = boundaryBeats - this.pickupRecordStartBeats;
        if (predictedDurationBeats <= 0)
            return;

        final double writeAtBeats = boundaryBeats - PREDICTIVE_WRITE_MARGIN_BEATS;
        final double bpm = this.transport.tempo().getRaw();
        final double delayMs = Math.max(0.0, (writeAtBeats - currentBeats) * (60000.0 / bpm));

        final int track = this.pickupTrack;
        final int scene = this.pickupScene;
        this.getHost().scheduleTask(() -> this.finalizeStillHeldNotes(track, scene, predictedDurationBeats), (long) delayMs);
    }

    private void finalizeStillHeldNotes(final int track, final int scene, final double durationBeats)
    {
        if (!this.pickupActive || track != this.pickupTrack || scene != this.pickupScene)
            return; // session moved on since this was scheduled - stale, ignore

        for (final Map.Entry<Integer, int[]> entry : this.pickupHeldNotes.entrySet())
        {
            final int pitch = entry.getKey();
            if (!this.pickupFinalizedPitches.contains(pitch))
                this.finalizeNote(track, scene, pitch, entry.getValue()[0], entry.getValue()[1], durationBeats);
        }
    }

    /**
     * Persistent heartbeat (same pattern as Push2ControllerExtension.keepDisplayAlive) - safety
     * net that keeps a still-held, not-yet-finalized note's duration close to correct even if
     * nothing else ever finalizes it in time (e.g. an abrupt, non-quantized stop).
     */
    private void extensionTick()
    {
        if (!this.running)
            return;

        if (this.pickupActive)
        {
            final double elapsedBeats = this.transport.getPosition().get() - this.pickupRecordStartBeats;
            if (elapsedBeats > 0)
            {
                for (final Map.Entry<Integer, int[]> entry : this.pickupHeldNotes.entrySet())
                {
                    final int pitch = entry.getKey();
                    if (!this.pickupFinalizedPitches.contains(pitch))
                        this.writeStep(this.pickupTrack, this.pickupScene, pitch, entry.getValue()[0], entry.getValue()[1], elapsedBeats);
                }
            }
        }

        final double bpm = this.transport.tempo().getRaw();
        final long intervalMs = Math.max(MIN_TICK_INTERVAL_MS, (long) ((60000.0 / bpm) / 4.0));
        this.getHost().scheduleTask(this::extensionTick, intervalMs);
    }

    /** Writes a note's real, final duration - guarded so only the first caller per pitch wins. */
    private void finalizeNote(final int track, final int scene, final int pitch, final int channel, final int velocity, final double durationBeats)
    {
        if (!this.pickupFinalizedPitches.add(pitch))
            return;
        this.writeStep(track, scene, pitch, channel, velocity, durationBeats);
    }

    /** Writes (or queues until the editor-focus handshake settles) one note-step - see class doc. */
    private void writeStep(final int track, final int scene, final int pitch, final int channel, final int velocity, final double durationBeats)
    {
        if (this.pickupEditorSettled)
        {
            this.launcherCursorClip.setStep(channel, 0, pitch, velocity, durationBeats);
            return;
        }

        this.pickupPendingWrites.add(new PendingNote(channel, pitch, velocity, durationBeats));
        if (this.pickupEditorShown)
            return; // already focused the editor and scheduled the flush - this note rides along

        this.pickupEditorShown = true;
        this.slotBanksByTrack[track].showInEditor(scene);
        this.getHost().scheduleTask(this::flushPickupPendingWrites, CURSOR_SETTLE_DELAY_MS);
    }

    private void flushPickupPendingWrites()
    {
        this.pickupEditorSettled = true;
        for (final PendingNote note : this.pickupPendingWrites)
            this.launcherCursorClip.setStep(note.channel(), 0, note.pitch(), note.velocity(), note.durationBeats());
        this.getHost().println("Note Pickup: flushed " + this.pickupPendingWrites.size() + " queued write(s)");
        this.pickupPendingWrites.clear();
    }

    /**
     * Bitwig's launch-quantization enum: "NONE", a bare number ("1", "2", "8" - confirmed on real
     * hardware to mean that many BEATS, not bars) or a fraction ("1/4", "1/8", ...), optionally
     * suffixed "T" for a triplet, same beat-count interpretation. No time-signature conversion.
     */
    private static double parseQuantizationToBeats(final String quantization)
    {
        if (quantization == null || "NONE".equalsIgnoreCase(quantization))
            return -1;

        final boolean triplet = quantization.endsWith("T");
        final String body = triplet ? quantization.substring(0, quantization.length() - 1) : quantization;

        try
        {
            double beats;
            if (body.contains("/"))
            {
                final String[] parts = body.split("/");
                if (parts.length != 2)
                    return -1;
                beats = Double.parseDouble(parts[0]) / Double.parseDouble(parts[1]);
            }
            else
            {
                beats = Double.parseDouble(body);
            }
            if (triplet)
                beats *= 2.0 / 3.0;
            return beats;
        }
        catch (final NumberFormatException ex)
        {
            return -1;
        }
    }

    private void retriggerHeldNotes()
    {
        for (final Map.Entry<Integer, int[]> note : this.heldNotes.entrySet())
        {
            final int pitch = note.getKey();
            final int channel = note.getValue()[0];
            final int velocity = note.getValue()[1];
            this.noteInput.sendRawMidiEvent(0x90 | channel, pitch, velocity);
        }
    }

    /** Single MIDI callback (Bitwig only allows one per MidiIn) - tracks held notes; finalizes a pickup note on release. */
    private void handleMidi(final int status, final int data1, final int data2)
    {
        final int command = status & 0xF0;
        final int channel = status & 0x0F;
        if (command == 0x90 && data2 > 0)
        {
            this.heldNotes.put(data1, new int[] { channel, data2 });
        }
        else if (command == 0x90 || command == 0x80)
        {
            if (this.pickupActive && this.pickupHeldNotes.containsKey(data1) && !this.pickupFinalizedPitches.contains(data1))
            {
                final double releaseBeats = this.transport.getPosition().get();
                final double durationBeats = releaseBeats - this.pickupRecordStartBeats;
                final int[] noteData = this.pickupHeldNotes.get(data1);
                if (durationBeats > 0)
                    this.finalizeNote(this.pickupTrack, this.pickupScene, data1, noteData[0], noteData[1], durationBeats);
            }
            this.heldNotes.remove(data1);
        }
    }
}
