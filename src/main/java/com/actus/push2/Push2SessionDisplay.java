package com.actus.push2;

import java.util.concurrent.atomic.AtomicInteger;

import com.bitwig.extension.api.graphics.GraphicsOutput;
import com.bitwig.extension.controller.api.ClipLauncherSlotBank;
import com.bitwig.extension.controller.api.ControllerHost;
import com.bitwig.extension.controller.api.Track;
import com.bitwig.extension.controller.api.TrackBank;

/**
 * Session clip grid on the Push 2 screen, adapted from DrivenByMoss's SessionMode screen
 * rendering (see CREDITS.md). Screen only, no relation to the pad grid.
 *
 * Rows run top-to-bottom in ascending scene order: {@link #BACK} rows above activeScene, then
 * activeScene, then {@link #FORWARD} rows below. Out-of-range rows are left empty. The bottom
 * row ({@link #TRACK_NAME_ROW}) is not a scene row at all - it always shows the 8 tracks' names,
 * replacing what would otherwise be the last (furthest-forward) clip row, so a column is
 * identifiable regardless of which scene happens to be active.
 */
public class Push2SessionDisplay
{
    private static final int NUM_TRACKS = 8;

    private static final int BACK       = 2;
    private static final int FORWARD    = 2;
    private static final int SCENE_ROWS = BACK + 1 + FORWARD;
    private static final int TRACK_NAME_ROW = SCENE_ROWS;
    private static final int ROWS       = SCENE_ROWS + 1;

    private static final int MAX_SCENES = Push2ControllerExtension.MAX_SCENES;

    private static final int    WIDTH   = 960;
    private static final int    HEIGHT  = 160;
    private static final double CELL_W  = WIDTH / (double) NUM_TRACKS;
    private static final double CELL_H  = HEIGHT / (double) ROWS;
    private static final double PADDING_X = 1.5;
    private static final double PADDING_Y = 1.5;

    private final ControllerHost host;
    private final Push2Display   display;
    private final AtomicInteger  activeScene;

    private final boolean [] [] hasContent  = new boolean [NUM_TRACKS] [MAX_SCENES];
    private final boolean [] [] isPlaying   = new boolean [NUM_TRACKS] [MAX_SCENES];
    private final boolean [] [] isQueued    = new boolean [NUM_TRACKS] [MAX_SCENES];
    private final boolean [] [] isRecording = new boolean [NUM_TRACKS] [MAX_SCENES];
    private final float [] [] [] clipColor  = new float [NUM_TRACKS] [MAX_SCENES] [3];
    private final String [] [] clipName     = new String [NUM_TRACKS] [MAX_SCENES];
    private final String []     trackName   = new String [NUM_TRACKS];

    // Multiple clips launching "at the same time" in Bitwig still arrive as separate observer
    // callbacks, one per slot. Without coalescing, each one triggered its own immediate full
    // redraw+USB-send, so simultaneous launches visibly lit up one after another instead of
    // together. This defers the actual repaint to the end of the current processing tick and
    // collapses any number of redraw requests within it into one.
    private boolean redrawScheduled;

    public Push2SessionDisplay(final ControllerHost host, final Push2Display display, final TrackBank trackBank, final AtomicInteger activeScene)
    {
        this.host = host;
        this.display = display;
        this.activeScene = activeScene;

        for (int t = 0; t < NUM_TRACKS; t++)
        {
            final int col = t;
            final Track track = trackBank.getItemAt(t);
            final ClipLauncherSlotBank slots = track.clipLauncherSlotBank();

            track.name().addValueObserver(value -> {
                this.trackName[col] = value;
                this.scheduleRedraw();
            });

            slots.addHasContentObserver((slot, value) -> this.update(this.hasContent, col, slot, value));
            slots.addIsPlayingObserver((slot, value) -> this.update(this.isPlaying, col, slot, value));
            slots.addIsPlaybackQueuedObserver((slot, value) -> this.update(this.isQueued, col, slot, value));
            slots.addIsRecordingObserver((slot, value) -> this.update(this.isRecording, col, slot, value));
            slots.addColorObserver((slot, red, green, blue) -> {
                if (slot < MAX_SCENES)
                {
                    this.clipColor[col][slot][0] = red;
                    this.clipColor[col][slot][1] = green;
                    this.clipColor[col][slot][2] = blue;
                }
                this.redrawIfVisible(slot);
            });
            slots.addNameObserver((slot, value) -> {
                if (slot < MAX_SCENES)
                    this.clipName[col][slot] = value;
                this.redrawIfVisible(slot);
            });
        }
    }

    private void update(final boolean [] [] cache, final int column, final int scene, final boolean value)
    {
        if (scene < MAX_SCENES)
            cache[column][scene] = value;
        this.redrawIfVisible(scene);
    }

    private void redrawIfVisible(final int scene)
    {
        final int current = this.activeScene.get();
        if (current >= 0 && scene >= current - BACK && scene <= current + FORWARD)
            this.scheduleRedraw();
    }

    private void scheduleRedraw()
    {
        if (this.redrawScheduled)
            return;
        this.redrawScheduled = true;
        this.host.scheduleTask(() -> {
            this.redrawScheduled = false;
            this.redraw();
        }, 0);
    }

    public void redraw()
    {
        this.display.render(gc -> {
            gc.setColor(0, 0, 0);
            gc.rectangle(0, 0, WIDTH, HEIGHT);
            gc.fill();

            final int current = this.activeScene.get();
            if (current >= 0)
            {
                for (int row = 0; row < SCENE_ROWS; row++)
                {
                    final int scene = current + (row - BACK);
                    if (scene < 0 || scene >= MAX_SCENES)
                        continue;

                    if (row == BACK)
                        this.drawCurrentRowMarker(gc);

                    for (int track = 0; track < NUM_TRACKS; track++)
                        this.drawCell(gc, track, row, scene, row == BACK);
                }
            }

            for (int track = 0; track < NUM_TRACKS; track++)
                this.drawTrackName(gc, track);
        });
    }

    private void drawCurrentRowMarker(final GraphicsOutput gc)
    {
        gc.setColor(1, 1, 1);
        gc.rectangle(0, BACK * CELL_H, 3, CELL_H);
        gc.fill();
        gc.rectangle(WIDTH - 3, BACK * CELL_H, 3, CELL_H);
        gc.fill();
    }

    /**
     * Bottom row - always shows the track name, regardless of activeScene. Centered when it
     * fits; a name too wide for the cell is left-aligned and truncated with "..." instead of
     * being centered and clipped from both sides.
     */
    private void drawTrackName(final GraphicsOutput gc, final int track)
    {
        final String name = this.trackName[track];
        if (name == null || name.isEmpty())
            return;

        final double x = track * CELL_W;
        final double y = TRACK_NAME_ROW * CELL_H;
        final double padding = 3;
        final double maxWidth = CELL_W - padding * 2;

        gc.save();
        gc.rectangle(x, y, CELL_W, CELL_H);
        gc.clip();

        gc.setColor(1, 1, 1);
        gc.setFontSize(12);

        final double textWidth = gc.getTextExtents(name).getWidth();
        if (textWidth <= maxWidth)
        {
            gc.moveTo(x + (CELL_W - textWidth) / 2, y + CELL_H / 2 + 4);
            gc.showText(name);
        }
        else
        {
            String truncated = name;
            while (truncated.length() > 1 && gc.getTextExtents(truncated + "...").getWidth() > maxWidth)
                truncated = truncated.substring(0, truncated.length() - 1);
            gc.moveTo(x + padding, y + CELL_H / 2 + 4);
            gc.showText(truncated + "...");
        }

        gc.restore();
    }

    /** Non-current rows are dimmed so the current-scene row stands out. */
    private static final float DIM = 0.18f;

    private void drawCell(final GraphicsOutput gc, final int track, final int row, final int scene, final boolean isCurrentRow)
    {
        final double x = track * CELL_W + PADDING_X;
        final double y = row * CELL_H + PADDING_Y;
        final double w = CELL_W - PADDING_X * 2;
        final double h = CELL_H - PADDING_Y * 2;

        if (!this.hasContent[track][scene])
            return;

        gc.save();
        gc.rectangle(x, y, w, h);
        gc.clip();

        final float [] rgb = this.clipColor[track][scene];
        final boolean recording = this.isRecording[track][scene];
        final boolean playing = this.isPlaying[track][scene];
        final boolean queued = this.isQueued[track][scene];
        final boolean active = recording || playing || queued;

        // A playing clip stays at full brightness even outside the current-scene row, so it's
        // visible at a glance regardless of which row happens to be active right now.
        final float dim = isCurrentRow || playing ? 1f : DIM;

        if (active)
        {
            gc.setColor(rgb[0] * dim, rgb[1] * dim, rgb[2] * dim);
            gc.rectangle(x, y, w, h);
            gc.fill();

            if (recording)
                gc.setColor(dim, 0, 0);
            else if (playing)
                gc.setColor(dim, dim, dim);
            else
                gc.setColor(0.6 * dim, 0.6 * dim, 0.6 * dim); // queued

            gc.setLineWidth(4);
        }
        else
        {
            // Stopped with content: zero fill, outline only in the clip's own color.
            gc.setColor(rgb[0] * dim, rgb[1] * dim, rgb[2] * dim);
            gc.setLineWidth(2);
        }

        gc.rectangle(x + 1, y + 1, w - 2, h - 2);
        gc.stroke();

        final String name = this.clipName[track][scene];
        if (name != null && !name.isEmpty())
        {
            // Black text reads on the bright active fill; the blank stopped background needs
            // the clip's own color so it stays legible without a fill to contrast against.
            if (active)
                gc.setColor(0, 0, 0, 0.85);
            else
                gc.setColor(rgb[0] * dim, rgb[1] * dim, rgb[2] * dim);
            gc.setFontSize(9);
            gc.moveTo(x + 3, y + h - 4);
            gc.showText(name);
        }

        gc.restore();
    }
}
