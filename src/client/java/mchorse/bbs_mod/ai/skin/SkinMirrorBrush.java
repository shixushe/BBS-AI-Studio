package mchorse.bbs_mod.ai.skin;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.ui.dashboard.textures.data.Document;
import mchorse.bbs_mod.utils.resources.Pixels;
import mchorse.bbs_mod.utils.colors.Color;

/**
 * AI 皮肤编辑器的镜像笔刷（spec §5.7 item 2）。
 *
 * <p>笔刷落点绘制时同步到对侧：左右臂/腿联动绘制。
 * 挂载：UITexturePainter 的笔刷笔画回调内调用 mirrorPixel。
 * 纯像素操作——不进入已保存数据，只影响当前编辑层。</p>
 */
public class SkinMirrorBrush
{
    private boolean enabled = true;
    private final Document document;

    public SkinMirrorBrush(Document document)
    {
        this.document = document;
    }

    public boolean isEnabled()
    {
        return this.enabled;
    }

    public void setEnabled(boolean enabled)
    {
        this.enabled = enabled;
    }

    /**
     * 镜像笔刷落点：在画布坐标 x,y 绘制颜色时同步到画布中心线对侧。
     * 纯像素操作——不进入已保存数据，只影响当前编辑层。
     */
    public void mirrorPixel(Pixels layerPixels, int x, int y, int argb, int centerX)
    {
        if (!this.enabled)
        {
            return;
        }

        int mirrored = centerX + (centerX - x);

        if (mirrored >= 0 && mirrored < layerPixels.width && mirrored != x)
        {
            Color c = layerPixels.getColor(x, y);

            layerPixels.setColor(mirrored, y, new Color().set(c.r, c.g, c.b, c.a));
        }
    }
}
