package mchorse.bbs_mod.ai.ui;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.ai.AiChatRequest;
import mchorse.bbs_mod.ai.AiChatResponse;
import mchorse.bbs_mod.ai.AiClient;
import mchorse.bbs_mod.ai.AiException;
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
import mchorse.bbs_mod.ui.framework.elements.UIScrollView;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_mod.utils.colors.Colors;

import java.util.ArrayList;
import java.util.List;

/**
 * The standalone dashboard AI panel (copilot spec section 5.2, milestone M1):
 * long-form script input on the left, the parsed {@link AnimationPlan} beat
 * table in the middle, generation parameters on the right, and a bottom bar
 * with the 生成 blocking action plus the backend status lamp.
 *
 * <p>With no key configured the panel stays fully navigable - the lamp reads
 * 未配置 and the generate action explains itself instead of failing silently
 * (iron rule 4). Turning a parsed plan into actual poses is the M5 solver's
 * job; until then the beat table is the deliverable, which is exactly M1's
 * completion definition.</p>
 */
public class UIAiPanel extends UIDashboardPanel
{
    private final UITextarea<?> script;

    private final UIScrollView beats;

    private final UILabel status;

    private final ParamField duration;
    private final ParamField fps;

    private AnimationPlan plan;

    private boolean busy;

    private static final int BAR = UIConstants.CONTROL_HEIGHT + 8;
    private static final int HEADER = UIConstants.CONTROL_HEIGHT + 2;

    public UIAiPanel(UIDashboard dashboard)
    {
        super(dashboard);

        /* Left column: the script */
        this.script = new UITextarea<>((t) -> {});

        UILabel scriptHeader = this.header(L10n.lang("bbs.ui.ai.panel.script"));

        UIElement left = UI.column(UIConstants.MARGIN, scriptHeader, this.script);

        left.w(160).h(1F);
        scriptHeader.h(HEADER).w(1F);
        this.script.h(1F, -HEADER);

        /* Middle column: the beat table */
        UILabel beatsHeader = this.header(L10n.lang("bbs.ui.ai.panel.beats"));

        this.beats = new UIScrollView();
        this.beats.column(UIConstants.MARGIN).vertical().stretch().padding(UIConstants.MARGIN);

        UIElement middle = UI.column(UIConstants.MARGIN, beatsHeader, this.beats);

        middle.h(1F);
        beatsHeader.h(HEADER).w(1F);
        this.beats.h(1F, -HEADER);

        /* Right column: generation parameters */
        this.duration = ParamField.seconds();
        this.fps = ParamField.fps();

        UIElement params = UI.column(UIConstants.MARGIN,
            UI.label(L10n.lang("bbs.ui.ai.panel.duration"), UIConstants.CONTROL_HEIGHT),
            this.duration.element().h(UIConstants.CONTROL_HEIGHT),
            UI.label(L10n.lang("bbs.ui.ai.panel.fps"), UIConstants.CONTROL_HEIGHT),
            this.fps.element().h(UIConstants.CONTROL_HEIGHT),
            UI.label(L10n.lang("bbs.ui.ai.panel.vision"), UIConstants.CONTROL_HEIGHT),
            UI.label(L10n.lang("bbs.ui.ai.panel.vision_state"), UIConstants.CONTROL_HEIGHT)
        );

        params.w(140).h(1F);

        /* The three columns; the middle one takes the slack */
        UIElement columns = UI.row(UIConstants.MARGIN, left, middle, params);

        columns.row(UIConstants.MARGIN).preferred(1);
        columns.relative(this).w(1F).h(1F, -BAR);

        /* Bottom bar: the action + the backend status lamp */
        UIButton generate = new UIButton(L10n.lang("bbs.ui.ai.panel.generate"), (b) -> this.generate());

        generate.color(BBSSettings.primaryColor.get() | Colors.A100);

        this.status = new UILabel(L10n.lang("bbs.ui.ai.panel.lamp.unconfigured"));
        this.status.color(Colors.LIGHTER_GRAY, false);

        UIElement bottom = UI.row(UIConstants.MARGIN, generate, this.status);

        bottom.row(UIConstants.MARGIN).preferred(1).height(UIConstants.CONTROL_HEIGHT + 4);
        bottom.relative(this).y(1F, -BAR).w(1F).h(BAR);

        this.add(columns);
        this.add(bottom);
    }

    private UILabel header(IKey title)
    {
        UILabel label = new UILabel(title);

        label.color(Colors.WHITE, false).background(BBSSettings.primaryColor(Colors.A25));

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
            this.status.label = L10n.lang("bbs.ui.ai.panel.lamp.unconfigured");
            this.status.color(Colors.LIGHTER_GRAY, false);

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
        this.status.color(Colors.LIGHTER_GRAY, false);

        float durationSeconds = (float) this.duration.value();
        int totalTicks = Math.max(1, Math.round(durationSeconds * 20F));

        String system = L10n.lang("bbs.ui.ai.panel.prompt").get();
        String user = script + "\n\n[" + L10n.lang("bbs.ui.ai.panel.prompt_ticks").get() + ": " + totalTicks + "]";

        AiChatRequest request = new AiChatRequest(system, user);

        request.temperature(AiSettings.temperature.get());
        request.json(AiSettings.jsonMode.get() && AiSettings.supportsJsonMode.get());

        AiClient.get().chat(request, this::onPlan, this::onError);
    }

    private void onPlan(AiChatResponse response)
    {
        this.busy = false;

        try
        {
            this.plan = AnimationPlan.parse(response.content);
        }
        catch (AiException e)
        {
            this.onError(e);

            return;
        }

        this.fillBeats();

        this.status.label = L10n.lang("bbs.ui.ai.panel.plan_ok").format(this.plan.beats.size(), response.promptTokens + response.completionTokens);
        this.status.color(Colors.LIGHTEST_GRAY, false);
    }

    private void onError(AiException error)
    {
        this.busy = false;

        String detail = error.detail == null || error.detail.isEmpty() ? "" : ": " + error.detail;

        this.status.label = L10n.lang("bbs.ui.ai.panel.failed").format(error.type.name() + detail);
        this.status.color(Colors.RED, false);
    }

    /** Rebuild the beat table rows: index / tick / phase / pose / intents. */
    private void fillBeats()
    {
        List<UIElement> rows = new ArrayList<>();

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

                UILabel row = UI.label(L10n.lang("bbs.ui.ai.panel.beat_row")
                    .format(beat.index + 1, beat.tick, beat.phase, beat.pose, intents.toString()), UIConstants.LIST_ITEM_HEIGHT + 2);

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

        UIElement element()
        {
            return this.pad;
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

        double value()
        {
            return this.pad.getValue();
        }
    }
}
