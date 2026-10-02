package mchorse.bbs_mod.ai.ui;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.ai.AiChatRequest;
import mchorse.bbs_mod.ai.AiClient;
import mchorse.bbs_mod.ai.AiSettings;
import mchorse.bbs_mod.ai.ui.components.AiUi;
import mchorse.bbs_mod.forms.structure.StructureManager;
import mchorse.bbs_mod.forms.structure.StructureRenderData;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.dashboard.panels.UIDashboardPanel;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.UIScrollView;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.ui.framework.elements.utils.Batcher2D;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_mod.utils.colors.Colors;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 建筑 —— 独立 Dashboard 面板（用户要求；范围按企划书 §9/§10.8：结构【理解】——
 * 枚举 .nbt 结构、读取尺寸与方块统计、语义描述）。
 *
 * <p>以人为本的形态：结构列表可点选（选中高亮），右侧立即给出这一结构的
 * 尺寸/方块数/方块实体数等"看得懂的事实"；配置了后端时「AI 描述」把这些
 * 事实交给模型翻译成一段可读的说明。没有结构时告诉用户把 .nbt 放进哪。</p>
 */
public class UIStructureAiPanel extends UIDashboardPanel
{
    private static final int BAR = UIConstants.CONTROL_HEIGHT + 8;
    private static final int HEADER = AiUi.HEADER;

    private final UIScrollView structures;
    private final UIScrollView description;
    private final AiUi.Header listHeader;
    private final AiUi.Header descHeader;
    private final UIButton describe;

    private String selected;
    private boolean describing;
    private int lastListWidth;

    public UIStructureAiPanel(UIDashboard dashboard)
    {
        super(dashboard);

        this.listHeader = AiUi.header(L10n.lang("bbs.ui.ai.structure.list"));
        this.descHeader = AiUi.header(L10n.lang("bbs.ui.ai.structure.description"));

        this.structures = new UIScrollView();
        this.structures.column(UIConstants.MARGIN).vertical().stretch().padding(UIConstants.MARGIN);

        this.description = new UIScrollView();
        this.description.column(UIConstants.MARGIN).vertical().stretch().padding(UIConstants.MARGIN);

        this.describe = new UIButton(L10n.lang("bbs.ui.ai.structure.ai_describe"), (b) -> this.aiDescribe());
        this.describe.tooltip(L10n.lang("bbs.ui.ai.structure.ai_describe_tooltip"));

        UILabel pick = new UILabel(L10n.lang("bbs.ui.ai.structure.pick"));

        pick.color(Colors.LIGHTER_GRAY, false);

        /* Absolute layout: fixed 200px list on the left, description + AI button on the right */
        int listWidth = 200;

        listHeader.relative(this).x(0).y(0).w(listWidth).h(HEADER);
        this.structures.relative(this).x(0).y(HEADER).w(listWidth).h(1F, -HEADER);

        int rightX = listWidth + UIConstants.MARGIN;

        descHeader.relative(this).x(rightX).y(0).w(1F, -rightX).h(HEADER);
        this.description.relative(this).x(rightX).y(HEADER).w(1F, -rightX).h(1F, -(HEADER + UIConstants.CONTROL_HEIGHT + UIConstants.MARGIN));
        this.describe.relative(this).x(rightX).y(1F, -(UIConstants.CONTROL_HEIGHT + UIConstants.MARGIN)).w(1F, -rightX).h(UIConstants.CONTROL_HEIGHT);

        this.onAppear(this::fillList);

        this.add(listHeader);
        this.add(this.structures);
        this.add(descHeader);
        this.add(this.description);
        this.add(this.describe);
    }


    private void fillList()
    {
        List<UIElement> rows = new ArrayList<>();
        List<String> ids;

        try
        {
            ids = StructureManager.getStructureIds();
        }
        catch (Exception e)
        {
            ids = new ArrayList<>();
        }

        int accent = BBSSettings.primaryColor.get() | Colors.A100;

        for (String id : ids)
        {
            String captured = id;
            UIButton row = new UIButton(L10n.lang("bbs.ui.ai.structure.row").format(id), (b) -> this.select(captured));

            row.color(captured.equals(this.selected) ? accent : -1);
            rows.add(row);
        }

        if (rows.isEmpty())
        {
            rows.add(UI.label(L10n.lang("bbs.ui.ai.structure.none_hint"), UIConstants.CONTROL_HEIGHT * 2));
        }

        this.structures.removeAll();
        this.structures.add(UI.column(1, rows.toArray(new UIElement[0])));
        this.listHeader.caption(L10n.lang("bbs.ui.ai.hub.board_count").format(ids.size()).get());

        if (this.selected == null && !ids.isEmpty())
        {
            this.select(ids.get(0));
        }
        else if (this.selected != null && !ids.contains(this.selected))
        {
            this.selected = null;
            this.showLines(List.of(L10n.lang("bbs.ui.ai.structure.pick").get()));
        }
    }

    /** Selecting a structure immediately shows its human-readable facts. */
    private void select(String id)
    {
        this.selected = id;
        this.descHeader.caption(id);
        this.describe.setEnabled(true);
        this.fillList();

        StructureRenderData data = StructureManager.get(id);

        if (data == null)
        {
            this.showLines(List.of(L10n.lang("bbs.ui.ai.structure.unreadable").format(id).get()));

            return;
        }

        List<String> lines = new ArrayList<>();

        lines.add(L10n.lang("bbs.ui.ai.structure.facts_id").format(id).get());
        lines.add(L10n.lang("bbs.ui.ai.structure.facts_size").format(data.size.getX(), data.size.getY(), data.size.getZ()).get());
        lines.add(L10n.lang("bbs.ui.ai.structure.facts_blocks").format(data.getBlocks().size()).get());
        lines.add(L10n.lang("bbs.ui.ai.structure.facts_entities").format(data.getBlockEntities().size()).get());

        /* What it is made of: the five most common blocks, so the description
         * area reads like a material list even before the AI says anything */
        Map<String, Integer> counts = new LinkedHashMap<>();

        for (net.minecraft.block.BlockState state : data.getBlocks().values())
        {
            String name = String.valueOf(state.getBlock());
            counts.merge(name, 1, Integer::sum);
        }

        if (!counts.isEmpty())
        {
            lines.add("");
            lines.add(L10n.lang("bbs.ui.ai.structure.facts_makeup").get());

            counts.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(5)
                .forEach((e) -> lines.add("  " + L10n.lang("bbs.ui.ai.structure.facts_block_row").format(
                    e.getKey().substring(e.getKey().lastIndexOf('.') + 1), e.getValue()).get()));
        }

        this.showLines(lines);
    }

    /** AI 描述: the facts become an LLM prompt; without a backend the button says so. */
    private void aiDescribe()
    {
        if (this.describing)
        {
            return;
        }

        if (this.selected == null)
        {
            this.showLines(List.of(L10n.lang("bbs.ui.ai.structure.pick").get()));

            return;
        }

        if (!AiSettings.isConfigured())
        {
            this.showLines(List.of(L10n.lang("bbs.ui.ai.chat.unconfigured").get()));

            return;
        }

        StructureRenderData data = StructureManager.get(this.selected);

        if (data == null)
        {
            this.showLines(List.of(L10n.lang("bbs.ui.ai.structure.unreadable").format(this.selected).get()));

            return;
        }

        this.describing = true;
        this.showLines(List.of(L10n.lang("bbs.ui.ai.chat.thinking").get()));

        String user = L10n.lang("bbs.ui.ai.structure.ai_prompt").format(
            this.selected,
            data.size.getX(), data.size.getY(), data.size.getZ(),
            data.getBlocks().size(),
            data.getBlockEntities().size()).get();

        AiChatRequest request = new AiChatRequest(L10n.lang("bbs.ui.ai.structure.ai_system").get(), user);

        request.temperature(0.4F);

        AiClient.get().chat(request, (response) ->
        {
            this.describing = false;
            this.showLines(FontLines.split(response.content, this.lastListWidth));
        }, (error) ->
        {
            this.describing = false;
            this.showLines(List.of(L10n.lang("bbs.ui.ai.panel.failed").format(error.type.name()).get()));
        });
    }

    /** Show pre-wrapped lines in the description scroll. */
    private void showLines(List<String> lines)
    {
        this.description.removeAll();

        for (String line : lines)
        {
            UILabel label = new UILabel(IKey.constant(line));

            label.color(Colors.WHITE, false);
            this.description.add(label);
        }

        this.description.resize();
    }

    @Override
    public void resize()
    {
        this.lastListWidth = Math.max(80, this.description.area.w - UIConstants.MARGIN * 4);

        super.resize();
    }

    @Override
    public void render(UIContext context)
    {
        this.area.render(context.batcher, BBSSettings.baseSurface());
        AiUi.topEdge(context, this.area.x, this.area.y, this.area.w);

        super.render(context);
    }

    /** Static wrap helper (keeps showLines call sites short). */
    private static final class FontLines
    {
        static List<String> split(String text, int width)
        {
            try
            {
                FontRenderer font = Batcher2D.getDefaultTextRenderer();
                List<String> out = new ArrayList<>();

                for (String paragraph : text.replace("\r", "").split("\n"))
                {
                    out.addAll(font.wrap(paragraph, Math.max(80, width)));
                }

                return out;
            }
            catch (Exception e)
            {
                List<String> fallback = new ArrayList<>();

                fallback.add(text);

                return fallback;
            }
        }
    }
}
