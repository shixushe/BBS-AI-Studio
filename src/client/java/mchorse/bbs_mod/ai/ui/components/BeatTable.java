package mchorse.bbs_mod.ai.ui.components;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.UIScrollView;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_mod.utils.colors.Colors;

import java.util.ArrayList;
import java.util.List;

/**
 * AnimationPlan 节拍表组件（mockup ② 的 #/tick/相位/pose/意图 五列网格）。
 * accent 通栏表头 + 数据行；选中行 accent 洗染。自带滚动。
 */
public class BeatTable extends UIElement
{
    public static class Row
    {
        public int index;
        public int tick;
        public String phase = "";
        public String pose = "";
        public String intents = "";
        public boolean selected;
    }

    private static final int ROW_H = UIConstants.LIST_ITEM_HEIGHT + 2;

    private final UIScrollView scroll;

    public BeatTable()
    {
        super();

        this.scroll = new UIScrollView();
        this.scroll.column(UIConstants.MARGIN).vertical().stretch().padding(UIConstants.MARGIN);

        UIElement header = this.buildRow(new String[] {"#", "tick", "相位", "pose", "意图"}, -1, true);

        this.scroll.add(header);
        this.add(this.scroll);
    }

    /** 用行数据重建表格；selected 高亮该行（accent 洗染）。 */
    public void setRows(List<Row> rows)
    {
        List<UIElement> elements = new ArrayList<>();

        elements.add(this.buildRow(new String[] {"#", "tick", "相位", "pose", "意图"}, -1, true));

        for (Row row : rows)
        {
            String[] values = {String.valueOf(row.index + 1), String.valueOf(row.tick), row.phase, row.pose, row.intents};

            elements.add(this.buildRow(values, row.selected ? BBSSettings.primaryColor(Colors.A50) : 0, false));
        }

        this.scroll.removeAll();
        this.scroll.add(UI.column(1, elements.toArray(new UIElement[0])));
    }

    private UIElement buildRow(String[] values, int wash, boolean isHeader)
    {
        List<UIElement> cells = new ArrayList<>();

        for (String value : values)
        {
            UILabel cell = new UILabel(mchorse.bbs_mod.l10n.keys.IKey.constant(value), Colors.WHITE);

            cell.color(Colors.WHITE, false).h(ROW_H);

            if (isHeader)
            {
                cell.background(Colors.opaque(BBSSettings.primaryColor.get()));
            }
            else if (wash != 0)
            {
                cell.background(wash);
            }

            cells.add(cell);
        }

        UIElement line = UI.row(1, cells.toArray(new UIElement[0]));

        line.row(1).height(ROW_H);

        return line;
    }
}
