package mchorse.bbs_mod.ai.capture;

import mchorse.bbs_mod.utils.resources.Pixels;

/**
 * One captured frame. The tick (scene source) or derived tick (external
 * source) is mandatory metadata - a frame without it can never be mapped back
 * onto the timeline, which makes it a wasted upload (copilot spec 6.1).
 */
public class CapturedFrame
{
    /** Timeline tick this frame belongs to (scene), or the mapped tick (external). */
    public final int tick;

    /** Scene: the tick's wall-clock capture time; external: seconds into the video. */
    public final double timestamp;

    /** Already downsampled pixels - original resolution is never retained. */
    public final Pixels image;

    public CapturedFrame(int tick, double timestamp, Pixels image)
    {
        this.tick = tick;
        this.timestamp = timestamp;
        this.image = image;
    }
}
