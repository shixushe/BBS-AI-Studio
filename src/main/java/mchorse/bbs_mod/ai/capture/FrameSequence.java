package mchorse.bbs_mod.ai.capture;

import java.util.ArrayList;
import java.util.List;

/**
 * The unified output of both capture directions (copilot spec section 6.3):
 * scene grabs and external reference videos reduce to the same shape, so
 * downstream understanding runs one implementation, not two.
 */
public class FrameSequence
{
    public enum Source
    {
        SCENE, EXTERNAL
    }

    /** Hard upload cap (spec 6.1): multi-image token cost is near linear. */
    public static final int HARD_LIMIT = 12;

    public final Source source;

    public final List<CapturedFrame> frames = new ArrayList<>();

    public FrameSequence(Source source)
    {
        this.source = source;
    }

    public void add(CapturedFrame frame)
    {
        this.frames.add(frame);
    }

    public boolean isEmpty()
    {
        return this.frames.isEmpty();
    }

    public int size()
    {
        return this.frames.size();
    }

    /** Whether the sequence carries a tick on every frame - frames without one are wasted uploads. */
    public boolean allTicksPresent()
    {
        return this.frames.stream().allMatch(f -> f.tick >= 0);
    }
}
