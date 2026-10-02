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
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.dashboard.panels.UIDashboardPanel;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.framework.UIContext;
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
 * Creative mode (copilot spec sections 5.5 + 11): a top-level dashboard panel
 * where a fuzzy theme becomes MULTIPLE candidate directions. The rules that
 * make it creative mode and not "a looser execution mode":
 *
 * <ul>
 * <li>products are batches - 换一批 keeps every previous batch (11.3)</li>
 * <li>NOTHING is written to the film from here; adoption is an explicit
 * selection that switches back to the film editor and goes through the M3
 * commit - exactly one undo entry (11.2 rule 2)</li>
 * <li>the session's call budget stops and asks instead of silently burning
 * the key (11.3)</li>
 * <li>drafts persist under {@code <settings>/ai_creative}, so closing the
 * panel loses nothing (5.5 hard rule)</li>
 * </ul>
 *
 * <p>It stops after 分镜 by design (spec 11.1): no modeling, no fake scene
 * building - adoption hands the beat plan to the execution pipeline.</p>
 */
public class UICreativeModePanel extends UIDashboardPanel
{
    private static final int BAR = UIConstants.CONTROL_HEIGHT + 8;
    private static final int HEADER = UIConstants.CONTROL_HEIGHT + 2;

    private final UITextarea<?> theme;

    private final UILabel status;
    private final UIElement candidates;

    private final CreativeSession session = new CreativeSession();

    private boolean busy;

    public UICreativeModePanel(UIDashboard dashboard)
    {
        super(dashboard);

        this.theme = new UITextarea<>((t) -> {});

        UILabel themeHeader = this.header(L10n.lang("bbs.ui.ai.creative.theme"));
        UILabel paramsHeader = this.header(L10n.lang("bbs.ui.ai.creative.params"));

        UILabel themeLabel = UI.label(L10n.lang("bbs.ui.ai.creative.theme_hint"), UIConstants.CONTROL_HEIGHT);
        UILabel budgetLabel = UI.label(L10n.lang("bbs.ui.ai.creative.budget").format(CreativeSession.MAX_CALLS), UIConstants.CONTROL_HEIGHT);

        UIElement left = UI.column(UIConstants.MARGIN, themeHeader, themeLabel, this.theme, paramsHeader, budgetLabel);

        left.w(180).h(1F);
        themeHeader.h(HEADER).w(1F);
        this.theme.h(1F, -(HEADER + UIConstants.CONTROL_HEIGHT + HEADER));
        paramsHeader.h(HEADER).w(1F);

        UILabel candidatesHeader = this.header(L10n.lang("bbs.ui.ai.creative.candidates"));

        this.candidates = UI.column(UIConstants.MARGIN);

        UIElement middle = UI.column(UIConstants.MARGIN, candidatesHeader, this.candidates);

        middle.h(1F);
        candidatesHeader.h(HEADER).w(1F);
        this.candidates.h(1F, -HEADER);

        UIElement columns = UI.row(UIConstants.MARGIN, left, middle);

        columns.row(UIConstants.MARGIN).preferred(1);
        columns.relative(this).w(1F).h(1F, -BAR);

        UIButton refresh = new UIButton(L10n.lang("bbs.ui.ai.creative.refresh"), (b) -> this.refresh());
        UIButton adopt = new UIButton(L10n.lang("bbs.ui.ai.creative.adopt"), (b) -> this.adopt());

        adopt.color(BBSSettings.primaryColor.get() | Colors.A100);

        this.status = new UILabel(L10n.lang("bbs.ui.ai.creative.idle"));
        this.status.color(Colors.LIGHTER_GRAY, false);

        UIElement bottom = UI.row(UIConstants.MARGIN, refresh, adopt, this.status);

        bottom.row(UIConstants.MARGIN).preferred(1).height(UIConstants.CONTROL_HEIGHT + 4);
        bottom.relative(this).y(1F, -BAR).w(1F).h(BAR);

        this.add(columns);
        this.add(bottom);

        mchorse.bbs_mod.ui.onboarding.TourAnchors.register("creative.theme", () -> this.theme);
        mchorse.bbs_mod.ui.onboarding.TourAnchors.register("creative.candidates", () -> this.candidates);
    }

    private UILabel header(IKey title)
    {
        UILabel label = new UILabel(title);

        label.color(Colors.WHITE, false).background(BBSSettings.primaryColor(Colors.A25));

        return label;
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

    /** The candidate board: every kept variant, oldest first. */
    private void fillCandidates()
    {
        List<UIElement> rows = new ArrayList<>();
        int index = 1;

        for (CreativeProposal proposal : this.session.proposals)
        {
            for (CreativeProposal.Variant variant : proposal.variants)
            {
                int beats = variant.plan == null ? 0 : variant.plan.beats.size();

                rows.add(UI.label(L10n.lang("bbs.ui.ai.creative.candidate_row")
                    .format(index++, beats, variant.notes.isBlank() ? "-" : variant.notes), UIConstants.LIST_ITEM_HEIGHT + 2));
            }
        }

        this.candidates.removeAll();
        this.candidates.add(UI.column(1, rows.toArray(new UIElement[0])));
    }

    /**
     * 采纳所选: the LAST generated variant is the selection (the board is a
     * flat list; row-level selection arrives with the semantic panel's
     * RowStyle work). Adoption switches to the film editor FIRST and then
     * commits through the M3 machinery - one undo entry, and this panel has
     * no write path of its own.
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

        CreativeProposal.Variant latest = this.session.variants()
            .get(this.session.variants().size() - 1);

        if (latest.plan == null)
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

        /* Bone inventory from ModelForm.bones (spec 3 L2); only EXACT alias
         * matches auto-confirm - anything fuzzier refuses and waits for the
         * user's pick, never guesses */
        List<String> inventory = new ArrayList<>();

        for (mchorse.bbs_mod.settings.values.base.BaseValue child : modelForm.bones.getAll())
        {
            inventory.add(child.getId());
        }

        BoneNameResolver.Result bones = BoneNameResolver.resolve(inventory);

        /* Saved model bindings answer before the ask dialog */
        mchorse.bbs_mod.ai.pose.AiBoneBindings.apply(modelForm.model.get(), inventory, bones);

        if (!bones.isComplete())
        {
            /* Ask - never guess (spec 12.2) */
            this.status.label = L10n.lang("bbs.ui.ai.ask.open");

            UIAiAskOverlayPanel ask = new UIAiAskOverlayPanel(this.getContext(), bones.unresolved, inventory, modelForm.model.get(), (confirmed) ->
            {
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
