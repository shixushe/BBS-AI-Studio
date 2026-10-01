package mchorse.bbs_mod.ai.capture;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.utils.resources.Pixels;

import java.io.File;
import java.util.List;

/**
 * Direction A of video capture (copilot spec 6.1): the film's own playback is
 * grabbed at a fixed tick interval inside a window.
 *
 * <p>Constraints this class enforces by construction:</p>
 * <ul>
 * <li>frames are grabbed on the render thread, same frame (via
 * {@link FrameGrabber}, called from the owning panel's render/update pass)</li>
 * <li>every frame carries its tick</li>
 * <li>the timeline is frozen for the duration: the owner pauses playback
 * before starting, and a cursor jump backwards mid-window aborts the session
 * instead of silently mixing frames</li>
 * <li>over the 12-frame cap the sequence gets thinned, not the capture
 * widened</li>
 * </ul>
 */
public class SceneFrameCapture
{
    public final int from;
    public final int to;
    public final int interval;

    private final FrameSequence sequence = new FrameSequence(FrameSequence.Source.SCENE);

    private int nextTick;
    private int lastTick = Integer.MIN_VALUE;

    public SceneFrameCapture(int from, int to, int interval)
    {
        this.from = Math.min(from, to);
        this.to = Math.max(from, to);
        this.interval = Math.max(1, interval);
        this.nextTick = this.from;
    }

    public boolean isActive()
    {
        return this.nextTick <= this.to;
    }

    public float progress()
    {
        int span = Math.max(1, this.to - this.from);

        return Math.min(1F, (this.nextTick - this.from) / (float) span);
    }

    /**
     * Feed the current film cursor (called every frame while active, render
     * thread). Captures the framebuffer when a target tick is crossed.
     * Returns false when the session is finished or was aborted by a timeline
     * jump.
     */
    public boolean update(int cursor, int screenW, int screenH)
    {
        if (!this.isActive())
        {
            return false;
        }

        if (this.lastTick != Integer.MIN_VALUE && cursor < this.lastTick)
        {
            /* Someone dragged the play head - the tick/frame mapping is dead */
            this.abort();

            return false;
        }

        this.lastTick = cursor;

        while (this.isActive() && cursor >= this.nextTick)
        {
            Pixels frame = FrameGrabber.grabScreen(screenW, screenH);

            this.sequence.add(new CapturedFrame(this.nextTick, System.currentTimeMillis(), frame));
            this.nextTick += this.interval;
        }

        return this.isActive();
    }

    public void abort()
    {
        this.nextTick = this.to + 1;
    }

    /** Finish and hand over the (capped) sequence; persists nothing by itself. */
    public FrameSequence finish()
    {
        FrameThinner.enforceCap(this.sequence);

        return this.sequence;
    }

    /** Where capture manifests persist across panel rebuilds (spec 5.6 hard rule). */
    public static File captureFolder()
    {
        return new File(BBSMod.getSettingsFolder(), "ai_captures");
    }
}
