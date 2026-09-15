package com.actus.push2;

import java.util.concurrent.atomic.AtomicInteger;

import com.bitwig.extension.controller.api.MidiOut;
import com.bitwig.extension.controller.api.SceneBank;

/**
 * Owns everything that changes or triggers {@code activeScene}.
 *
 * The one wired Scene Launch button ({@link #LAUNCH_CC}, out of Push 2's 8 dedicated Scene
 * Launch buttons at CC 36-43 - not part of the 64-pad grid). The other 7 are left dark/ignored,
 * same treatment as pad rows 1-7. CC 36 (DrivenByMoss's SCENE1) is physically aligned with the
 * bottom pad row that {@link Push2ClipLaunchRow} uses, matching DrivenByMoss's own
 * `PUSH_BUTTON_SCENE1 + 7 - i` layout (SCENE1 = bottom, SCENE8 = top).
 *
 * The Up/Down cursor buttons (CC 46/47, {@code PUSH_BUTTON_UP}/{@code _DOWN} in DrivenByMoss)
 * move {@code activeScene} by one - added because there is otherwise no way to reach any scene
 * but the bootstrap one. Navigation only; it does not launch anything.
 *
 * Scene Launch behaves exactly like clicking that scene's own play button in Bitwig's session
 * sidebar: it always launches {@code activeScene}, it never picks a different scene itself. If
 * {@code activeScene} is still unset (-1) when a Launch, Up/Down, or the extension's own startup
 * bootstrap happens, it's set first - to the first scene (0-127) that actually has clips, or
 * scene 0 if none do - rather than blindly assuming scene 0 is useful.
 */
public class Push2SceneButtons
{
    static final int LAUNCH_CC = 36;

    // One row up from LAUNCH_CC, aligned with Push2StopRow (notes 44-51) the same way LAUNCH_CC
    // aligns with Push2ClipLaunchRow (notes 36-43). Mirrors Bitwig's own "Stop All Clips" button
    // in the Clip Launcher's scenes sidebar - stops every track's playing clip, unrelated to
    // activeScene.
    static final int STOP_ALL_CC = 37;

    // Push 2's physical Octave Up/Down buttons, repurposed as an extra pair of scene
    // navigation buttons (same one-scene-at-a-time move as the Up/Down cursor buttons below,
    // just physically separate) since Actus has no note/octave transposition feature to give
    // them their usual job. Monochrome single-LED buttons, not RGB - the CC value sent is still
    // a Push2Colors palette index, resolved against the palette entry's white field (see
    // CLAUDE.md's white-LED gotcha).
    static final int OCTAVE_DOWN_CC = 54; // later scenes - matches DOWN_CC's direction
    static final int OCTAVE_UP_CC   = 55; // earlier scenes - matches UP_CC's direction

    private static final int COLOR_OFF    = 0;
    private static final int COLOR_ACTIVE = 21; // green, high brightness
    private static final int COLOR_STOP   = 1;  // dim grey - matches Push2StopRow, not a loud alert color
    private static final int MONO_LIT     = 127; // full brightness for the monochrome octave buttons

    private final MidiOut       midiOut;
    private final SceneBank     sceneBank;
    private final AtomicInteger activeScene;
    private final Runnable      onSceneChanged;

    public Push2SceneButtons(final MidiOut midiOut, final SceneBank sceneBank, final AtomicInteger activeScene, final Runnable onSceneChanged)
    {
        this.midiOut = midiOut;
        this.sceneBank = sceneBank;
        this.activeScene = activeScene;
        this.onSceneChanged = onSceneChanged;

        // Bitwig's values are lazy - reading .get() on one that was never observed or marked
        // interested throws. findInitialScene() reads clipCount() synchronously for every
        // scene, so every one of them needs this up front.
        for (int i = 0; i < Push2ControllerExtension.MAX_SCENES; i++)
            sceneBank.getScene(i).clipCount().markInterested();
    }

    /** Called once from the extension's init() so the row shows real state before any press. */
    public void bootstrapWithoutLaunching()
    {
        if (this.activeScene.get() < 0)
        {
            this.activeScene.set(this.findInitialScene());
            this.onSceneChanged.run();
        }
    }

    /** Called from the extension's central MIDI dispatch when the Scene Launch button is pressed. */
    public void onButtonPressed()
    {
        this.bootstrapWithoutLaunching();
        this.sceneBank.getScene(this.activeScene.get()).launch();
    }

    /** Called from the central MIDI dispatch when the Stop All Clips button is pressed. */
    public void onStopAllPressed()
    {
        this.sceneBank.stop();
    }

    /** Called from the central MIDI dispatch for the Up (delta -1) / Down (delta +1) buttons. */
    public void onNavigate(final int delta)
    {
        final int current = this.activeScene.get() < 0 ? this.findInitialScene() : this.activeScene.get();
        final int max = Push2ControllerExtension.MAX_SCENES - 1;
        this.activeScene.set(Math.max(0, Math.min(max, current + delta)));
        this.onSceneChanged.run();
    }

    public void redraw()
    {
        final int color = this.activeScene.get() < 0 ? COLOR_OFF : COLOR_ACTIVE;
        this.midiOut.sendMidi(0xB0, LAUNCH_CC, color);
        this.midiOut.sendMidi(0xB0, STOP_ALL_CC, COLOR_STOP); // always available, unlike scene launch
        this.midiOut.sendMidi(0xB0, OCTAVE_DOWN_CC, MONO_LIT); // always available - just lit so it's visible in the dark
        this.midiOut.sendMidi(0xB0, OCTAVE_UP_CC, MONO_LIT);
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
