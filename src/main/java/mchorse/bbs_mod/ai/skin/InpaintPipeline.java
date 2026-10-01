package mchorse.bbs_mod.ai.skin;

import mchorse.bbs_mod.ai.AiException;
import mchorse.bbs_mod.ai.AiImageBackend;
import mchorse.bbs_mod.utils.resources.Pixels;
import mchorse.bbs_mod.utils.colors.Color;

/**
 * AI 局部重绘（inpainting）管线 (spec §10.7)。
 *
 * 核心思路：裁剪选区 → 单独生成 → 像素级合成回原图。
 * 这比 OpenAI 专用 images/edits multipart 更通用——任何支持
 * images/generations 的网关都能用，且选区外像素逐位不动。
 */
public class InpaintPipeline
{
    /**
     * 局部重绘：裁剪选区区域 → 用区域描述生成替换内容 → 像素级合成回原图。
     * 选区外像素逐位不动。
     *
     * @param base         原图像素
     * @param x            选区左上角 x
     * @param y            选区左上角 y
     * @param w            选区宽
     * @param h            选区高
     * @param prompt       重绘描述
     * @param backend      图像后端
     * @return 替换选区内的像素（调用方负责写回原图）
     */
    public static Pixels repaint(Pixels base, int x, int y, int w, int h, String prompt, AiImageBackend backend) throws AiException
    {
        /* 裁剪选区 */
        Pixels crop = Pixels.fromSize(w, h);

        for (int py = 0; py < h; py++)
        {
            for (int px = 0; px < w; px++)
            {
                int sx = x + px;
                int sy = y + py;

                if (sx < base.width && sy < base.height)
                {
                    Color c = base.getColor(sx, sy);
                    crop.setColor(px, py, new Color().set(c.r, c.g, c.b, c.a));
                }
            }
        }

        /* 用裁剪区域 + prompt 生成替换内容 */
        Pixels generated = backend.generate(prompt, crop, w, h);

        return generated;
    }
}
