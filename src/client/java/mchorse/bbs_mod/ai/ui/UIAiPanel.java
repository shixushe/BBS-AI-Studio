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
import mchorse.bbs_mod.ai.ui.components.AiChatHistory;
import mchorse.bbs_mod.ai.ui.components.AiChatMessage;
import mchorse.bbs_mod.ai.ui.components.AiUi;
import mchorse.bbs_mod.ai.ui.components.BeatTable;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.dashboard.panels.UIDashboardPanel;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIScrollView;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_mod.utils.colors.Colors;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 对话中心 —— 仪表盘里唯一的"想动作"入口（吸收并取代旧的 §5.2 面板与
 * 创意模式两个面板：它们做的是同一件事，一个出单方案一个出多方案）。
 *
 * <p>一条时间线：对话（transcript + 输入）→ 方案板（每批候选留存、点选）
 * → 节拍明细（选中方案的分解）→ 写入影片（切回影片编辑器，走 M3 提交，
 * 恰一个撤销条目）。路由与影片对话栏一致：问候本地回应；曲线打磨词
 * 指回影片编辑器（那里才有具体曲线上下文）；其余按主题出一批方案。</p>
 */
public class UIAiPanel extends UIDashboardPanel
{
    private final AiChatHistory transcript;
    private final UITextbox input;
    private final UIScrollView board;
    private final UILabel boardHint;
    private final UILabel beatsHint;
    private final BeatTable beats;
    private final UILabel status;
    private final UIButton write;

    private final CreativeSession session = new CreativeSession();

    /** The variant the user picked on the board (-1 = none, write falls to the latest). */
    private int selectedVariant = -1;
    private int variantCount;
    private boolean busy;

    public UIAiPanel(UIDashboard dashboard)
    {
        super(dashboard);

        /* Conversation half */
        this.transcript = new AiChatHistory();

        UILabel chatHeader = AiUi.header(L10n.lang("bbs.ui.ai.hub.transcript"));

        this.input = new UITextbox(256, (t) -> {})
        {
            @Override
            public boolean subKeyPressed(UIContext context)
            {
                if (this.isFocused() && (context.isPressed(GLFW.GLFW_KEY_ENTER) || context.isPressed(GLFW.GLFW_KEY_KP_ENTER)))
                {
                    UIAiPanel.this.send();

                    return true;
                }

                return super.subKeyPressed(context);
            }
        };
        this.input.placeholder(L10n.lang("bbs.ui.ai.hub.placeholder"));

        UIButton send = new UIButton(L10n.lang("bbs.ui.ai.hub.send"), (b) -> this.send());

        send.color(BBSSettings.primaryColor.get() | Colors.A100);
        send.tooltip(L10n.lang("bbs.ui.ai.hub.send_tooltip"));

        this.write = new UIButton(L10n.lang("bbs.ui.ai.hub.write"), (b) -> this.writeToFilm());

        this.write.color(BBSSettings.primaryColor.get() | Colors.A100);
        this.write.tooltip(L10n.lang("bbs.ui.ai.hub.write_tooltip"));

        this.status = new UILabel(L10n.lang("bbs.ui.ai.hub.welcome"));
        this.status.color(Colors.LIGHTER_GRAY, false);

        UIElement bottom = UI.row(UIConstants.MARGIN, this.write, this.status);

        bottom.row(UIConstants.MARGIN).preferred(1).height(AiUi.BAR - 8);
        bottom.relative(this).y(1F, -AiUi.BAR).w(1F).h(AiUi.BAR);

        UIElement inputRow = UI.row(UIConstants.MARGIN, this.input, send);

        UILabel boardHeader = AiUi.header(L10n.lang("bbs.ui.ai.hub.board"));
        UILabel beatsHeader = AiUi.header(L10n.lang("bbs.ui.ai.hub.beats"));

        inputRow.row(UIConstants.MARGIN).preferred(0).height(UIConstants.CONTROL_HEIGHT + 4);

        this.board = new UIScrollView();
        this.board.column(UIConstants.MARGIN).vertical().stretch().scroll().padding(UIConstants.MARGIN);

        this.boardHint = UI.label(L10n.lang("bbs.ui.ai.hub.board_hint"), UIConstants.CONTROL_HEIGHT * 2);
        this.boardHint.color(Colors.LIGHTER_GRAY, false);
        this.board.add(this.boardHint);

        /* Example themes: fill the empty board with one-click starts */
        for (String example : new String[]{"bbs.ui.ai.hub.example_1", "bbs.ui.ai.hub.example_2", "bbs.ui.ai.hub.example_3"})
        {
            String theme = L10n.lang(example).get();
            UIButton chip = new UIButton(IKey.constant(theme), (b) ->
            {
                this.input.setText(theme);
                this.getContext().focus(this.input);
            });

            chip.tooltip(L10n.lang("bbs.ui.ai.hub.example_tooltip"));
            this.board.add(chip);
        }

        this.beats = new BeatTable();

        this.beatsHint = UI.label(L10n.lang("bbs.ui.ai.hub.beats_hint"), UIConstants.CONTROL_HEIGHT * 2);
        this.beatsHint.color(Colors.LIGHTER_GRAY, false);

        /* Absolute layout - every block pinned; no nested resizers to guess at */
        int m = UIConstants.MARGIN;
        int row = UIConstants.CONTROL_HEIGHT + 4;
        int topPx = AiUi.HEADER + m * 2 + row + m + AiUi.HEADER + m;

        chatHeader.relative(this).x(0).y(0).w(1F).h(AiUi.HEADER);
        this.transcript.relative(this).x(m).y(AiUi.HEADER + m).w(1F, -m * 2).h(0.32F);

        inputRow.relative(this).x(m).y(0.32F, AiUi.HEADER + m * 2).w(1F, -m * 2).h(row);

        boardHeader.relative(this).x(0).y(0.32F, AiUi.HEADER + m * 2 + row + m).w(0.55F, -m).h(AiUi.HEADER);
        beatsHeader.relative(this).x(0.55F, m * 2).y(0.32F, AiUi.HEADER + m * 2 + row + m).w(0.45F, -m * 3).h(AiUi.HEADER);

        this.board.relative(this).x(m).y(0.32F, topPx).w(0.55F, -m).h(0.68F, -(topPx + AiUi.BAR));
        this.beats.relative(this).x(0.55F, m * 2).y(0.32F, topPx).w(0.45F, -m * 3).h(0.68F, -(topPx + AiUi.BAR));
        this.beatsHint.relative(this).x(0.55F, m * 3).y(0.32F, topPx + m).w(0.45F, -m * 4).h(UIConstants.CONTROL_HEIGHT * 2);

        this.write.relative(this).x(m).y(1F, -AiUi.BAR).w(UIConstants.VALUE_WIDTH).h(AiUi.BAR - 8);
        this.status.relative(this).x(m + UIConstants.VALUE_WIDTH + m).y(1F, -AiUi.BAR).w(1F, -(UIConstants.VALUE_WIDTH + m * 3)).h(AiUi.BAR - 8);

        this.add(chatHeader);
        this.add(this.transcript);
        this.add(inputRow);
        this.add(boardHeader);
        this.add(beatsHeader);
        this.add(this.board);
        this.add(this.beats);
        this.add(this.beatsHint);
        this.add(bottom);

        this.transcript.log(AiChatMessage.Role.SYSTEM, L10n.lang("bbs.ui.ai.hub.welcome_long").get());

        mchorse.bbs_mod.ui.onboarding.TourAnchors.register("creative.theme", () -> this.input);
        mchorse.bbs_mod.ui.onboarding.TourAnchors.register("creative.candidates", () -> this.board);
    }

    /** One entry point: the sentence decides everything, same routing as the film chat. */
    private void send()
    {
        if (this.busy)
        {
            return;
        }

        String text = this.input.getText().trim();

        if (text.isEmpty())
        {
            this.status.label = L10n.lang("bbs.ui.ai.panel.empty_script");

            return;
        }

        this.transcript.log(AiChatMessage.Role.USER, text);
        this.input.setText("");

        /* Small talk answers locally - never a backend call */
        if (mchorse.bbs_mod.ai.AiSmallTalk.isSmallTalk(text))
        {
            this.transcript.log(AiChatMessage.Role.ASSISTANT, mchorse.bbs_mod.ai.AiSmallTalk.reply(text));

            return;
        }

        /* Curve polish lives in the film editor's chat - that is where the
         * concrete curves are; this panel plans actions */
        if (!mchorse.bbs_mod.ai.curve.PolishCommandParser.parse(text).isEmpty())
        {
            this.transcript.log(AiChatMessage.Role.SYSTEM, L10n.lang("bbs.ui.ai.hub.polish_hint").get());

            return;
        }

        this.generateBatch(text);
    }

    /** A theme becomes a BATCH of candidate directions; previous batches stay on the board. */
    private void generateBatch(String theme)
    {
        if (!AiSettings.isConfigured())
        {
            this.transcript.log(AiChatMessage.Role.SYSTEM, L10n.lang("bbs.ui.ai.chat.unconfigured").get());

            return;
        }

        if (!this.session.canCall())
        {
            /* Stop and ask, never silently burn the key (spec 11.3) */
            this.transcript.log(AiChatMessage.Role.SYSTEM, L10n.lang("bbs.ui.ai.creative.budget_spent").format(CreativeSession.MAX_CALLS).get());

            return;
        }

        this.busy = true;
        this.transcript.log(AiChatMessage.Role.ASSISTANT, L10n.lang("bbs.ui.ai.chat.thinking").get());

        int count = CreativeSession.DEFAULT_CANDIDATES;
        String system = L10n.lang("bbs.ui.ai.creative.prompt").format(count).get();
        AiChatRequest request = new AiChatRequest(system, theme);

        request.temperature(1F);

        AiClient.get().chat(request, (response) ->
        {
            this.busy = false;

            try
            {
                CreativeProposal proposal = CreativeProposal.parse(theme, response.content);

                this.session.add(proposal);
                this.selectedVariant = -1;
                this.fillBoard();
                this.persist();

                this.transcript.log(AiChatMessage.Role.ASSISTANT,
                    L10n.lang("bbs.ui.ai.hub.generated").format(proposal.variants.size(), this.session.proposals.size()).get());
            }
            catch (AiException e)
            {
                this.onError(e);
            }
        }, (error) ->
        {
            this.busy = false;
            this.onError(error);
        });
    }

    private void onError(AiException error)
    {
        this.transcript.log(AiChatMessage.Role.ERROR, L10n.lang("bbs.ui.ai.panel.failed").format(error.type.name()).get());
    }

    /** The board: every kept variant, oldest first; click to inspect its beats. */
    private void fillBoard()
    {
        List<UIElement> rows = new ArrayList<>();
        int index = 1;
        int accent = BBSSettings.primaryColor.get() | Colors.A100;

        this.variantCount = 0;

        for (CreativeProposal proposal : this.session.proposals)
        {
            for (CreativeProposal.Variant variant : proposal.variants)
            {
                int beatsCount = variant.plan == null ? 0 : variant.plan.beats.size();
                int captured = this.variantCount;

                UIButton row = new UIButton(L10n.lang("bbs.ui.ai.creative.candidate_row")
                    .format(index++, beatsCount, variant.notes.isBlank() ? "-" : variant.notes), (b) -> this.selectVariant(captured));

                row.color(captured == this.selectedVariant ? accent : -1);
                row.tooltip(L10n.lang("bbs.ui.ai.creative.pick_tooltip"));
                rows.add(row);

                this.variantCount++;
            }
        }

        this.board.removeAll();

        if (rows.isEmpty())
        {
            this.board.add(this.boardHint);
        }
        else
        {
            if (this.selectedVariant < 0 || this.selectedVariant >= this.variantCount)
            {
                this.selectedVariant = this.variantCount - 1;
            }

            this.board.add(UI.column(1, rows.toArray(new UIElement[0])));
        }

        this.showSelectedBeats();
    }

    private void selectVariant(int index)
    {
        this.selectedVariant = index;
        this.fillBoard();
    }

    /** The right-hand beat table mirrors the picked variant. */
    private void showSelectedBeats()
    {
        CreativeProposal.Variant variant = this.selectedOrLatest();

        this.beats.setRows(new ArrayList<>());
        this.beatsHint.setVisible(variant == null || variant.plan == null);

        if (variant == null || variant.plan == null)
        {
            return;
        }

        List<BeatTable.Row> rows = new ArrayList<>();

        for (mchorse.bbs_mod.ai.plan.AnimationPlan.Beat beat : variant.plan.beats)
        {
            BeatTable.Row row = new BeatTable.Row();

            row.index = beat.index;
            row.tick = beat.tick;
            row.phase = beat.phase;
            row.pose = beat.pose;
            row.selected = beat == variant.plan.beats.get(variant.plan.beats.size() - 1);

            StringBuilder intents = new StringBuilder();

            for (int i = 0; i < beat.intents.size(); i++)
            {
                if (i > 0)
                {
                    intents.append(",");
                }

                intents.append(beat.intents.get(i).name().toLowerCase());
            }

            row.intents = intents.toString();
            rows.add(row);
        }

        this.beats.setRows(rows);
    }

    /** The variant writing takes: the user's pick, or the latest when untouched. */
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

    /** 写入影片: switch to the film editor, then commit through M3 - one undo entry. */
    private void writeToFilm()
    {
        if (this.session.proposals.isEmpty())
        {
            this.transcript.log(AiChatMessage.Role.SYSTEM, L10n.lang("bbs.ui.ai.creative.nothing").get());

            return;
        }

        UIFilmPanel panel = this.dashboard.getPanel(UIFilmPanel.class);

        if (panel == null || panel.getData() == null || panel.replayEditor.getReplay() == null)
        {
            this.transcript.log(AiChatMessage.Role.SYSTEM, L10n.lang("bbs.ui.ai.creative.no_replay").get());

            return;
        }

        /* Switch to the film editor explicitly (spec 5.5 hard rule 2) */
        this.dashboard.setPanel(panel);

        CreativeProposal.Variant variant = this.selectedOrLatest();

        if (variant == null || variant.plan == null)
        {
            this.transcript.log(AiChatMessage.Role.SYSTEM, L10n.lang("bbs.ui.ai.creative.nothing").get());

            return;
        }

        var replay = panel.replayEditor.getReplay();

        if (!(replay.form.get() instanceof ModelForm modelForm))
        {
            this.transcript.log(AiChatMessage.Role.SYSTEM, L10n.lang("bbs.ui.ai.creative.not_model").get());

            return;
        }

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
                    this.transcript.log(AiChatMessage.Role.SYSTEM,
                        L10n.lang("bbs.ui.ai.chat.bindings_missing").format(String.join(", ", confirmed.unresolved)).get());

                    return;
                }

                this.commitWrites(panel, panel.getData(), variant, replay, confirmed);
            });

            mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay.addOverlay(this.getContext(), ask, 280, 0.7F);

            return;
        }

        this.commitWrites(panel, panel.getData(), variant, replay, bones);
    }

    private void commitWrites(UIFilmPanel panel, ValueGroup film, CreativeProposal.Variant variant, mchorse.bbs_mod.film.replays.Replay replay, BoneNameResolver.Result bones)
    {
        try
        {
            List<PoseSolver.KeyPose> poses = PoseSolver.solve(variant.plan, bones);
            List<FrameCommitter.ChannelWrite> writes = PoseSolver.toChannelWrites(poses, replay.properties);

            mchorse.bbs_mod.ai.commit.FrameDiff diff = mchorse.bbs_mod.ai.commit.FrameCommitter.commit(film, panel.getUndoHandler().getUndoManager(), writes);

            if (!diff.affectedChannels.isEmpty())
            {
                mchorse.bbs_mod.api.client.events.FilmEditEvents.notifyChanges(
                    new ArrayList<mchorse.bbs_mod.settings.values.base.BaseValue>(diff.affectedChannels),
                    mchorse.bbs_mod.api.client.events.FilmEditEvents.Cause.EDIT);
            }

            this.transcript.log(AiChatMessage.Role.ASSISTANT,
                L10n.lang("bbs.ui.ai.hub.written").format(poses.size(), diff.changedKeyCount()).get());
        }
        catch (Exception e)
        {
            /* A panel action must never take the game down */
            this.transcript.log(AiChatMessage.Role.ERROR,
                L10n.lang("bbs.ui.ai.panel.failed").format(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()).get());
        }
    }

    /** Draft persistence (spec 5.5 hard rule 1) - one slug per theme. */
    private void persist()
    {
        String theme = this.input.getText().trim();
        String slug = theme.isEmpty() ? "draft" : theme.substring(0, Math.min(24, theme.length())).replaceAll("[^\\p{L}\\p{N}]+", "_");

        this.session.save(CreativeSession.draftFile(BBSMod.getSettingsFolder(), slug));
    }

    @Override
    public void render(UIContext context)
    {
        this.area.render(context.batcher, BBSSettings.baseSurface());

        super.render(context);
    }
}
