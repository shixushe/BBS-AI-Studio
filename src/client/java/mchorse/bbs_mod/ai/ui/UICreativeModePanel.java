package mchorse.bbs_mod.ai.ui;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.ai.AiChatRequest;
import mchorse.bbs_mod.ai.AiClient;
import mchorse.bbs_mod.ai.AiException;
import mchorse.bbs_mod.ai.AiSettings;
import mchorse.bbs_mod.ai.commit.FrameCommitter;
import mchorse.bbs_mod.ai.creative.CreativeProposal;
import mchorse.bbs_mod.ai.creative.CreativeSession;
import mchorse.bbs_mod.ai.pose.BoneNameResolver;
import mchorse.bbs_mod.ai.pose.PoseSolver;
import mchorse.bbs_mod.ai.ui.components.AiUi;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.dashboard.panels.UIDashboardPanel;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIScrollView;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextarea;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_mod.utils.colors.Colors;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 创意模式（copilot spec sections 5.5 + 11）—— 从零重写的竖向流：
 *
 * ┌ 主题 ──────────────────────────┐
 * │ [主题输入……]         [换一批]  │
 * ├ 候选板 ────────────────────────┤
 * │ #1 摔跤…   ← 点选（高亮）       │
 * │ #2 舞蹈…                       │
 * └ [采纳所选]  状态回执 ──────────┘
 *
 * 规则不变：候选成批保留（换一批不清板）；这里不写影片——采纳是显式选择，
 * 切回影片编辑器走 M3 提交（恰一个撤销条目）；调用额度到顶即停（11.3）；
 * 草稿持久化，面板关闭不丢（5.5 硬规则）。止步分镜（11.1）。
 */
public class UICreativeModePanel extends UIDashboardPanel
{
    private static final int THEME_HEIGHT = UIConstants.CONTROL_HEIGHT * 3;

    private final UITextarea<?> theme;
    private final UIScrollView candidates;
    private final UILabel candidatesHint;
    private final UILabel status;
    private final UIButton refresh;

    private final CreativeSession session = new CreativeSession();

    /** The variant the user picked on the board (-1 = none, adopt falls to the latest). */
    private int selectedVariant = -1;
    private int variantCount;
    private boolean busy;

    public UICreativeModePanel(UIDashboard dashboard)
    {
        super(dashboard);

        this.theme = new UITextarea<>((t) -> {});
        this.theme.h(THEME_HEIGHT);

        this.refresh = new UIButton(L10n.lang("bbs.ui.ai.creative.refresh"), (b) -> this.refresh());
        this.refresh.tooltip(L10n.lang("bbs.ui.ai.creative.budget").format(CreativeSession.MAX_CALLS));

        UIElement themeRow = UI.row(UIConstants.MARGIN, this.theme, this.refresh);

        themeRow.row(UIConstants.MARGIN).preferred(0).height(THEME_HEIGHT);

        UILabel themeHeader = AiUi.header(L10n.lang("bbs.ui.ai.creative.theme"));
        UILabel boardHeader = AiUi.header(L10n.lang("bbs.ui.ai.creative.candidates"));

        this.candidates = new UIScrollView();
        this.candidates.column(UIConstants.MARGIN).vertical().stretch().scroll().padding(UIConstants.MARGIN);

        this.candidatesHint = UI.label(L10n.lang("bbs.ui.ai.creative.pick_hint"), UIConstants.CONTROL_HEIGHT * 2);
        this.candidatesHint.color(Colors.LIGHTER_GRAY, false);
        this.candidates.add(this.candidatesHint);

        this.status = new UILabel(L10n.lang("bbs.ui.ai.creative.idle"));
        this.status.color(Colors.LIGHTER_GRAY, false);

        UIButton adopt = new UIButton(L10n.lang("bbs.ui.ai.creative.adopt"), (b) -> this.adopt());

        adopt.color(BBSSettings.primaryColor.get() | Colors.A100);

        UIElement bottom = UI.row(UIConstants.MARGIN, adopt, this.status);

        bottom.row(UIConstants.MARGIN).preferred(1).height(AiUi.BAR - 8);
        bottom.relative(this).y(1F, -AiUi.BAR).w(1F).h(AiUi.BAR);

        UIElement content = UI.column(UIConstants.MARGIN, themeHeader, themeRow, boardHeader, this.candidates);

        content.row(UIConstants.MARGIN).preferred(0);
        content.relative(this).x(0).y(0).w(1F).h(1F, -AiUi.BAR);

        this.add(content);
        this.add(bottom);

        mchorse.bbs_mod.ui.onboarding.TourAnchors.register("creative.theme", () -> this.theme);
        mchorse.bbs_mod.ui.onboarding.TourAnchors.register("creative.candidates", () -> this.candidates);
    }

    /** 换一批: ask for another batch; previous batches stay on the board. */
    private void refresh()
    {
        if (this.busy)
        {
            return;
        }

        if (!AiSettings.isConfigured())
        {
            this.status.label = L10n.lang("bbs.ui.ai.panel.lamp.unconfigured");

            return;
        }

        if (!this.session.canCall())
        {
            /* Stop and ask, never silently burn the key (spec 11.3) */
            this.status.label = L10n.lang("bbs.ui.ai.creative.budget_spent").format(CreativeSession.MAX_CALLS);

            return;
        }

        String theme = this.theme.getText().trim();

        if (theme.isEmpty())
        {
            this.status.label = L10n.lang("bbs.ui.ai.panel.empty_script");

            return;
        }

        this.busy = true;
        this.status.label = L10n.lang("bbs.ui.ai.panel.generating");

        int count = CreativeSession.DEFAULT_CANDIDATES;
        String system = L10n.lang("bbs.ui.ai.creative.prompt").format(count).get();
        AiChatRequest request = new AiChatRequest(system, theme);

        request.temperature(1F);

        AiClient.get().chat(request, this::onProposals, this::onError);
    }

    private void onProposals(mchorse.bbs_mod.ai.AiChatResponse response)
    {
        this.busy = false;

        try
        {
            CreativeProposal proposal = CreativeProposal.parse(this.theme.getText().trim(), response.content);

            this.session.add(proposal);
            this.selectedVariant = -1;
            this.fillCandidates();
            this.persist();

            this.status.label = L10n.lang("bbs.ui.ai.creative.batch_ok").format(proposal.variants.size(), this.session.proposals.size());
            this.status.color(Colors.LIGHTEST_GRAY, false);
        }
        catch (AiException e)
        {
            this.onError(e);
        }
    }

    private void onError(AiException error)
    {
        this.busy = false;

        this.status.label = L10n.lang("bbs.ui.ai.panel.failed").format(error.type.name());
        this.status.color(Colors.RED, false);
    }

    /** The candidate board: every kept variant, oldest first; click to pick the adoption target. */
    private void fillCandidates()
    {
        List<UIElement> rows = new ArrayList<>();
        int index = 1;
        int accent = BBSSettings.primaryColor.get() | Colors.A100;

        this.variantCount = 0;

        for (CreativeProposal proposal : this.session.proposals)
        {
            for (CreativeProposal.Variant variant : proposal.variants)
            {
                int beats = variant.plan == null ? 0 : variant.plan.beats.size();
                int captured = this.variantCount;

                UIButton row = new UIButton(L10n.lang("bbs.ui.ai.creative.candidate_row")
                    .format(index++, beats, variant.notes.isBlank() ? "-" : variant.notes), (b) -> this.selectVariant(captured));

                row.color(captured == this.selectedVariant ? accent : -1);
                row.tooltip(L10n.lang("bbs.ui.ai.creative.pick_tooltip"));
                rows.add(row);

                this.variantCount++;
            }
        }

        this.candidates.removeAll();

        if (rows.isEmpty())
        {
            this.candidates.add(this.candidatesHint);
        }
        else
        {
            if (this.selectedVariant < 0 || this.selectedVariant >= this.variantCount)
            {
                this.selectedVariant = this.variantCount - 1;
            }

            this.candidates.add(UI.column(1, rows.toArray(new UIElement[0])));
        }
    }

    private void selectVariant(int index)
    {
        this.selectedVariant = index;
        this.fillCandidates();
    }

    /** The variant adoption takes: the user's pick, or the latest when untouched. */
    private CreativeProposal.Variant selectedOrLatest()
    {
        List<CreativeProposal.Variant> variants = this.session.variants();

        if (variants.isEmpty())
        {
            return null;
        }

        if (this.selectedVariant >= 0 && this.selectedVariant < variants.size())
        {
            return variants.get(this.selectedVariant);
        }

        return variants.get(variants.size() - 1);
    }

    /**
     * 采纳所选: switches to the film editor FIRST and then commits through the
     * M3 machinery - one undo entry, and this panel has no write path of its own.
     */
    private void adopt()
    {
        if (this.session.proposals.isEmpty())
        {
            this.status.label = L10n.lang("bbs.ui.ai.creative.nothing");

            return;
        }

        UIFilmPanel panel = this.dashboard.getPanel(UIFilmPanel.class);

        if (panel == null || panel.getData() == null || panel.replayEditor.getReplay() == null)
        {
            this.status.label = L10n.lang("bbs.ui.ai.creative.no_replay");

            return;
        }

        /* Switch to the film editor explicitly (spec 5.5 hard rule 2) */
        this.dashboard.setPanel(panel);

        CreativeProposal.Variant latest = this.selectedOrLatest();

        if (latest == null || latest.plan == null)
        {
            this.status.label = L10n.lang("bbs.ui.ai.creative.nothing");

            return;
        }

        var replay = panel.replayEditor.getReplay();

        if (!(replay.form.get() instanceof ModelForm modelForm))
        {
            this.status.label = L10n.lang("bbs.ui.ai.creative.not_model");

            return;
        }

        /* Bone inventory from ModelForm.bones (spec 3 L2); saved bindings answer
         * first, anything still unresolved asks - never guesses (spec 12.2) */
        List<String> inventory = new ArrayList<>();

        for (mchorse.bbs_mod.settings.values.base.BaseValue child : modelForm.bones.getAll())
        {
            inventory.add(child.getId());
        }

        BoneNameResolver.Result bones = BoneNameResolver.resolve(inventory);

        mchorse.bbs_mod.ai.pose.AiBoneBindings.apply(modelForm.model.get(), inventory, bones);

        if (!bones.isComplete())
        {
            UIAiAskOverlayPanel ask = new UIAiAskOverlayPanel(this.getContext(), bones.unresolved, inventory, modelForm.model.get(), (confirmed) ->
            {
                if (!confirmed.isComplete())
                {
                    this.status.label = L10n.lang("bbs.ui.ai.chat.bindings_missing").format(String.join(", ", confirmed.unresolved));

                    return;
                }

                this.adoptContinue(latest, replay, panel, confirmed);
            });

            mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay.addOverlay(this.getContext(), ask, 280, 0.7F);

            return;
        }

        this.adoptContinue(latest, replay, panel, bones);
    }

    private void adoptContinue(CreativeProposal.Variant latest, mchorse.bbs_mod.film.replays.Replay replay, UIFilmPanel panel, BoneNameResolver.Result bones)
    {
        List<PoseSolver.KeyPose> poses = PoseSolver.solve(latest.plan, bones);
        List<FrameCommitter.ChannelWrite> writes = PoseSolver.toChannelWrites(poses, replay.properties);
        ValueGroup film = panel.getData();

        FrameDiffCommit.commit(panel, film, writes);

        this.status.label = L10n.lang("bbs.ui.ai.creative.adopted").format(poses.size());
        this.status.color(Colors.LIGHTEST_GRAY, false);
    }

    /** Draft persistence (spec 5.5 hard rule 1) - one slug per theme. */
    private void persist()
    {
        String theme = this.theme.getText().trim();
        String slug = theme.isEmpty() ? "draft" : theme.substring(0, Math.min(24, theme.length())).replaceAll("[^\\p{L}\\p{N}]+", "_");

        CreativeSession.draftFile(BBSMod.getSettingsFolder(), slug);
        this.session.save(CreativeSession.draftFile(BBSMod.getSettingsFolder(), slug));
    }

    @Override
    public void render(UIContext context)
    {
        this.area.render(context.batcher, BBSSettings.baseSurface());

        super.render(context);
    }

    /** Client-side commit wrapper mirroring AiFilmBridge but taking explicit pieces. */
    private static class FrameDiffCommit
    {
        static mchorse.bbs_mod.ai.commit.FrameDiff commit(UIFilmPanel panel, ValueGroup film, List<FrameCommitter.ChannelWrite> writes)
        {
            mchorse.bbs_mod.ai.commit.FrameDiff diff = mchorse.bbs_mod.ai.commit.FrameCommitter.commit(film, panel.getUndoHandler().getUndoManager(), writes);

            if (!diff.affectedChannels.isEmpty())
            {
                mchorse.bbs_mod.api.client.events.FilmEditEvents.notifyChanges(
                    new ArrayList<mchorse.bbs_mod.settings.values.base.BaseValue>(diff.affectedChannels),
                    mchorse.bbs_mod.api.client.events.FilmEditEvents.Cause.EDIT);
            }

            return diff;
        }
    }
}
