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

    /** Pre-apply snapshots: rollback on 丢弃, deferred undo entry on 入框. */
    private final List<FrameCommitter.PendingCapture> captures = new ArrayList<>();

    /**
     * Begin (or replace) a preview: the writes are applied FOR REAL so the
     * viewport and playback show the proposal immediately - nothing is
     * committed to the undo history yet. 丢弃 restores the captures; 入框
     * promotes them into one CompoundUndo.
     */
    public void begin(Replay replay, List<FrameCommitter.ChannelWrite> plan, FrameDiff diff)
    {
        /* A stale preview (user generated again without discarding) must not
         * leak its captures - roll them back first */
        this.rollbackCaptures();

        this.replay = replay;
        this.plan = plan;
        this.diff = diff;

        this.ticks.clear();
        this.entries.clear();
        this.captures.clear();

        if (plan != null && !plan.isEmpty())
        {
            this.captures.addAll(FrameCommitter.applyPreview(plan, diff));
        }

        for (FrameDiff.Entry entry : diff.entries)
        {
            this.ticks.add(entry.tick);
            this.entries.add(entry);
        }
    }

    /**
     * 入框: the preview keys are already in the channels - wrap the captured
     * pre-preview states into one no-merge undo entry so Ctrl+Z returns to
     * the pre-AI state, and hand the diff back for the receipt/broadcast.
     */
    public FrameDiff confirm(mchorse.bbs_mod.utils.undo.UndoManager<mchorse.bbs_mod.settings.values.core.ValueGroup> undoManager)
    {
        FrameDiff result = this.diff;

        if (!this.captures.isEmpty() && undoManager != null)
        {
            List<mchorse.bbs_mod.utils.undo.IUndo<mchorse.bbs_mod.settings.values.core.ValueGroup>> undos = new ArrayList<>();

            for (FrameCommitter.PendingCapture capture : this.captures)
            {
                undos.add(new mchorse.bbs_mod.ai.commit.ChannelStateUndo(
                    capture.channel.getPath(), capture.oldState,
                    mchorse.bbs_mod.ai.commit.ChannelStateUndo.capture(capture.channel)));
            }

            mchorse.bbs_mod.utils.undo.CompoundUndo<mchorse.bbs_mod.settings.values.core.ValueGroup> compound =
                new mchorse.bbs_mod.utils.undo.CompoundUndo<>(undos.toArray(new mchorse.bbs_mod.utils.undo.IUndo[0]));

            compound.noMerging();
            undoManager.pushUndo(compound);
        }

        this.replay = null;
        this.plan = null;
        this.diff = null;
        this.ticks.clear();
        this.entries.clear();
        this.captures.clear();

        return result;
    }

    /** 丢弃: bitwise-restore every channel the preview touched. */
    private void rollbackCaptures()
    {
        for (FrameCommitter.PendingCapture capture : this.captures)
        {
            try
            {
                capture.channel.fromData(capture.oldState);
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }

        this.captures.clear();
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
        this.rollbackCaptures();

        this.replay = null;
        this.plan = null;
        this.diff = null;
        this.ticks.clear();
        this.entries.clear();
    }
}
