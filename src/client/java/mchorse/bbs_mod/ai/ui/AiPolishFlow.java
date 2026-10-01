package mchorse.bbs_mod.ai.ui;

import mchorse.bbs_mod.ai.AiFilmBridge;
import mchorse.bbs_mod.ai.commit.EditPatch;
import mchorse.bbs_mod.ai.commit.EditPatchBuilder;
import mchorse.bbs_mod.ai.commit.FrameCommitter;
import mchorse.bbs_mod.ai.commit.FrameDiff;
import mchorse.bbs_mod.ai.curve.PolishCommandParser;
import mchorse.bbs_mod.ai.curve.PolishOp;
import mchorse.bbs_mod.ai.preview.AiPreviewState;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;

import java.util.ArrayList;
import java.util.List;

/**
 * The polish flow shared by the chat bar (section 5.1) and the form panel's
 * semantic section (section 5.3): one parser, one preview, one confirm/discard
 * pair - the same proposal is visible and actionable from both surfaces
 * because it lives in {@link AiPreviewState}, not in either panel.
 */
public class AiPolishFlow
{
    /** Run a polish sentence; updates the status label with the outcome. */
    public static void run(UIFilmPanel panel, String text, UILabel status)
    {
        List<PolishOp> ops = PolishCommandParser.parse(text);

        if (ops.isEmpty())
        {
            status.label = L10n.lang("bbs.ui.ai.bar.no_intent");

            return;
        }

        Replay replay = panel.replayEditor.getReplay();

        if (replay == null)
        {
            status.label = L10n.lang("bbs.ui.ai.bar.no_replay");

            return;
        }

        List<FrameCommitter.ChannelWrite> plan = new ArrayList<>();
        int considered = 0;

        for (KeyframeChannel<?> channel : replay.properties.tracks.values())
        {
            if (mchorse.bbs_mod.ai.curve.CurvePolisher.isPolishable(channel.getFactory()))
            {
                considered++;
                plan.add(EditPatchBuilder.build(channel.getId(), channel, ops));
            }
        }

        for (KeyframeChannel<?> channel : replay.keyframes.getChannels())
        {
            if (mchorse.bbs_mod.ai.curve.CurvePolisher.isPolishable(channel.getFactory()))
            {
                considered++;
                plan.add(EditPatchBuilder.build(channel.getId(), channel, ops));
            }
        }

        if (considered == 0)
        {
            status.label = L10n.lang("bbs.ui.ai.bar.no_numeric");

            return;
        }

        FrameDiff diff = new FrameDiff();

        for (FrameCommitter.ChannelWrite write : plan)
        {
            for (EditPatch.KeyWrite key : write.keys)
            {
                mchorse.bbs_mod.utils.keyframes.Keyframe existing = FrameCommitter.findKeyAt(write.channel, key.tick);

                if (existing == null)
                {
                    diff.entries.add(new FrameDiff.Entry(write.trackId, key.tick, FrameDiff.Change.ADDED, Double.NaN, key.value));
                }
                else if (!FrameCommitter.sameState(existing, key))
                {
                    diff.entries.add(new FrameDiff.Entry(write.trackId, key.tick, FrameDiff.Change.UPDATED, existing.getY(), key.value));
                }
            }
        }

        AiPreviewState.get().begin(replay, plan, diff);
        status.label = L10n.lang("bbs.ui.ai.bar.preview").format(AiPreviewState.get().getChangeCount());
    }

    /** 入框: commit the pending proposal through the M3 machinery (one undo entry). */
    public static void confirm(UIFilmPanel panel, UILabel status)
    {
        AiPreviewState state = AiPreviewState.get();

        if (!state.isActive())
        {
            return;
        }

        FrameDiff diff = AiFilmBridge.commit(panel, state.getPlan());

        state.discard();
        status.label = L10n.lang("bbs.ui.ai.bar.committed").format(diff.changedKeyCount());
    }

    /** 丢弃: drop the pending proposal, channels untouched. */
    public static void discard(UILabel status)
    {
        AiPreviewState.get().discard();
        status.label = L10n.lang("bbs.ui.ai.bar.preview").format(0);
    }
}
