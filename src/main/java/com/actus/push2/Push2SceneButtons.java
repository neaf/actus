package com.actus.push2;

import java.util.concurrent.atomic.AtomicInteger;

import com.bitwig.extension.controller.api.MidiOut;
import com.bitwig.extension.controller.api.SceneBank;
import com.bitwig.extension.controller.api.TrackBank;

/**
 * Owns everything that changes or triggers {@code activeScene}.
 *
 * The one wired Scene Launch button ({@link #LAUNCH_CC}, out of Push 2's 8 dedicated Scene
 * Launch buttons at CC 36-43 - not part of the 64-pad grid). The other 7 are left dark/ignored,
 * same treatment as pad rows 1-7. CC 36 (DrivenByMoss's SCENE1) is physically aligned with the
 * bottom pad row that {@link Push2ClipLaunchRow} uses, matching DrivenByMoss's own
 * `PUSH_BUTTON_SCENE1 + 7 - i` layout (SCENE1 = bottom, SCENE8 = top).
 *
 * {@code activeScene} syncs from Bitwig's own scene selection cursor, not local hardware
 * navigation: every scene's {@code Scene.isSelectedInEditor()} is observed (128 observers, one
 * per scene - cheap, same pattern as the {@code clipCount} loop below), and whichever one flips
 * true becomes {@code activeScene}. This is a real, exposed-to-controller-scripts per-scene
 * property (unlike "is playing" - see CLAUDE.md's audit note, which is about playing state, not
 * selection), so no controller-local navigation bookkeeping is needed: clicking a scene in
 * Bitwig's own Session View is what moves the bottom pad row now. The former Up/Down cursor
 * buttons (CC 46/47) and the Octave Up/Down repurposing (CC 54/55) have both been removed -
 * check git history if this ever needs reviving.
 *
 * Scene Launch behaves exactly like clicking that scene's own play button in Bitwig's session
 * sidebar: it always launches {@code activeScene}, it never picks a different scene itself. If
 * {@code activeScene} is still unset (-1) when a Launch or the extension's own startup bootstrap
 * happens, it's set first - to the first scene (0-127) that actually has clips, or scene 0 if
 * none do - rather than blindly assuming scene 0 is useful; in practice the selection-sync above
 * usually supersedes this almost immediately.
 *
 * {@code playingScene}, a second and deliberately separate piece of controller-local state (not
 * synced from Bitwig - there is no per-scene "is playing" property, see CLAUDE.md), tracks what
 * was actually last launched: only Scene Launch sets it (to whatever {@code activeScene} was at
 * that moment), and only Stop All Clips clears it back to -1. This is what lets
 * {@link Push2SessionDisplay} mark "the scene I'm looking at" and "the scene that's actually
 * playing" independently when they differ.
 *
 * This class also owns the other direction: telling Bitwig's own UI where our cursor is, via its
 * "Show Frame in Clip Launcher" indication feature (a per-controller Bitwig preference - it does
 * nothing unless the script actively drives it). {@code indicationBank}, a second, otherwise
 * unused {@code TrackBank} windowed to exactly 1 scene (unlike the main {@code trackBank}, which
 * stays 128-wide so every scene is directly addressable elsewhere), gets
 * {@code setShouldShowClipLauncherFeedback(true)} called once in the constructor - the same
 * single call every simple DrivenByMoss controller makes (its own
 * `AbstractTrackBankImpl.setIndication` wrapper does nothing else). Bitwig frames whatever falls
 * inside that bank's window on its own; with a 1-wide window that's always exactly one row.
 * {@link #syncIndicationFrame} then just moves that window's {@code scrollPosition()} to
 * {@code activeScene}.
 *
 * **Do not add per-item `ClipLauncherSlotOrScene.setIndication(boolean)` or bank-level
 * `SceneBank`/`ClipLauncherSlotBank.setIndication(boolean)` calls here.** They look like the
 * natural way to fine-tune the frame and compile clean, but that whole family has been
 * deprecated since API version 17 - and unlike the merely-noisy deprecated
 * `SceneBank.scrollTo(int)` (replaced below by `Scrollable.scrollPosition().set(int)`),
 * `setIndication` is confirmed **fatal at runtime on this Bitwig version - it crashes the whole
 * script**, not just logging a warning, despite DrivenByMoss's own `ClipLauncherNavigatorImpl`
 * calling `SceneBank.setIndication` directly elsewhere. Do not reintroduce it without testing
 * against real Bitwig first.
 */
public class Push2SceneButtons
{
    static final int LAUNCH_CC = 36;

    // One row up from LAUNCH_CC, aligned with Push2StopRow (notes 44-51) the same way LAUNCH_CC
    // aligns with Push2ClipLaunchRow (notes 36-43). Mirrors Bitwig's own "Stop All Clips" button
    // in the Clip Launcher's scenes sidebar - stops every track's playing clip, unrelated to
    // activeScene.
    static final int STOP_ALL_CC = 37;

    // Push 2's physical Octave Up/Down buttons - no longer used for scene navigation (see class
    // doc), explicitly turned dark in redraw() rather than left showing stale light from before.
    static final int OCTAVE_DOWN_CC = 54;
    static final int OCTAVE_UP_CC   = 55;

    private static final int COLOR_OFF    = 0;
    private static final int COLOR_ACTIVE = 21; // green, high brightness
    private static final int COLOR_STOP   = 1;  // dim grey - matches Push2StopRow, not a loud alert color

    private final MidiOut       midiOut;
    private final SceneBank     sceneBank;
    private final SceneBank     indicationSceneBank;
    private final AtomicInteger activeScene;
    private final AtomicInteger playingScene;
    private final Runnable      onSceneChanged;

    public Push2SceneButtons(final MidiOut midiOut, final TrackBank trackBank, final TrackBank indicationBank, final AtomicInteger activeScene, final AtomicInteger playingScene, final Runnable onSceneChanged)
    {
        this.midiOut = midiOut;
        this.sceneBank = trackBank.sceneBank();
        this.indicationSceneBank = indicationBank.sceneBank();
        this.activeScene = activeScene;
        this.playingScene = playingScene;
        this.onSceneChanged = onSceneChanged;

        // The only call needed to draw the frame - see class doc. setIndication() (per-scene,
        // per-slot-bank) turned out to be a hard crash at runtime on this deprecated path
        // despite compiling clean and DrivenByMoss calling it elsewhere - do not add it back.
        indicationBank.setShouldShowClipLauncherFeedback(true);

        // Bitwig's values are lazy - reading .get() on one that was never observed or marked
        // interested throws. findInitialScene() reads clipCount() synchronously for every
        // scene, so every one of them needs this up front.
        for (int i = 0; i < Push2ControllerExtension.MAX_SCENES; i++)
            this.sceneBank.getScene(i).clipCount().markInterested();

        // activeScene's real source of truth, going forward: whichever scene Bitwig itself
        // reports as selected. Only the true transition matters - the previously-selected
        // scene's observer firing false around the same time needs no handling.
        for (int i = 0; i < Push2ControllerExtension.MAX_SCENES; i++)
        {
            final int scene = i;
            this.sceneBank.getScene(i).addIsSelectedInEditorObserver(selected -> {
                if (selected)
                {
                    this.activeScene.set(scene);
                    this.syncIndicationFrame();
                    this.onSceneChanged.run();
                }
            });
        }
    }

    /** Called once from the extension's init() so the row shows real state before any press. */
    public void bootstrapWithoutLaunching()
    {
        if (this.activeScene.get() < 0)
        {
            this.activeScene.set(this.findInitialScene());
            this.syncIndicationFrame();
            this.onSceneChanged.run();
        }
    }

    /** Called from the extension's central MIDI dispatch when the Scene Launch button is pressed. */
    public void onButtonPressed()
    {
        this.bootstrapWithoutLaunching();
        this.sceneBank.getScene(this.activeScene.get()).launch();
        this.playingScene.set(this.activeScene.get());
        this.onSceneChanged.run();
    }

    /**
     * Called from the extension's central MIDI dispatch when the Play button
     * ({@link Push2TransportPlay#PLAY_CC}) is pressed. If nothing is currently playing (per
     * {@code playingScene}, not raw transport state), launches {@code activeScene} - same as
     * Scene Launch. If a scene is already playing, launches the next one instead
     * ({@code playingScene + 1}, clamped to the last addressable scene) - a quick way to step
     * through an arrangement one press at a time. Either way, the launched scene is also
     * selected in Bitwig's editor, so {@code activeScene} and the indication frame follow along
     * via the same {@code isSelectedInEditor} observer as a real UI click would trigger.
     */
    public void onPlayButtonPressed()
    {
        this.bootstrapWithoutLaunching();

        final int scene = this.playingScene.get() < 0
            ? this.activeScene.get()
            : Math.min(this.playingScene.get() + 1, Push2ControllerExtension.MAX_SCENES - 1);

        this.sceneBank.getScene(scene).launch();
        this.playingScene.set(scene);
        this.sceneBank.getScene(scene).selectInEditor();
        this.onSceneChanged.run();
    }

    /** Scrolls the 1-row indication window to {@code activeScene} - see class doc. */
    private void syncIndicationFrame()
    {
        final int scene = this.activeScene.get();
        if (scene >= 0)
            this.indicationSceneBank.scrollPosition().set(scene); // Scrollable's non-deprecated position setter, unlike scrollTo(int)
    }

    /** Called from the central MIDI dispatch when the Stop All Clips button is pressed. */
    public void onStopAllPressed()
    {
        this.sceneBank.stop();
        this.playingScene.set(-1);
        this.onSceneChanged.run();
    }

    public void redraw()
    {
        final int color = this.activeScene.get() < 0 ? COLOR_OFF : COLOR_ACTIVE;
        this.midiOut.sendMidi(0xB0, LAUNCH_CC, color);
        this.midiOut.sendMidi(0xB0, STOP_ALL_CC, COLOR_STOP); // always available, unlike scene launch
        this.midiOut.sendMidi(0xB0, OCTAVE_DOWN_CC, COLOR_OFF); // no longer used - see class doc
        this.midiOut.sendMidi(0xB0, OCTAVE_UP_CC, COLOR_OFF);
    }

    /** First scene (0-127) with any clips, or 0 if the project has none - not a blind guess. */
    private int findInitialScene()
    {
        for (int i = 0; i < Push2ControllerExtension.MAX_SCENES; i++)
            if (this.sceneBank.getScene(i).clipCount().get() > 0)
                return i;
        return 0;
    }
}
