package mchorse.bbs_mod.ai.ui;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.ai.AiChatRequest;
import mchorse.bbs_mod.ai.AiException;
import mchorse.bbs_mod.ai.AiPlans;
import mchorse.bbs_mod.ai.AiSettings;
import mchorse.bbs_mod.ai.plan.AnimationPlan;
import mchorse.bbs_mod.ai.ui.components.AiUi;
import mchorse.bbs_mod.ai.ui.components.BeatTable;
import mchorse.bbs_mod.ai.ui.components.BeatTable.Row;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.dashboard.panels.UIDashboardPanel;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextarea;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_mod.utils.colors.Colors;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 副驾面板（copilot spec section 5.2）—— 从零重写的聚焦形态：
 *
 * ┌ ① 剧本 ──────────────┬ ② 节拍表 ──────────┐
 * │ 大输入区              │ 分镜节拍（滚动）    │
 * │ 角色 + 时长（一行）   │                    │
 * ├ [生成] ───────────────┴────────────────────┤
 * └ 状态回执                                   ┘
 *
 * 旧版三栏的装饰物（意图 chips、拖入占位框、fps/供应商/视觉参数、输出统计框）
 * 全部移除：chips 和拖入框没有任何行为，供应商与视觉属于设置页——面板只留与
 * "这一句话生成什么"直接相关的两个参数。问候语照聊天栏一样本地回应。
 */
public class UIAiPanel extends UIDashboardPanel
{
    private final UITextarea<?> script;
    private final UITextbox character;
    private final UITextbox duration;
    private final BeatTable beats;
    private final UILabel status;
    private final UIButton generate;

    private AnimationPlan plan;
    private boolean busy;

    public UIAiPanel(UIDashboard dashboard)
    {
        super(dashboard);

        this.script = new UITextarea<>((t) -> {});

        this.character = new UITextbox(64, (t) -> {});
        this.character.placeholder(L10n.lang("bbs.ui.ai.panel.character_hint"));

        this.duration = new UITextbox(8, (t) -> {});
        this.duration.setText("4");

        UIElement params = UI.row(UIConstants.MARGIN,
            UI.label(L10n.lang("bbs.ui.ai.panel.character"), UIConstants.CONTROL_HEIGHT),
            this.character.h(UIConstants.CONTROL_HEIGHT),
            UI.label(L10n.lang("bbs.ui.ai.panel.duration"), UIConstants.CONTROL_HEIGHT),
            this.duration.h(UIConstants.CONTROL_HEIGHT));

        params.row(UIConstants.MARGIN).preferred(0).height(UIConstants.CONTROL_HEIGHT);

        UILabel scriptHeader = AiUi.header(L10n.lang("bbs.ui.ai.panel.script"));

        UIElement left = UI.column(UIConstants.MARGIN, scriptHeader, this.script, params);

        left.w(0.45F).h(1F);
        this.script.h(1F, -(AiUi.HEADER + UIConstants.CONTROL_HEIGHT + UIConstants.MARGIN));

        this.beats = new BeatTable();

        UIElement right = UI.column(UIConstants.MARGIN, AiUi.header(L10n.lang("bbs.ui.ai.panel.beats")), this.beats);

        right.w(0.55F, -UIConstants.MARGIN).h(1F);
        this.beats.h(1F);

        UIElement columns = UI.row(UIConstants.MARGIN, left, right);

        columns.row(UIConstants.MARGIN).preferred(1);
        columns.relative(this).x(0).y(0).w(1F).h(1F, -AiUi.BAR);

        this.generate = new UIButton(L10n.lang("bbs.ui.ai.panel.generate"), (b) -> this.generate());

        this.generate.color(BBSSettings.primaryColor.get() | Colors.A100);
        this.generate.tooltip(L10n.lang("bbs.ui.ai.panel.generate_tooltip"));

        this.status = new UILabel(L10n.lang("bbs.ui.ai.panel.ready_hint"));
        this.status.color(Colors.LIGHTER_GRAY, false);

        UIElement bottom = UI.row(UIConstants.MARGIN, this.generate, this.status);

        bottom.row(UIConstants.MARGIN).preferred(1).height(AiUi.BAR - 8);
        bottom.relative(this).y(1F, -AiUi.BAR).w(1F).h(AiUi.BAR);

        this.add(columns);
        this.add(bottom);
    }

    /* 生成流程 */

    private void generate()
    {
        if (this.busy)
        {
            return;
        }

        String script = this.script.getText().trim();

        if (script.isEmpty())
        {
            this.status.label = L10n.lang("bbs.ui.ai.panel.empty_script");

            return;
        }

        /* A bare greeting is conversation, not a script - answer locally */
        if (mchorse.bbs_mod.ai.AiSmallTalk.isSmallTalk(script))
        {
            this.status.label = IKey.constant(mchorse.bbs_mod.ai.AiSmallTalk.reply(script));

            return;
        }

        this.busy = true;
        this.status.label = L10n.lang("bbs.ui.ai.panel.generating");

        float durationSeconds = this.parseDuration();
        int totalTicks = Math.max(1, Math.round(durationSeconds * 20F));

        String system = L10n.lang("bbs.ui.ai.panel.prompt").get();
        String characterName = this.character.getText().trim();
        String user = script + (characterName.isEmpty() ? "" : "\n[" + L10n.lang("bbs.ui.ai.panel.character").get() + ": " + characterName + "]")
            + "\n[" + L10n.lang("bbs.ui.ai.panel.prompt_ticks").get() + ": " + totalTicks + "]";

        AiChatRequest request = new AiChatRequest(system, user);

        request.temperature(AiSettings.temperature.get());
        request.json(AiSettings.jsonMode.get() && AiSettings.supportsJsonMode.get());

        AiPlans.generatePlan(request, this::onPlan, this::onError);
    }

    private float parseDuration()
    {
        try
        {
            return Math.max(0.5F, Float.parseFloat(this.duration.getText().trim()));
        }
        catch (NumberFormatException e)
        {
            return 4F;
        }
    }

    private void onPlan(AnimationPlan plan)
    {
        this.busy = false;
        this.plan = plan;

        List<BeatTable.Row> rows = new ArrayList<>();

        for (AnimationPlan.Beat beat : plan.beats)
        {
            BeatTable.Row row = new BeatTable.Row();

            row.index = beat.index;
            row.tick = beat.tick;
            row.phase = beat.phase;
            row.pose = beat.pose;
            row.selected = beat == plan.beats.get(plan.beats.size() - 1);

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

        int lastTick = plan.beats.isEmpty() ? 0 : plan.beats.get(plan.beats.size() - 1).tick;

        this.status.label = L10n.lang("bbs.ui.ai.panel.output_summary").format(plan.beats.size(), lastTick);
    }

    private void onError(AiException error)
    {
        this.busy = false;

        String detail = error.detail == null || error.detail.isEmpty() ? "" : ": " + error.detail;

        this.status.label = L10n.lang("bbs.ui.ai.panel.failed").format(error.type.name() + detail);
    }

    @Override
    public void render(UIContext context)
    {
        this.area.render(context.batcher, BBSSettings.baseSurface());

        super.render(context);
    }
}
