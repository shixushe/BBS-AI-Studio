package mchorse.bbs_mod.ai.capture;

import mchorse.bbs_mod.utils.resources.Pixels;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Render-thread GL texture -> {@link Pixels} grab, the same read the vanilla
 * screenshot path uses (float RGBA, rows flipped), minus the file writing.
 *
 * <p>Hard constraint (copilot spec 6.1): this MUST run on the render thread
 * in the same frame the texture is current - never deferred, never from
 * another thread. Both capture chains (scene framebuffer, external video
 * frames) go through exactly this one grabber, which is what makes them the
 * same {@link FrameSequence}.</p>
 */
public class FrameGrabber
{
    /** Long edge of an uploaded frame. Full resolution is never kept around. */
    public static final int UPLOAD_LONG_EDGE = 512;

    /**
     * Read the current framebuffer into downsampled pixels. Render thread
     * only, same frame as the content being grabbed.
     */
    public static Pixels grabScreen(int width, int height)
    {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocateDirect(width * height * 4).order(java.nio.ByteOrder.nativeOrder());

        GL11.glReadPixels(0, 0, width, height, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, buffer);
        buffer.rewind();

        Pixels pixels = new Pixels(buffer, width, height);

        return FrameThinner.downscale(pixels, UPLOAD_LONG_EDGE);
    }

    /**
     * Read a GL texture into downsampled pixels. Render thread only.
     */
    public static Pixels grab(int texture, int width, int height)
    {
        ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder());

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, buffer);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        buffer.rewind();

        Pixels pixels = new Pixels(buffer, width, height);

        return FrameThinner.downscale(pixels, UPLOAD_LONG_EDGE);
    }
}
