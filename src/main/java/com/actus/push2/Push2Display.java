package com.actus.push2;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.bitwig.extension.api.MemoryBlock;
import com.bitwig.extension.api.graphics.Bitmap;
import com.bitwig.extension.api.graphics.BitmapFormat;
import com.bitwig.extension.api.graphics.FontExtents;
import com.bitwig.extension.api.graphics.Renderer;
import com.bitwig.extension.controller.api.ControllerHost;
import com.bitwig.extension.controller.api.UsbDevice;
import com.bitwig.extension.controller.api.UsbOutputPipe;

/**
 * Drives the Push 2 hardware screen. It is not reachable over MIDI: frames are raw 16-bit
 * pixels sent as bulk USB transfers, per Ableton's published protocol
 * (https://github.com/Ableton/push-interface/blob/master/doc/AbletonPush2MIDIDisplayInterface.asc).
 */
public class Push2Display
{
    private static final int WIDTH  = 960;
    private static final int HEIGHT = 160;

    /** Fixed transfer size Push 2 expects per frame, including per-row padding. */
    private static final int FRAME_SIZE = 20 * 0x4000;

    private static final int TIMEOUT_MS = 1000;

    private static final byte [] FRAME_HEADER =
    {
        (byte) 0xFF, (byte) 0xCC, (byte) 0xAA, (byte) 0x88,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
    };

    private final Bitmap        bitmap;
    private final UsbOutputPipe pipe;
    private final MemoryBlock   headerBlock;
    private final MemoryBlock   frameBlock;
    private final byte []       frame = new byte [FRAME_SIZE];

    // The actual USB transfer runs here, off the caller's thread (same idea as DrivenByMoss's
    // PushUsbDisplay) so a slow/stalled write (up to 2s worst case, two 1000ms timeouts) can't
    // block whatever's driving refresh() - in our case, the same scheduled task that also keeps
    // the display alive and animates the progress wipe. `sending` guards frame/frameBlock, which
    // the background thread reads: refresh() skips (doesn't touch either) if a send is still in
    // flight, rather than racing it - dropping an occasional frame is harmless here.
    private final ExecutorService sendExecutor = Executors.newSingleThreadExecutor();
    private volatile boolean      sending;

    public Push2Display(final ControllerHost host)
    {
        this.bitmap = host.createBitmap(WIDTH, HEIGHT, BitmapFormat.ARGB32);

        final UsbDevice device = (UsbDevice) host.hardwareDevice(0);
        this.pipe = (UsbOutputPipe) device.iface(0).pipe(0);

        this.headerBlock = host.allocateMemoryBlock(FRAME_HEADER.length);
        this.headerBlock.createByteBuffer().put(FRAME_HEADER);

        this.frameBlock = host.allocateMemoryBlock(FRAME_SIZE);
    }

    /** Draws the given text centered on the screen and sends it immediately. */
    public void showText(final String text)
    {
        this.bitmap.render(gc -> {
            gc.setColor(0, 0, 0);
            gc.rectangle(0, 0, WIDTH, HEIGHT);
            gc.fill();

            gc.setColor(1, 1, 1);
            gc.setFontSize(56);

            final double textWidth = gc.getTextExtents(text).getWidth();
            final FontExtents fontExtents = gc.getFontExtents();
            final double baselineY = (HEIGHT + fontExtents.getAscent() - fontExtents.getDescent()) / 2.0;

            gc.moveTo((WIDTH - textWidth) / 2.0, baselineY);
            gc.showText(text);
        });

        this.refresh();
    }

    /** Runs an arbitrary paint routine against the screen bitmap and sends it immediately. */
    public void render(final Renderer painter)
    {
        this.bitmap.render(painter);
        this.refresh();
    }

    /**
     * Re-sends the last rendered frame as-is (no redraw). Push 2's screen goes blank a few
     * seconds after its last frame, so this must be called on every host flush to keep it lit -
     * same requirement DrivenByMoss's {@code AbstractGraphicDisplay.send()} works around.
     */
    public void refresh()
    {
        if (this.sending)
            return; // previous frame still transmitting - skip this one rather than race it

        final ByteBuffer pixels = this.bitmap.getMemoryBlock().createByteBuffer();
        pixels.rewind();

        final int padding = (FRAME_SIZE - HEIGHT * WIDTH * 2) / HEIGHT;
        int pos = 0;
        for (int y = 0; y < HEIGHT; y++)
        {
            for (int x = 0; x < WIDTH; x++)
            {
                final int blue = pixels.get() & 0xFF;
                final int green = pixels.get() & 0xFF;
                final int red = pixels.get() & 0xFF;
                pixels.get(); // Alpha - unused, Push 2's screen has no transparency

                final int pixel = toBgr565(red, green, blue);
                this.frame[pos] = (byte) (pixel & 0xFF);
                this.frame[pos + 1] = (byte) (pixel >> 8 & 0xFF);
                pos += 2;
            }

            for (int p = 0; p < padding; p++)
                this.frame[pos++] = 0;
        }

        // Push 2 requires every 4-byte group of the frame to be XORed with this fixed pattern
        // before sending ("signal shaping" in Ableton's spec).
        for (int i = 0; i < this.frame.length; i += 4)
        {
            this.frame[i] ^= 0xE7;
            this.frame[i + 1] ^= 0xF3;
            this.frame[i + 2] ^= 0xE7;
            this.frame[i + 3] ^= (byte) 0xFF;
        }

        final ByteBuffer frameBuffer = this.frameBlock.createByteBuffer();
        frameBuffer.clear();
        frameBuffer.put(this.frame);

        this.sending = true;
        this.sendExecutor.submit(() -> {
            try
            {
                this.pipe.write(this.headerBlock, TIMEOUT_MS);
                this.pipe.write(this.frameBlock, TIMEOUT_MS);
            }
            finally
            {
                this.sending = false;
            }
        });
    }

    /** Stops the background send thread. Call from the extension's exit(). */
    public void shutdown()
    {
        this.sendExecutor.shutdownNow();
    }

    /** Push 2 wants BGR565 (5-6-5 bits), not the more common RGB565. */
    private static int toBgr565(final int red, final int green, final int blue)
    {
        int pixel = (blue & 0xF8) >> 3;
        pixel <<= 6;
        pixel += (green & 0xFC) >> 2;
        pixel <<= 5;
        pixel += (red & 0xF8) >> 3;
        return pixel;
    }
}
