package mchorse.bbs_mod.ai.ui.components;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_mod.utils.colors.Colors;

/**
 * AI 面板的通栏 accent 标题条（mockup ② ①②③ 红条）。
 * 自管渲染：accent 实心底 + 白字 + 左侧序号。高度随 UI 缩放。
 */
public class AiSectionHeader extends UIElement
{
    private final String title;

    public AiSectionHeader(String title)
    {
        super();

        this.title = title;
        this.h(HEADER_HEIGHT);
    }

    public static final int HEADER_HEIGHT = UIConstants.CONTROL_HEIGHT + 4;

    @Override
    public void render(UIContext context)
    {
        int accent = Colors.opaque(BBSSettings.primaryColor.get());

        context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.ey(), accent);

        FontRenderer font = context.batcher.getFont();
        int textHeight = font.getHeight();
        int textWidth = font.getWidth(this.title);

        context.batcher.textCard(this.title, this.area.x(0F, 6), this.area.y(0.5F, -textHeight / 2), Colors.WHITE, 0, 1, true);

        super.render(context);
    }
}
