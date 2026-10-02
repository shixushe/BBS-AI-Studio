package mchorse.bbs_mod.ai.ui;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.ai.AiFilmBridge;
import mchorse.bbs_mod.ai.commit.EditPatch;
import mchorse.bbs_mod.ai.commit.EditPatchBuilder;
import mchorse.bbs_mod.ai.commit.FrameCommitter;
import mchorse.bbs_mod.ai.AiChatRequest;
import mchorse.bbs_mod.ai.AiSettings;
import mchorse.bbs_mod.ai.commit.FrameDiff;
import mchorse.bbs_mod.ai.curve.PolishCommandParser;
import mchorse.bbs_mod.ai.curve.PolishOp;
import mchorse.bbs_mod.ai.plan.AnimationPlan;
import mchorse.bbs_mod.ai.preview.AiPreviewState;
import mchorse.bbs_mod.ai.ui.components.AiChatHistory;
import mchorse.bbs_mod.ai.ui.components.AiChatMessage;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.UIConstants;

import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.colors.Colors;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * The film editor's AI chat surface (copilot spec section 5.1) - the lower half
 * of the properties column. One input, no modes: the sentence itself decides
 * what happens. Curve-shaping language (平滑/缓入缓出/6-22...) routes to the
 * offline polish pipeline (iron rule 4 - no backend needed); anything else is
 * a script for the action generation pipeline. Whatever the current scene
 * cannot do, the transcript explains instead of silently failing.
 *
 * <p>Results never land directly: an accepted execution builds channel writes
 * and hands them to {@link AiPreviewState}, which the ghost frame layer draws
 * and the preview row can 入框 (commit through the M3 undo transaction) or
 * 丢弃 (discard). One accepted operation = one undo entry.</p>
 */
public class UIAiChatBar extends UIElement
{
    private static final int ROW = UIConstants.CONTROL_HEIGHT + 4;
    private static final int GAP = UIConstants.MARGIN;

    private final UIFilmPanel panel;

    /** The transcript: everything asked and answered, newest at the bottom. */
    private final AiChatHistory history;

    private final UITextbox input;
    private final UIButton execute;

    private final UIElement inputRow;
    private final UIElement previewRow;
    private final UILabel status;

    private boolean busy;

    /** Change count already reported to the transcript, so the preview entry logs once per result. */
    private int lastLoggedCount = -1;

    public UIAiChatBar(UIFilmPanel panel)
    {
        this.panel = panel;

        this.history = new AiChatHistory();
        this.add(this.history);

        /* Enter sends, like any chat box - execute() clears the line itself */
        this.input = new UITextbox(256, (t) -> {})
        {
            @Override
            public boolean subKeyPressed(UIContext context)
            {
                if (this.isFocused() && (context.isPressed(GLFW.GLFW_KEY_ENTER) || context.isPressed(GLFW.GLFW_KEY_KP_ENTER)))
                {
                    UIAiChatBar.this.execute();

                    return true;
                }

                return super.subKeyPressed(context);
            }
        };
        this.input.placeholder(L10n.lang("bbs.ui.ai.bar.placeholder"));

        this.execute = new UIButton(L10n.lang("bbs.ui.ai.bar.execute"), (b) -> this.execute());
        this.execute.color(BBSSettings.primaryColor.get() | Colors.A100);
        this.execute.tooltip(L10n.lang("bbs.ui.ai.bar.execute_tooltip"));

        this.inputRow = UI.row(1, this.input, this.execute);
        this.inputRow.row(1).preferred(0).height(ROW);
        this.add(this.inputRow);

        this.status = new UILabel(L10n.lang("bbs.ui.ai.bar.preview"));
        this.status.color(Colors.LIGHTER_GRAY, false);

        UIButton commit = new UIButton(L10n.lang("bbs.ui.ai.bar.commit"), (b) -> this.confirm());
        UIButton discard = new UIButton(L10n.lang("bbs.ui.ai.bar.discard"), (b) -> this.discard());

        commit.color(BBSSettings.primaryColor.get() | Colors.A100);
        commit.tooltip(L10n.lang("bbs.ui.ai.bar.commit_tooltip"));
        discard.tooltip(L10n.lang("bbs.ui.ai.bar.discard_tooltip"));

        this.previewRow = UI.row(GAP, this.status, commit, discard);
        this.previewRow.row(GAP).preferred(0).height(ROW);
        this.previewRow.setVisible(false);
        this.add(this.previewRow);

        this.relayout();
        this.history.log(AiChatMessage.Role.SYSTEM, L10n.lang("bbs.ui.ai.chat.welcome").get());
    }

    /** Bottom-anchored rows: input always, preview above it while active, transcript fills the rest. */
    private void relayout()
    {
        boolean preview = this.previewRow.isVisible();

        this.inputRow.relative(this).x(GAP).y(1F, -(GAP + ROW)).w(1F, -GAP * 2).h(ROW);

        if (preview)
        {
            this.previewRow.relative(this).x(GAP).y(1F, -(GAP * 2 + ROW * 2)).w(1F, -GAP * 2).h(ROW);
        }

        this.history.relative(this).x(GAP).y(GAP).w(1F, -GAP * 2).h(1F, -(GAP * (preview ? 3 : 2) + ROW * (preview ? 2 : 1)));
    }

    private void execute()
    {
        if (this.busy)
        {
            return;
        }

        String text = this.input.getText().trim();

        if (text.isEmpty())
        {
            this.history.log(AiChatMessage.Role.SYSTEM, L10n.lang("bbs.ui.ai.panel.empty_script").get());

            return;
        }

        /* Small talk (a bare 你好) gets a local conversational reply - never a
         * generation call and never a backend request */
        if (mchorse.bbs_mod.ai.AiSmallTalk.isSmallTalk(text))
        {
            this.history.log(AiChatMessage.Role.USER, text);
            this.input.setText("");
            this.history.log(AiChatMessage.Role.ASSISTANT, mchorse.bbs_mod.ai.AiSmallTalk.reply(text));

            return;
        }

        /* Auto routing: curve-shaping language polishes the open replay's curves
         * (offline); everything else is an action script for the generation
         * pipeline. The scene checks inside each path explain what to change. */
        List<PolishOp> ops = PolishCommandParser.parse(text);

        if (!ops.isEmpty() && this.canPolishScene())
        {
            this.executePolish(text, ops);
        }
        else
        {
            this.executeGenerate(text);
        }
    }

    /** Whether the open scene offers numeric curves the polisher could act on. */
    private boolean canPolishScene()
    {
        Replay replay = this.panel.replayEditor.getReplay();

        if (replay == null)
        {
            return false;
        }

        for (KeyframeChannel<?> channel : replay.properties.tracks.values())
        {
            if (CurveGuard.polishable(channel))
            {
                return true;
            }
        }

        for (KeyframeChannel<?> channel : replay.keyframes.getChannels())
        {
            if (CurveGuard.polishable(channel))
            {
                return true;
            }
        }

        return false;
    }

    /**
     * 生成: script -> (backend) AnimationPlan -> PoseSolver on the open
     * replay's model bones -> the same preview/commit pipeline polish uses.
     * The end-to-end loop of the copilot spec's delivery goal.
     */
    private void executeGenerate(String script)
    {
        this.history.log(AiChatMessage.Role.USER, script);
        this.input.setText("");

        if (!AiSettings.isConfigured())
        {
            this.history.log(AiChatMessage.Role.SYSTEM, L10n.lang("bbs.ui.ai.chat.unconfigured").get());

            return;
        }

        Replay replay = this.panel.replayEditor.getReplay();

        if (replay == null)
        {
            this.history.log(AiChatMessage.Role.SYSTEM, L10n.lang("bbs.ui.ai.bar.no_replay").get());

            return;
        }

        if (!(replay.form.get() instanceof mchorse.bbs_mod.forms.forms.ModelForm modelForm))
        {
            this.history.log(AiChatMessage.Role.SYSTEM, L10n.lang("bbs.ui.ai.creative.not_model").get());

            return;
        }

        this.busy = true;
        this.status.label = L10n.lang("bbs.ui.ai.panel.generating");

        AiChatMessage thinking = this.history.log(AiChatMessage.Role.ASSISTANT, L10n.lang("bbs.ui.ai.chat.thinking").get());

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

            /* Saved model bindings (model editor's AI tab / past confirmations) answer first */
            mchorse.bbs_mod.ai.pose.AiBoneBindings.apply(modelForm.model.get(), inventory, bones);

            if (!bones.isComplete())
            {
                /* The assistant never guesses bone names - it asks */
                thinking.setText(L10n.lang("bbs.ui.ai.ask.open").get());
                this.history.refresh();

                UIAiAskOverlayPanel ask = new UIAiAskOverlayPanel(context, bones.unresolved, inventory, modelForm.model.get(), (confirmed) ->
                {
                    /* The user may skip bones - then the map is still incomplete and
                     * PoseSolver would throw. Explain instead of ever crashing. */
                    if (!confirmed.isComplete())
                    {
                        thinking.setRole(AiChatMessage.Role.SYSTEM);
                        thinking.setText(L10n.lang("bbs.ui.ai.chat.bindings_missing").format(String.join(", ", confirmed.unresolved)).get());
                        this.history.refresh();

                        return;
                    }

                    this.previewGenerated(generated, confirmed, replay, thinking);
                });

                mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay.addOverlay(context, ask, 280, 0.7F);

                return;
            }

            this.previewGenerated(generated, bones, replay, thinking);
        }, (error) ->
        {
            this.busy = false;
            thinking.setRole(AiChatMessage.Role.ERROR);
            String detail = error.getMessage() == null || error.getMessage().isEmpty() ? error.type.name() : error.getMessage();

            thinking.setText(L10n.lang("bbs.ui.ai.panel.failed").format(error.type.name()).get() + " " + detail);
            this.history.refresh();
        });
    }

    private void previewGenerated(AnimationPlan generated, mchorse.bbs_mod.ai.pose.BoneNameResolver.Result bones, Replay replay, AiChatMessage thinking)
    {
        List<mchorse.bbs_mod.ai.pose.PoseSolver.KeyPose> poses;
        List<FrameCommitter.ChannelWrite> writes;

        try
        {
            poses = mchorse.bbs_mod.ai.pose.PoseSolver.solve(generated, bones);
            writes = mchorse.bbs_mod.ai.pose.PoseSolver.toChannelWrites(poses, replay.properties);
        }
        catch (Exception e)
        {
            /* A chat action must never take the game down (crash report 2026-10-02_11.56) */
            thinking.setRole(AiChatMessage.Role.ERROR);
            thinking.setText(L10n.lang("bbs.ui.ai.panel.failed").format(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()).get());
            this.history.refresh();

            return;
        }

        AiPreviewState.get().begin(replay, writes, this.buildPreviewDiff(writes));

        int lastTick = generated.beats.isEmpty() ? 0 : generated.beats.get(generated.beats.size() - 1).tick;

        thinking.setText(L10n.lang("bbs.ui.ai.chat.generated").format(generated.beats.size(), lastTick).get());
        this.refreshPreviewRow();
    }

    /** Polish: local intent parsing -> L3 on every numeric channel of the open replay -> preview. */
    private void executePolish(String text, List<PolishOp> ops)
    {
        this.history.log(AiChatMessage.Role.USER, text);
        this.input.setText("");

        Replay replay = this.panel.replayEditor.getReplay();

        if (replay == null)
        {
            this.history.log(AiChatMessage.Role.SYSTEM, L10n.lang("bbs.ui.ai.bar.no_replay").get());

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
            this.history.log(AiChatMessage.Role.SYSTEM, L10n.lang("bbs.ui.ai.bar.no_numeric").get());

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

        /* The transcript keeps the receipt around - what was accepted and how
         * to take it back, per the undo contract */
        this.history.log(AiChatMessage.Role.ASSISTANT, L10n.lang("bbs.ui.ai.bar.committed").format(diff.changedKeyCount()).get());
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
        boolean active = state.isActive();

        if (active)
        {
            this.status.label = L10n.lang("bbs.ui.ai.bar.preview").format(state.getChangeCount());

            if (state.getChangeCount() != this.lastLoggedCount)
            {
                this.history.log(AiChatMessage.Role.ASSISTANT, L10n.lang("bbs.ui.ai.bar.preview").format(state.getChangeCount()).get());
                this.lastLoggedCount = state.getChangeCount();
            }
        }
        else
        {
            this.lastLoggedCount = -1;
        }

        if (this.previewRow.isVisible() != active)
        {
            this.previewRow.setVisible(active);
            this.relayout();
        }

        this.resize();
    }

    @Override
    public void render(UIContext context)
    {
        /* Chrome surface: the bar is editor chrome, not workspace (spec 5.0.1 I) */
        this.area.render(context.batcher, BBSSettings.chromeSurface());
        context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.y + 2, Colors.opaque(BBSSettings.primaryColor.get()));

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
