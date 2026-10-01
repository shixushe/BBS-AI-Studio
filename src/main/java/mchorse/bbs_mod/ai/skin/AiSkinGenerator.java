package mchorse.bbs_mod.ai.skin;

import mchorse.bbs_mod.ai.AiException;
import mchorse.bbs_mod.ai.AiImageBackend;
import mchorse.bbs_mod.utils.resources.Pixels;

/**
 * AI 皮肤生成端到端（spec §10.7）：图像模型 → 像素管线 → 可预览的像素画。
 * 预览与写入分离——写盘不可 Ctrl+Z，必须走"预览+备份+确认"三件套。
 * 纯逻辑类（无 MC/游戏依赖），可在无头环境测试。
 */
public class AiSkinGenerator
{
    public static final int DEFAULT_SIZE = 64;
    public static final int DEFAULT_COLORS = 16;

    private final AiImageBackend backend;
    private int size = DEFAULT_SIZE;
    private int paletteColors = DEFAULT_COLORS;

    public AiSkinGenerator(AiImageBackend backend)
    {
        this.backend = backend;
    }

    public AiSkinGenerator size(int size)
    {
        this.size = size;

        return this;
    }

    public AiSkinGenerator colors(int colors)
    {
        this.paletteColors = colors;

        return this;
    }

    /**
     * 生成 → 降采样 → 量化，产出可直接写入皮肤 PNG 的像素画。
     * 纯管线，确定性，不落盘——预览用。
     */
    public Pixels preview(String prompt, Pixels reference) throws AiException
    {
        Pixels raw = this.backend.generate(prompt, reference, 1024, 1024);

        return PixelPipeline.process(raw, this.size, this.paletteColors);
    }
}
