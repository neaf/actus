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
 * Rows run top-to-bottom in ascending scene order: {@link #ACTIVE_ROW} (always row 0, the
 * topmost row - there is nothing shown above activeScene, by design), then {@link #FORWARD}
 * rows below it. Out-of-range rows are left empty. Only the active row and the bottom
 * track-name row get full height ({@link #FULL_H}) - the {@code FORWARD} rows are half that
 * ({@link #HALF_H}), which is what makes room for 4 of them (matching
 * {@link Push2ClipJumpRow}'s next-4-clips shortcut) in the same 160px screen height. The bottom
 * row ({@link #TRACK_NAME_ROW}) is not a scene row at all - it always shows the 8 tracks' names,
 * so a column is identifiable regardless of which scene happens to be active.
 *
 * The white left/right strip marks {@code activeScene} - the scene Bitwig itself reports as
 * selected (see {@link Push2SceneButtons}'s class doc), always drawn on row 0. A separate green
 * underline marks {@code playingScene} - what Scene Launch actually launched most recently,
 * cleared by Stop All Clips - on whichever row it falls in, if it's currently within the
 * visible window at all; the two can be the same row (both markers show), different rows, or
 * {@code playingScene} can be off-screen entirely (no marker drawn) or unset (-1, nothing
 * launched yet).
 */
public class Push2SessionDisplay
{
    private static final int NUM_TRACKS = 8;

    private static final int ACTIVE_ROW = 0;
    private static final int FORWARD    = 4;
    private static final int SCENE_ROWS = 1 + FORWARD;
    private static final int TRACK_NAME_ROW = SCENE_ROWS;
    private static final int ROWS       = SCENE_ROWS + 1;

    private static final int MAX_SCENES = Push2ControllerExtension.MAX_SCENES;

    private static final int    WIDTH   = 960;
    private static final int    HEIGHT  = 160;
    private static final double CELL_W  = WIDTH / (double) NUM_TRACKS;
    private static final double PADDING_X = 1.5;
    private static final double PADDING_Y = 1.5;

    // Vertical "slot" accounting: FORWARD rows are 1 slot tall, the active row and the
    // track-name row are 2 (i.e. full height). Dividing HEIGHT by the total slot count means the
    // whole 160px is used exactly, with no leftover gap.
    private static final double HALF_H = HEIGHT / (double) (FORWARD + 4);
    private static final double FULL_H = HALF_H * 2;

    // Precomputed per-row top-Y and height, indexed by row (0 is ACTIVE_ROW, then FORWARD rows,
    // TRACK_NAME_ROW is the last one) - rows no longer share one uniform CELL_H.
    private static final double [] ROW_TOP    = new double [ROWS];
    private static final double [] ROW_HEIGHT = new double [ROWS];
    static
    {
        double y = 0;
        for (int row = 0; row < ROWS; row++)
        {
            final double h = row == ACTIVE_ROW || row == TRACK_NAME_ROW ? FULL_H : HALF_H;
            ROW_TOP[row] = y;
            ROW_HEIGHT[row] = h;
            y += h;
        }
    }

    private final ControllerHost host;
    private final Push2Display   display;
    private final AtomicInteger  activeScene;
    private final AtomicInteger  playingScene;

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

    public Push2SessionDisplay(final ControllerHost host, final Push2Display display, final TrackBank trackBank, final AtomicInteger activeScene, final AtomicInteger playingScene)
    {
        this.host = host;
        this.display = display;
        this.activeScene = activeScene;
        this.playingScene = playingScene;

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
        if (current >= 0 && scene >= current && scene <= current + FORWARD)
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
                    final int scene = current + row;
                    if (scene < 0 || scene >= MAX_SCENES)
                        continue;

                    if (row == ACTIVE_ROW)
                        this.drawCurrentRowMarker(gc);

                    for (int track = 0; track < NUM_TRACKS; track++)
                        this.drawCell(gc, track, row, scene, row == ACTIVE_ROW);

                    if (scene == this.playingScene.get())
                        this.drawPlayingRowMarker(gc, row);
                }
            }

            for (int track = 0; track < NUM_TRACKS; track++)
                this.drawTrackName(gc, track);
        });
    }

    private void drawCurrentRowMarker(final GraphicsOutput gc)
    {
        gc.setColor(1, 1, 1);
        gc.rectangle(0, ROW_TOP[ACTIVE_ROW], 3, ROW_HEIGHT[ACTIVE_ROW]);
        gc.fill();
        gc.rectangle(WIDTH - 3, ROW_TOP[ACTIVE_ROW], 3, ROW_HEIGHT[ACTIVE_ROW]);
        gc.fill();
    }

    /**
     * Thin green bar along the bottom edge of the row that {@code playingScene} falls in -
     * drawn after that row's cells, so it sits on top of them rather than under. Independent of
     * {@link #drawCurrentRowMarker} (the white strip); both can be drawn for the same row.
     */
    private static final double PLAYING_STRIP_H = 3;

    private void drawPlayingRowMarker(final GraphicsOutput gc, final int row)
    {
        gc.setColor(0, 1, 0);
        gc.rectangle(0, ROW_TOP[row] + ROW_HEIGHT[row] - PLAYING_STRIP_H, WIDTH, PLAYING_STRIP_H);
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
        final double y = ROW_TOP[TRACK_NAME_ROW];
        final double h = ROW_HEIGHT[TRACK_NAME_ROW];
        final double padding = 3;
        final double maxWidth = CELL_W - padding * 2;

        gc.save();
        gc.rectangle(x, y, CELL_W, h);
        gc.clip();

        gc.setColor(1, 1, 1);
        gc.setFontSize(12);

        final double textWidth = gc.getTextExtents(name).getWidth();
        if (textWidth <= maxWidth)
        {
            gc.moveTo(x + (CELL_W - textWidth) / 2, y + h / 2 + 4);
            gc.showText(name);
        }
        else
        {
            String truncated = name;
            while (truncated.length() > 1 && gc.getTextExtents(truncated + "...").getWidth() > maxWidth)
                truncated = truncated.substring(0, truncated.length() - 1);
            gc.moveTo(x + padding, y + h / 2 + 4);
            gc.showText(truncated + "...");
        }

        gc.restore();
    }

    /** Non-current rows are dimmed so the current-scene row stands out. */
    private static final float DIM = 0.18f;

    private void drawCell(final GraphicsOutput gc, final int track, final int row, final int scene, final boolean isCurrentRow)
    {
        final boolean halfRow = ROW_HEIGHT[row] < FULL_H - 0.01;
        final double x = track * CELL_W + PADDING_X;
        final double y = ROW_TOP[row] + PADDING_Y;
        final double w = CELL_W - PADDING_X * 2;
        final double h = ROW_HEIGHT[row] - PADDING_Y * 2;

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

            gc.setLineWidth(halfRow ? 3 : 4);
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
            gc.setFontSize(halfRow ? 7 : 9);
            gc.moveTo(x + 3, y + h - (halfRow ? 3 : 4));
            gc.showText(name);
        }

        gc.restore();
    }
}
