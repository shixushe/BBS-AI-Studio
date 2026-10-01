package mchorse.bbs_mod.ai.ui;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.forms.structure.StructureManager;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.dashboard.panels.UIDashboardPanel;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.UIScrollView;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_mod.utils.colors.Colors;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 建筑 —— 独立 Dashboard 面板(用户要求;范围按企划书 §9/§10.8:本轮只做
 * 结构【理解】—— 枚举 .nbt 结构、读取尺寸与方块实体统计、给出语义描述;
 * AI 直接盖房子仍然不做)。结构来自 {@code StructureManager.getStructureIds()}
 * 的同一注册表,所以游戏里能点到的结构 AI 就能看到(§10.9 原则)。
 */
public class UIStructureAiPanel extends UIDashboardPanel
{
    private static final int BAR = UIConstants.CONTROL_HEIGHT + 8;
    private static final int HEADER = UIConstants.CONTROL_HEIGHT + 4;

    private final UIScrollView structures;
    private final UILabel description;

    public UIStructureAiPanel(UIDashboard dashboard)
    {
        super(dashboard);

        UILabel listHeader = this.header(L10n.lang("bbs.ui.ai.structure.list"));
        UILabel descHeader = this.header(L10n.lang("bbs.ui.ai.structure.description"));

        this.structures = new UIScrollView();
        this.structures.column(UIConstants.MARGIN).vertical().stretch().padding(UIConstants.MARGIN);

        this.description = new UILabel(L10n.lang("bbs.ui.ai.structure.pick"));
        this.description.color(Colors.LIGHTER_GRAY, false).background(BBSSettings.deepSurface());

        UIElement left = UI.column(UIConstants.MARGIN, listHeader, this.structures);

        left.w(200).h(1F);
        listHeader.h(HEADER).w(1F);
        this.structures.h(1F, -HEADER);

        UIElement right = UI.column(UIConstants.MARGIN, descHeader, this.description);

        right.h(1F);
        descHeader.h(HEADER).w(1F);
        this.description.h(1F, -HEADER);

        UIElement columns = UI.row(UIConstants.MARGIN, left, right);

        columns.row(UIConstants.MARGIN).preferred(1);
        columns.relative(this).w(1F).h(1F);

        this.onAppear(this::fillList);

        this.add(columns);
    }

    private UILabel header(IKey title)
    {
        UILabel label = new UILabel(title);

        label.color(Colors.WHITE, false).background(Colors.opaque(BBSSettings.primaryColor.get()));

        return label;
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

        for (String id : ids)
        {
            UILabel row = UI.label(L10n.lang("bbs.ui.ai.structure.row").format(id), UIConstants.LIST_ITEM_HEIGHT + 2);

            rows.add(row);
        }

        if (rows.isEmpty())
        {
            rows.add(UI.label(L10n.lang("bbs.ui.ai.structure.none"), UIConstants.CONTROL_HEIGHT));
        }

        this.structures.removeAll();
        this.structures.add(UI.column(1, rows.toArray(new UIElement[0])));

        this.description.label = L10n.lang("bbs.ui.ai.structure.count").format(ids.size());
    }

    @Override
    public void render(UIContext context)
    {
        this.area.render(context.batcher, BBSSettings.baseSurface());

        super.render(context);
    }
}
