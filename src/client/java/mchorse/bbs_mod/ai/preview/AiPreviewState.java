package mchorse.bbs_mod.ai.preview;

import mchorse.bbs_mod.ai.commit.FrameCommitter;
import mchorse.bbs_mod.ai.commit.FrameDiff;
import mchorse.bbs_mod.film.replays.Replay;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The pending AI proposal: changes computed and diffed but NOT committed -
 * nothing lands on a channel until the user presses 入框 (confirm). Both the
 * ghost frame layer and the chat bar / semantic panels read this, so every AI
 * entry point shares one preview and one confirm/discard pair (and therefore
 * one undo entry per accepted operation).
 *
 * <p>State lives outside any panel instance on purpose: film editor panels
 * rebuild on every edit, and the preview must survive that (copilot spec
 * section 5.3).</p>
 */
public class AiPreviewState
{
    private static final AiPreviewState INSTANCE = new AiPreviewState();

    public static AiPreviewState get()
    {
        return INSTANCE;
    }

    private Replay replay;

    /**
     * Ticks the pending proposal touches, per track address - the ghost frame
     * layer draws accent marks exactly there and nowhere else.
     */
    private final Set<Float> ticks = new HashSet<>();

    private final List<FrameDiff.Entry> entries = new ArrayList<>();

    private FrameDiff diff;

    /** The pending writes - preview and confirm run the exact same list. */
    private List<FrameCommitter.ChannelWrite> plan;

    /** Begin (or replace) a preview for the given replay. */
    public void begin(Replay replay, List<FrameCommitter.ChannelWrite> plan, FrameDiff diff)
    {
        this.replay = replay;
        this.plan = plan;
        this.diff = diff;

        this.ticks.clear();
        this.entries.clear();

        if (diff != null)
        {
            this.entries.addAll(diff.entries);

            for (FrameDiff.Entry entry : diff.entries)
            {
                this.ticks.add(entry.tick);
            }
        }
    }

    public boolean isActive()
    {
        return this.diff != null && !this.diff.isEmpty();
    }

    public Replay getReplay()
    {
        return this.replay;
    }

    public List<FrameCommitter.ChannelWrite> getPlan()
    {
        return this.plan;
    }

    public FrameDiff getDiff()
    {
        return this.diff;
    }

    /** Ticks the pending proposal touches (unmodifiable view). */
    public Set<Float> getTicks()
    {
        return this.ticks;
    }

    public List<FrameDiff.Entry> getEntries()
    {
        return this.entries;
    }

    public int getChangeCount()
    {
        return this.diff == null ? 0 : this.diff.changedKeyCount();
    }

    public void discard()
    {
        this.replay = null;
        this.plan = null;
        this.diff = null;
        this.ticks.clear();
        this.entries.clear();
    }
}
