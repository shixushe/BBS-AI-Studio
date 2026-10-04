package mchorse.bbs_mod.ai.preview;

import mchorse.bbs_mod.ai.commit.EditPatch;
import mchorse.bbs_mod.ai.commit.FrameCommitter;
import mchorse.bbs_mod.ai.commit.FrameDiff;
import mchorse.bbs_mod.film.replays.Replay;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

    /** 结构性特效请求（粒子回放等）——预览不执行，入框时创建 */
    private List<mchorse.bbs_mod.ai.plan.AnimationPlan.Fx> fx = new ArrayList<>();

    /** 上一次 AI 入框的痕迹：每条通道写到哪个 tick 为止——下次预览时把
     * 超出新计划范围的旧键清掉（旧动画比新计划长的"尾巴"），否则残键
     * 会一直留在时间轴上，看起来像幽灵帧没消失 */
    private final Map<String, Float> footprint = new java.util.HashMap<>();

    /** begin() 时清掉的旧残留键数 */
    private int trimmedCount;

    public Map<String, Float> getFootprint()
    {
        return this.footprint;
    }

    public List<mchorse.bbs_mod.ai.plan.AnimationPlan.Fx> getFx()
    {
        return this.fx;
    }

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
        this.trimmedCount = 0;

        if (plan != null && !plan.isEmpty())
        {
            this.captures.addAll(FrameCommitter.applyPreview(plan, diff));

            /* 预览即替换：上一次 AI 入框的残留键（超出新计划范围的部分）
             * 立刻清掉——否则旧内容（比如旧版走路留下的 y 上下键）会在
             * 新预览里继续播放，看起来像"新动画还在上下跳"。回滚快照在
             * applyPreview 前已拍好，丢弃仍能完整还原旧状态 */
            this.trimmedCount = this.trimLastFootprint();
        }

        for (FrameDiff.Entry entry : diff.entries)
        {
            this.ticks.add(entry.tick);
            this.entries.add(entry);
        }
    }

    /** 上一次 begin() 清掉的旧残留键数量（供对话回执） */
    public int getTrimmedCount()
    {
        return this.trimmedCount;
    }

    /** 把上次 AI 痕迹超出新计划范围的键删掉，返回删除数 */
    private int trimLastFootprint()
    {
        if (this.footprint.isEmpty() || this.plan == null || this.replay == null)
        {
            return 0;
        }

        int removed = 0;
        Map<String, Float> remaining = new java.util.HashMap<>(this.footprint);

        for (FrameCommitter.ChannelWrite write : this.plan)
        {
            Float oldMax = remaining.get(write.trackId);

            if (oldMax == null)
            {
                continue;
            }

            float newMax = -1F;

            for (EditPatch.KeyWrite key : write.keys)
            {
                newMax = Math.max(newMax, key.tick);
            }

            mchorse.bbs_mod.utils.keyframes.KeyframeChannel<?> channel = write.channel;

            for (int i = channel.getKeyframes().size() - 1; i >= 0; i--)
            {
                float tick = channel.getKeyframes().get(i).getTick();

                if (tick > newMax && tick <= oldMax)
                {
                    channel.remove(i);
                    removed++;
                }
            }

            remaining.remove(write.trackId);
        }

        /* 上次碰过、这次没碰的通道：旧键整个都是残留 */
        for (Map.Entry<String, Float> entry : remaining.entrySet())
        {
            try
            {
                mchorse.bbs_mod.utils.keyframes.KeyframeChannel<?> channel = this.replay.properties
                    .get(mchorse.bbs_mod.film.replays.tracks.TrackId.parse(entry.getKey()));

                if (channel == null)
                {
                    continue;
                }

                for (int i = channel.getKeyframes().size() - 1; i >= 0; i--)
                {
                    if (channel.getKeyframes().get(i).getTick() <= entry.getValue())
                    {
                        channel.remove(i);
                        removed++;
                    }
                }
            }
            catch (Exception e)
            {
                /* 未知通道键格式：保守跳过 */
            }
        }

        return removed;
    }

    /**
     * 入框: the preview keys are already in the channels - wrap the captured
     * pre-preview states into one no-merge undo entry so Ctrl+Z returns to
     * the pre-AI state, and hand the diff back for the receipt/broadcast.
     */
    public FrameDiff confirm(mchorse.bbs_mod.utils.undo.UndoManager<mchorse.bbs_mod.settings.values.core.ValueGroup> undoManager)
    {
        FrameDiff result = this.diff;

        /* 记录本次计划每条通道写到的最大 tick，供下次入框清旧尾巴 */
        this.footprint.clear();

        if (this.plan != null)
        {
            for (FrameCommitter.ChannelWrite write : this.plan)
            {
                float max = -1F;

                for (EditPatch.KeyWrite key : write.keys)
                {
                    max = Math.max(max, key.tick);
                }

                if (max >= 0F)
                {
                    this.footprint.put(write.trackId, max);
                }
            }
        }

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
        this.fx = new ArrayList<>();

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

    public void setFx(List<mchorse.bbs_mod.ai.plan.AnimationPlan.Fx> fx)
    {
        this.fx = fx == null ? new ArrayList<>() : fx;
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
        this.fx = new ArrayList<>();
    }
}
