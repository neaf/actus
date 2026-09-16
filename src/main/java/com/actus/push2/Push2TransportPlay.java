package com.actus.push2;

import java.util.concurrent.atomic.AtomicInteger;

import com.bitwig.extension.controller.api.MidiOut;

/**
 * Owns Push 2's dedicated Play button (CC 85, RGB-colored - not part of the 64-pad grid).
 * Repurposed away from toggling transport play/stop and finishing recordings - per explicit user
 * request - to a scene-advancing shortcut: all the actual logic lives in
 * {@link Push2SceneButtons#onPlayButtonPressed}, the class that already owns every other
 * {@code activeScene}/{@code playingScene} mutation. This class is just the button's thin MIDI
 * dispatch + LED, same as it always was.
 */
public class Push2TransportPlay
{
    static final int PLAY_CC = 85;

    private static final int COLOR_STOPPED = 1;  // dim grey - matches Push2StopRow/SceneButtons convention
    private static final int COLOR_PLAYING = 21; // green, high brightness

    private final MidiOut           midiOut;
    private final Push2SceneButtons sceneButtons;
    private final AtomicInteger     playingScene;

    public Push2TransportPlay(final MidiOut midiOut, final Push2SceneButtons sceneButtons, final AtomicInteger playingScene)
    {
        this.midiOut = midiOut;
        this.sceneButtons = sceneButtons;
        this.playingScene = playingScene;
    }

    /** Called from the extension's central MIDI dispatch when the Play button is pressed. */
    public void onButtonPressed()
    {
        this.sceneButtons.onPlayButtonPressed();
    }

    /** Lit while {@code playingScene} is set - not raw transport state, see class doc. */
    public void redraw()
    {
        this.midiOut.sendMidi(0xB0, PLAY_CC, this.playingScene.get() >= 0 ? COLOR_PLAYING : COLOR_STOPPED);
    }
}
