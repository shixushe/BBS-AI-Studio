package mchorse.bbs_mod.ai.ui;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.ai.AiFilmBridge;
import mchorse.bbs_mod.ai.commit.EditPatch;
import mchorse.bbs_mod.ai.commit.EditPatchBuilder;
import mchorse.bbs_mod.ai.commit.FrameCommitter;
import mchorse.bbs_mod.ai.AiChatRequest;
import mchorse.bbs_mod.ai.AiClient;
import mchorse.bbs_mod.ai.AiException;
import mchorse.bbs_mod.ai.AiSettings;
import mchorse.bbs_mod.ai.commit.FrameDiff;
import mchorse.bbs_mod.ai.curve.PolishCommandParser;
import mchorse.bbs_mod.ai.curve.PolishOp;
import mchorse.bbs_mod.ai.preview.AiPreviewState;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.framework.elements.utils.Batcher2D;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.UIConstants;

import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.colors.Colors;

import java.util.ArrayList;
import java.util.List;

/**
 * The film editor's bottom AI chat bar (copilot spec section 5.1) - the main
 * entry point. Two modes: 生成 (generate, M5) and 打磨 (polish, fully local -
 * iron rule 4 keeps it working with no backend configured).
 *
 * <p>Results never land directly: an accepted execution builds channel writes
 * and hands them to {@link AiPreviewState}, which the ghost frame layer draws
 * and this bar's second row can 入框 (commit through the M3 undo transaction)
 * or 丢弃 (discard). One accepted operation = one undo entry.</p>
 */
public class UIAiChatBar extends UIElement
{
    private final UIFilmPanel panel;

    private final UIButton generate;
    private final UIButton polish;

    private final UITextbox input;

    private final UIElement previewRow;
    private final UILabel status;

    /** The mockup's numbered chip - shows pending change count while previewing. */
    private final UILabel chip;

    /** Total bar height: two rows of controls plus the gaps between them. */
    public static final int BAR_HEIGHT = 2 * (UIConstants.CONTROL_HEIGHT + 4) + UIConstants.MARGIN * 3;

    /** Whether the polish mode is active (generate is the other face). */
    private boolean polishMode = false;

    private boolean busy;

    public UIAiChatBar(UIFilmPanel panel)
    {
        this.panel = panel;

        IKey generateLabel = L10n.lang("bbs.ui.ai.bar.generate");
        IKey polishLabel = L10n.lang("bbs.ui.ai.bar.polish");
        IKey commitLabel = L10n.lang("bbs.ui.ai.bar.commit");
        IKey discardLabel = L10n.lang("bbs.ui.ai.bar.discard");

        this.generate = new UIButton(generateLabel, (b) -> this.setPolishMode(false));
        this.polish = new UIButton(polishLabel, (b) -> this.setPolishMode(true));

        this.input = new UITextbox(256, (t) -> {});
        this.input.placeholder(L10n.lang("bbs.ui.ai.bar.placeholder"));

        UIButton execute = new UIButton(L10n.lang("bbs.ui.ai.bar.execute"), (b) -> this.execute());

        execute.tooltip(L10n.lang("bbs.ui.ai.bar.execute_tooltip"));
        this.generate.tooltip(L10n.lang("bbs.ui.ai.bar.generate_tooltip"));
        this.polish.tooltip(L10n.lang("bbs.ui.ai.bar.polish_tooltip"));

        this.chip = new UILabel(L10n.lang("bbs.ui.ai.bar.chip"));
        this.chip.color(Colors.WHITE, false).background(Colors.opaque(BBSSettings.primaryColor.get())).labelAnchor(0.5F, 0.5F).h(UIConstants.CONTROL_HEIGHT + 4);

        UIElement row = UI.row(1, this.chip, this.generate, this.polish, this.input, execute);

        row.row(1).preferred(3).height(UIConstants.CONTROL_HEIGHT + 4);

        this.status = new UILabel(L10n.lang("bbs.ui.ai.bar.preview"));
        this.status.color(Colors.LIGHTER_GRAY, false);

        UIButton commit = new UIButton(commitLabel, (b) -> this.confirm());
        UIButton discard = new UIButton(discardLabel, (b) -> this.discard());

        commit.color(BBSSettings.primaryColor.get() | Colors.A100);
        commit.tooltip(L10n.lang("bbs.ui.ai.bar.commit_tooltip"));
        discard.tooltip(L10n.lang("bbs.ui.ai.bar.discard_tooltip"));

        this.previewRow = UI.row(UIConstants.MARGIN, this.status, commit, discard);

        this.previewRow.row(UIConstants.MARGIN).preferred(0).height(UIConstants.CONTROL_HEIGHT + 4);
        this.previewRow.setVisible(false);

        this.column(UIConstants.MARGIN).height(BAR_HEIGHT);
        this.h(BAR_HEIGHT);
        this.add(row);
        this.add(this.previewRow);
    }

    private void setPolishMode(boolean polish)
    {
        this.polishMode = polish;

        int accent = BBSSettings.primaryColor.get() | Colors.A100;

        this.polish.color(polish ? accent : -1);
        this.generate.color(polish ? -1 : accent);
    }

    private void execute()
    {
        if (this.polishMode)
        {
            this.executePolish();
        }
        else
        {
            this.executeGenerate();
        }
    }


    /**
     * 生成: script -> (backend) AnimationPlan -> PoseSolver on the open
     * replay's model bones -> the same preview/commit pipeline polish uses.
     * The end-to-end loop of the copilot spec's delivery goal.
     */
    private void executeGenerate()
    {
        if (!AiSettings.isConfigured())
        {
            this.status.label = L10n.lang("bbs.ui.ai.panel.lamp.unconfigured");
            this.previewRow.setVisible(true);

            return;
        }

        Replay replay = this.panel.replayEditor.getReplay();

        if (replay == null)
        {
            this.status.label = L10n.lang("bbs.ui.ai.bar.no_replay");
            this.previewRow.setVisible(true);

            return;
        }

        if (!(replay.form.get() instanceof mchorse.bbs_mod.forms.forms.ModelForm modelForm))
        {
            this.status.label = L10n.lang("bbs.ui.ai.creative.not_model");
            this.previewRow.setVisible(true);

            return;
        }

        String script = this.input.getText().trim();

        if (script.isEmpty())
        {
            this.status.label = L10n.lang("bbs.ui.ai.panel.empty_script");
            this.previewRow.setVisible(true);

            return;
        }

        this.busy = true;
        this.status.label = L10n.lang("bbs.ui.ai.panel.generating");
        this.previewRow.setVisible(true);

        String system = L10n.lang("bbs.ui.ai.panel.prompt").get();
        AiChatRequest request = new AiChatRequest(system, script);

        request.temperature(AiSettings.temperature.get());
        request.json(AiSettings.jsonMode.get() && AiSettings.supportsJsonMode.get());

        mchorse.bbs_mod.ui.framework.UIContext context = this.getContext();

        mchorse.bbs_mod.ai.AiPlans.generatePlan(request, (generated) ->
        {
            this.busy = false;

            java.util.List<String> inventory = new ArrayList<>();

            for (mchorse.bbs_mod.settings.values.base.BaseValue child : modelForm.bones.getAll())
            {
                inventory.add(child.getId());
            }

            mchorse.bbs_mod.ai.pose.BoneNameResolver.Result bones = mchorse.bbs_mod.ai.pose.BoneNameResolver.resolve(inventory);

            if (!bones.isComplete())
            {
                /* The assistant never guesses bone names - it asks */
                this.status.label = L10n.lang("bbs.ui.ai.ask.open");
                this.previewRow.setVisible(true);

                UIAiAskOverlayPanel ask = new UIAiAskOverlayPanel(context, bones.unresolved, inventory, (confirmed) ->
                {
                    this.previewGenerated(generated, confirmed, replay);
                });

                mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay.addOverlay(context, ask, 280, 0.7F);

                return;
            }

            this.previewGenerated(generated, bones, replay);
        }, (error) ->
        {
            this.busy = false;
            this.status.label = L10n.lang("bbs.ui.ai.panel.failed").format(error.type.name());
            this.previewRow.setVisible(true);
        });
    }

    private void previewGenerated(mchorse.bbs_mod.ai.plan.AnimationPlan generated, mchorse.bbs_mod.ai.pose.BoneNameResolver.Result bones, Replay replay)
    {
        List<mchorse.bbs_mod.ai.pose.PoseSolver.KeyPose> poses = mchorse.bbs_mod.ai.pose.PoseSolver.solve(generated, bones);
        List<FrameCommitter.ChannelWrite> writes = mchorse.bbs_mod.ai.pose.PoseSolver.toChannelWrites(poses, replay.properties);

        AiPreviewState.get().begin(replay, writes, this.buildPreviewDiff(writes));
        this.status.label = L10n.lang("bbs.ui.ai.bar.preview").format(AiPreviewState.get().getChangeCount());
        this.previewRow.setVisible(true);
    }

    /** Polish: local intent parsing -> L3 on every numeric channel of the open replay -> preview. */
    private void executePolish()
    {
        String text = this.input.getText().trim();
        List<PolishOp> ops = PolishCommandParser.parse(text);

        if (ops.isEmpty())
        {
            this.status.label = L10n.lang("bbs.ui.ai.bar.no_intent");
            this.previewRow.setVisible(true);

            return;
        }

        Replay replay = this.panel.replayEditor.getReplay();

        if (replay == null)
        {
            this.status.label = L10n.lang("bbs.ui.ai.bar.no_replay");
            this.previewRow.setVisible(true);

            return;
        }

        List<FrameCommitter.ChannelWrite> plan = new ArrayList<>();
        int considered = 0;

        for (KeyframeChannel<?> channel : replay.properties.tracks.values())
        {
            if (!CurveGuard.polishable(channel))
            {
                continue;
            }

            considered++;
            plan.add(EditPatchBuilder.build(channel.getId(), channel, ops));
        }

        for (KeyframeChannel<?> channel : replay.keyframes.getChannels())
        {
            if (!CurveGuard.polishable(channel))
            {
                continue;
            }

            considered++;
            plan.add(EditPatchBuilder.build(channel.getId(), channel, ops));
        }

        if (considered == 0)
        {
            this.status.label = L10n.lang("bbs.ui.ai.bar.no_numeric");
            this.previewRow.setVisible(true);

            return;
        }

        /* Compute the diff by dry-running the polisher result against the
         * current keys - the preview state only holds what WOULD change */
        AiPreviewState.get().begin(replay, plan, this.buildPreviewDiff(plan));
        this.refreshPreviewRow();
    }

    /** Diff of what the plan would change, computed without touching channels. */
    private FrameDiff buildPreviewDiff(List<FrameCommitter.ChannelWrite> plan)
    {
        FrameDiff diff = new FrameDiff();

        for (FrameCommitter.ChannelWrite write : plan)
        {
            /* Every resulting key that differs from the channel's current key
             * at the same tick is an UPDATED entry; keys absent now are ADDED */
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

        return diff;
    }

    private void confirm()
    {
        AiPreviewState state = AiPreviewState.get();

        if (!state.isActive())
        {
            return;
        }

        FrameDiff diff = AiFilmBridge.commit(this.panel, state.getPlan());

        state.discard();
        this.refreshPreviewRow();

        /* The status line keeps the last result around briefly - it is the
         * receipt that something happened, per the undo contract */
        this.status.label = L10n.lang("bbs.ui.ai.bar.committed").format(diff.changedKeyCount());
        this.previewRow.setVisible(true);
    }

    private void discard()
    {
        AiPreviewState.get().discard();
        this.refreshPreviewRow();
    }

    /** Sync the preview row with the shared preview state (also called on open). */
    public void refreshPreviewRow()
    {
        AiPreviewState state = AiPreviewState.get();

        if (state.isActive())
        {
            this.status.label = L10n.lang("bbs.ui.ai.bar.preview").format(state.getChangeCount());
            this.chip.label = L10n.lang("bbs.ui.ai.bar.chip_count").format(state.getChangeCount());
        }
        else
        {
            this.chip.label = L10n.lang("bbs.ui.ai.bar.chip");
        }

        this.previewRow.setVisible(state.isActive());
    }

    @Override
    public void render(UIContext context)
    {
        /* Chrome surface: the bar is editor chrome, not workspace (spec 5.0.1 I) */
        this.area.render(context.batcher, BBSSettings.chromeSurface());
        context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.y + 1, BBSSettings.dividerColor());

        super.render(context);
    }

    /** Static guard indirection so the bar reads like the rest of the AI surface. */
    private static final class CurveGuard
    {
        private static boolean polishable(KeyframeChannel<?> channel)
        {
            return mchorse.bbs_mod.ai.curve.CurvePolisher.isPolishable(channel.getFactory());
        }
    }
}
