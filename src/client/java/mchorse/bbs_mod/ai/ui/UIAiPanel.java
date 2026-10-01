package mchorse.bbs_mod.ai.ui;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.ai.AiChatRequest;
import mchorse.bbs_mod.ai.AiException;
import mchorse.bbs_mod.ai.AiPlans;
import mchorse.bbs_mod.ai.AiSettings;
import mchorse.bbs_mod.ai.plan.AnimationPlan;
import mchorse.bbs_mod.ai.ui.components.AiDropZone;
import mchorse.bbs_mod.ai.ui.components.AiSectionHeader;
import mchorse.bbs_mod.ai.ui.components.BeatTable;
import mchorse.bbs_mod.ai.ui.components.BeatTable.Row;
import mchorse.bbs_mod.ai.ui.components.IntentChip;
import mchorse.bbs_mod.ai.ui.components.StatusLamp;
import mchorse.bbs_mod.l10n.L10n;
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
 * AI 对话面板（copilot spec section 5.2，milestone M1），mockup 02 一比一：
 *
 * ┌ ① 剧本/提示 ┬ ② AnimationPlan 节拍表 ┬ ③ 生成参数 ┐
 * │ 剧本文本     │ 节拍表(5列网格)         │ 供应商下拉  │
 * │ 拖入框       │ 选中行 accent 洗染      │ 参数控件    │
 * │ 意图 chips   │                        │ 输出统计框  │
 * └ 生成 blocking ┴ 后端状态灯 ─────────────┘
 *
 * 面向对象设计：三个区域各自封装（AiSectionHeader/BeatTable/StatusLamp/
 * IntentChip/AiDropZone 组件类），布局全按窗口比例（fraction）伸缩——
 * 分辨率与 UI 缩放下形态一致，不再有固定像素小窗。
 *
 * 无后端时面板完全可用（iron rule 4）：状态灯提示未配置，生成动作说明缺什么。
 */
public class UIAiPanel extends UIDashboardPanel
{
    /** 底部操作栏高度。 */
    public static final int BAR = UIConstants.CONTROL_HEIGHT + 8;

    /* 三栏比例（mockup 02：左窄、中宽、右中） */
    private static final float LEFT_W = 0.26F;
    private static final float MID_W = 0.46F;
    private static final float RIGHT_W = 0.27F;

    private UITextarea<?> script;
    private UITextbox character;
    private BeatTable beats;
    private StatusLamp lamp;
    private UILabel status;
    private UILabel output;
    private ParamField duration;
    private ParamField fps;

    private final List<IntentChip> chips = new ArrayList<>();

    private AnimationPlan plan;
    private boolean busy;

    public UIAiPanel(UIDashboard dashboard)
    {
        super(dashboard);

        this.buildLeft();
        this.buildMiddle();
        this.buildRight();
        this.buildBottom();
    }

    /* ① 剧本 / 提示 */

    private void buildLeft()
    {
        UIElement column = UI.column(UIConstants.MARGIN);

        column.relative(this).x(0F).y(0F).w(LEFT_W).h(1F, -BAR);

        column.add(new AiSectionHeader(L10n.lang("bbs.ui.ai.panel.script").get()));

        this.script = new UITextarea<>((t) -> {});
        this.script.h(1F, -(AiSectionHeader.HEADER_HEIGHT + UIConstants.CONTROL_HEIGHT * 3 + 56));
        column.add(this.script);

        column.add(UI.label(L10n.lang("bbs.ui.ai.panel.draggable"), UIConstants.CONTROL_HEIGHT));

        UIElement drops = UI.row(UIConstants.MARGIN,
            new AiDropZone(L10n.lang("bbs.ui.ai.panel.drop_image")),
            new AiDropZone(L10n.lang("bbs.ui.ai.panel.drop_video")));

        drops.row(UIConstants.MARGIN).preferred(0).height(44);
        column.add(drops);

        column.add(UI.label(L10n.lang("bbs.ui.ai.panel.intents"), UIConstants.CONTROL_HEIGHT));

        UIElement chipRow = UI.row(UIConstants.MARGIN);

        chipRow.row(UIConstants.MARGIN).height(UIConstants.CONTROL_HEIGHT);

        for (String tag : new String[] {"起势 anticipation", "蓄力 compress", "腾空 rise", "落地 impact"})
        {
            IntentChip chip = new IntentChip(tag);

            this.chips.add(chip);
            chipRow.add(chip);
        }

        column.add(chipRow);
        this.add(column);
    }

    /* ② AnimationPlan 节拍表 */

    private void buildMiddle()
    {
        UIElement column = UI.column(UIConstants.MARGIN);

        column.relative(this).x(LEFT_W).y(0F).w(MID_W).h(1F, -BAR);

        column.add(new AiSectionHeader(L10n.lang("bbs.ui.ai.panel.beats").get()));

        this.beats = new BeatTable();
        this.beats.h(1F, -AiSectionHeader.HEADER_HEIGHT);
        column.add(this.beats);

        this.add(column);
    }

    /* ③ 生成参数 */

    private void buildRight()
    {
        this.duration = ParamField.seconds();
        this.fps = ParamField.fps();
        this.character = new UITextbox(64, (t) -> {});

        this.character.placeholder(L10n.lang("bbs.ui.ai.panel.character_hint"));

        this.lamp = new StatusLamp();

        this.output = new UILabel(L10n.lang("bbs.ui.ai.panel.output_empty"));
        this.output.color(Colors.LIGHTER_GRAY, false).background(BBSSettings.deepSurface());

        UIElement column = UI.column(UIConstants.MARGIN,
            new AiSectionHeader(L10n.lang("bbs.ui.ai.panel.params").get()),
            UI.label(L10n.lang("bbs.ui.ai.panel.provider_label"), UIConstants.CONTROL_HEIGHT),
            this.providerPick().h(UIConstants.CONTROL_HEIGHT),
            UI.label(L10n.lang("bbs.ui.ai.panel.duration"), UIConstants.CONTROL_HEIGHT),
            this.duration.element().h(UIConstants.CONTROL_HEIGHT),
            UI.label(L10n.lang("bbs.ui.ai.panel.fps"), UIConstants.CONTROL_HEIGHT),
            this.fps.element().h(UIConstants.CONTROL_HEIGHT),
            UI.label(L10n.lang("bbs.ui.ai.panel.character"), UIConstants.CONTROL_HEIGHT),
            this.character.h(UIConstants.CONTROL_HEIGHT),
            UI.label(L10n.lang("bbs.ui.ai.panel.vision"), UIConstants.CONTROL_HEIGHT),
            UI.label(L10n.lang("bbs.ui.ai.panel.vision_state"), UIConstants.CONTROL_HEIGHT),
            UI.label(L10n.lang("bbs.ui.ai.panel.output"), UIConstants.CONTROL_HEIGHT),
            this.output.h(UIConstants.CONTROL_HEIGHT * 3)
        );

        column.relative(this).x(LEFT_W + MID_W).y(0F).w(RIGHT_W).h(1F, -BAR);

        this.add(column);
    }

    /* 供应商下拉框（11 家预设，选完自动填接口地址） */

    private mchorse.bbs_mod.ui.framework.elements.buttons.UIChoiceButton<String> providerPick()
    {
        mchorse.bbs_mod.ui.framework.elements.buttons.UIChoiceButton<String> pick =
            new mchorse.bbs_mod.ui.framework.elements.buttons.UIChoiceButton<>(
                AiSettings.PRESETS.keySet(),
                (key) -> mchorse.bbs_mod.ui.utils.icons.Icons.SERVER,
                (key) -> mchorse.bbs_mod.l10n.keys.IKey.constant(key));

        pick.callback((key) ->
        {
            AiSettings.provider.set(key);
            AiSettings.applyPreset(key);
        });

        String current = AiSettings.provider.get();

        if (current != null && !current.isEmpty())
        {
            pick.setValue(current);
        }

        return pick;
    }

    /* 底部操作栏 */

    private void buildBottom()
    {
        UIButton generate = new UIButton(L10n.lang("bbs.ui.ai.panel.generate"), (b) -> this.generate());

        generate.color(BBSSettings.primaryColor.get() | Colors.A100);
        generate.tooltip(L10n.lang("bbs.ui.ai.panel.generate_tooltip"));

        this.lamp = new StatusLamp();

        this.status = new UILabel(L10n.lang("bbs.ui.ai.panel.lamp.unconfigured"));
        this.status.color(Colors.LIGHTER_GRAY, false);

        UIElement bottom = UI.row(UIConstants.MARGIN, generate, this.status);

        bottom.row(UIConstants.MARGIN).preferred(1).height(BAR - 8);
        bottom.relative(this).y(1F, -BAR).x(0F).w(1F).h(BAR);

        this.add(bottom);
    }

    /* 生成流程 */

    private void generate()
    {
        if (this.busy)
        {
            return;
        }

        if (!AiSettings.isConfigured())
        {
            this.lamp.set(StatusLamp.State.OFF);

            return;
        }

        String script = this.script.getText().trim();

        if (script.isEmpty())
        {
            this.status.label = L10n.lang("bbs.ui.ai.panel.empty_script");

            return;
        }

        this.busy = true;
        this.status.label = L10n.lang("bbs.ui.ai.panel.generating");

        float durationSeconds = (float) this.duration.value();
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

        this.output.label = L10n.lang("bbs.ui.ai.panel.output_summary").format(plan.beats.size(), lastTick);
        this.lamp.set(StatusLamp.State.READY);
    }

    private void onError(AiException error)
    {
        this.busy = false;

        String detail = error.detail == null || error.detail.isEmpty() ? "" : ": " + error.detail;

        this.status.label = L10n.lang("bbs.ui.ai.panel.failed").format(error.type.name() + detail);

        if (error.type == AiException.Type.TIMEOUT)
        {
            this.lamp.set(StatusLamp.State.TIMEOUT);
        }
    }

    @Override
    public void render(UIContext context)
    {
        this.area.render(context.batcher, BBSSettings.baseSurface());

        super.render(context);

        /* 剧本框可见外框(mockup 02:深色输入区有清晰边界) */
        var scriptArea = this.script.area;

        if (scriptArea.w > 0 && scriptArea.h > 0)
        {
            int border = BBSSettings.dividerColor();

            context.batcher.box(scriptArea.x - 1, scriptArea.y - 1, scriptArea.ex() + 1, scriptArea.y, border);
            context.batcher.box(scriptArea.x - 1, scriptArea.ey(), scriptArea.ex() + 1, scriptArea.ey() + 1, border);
            context.batcher.box(scriptArea.x - 1, scriptArea.y - 1, scriptArea.x, scriptArea.ey() + 1, border);
            context.batcher.box(scriptArea.ex(), scriptArea.y - 1, scriptArea.ex() + 1, scriptArea.ey() + 1, border);
        }
    }

    /* 数值参数字段封装（trackpad，原生数值输入） */

    private static class ParamField
    {
        private final mchorse.bbs_mod.ui.framework.elements.input.UITrackpad pad;

        private ParamField(mchorse.bbs_mod.ui.framework.elements.input.UITrackpad pad)
        {
            this.pad = pad;
        }

        static ParamField seconds()
        {
            mchorse.bbs_mod.ui.framework.elements.input.UITrackpad pad = new mchorse.bbs_mod.ui.framework.elements.input.UITrackpad((v) -> {});

            pad.limit(0.5D, 600D);
            pad.setValue(4D);

            return new ParamField(pad);
        }

        static ParamField fps()
        {
            mchorse.bbs_mod.ui.framework.elements.input.UITrackpad pad = new mchorse.bbs_mod.ui.framework.elements.input.UITrackpad((v) -> {});

            pad.limit(1D, 120D);
            pad.setValue(20D);

            return new ParamField(pad);
        }

        UIElement element()
        {
            return this.pad;
        }

        double value()
        {
            return this.pad.getValue();
        }
    }
}
