package mchorse.bbs_mod.ai.capture;

import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.utils.resources.Pixels;
import mchorse.bbs_mod.video.VideoManager;
import mchorse.bbs_mod.video.VideoPlayer;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Direction B of video capture (copilot spec 6.2): an external reference
 * video is decoded through the EXISTING video stack ({@code VideoManager} /
 * {@code VideoPlayer} - no new decoder, no export path), sampled at a user
 * chosen frame rate, and reduced to the same {@link FrameSequence} the scene
 * chain produces.
 *
 * <p>Two-stage thinning per the spec: first a coarse pass (one frame per
 * second) establishes overall rhythm, then a fine pass over the user's window
 * if they circle one. Uploads only happen after the explicit "N frames will
 * be uploaded" confirmation the panel shows.</p>
 */
public class ExternalVideoCapture
{
    /**
     * Sample a video into a frame sequence. Must run on the render thread -
     * {@code getFrame} uploads/reads GL textures.
     *
     * @param link        the video file
     * @param fps         user-chosen sampling rate (spec: they pick, always)
     * @param fromSeconds fine window start, 0 for whole video
     * @param toSeconds   fine window end, or the video duration for whole
     */
    public static FrameSequence capture(VideoManager videos, Object owner, Link link, float fps, float fromSeconds, float toSeconds)
    {
        VideoPlayer player = videos.getPlayer(owner, link);

        if (player == null || player.isInvalid())
        {
            return new FrameSequence(FrameSequence.Source.EXTERNAL);
        }

        FrameSequence sequence = sample(player, fps, fromSeconds, toSeconds);

        videos.release(owner);

        return sequence;
    }

    /**
     * Same sampling for a file the user picked in the explorer - it lives
     * outside the asset provider, so it gets its own player, released right
     * after the pass.
     */
    public static FrameSequence captureFile(Object owner, File file, float fps, float fromSeconds, float toSeconds)
    {
        VideoPlayer player = new VideoPlayer(file);

        if (player.isInvalid())
        {
            return new FrameSequence(FrameSequence.Source.EXTERNAL);
        }

        FrameSequence sequence = sample(player, fps, fromSeconds, toSeconds);

        player.delete();

        return sequence;
    }

    /** The shared sampling loop: walk the window, grab a frame every 1/fps. */
    private static FrameSequence sample(VideoPlayer player, float fps, float fromSeconds, float toSeconds)
    {
        FrameSequence sequence = new FrameSequence(FrameSequence.Source.EXTERNAL);

        player.ensureProbed();

        float duration = player.getDuration();
        float end = toSeconds <= 0F || toSeconds > duration ? duration : toSeconds;
        float step = 1F / Math.max(0.1F, fps);

        List<Pixels> grabbed = new ArrayList<>();
        List<Float> times = new ArrayList<>();

        for (float time = Math.max(0F, fromSeconds); time < end; time += step)
        {
            mchorse.bbs_mod.graphics.texture.Texture texture = player.getFrame(time);

            if (texture == null)
            {
                continue;
            }

            grabbed.add(FrameGrabber.grab(texture.id, texture.width, texture.height));
            times.add(time);
        }

        /* Map sampled seconds onto film ticks at 20 tps so both sources speak
         * the same unit downstream */
        for (int i = 0; i < grabbed.size(); i++)
        {
            sequence.add(new CapturedFrame(Math.round(times.get(i) * 20F), times.get(i), grabbed.get(i)));
        }

        FrameThinner.enforceCap(sequence);

        return sequence;
    }
}
