package mchorse.bbs_mod.ai.ui;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.ai.AiChatRequest;
import mchorse.bbs_mod.ai.AiException;
import mchorse.bbs_mod.ai.AiPlans;
import mchorse.bbs_mod.ai.AiSettings;
import mchorse.bbs_mod.ai.plan.AnimationPlan;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.dashboard.panels.UIDashboardPanel;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextarea;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.framework.elements.UIScrollView;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_mod.utils.colors.Colors;

import java.util.ArrayList;
import java.util.List;

/**
 * The standalone dashboard AI panel (copilot spec section 5.2, milestone M1),
 * laid out 1:1 against the UI mockup: three columns under full width accent
 * headers (① 剧本/提示, ② AnimationPlan 节拍表, ③ 生成参数), a beat table
 * with the #/tick/phase/pose/intent columns, drag-in placeholders for
 * reference material, parsed intent chips, an output box, and the bottom bar
 * with 生成 blocking plus the backend status lamp
 * (未配置 / 已配置 / 超时).
 */
public class UIAiPanel extends UIDashboardPanel
{
    private static final int BAR = UIConstants.CONTROL_HEIGHT + 8;
    private static final int HEADER = UIConstants.CONTROL_HEIGHT + 4;

    private final UITextarea<?> script;

    private final UIScrollView beats;

    private final UILabel status;
    private final UILabel output;

    private final ParamField duration;
    private final ParamField fps;
    private final UITextbox character;

    private AnimationPlan plan;

    private boolean busy;

    public UIAiPanel(UIDashboard dashboard)
    {
        super(dashboard);

        /* ① 剧本 / 提示 */
        UILabel scriptHeader = this.header(L10n.lang("bbs.ui.ai.panel.script"));

        this.script = new UITextarea<>((t) -> {});

        UILabel draggable = UI.label(L10n.lang("bbs.ui.ai.panel.draggable"), UIConstants.CONTROL_HEIGHT);
        UILabel intentsTitle = UI.label(L10n.lang("bbs.ui.ai.panel.intents"), UIConstants.CONTROL_HEIGHT);

        UIElement dropBoxes = UI.row(UIConstants.MARGIN,
            this.dropBox(L10n.lang("bbs.ui.ai.panel.drop_image")),
            this.dropBox(L10n.lang("bbs.ui.ai.panel.drop_video")));

        dropBoxes.row(UIConstants.MARGIN).preferred(0).height(44);

        UIElement chipsRow = UI.row(UIConstants.MARGIN);

        chipsRow.row(UIConstants.MARGIN).height(UIConstants.CONTROL_HEIGHT);

        for (String tag : new String[] {"起势 anticipation", "蓄力 compress", "腾空 rise", "落地 impact"})
        {
            chipsRow.add(new IntentChip(tag));
        }

        UIElement left = UI.column(UIConstants.MARGIN, scriptHeader, this.script, draggable, dropBoxes, intentsTitle, chipsRow);

        left.w(210).h(1F);
        scriptHeader.h(HEADER).w(1F);
        this.script.h(1F, -(HEADER + UIConstants.CONTROL_HEIGHT * 3 + 52));

        /* ② AnimationPlan 节拍表 */
        UILabel beatsHeader = this.header(L10n.lang("bbs.ui.ai.panel.beats"));

        this.beats = new UIScrollView();
        this.beats.column(UIConstants.MARGIN).vertical().stretch().padding(UIConstants.MARGIN);

        UIElement middle = UI.column(UIConstants.MARGIN, beatsHeader, this.beats);

        middle.h(1F);
        beatsHeader.h(HEADER).w(1F);
        this.beats.h(1F, -HEADER);

        /* ③ 生成参数 */
        this.duration = ParamField.seconds();
        this.fps = ParamField.fps();
        this.character = new UITextbox(64, (t) -> {});

        this.character.placeholder(L10n.lang("bbs.ui.ai.panel.character_hint"));

        this.output = new UILabel(L10n.lang("bbs.ui.ai.panel.output_empty"));
        this.output.color(Colors.LIGHTER_GRAY, false).background(BBSSettings.deepSurface());

        // 供应商下拉框:11 家预设,选完自动填接口地址(用户要求用下拉框)
        mchorse.bbs_mod.ui.framework.elements.buttons.UIChoiceButton<String> providerPick =
            new mchorse.bbs_mod.ui.framework.elements.buttons.UIChoiceButton<>(
                AiSettings.PRESETS.keySet(),
                (key) -> mchorse.bbs_mod.ui.utils.icons.Icons.SERVER,
                (key) -> mchorse.bbs_mod.l10n.keys.IKey.constant(key));

        providerPick.callback((key) ->
        {
            AiSettings.provider.set(key);
            AiSettings.applyPreset(key);
        });

        String currentProvider = AiSettings.provider.get();

        if (currentProvider != null && !currentProvider.isEmpty())
        {
            providerPick.setValue(currentProvider);
        }

        providerPick.h(UIConstants.CONTROL_HEIGHT);

        UIElement params = UI.column(UIConstants.MARGIN,
            UI.label(L10n.lang("bbs.ui.ai.panel.provider_label"), UIConstants.CONTROL_HEIGHT),
            providerPick,
            this.duration.element().h(UIConstants.CONTROL_HEIGHT),
            UI.label(L10n.lang("bbs.ui.ai.panel.fps"), UIConstants.CONTROL_HEIGHT),
            this.fps.element().h(UIConstants.CONTROL_HEIGHT),
            UI.label(L10n.lang("bbs.ui.ai.panel.character"), UIConstants.CONTROL_HEIGHT),
            this.character.h(UIConstants.CONTROL_HEIGHT),
            UI.label(L10n.lang("bbs.ui.ai.panel.vision"), UIConstants.CONTROL_HEIGHT),
            UI.label(AiSettings.supportsVision.get() ? L10n.lang("bbs.ui.ai.panel.vision_on") : L10n.lang("bbs.ui.ai.panel.vision_off"), UIConstants.CONTROL_HEIGHT),
            this.output.h(UIConstants.CONTROL_HEIGHT * 3)
        );

        params.w(150).h(1F);

        /* The three columns; the middle takes the slack */
        UIElement columns = UI.row(UIConstants.MARGIN, left, middle, params);

        columns.row(UIConstants.MARGIN).preferred(1);
        columns.relative(this).w(1F).h(1F, -BAR);

        /* Bottom: 生成 blocking + backend status lamp */
        UIButton generate = new UIButton(L10n.lang("bbs.ui.ai.panel.generate"), (b) -> this.generate());

        generate.color(BBSSettings.primaryColor.get() | Colors.A100);
        generate.tooltip(L10n.lang("bbs.ui.ai.panel.generate_tooltip"));

        this.status = new UILabel(L10n.lang("bbs.ui.ai.panel.lamp.unconfigured"));
        this.status.color(Colors.LIGHTER_GRAY, false);

        UIElement bottom = UI.row(UIConstants.MARGIN, generate, this.status);

        bottom.row(UIConstants.MARGIN).preferred(1).height(UIConstants.CONTROL_HEIGHT + 4);
        bottom.relative(this).y(1F, -BAR).w(1F).h(BAR);

        this.add(columns);
        this.add(bottom);
    }


    /** Plain-string label (table cells and such, no l10n key needed). */
    private UILabel textLabel(String text, int height)
    {
        UILabel label = new UILabel(new mchorse.bbs_mod.l10n.keys.StringKey(text));

        label.h(height);

        return label;
    }

    /** Full width accent bar with white text - the mockup's section headers. */
    private UILabel header(IKey title)
    {
        UILabel label = new UILabel(title);

        label.color(Colors.WHITE, false).background(Colors.opaque(BBSSettings.primaryColor.get()));

        return label;
    }

    /** Drop placeholder box (reference image / reference video). */
    private UILabel dropBox(IKey title)
    {
        UILabel label = new UILabel(title);

        label.color(Colors.LIGHTER_GRAY, false).background(BBSSettings.deepSurface()).labelAnchor(0.5F, 0.5F);

        return label;
    }

    private void generate()
    {
        if (this.busy)
        {
            return;
        }

        if (!AiSettings.isConfigured())
        {
            this.lamp(L10n.lang("bbs.ui.ai.panel.lamp.unconfigured"), Colors.LIGHTER_GRAY);

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
        String character = this.character.getText().trim();
        String user = script + (character.isEmpty() ? "" : "\n[" + L10n.lang("bbs.ui.ai.panel.character").get() + ": " + character + "]")
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

        this.fillBeats();

        int ticks = plan.beats.isEmpty() ? 0 : plan.beats.get(plan.beats.size() - 1).tick;

        this.output.label = L10n.lang("bbs.ui.ai.panel.output_summary").format(plan.beats.size(), ticks);
        this.lamp(L10n.lang("bbs.ui.ai.panel.lamp.ready"), Colors.GREEN);
    }

    private void onError(AiException error)
    {
        this.busy = false;

        String detail = error.detail == null || error.detail.isEmpty() ? "" : ": " + error.detail;

        this.status.label = L10n.lang("bbs.ui.ai.panel.failed").format(error.type.name() + detail);

        if (error.type == AiException.Type.TIMEOUT)
        {
            this.lamp(L10n.lang("bbs.ui.ai.panel.lamp.timeout"), Colors.RED);
        }
    }

    /** Status lamp: 未配置 (grey) / 已配置 (green) / 超时 (red). */
    private void lamp(IKey text, int color)
    {
        this.status.label = text;
        this.status.color(color, false);
    }

    /** Rebuild the beat table: the #/tick/phase/pose/intent grid of the mockup. */
    private void fillBeats()
    {
        List<UIElement> rows = new ArrayList<>();

        UILabel head0 = this.textLabel("#", UIConstants.LIST_ITEM_HEIGHT + 2);
        UILabel head1 = this.textLabel("tick", UIConstants.LIST_ITEM_HEIGHT + 2);
        UILabel head2 = this.textLabel(L10n.lang("bbs.ui.ai.table.phase").get(), UIConstants.LIST_ITEM_HEIGHT + 2);
        UILabel head3 = this.textLabel("pose", UIConstants.LIST_ITEM_HEIGHT + 2);
        UILabel head4 = this.textLabel(L10n.lang("bbs.ui.ai.table.intent").get(), UIConstants.LIST_ITEM_HEIGHT + 2);

        UILabel[] heads = {head0, head1, head2, head3, head4};

        for (UILabel head : heads)
        {
            head.color(Colors.WHITE, false).background(Colors.opaque(BBSSettings.primaryColor.get()));
        }

        UIElement head = UI.row(1, heads);

        head.row(1).height(UIConstants.LIST_ITEM_HEIGHT + 2);
        rows.add(head);

        if (this.plan != null)
        {
            for (AnimationPlan.Beat beat : this.plan.beats)
            {
                StringBuilder intents = new StringBuilder();

                for (int i = 0; i < beat.intents.size(); i++)
                {
                    if (i > 0)
                    {
                        intents.append(",");
                    }

                    intents.append(beat.intents.get(i).name().toLowerCase());
                }

                UILabel c0 = this.textLabel(String.valueOf(beat.index + 1), UIConstants.LIST_ITEM_HEIGHT + 2);
                UILabel c1 = this.textLabel(String.valueOf(beat.tick), UIConstants.LIST_ITEM_HEIGHT + 2);
                UILabel c2 = this.textLabel(beat.phase, UIConstants.LIST_ITEM_HEIGHT + 2);
                UILabel c3 = this.textLabel(beat.pose, UIConstants.LIST_ITEM_HEIGHT + 2);
                UILabel c4 = this.textLabel(intents.toString(), UIConstants.LIST_ITEM_HEIGHT + 2);

                UILabel[] cells = {c0, c1, c2, c3, c4};

                for (UILabel cell : cells)
                {
                    cell.color(Colors.WHITE, false);
                }

                UIElement row = UI.row(1, cells);

                row.row(1).height(UIConstants.LIST_ITEM_HEIGHT + 2);
                rows.add(row);
            }
        }

        this.beats.removeAll();
        this.beats.add(UI.column(1, rows.toArray(new UIElement[0])));
    }

    @Override
    public void render(UIContext context)
    {
        this.area.render(context.batcher, BBSSettings.baseSurface());

        super.render(context);
    }

    /** Accent chip with white text (the mockup's parsed intent tags). */
    private static class IntentChip extends UILabel
    {
        public IntentChip(String text)
        {
            super(L10n.lang("bbs.ui.ai.panel.chip").format(text), Colors.WHITE);

            this.color(Colors.WHITE, false).background(Colors.opaque(BBSSettings.primaryColor.get()));
            this.h(UIConstants.CONTROL_HEIGHT);
        }
    }

    /**
     * Thin wrapper so parameter inputs read as fields, not as widget plumbing.
     * Backed by a trackpad - the native numeric input (spec 5.0.1 G styling).
     */
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
