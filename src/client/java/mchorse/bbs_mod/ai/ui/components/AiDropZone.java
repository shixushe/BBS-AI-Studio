package mchorse.bbs_mod.ai.ui.components;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer;
import mchorse.bbs_mod.utils.colors.Colors;

/**
 * AI 面板的拖入占位框（mockup ② 的"参考图 / 参考视频"深色方块）。
 * 深底 + 居中灰字 + 1px 外框；后续接入拖入监听时只改这个类。
 */
public class AiDropZone extends UIElement
{
    private final IKey title;

    public AiDropZone(IKey title)
    {
        super();

        this.title = title;
    }

    @Override
    public void render(UIContext context)
    {
        int surface = BBSSettings.deepSurface();
        int border = BBSSettings.dividerColor();

        context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.ey(), surface);
        context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.y + 1, border);
        context.batcher.box(this.area.x, this.area.ey() - 1, this.area.ex(), this.area.ey(), border);
        context.batcher.box(this.area.x, this.area.y, this.area.x + 1, this.area.ey(), border);
        context.batcher.box(this.area.ex() - 1, this.area.y, this.area.ex(), this.area.ey(), border);

        FontRenderer font = context.batcher.getFont();
        int textWidth = font.getWidth(this.title.get());
        int textHeight = font.getHeight();

        context.batcher.textCard(this.title.get(), this.area.x(0.5F, -textWidth / 2), this.area.y(0.5F, -textHeight / 2), Colors.LIGHTER_GRAY, 0, 1, false);

        super.render(context);
    }
}
