package mchorse.bbs_mod.ai.skin;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minecraft 皮肤 UV 布局的固定矩形分区（64×64 与 64×32 两套）。
 * 纯数据——分区位置是可计算的几何事实，绝不靠模型猜（spec 10.7 step 2）。
 */
public class SkinUVLayout
{
    public static class Region
    {
        public final int u;
        public final int v;
        public final int width;
        public final int height;

        public Region(int u, int v, int width, int height)
        {
            this.u = u;
            this.v = v;
            this.width = width;
            this.height = height;
        }
    }

    /** 64×64 布局的全部分区（含上下两层：上层=头部区，下层=身体/四肢） */
    public static final Map<String, Region> LAYOUT_64 = new LinkedHashMap<>();

    static
    {
        /* 头 (8×8 面 × 6) */
        LAYOUT_64.put("head_top",    new Region(8, 0, 8, 8));
        LAYOUT_64.put("head_bottom", new Region(16, 0, 8, 8));
        LAYOUT_64.put("head_right",  new Region(0, 8, 8, 8));
        LAYOUT_64.put("head_front",  new Region(8, 8, 8, 8));
        LAYOUT_64.put("head_left",   new Region(16, 8, 8, 8));
        LAYOUT_64.put("head_back",   new Region(24, 8, 8, 8));

        /* 身体 + 头部帽层 */
        LAYOUT_64.put("body_front",  new Region(16, 16, 8, 12));
        LAYOUT_64.put("body_back",   new Region(32, 16, 8, 12));
        LAYOUT_64.put("body_right",  new Region(0, 16, 4, 12));
        LAYOUT_64.put("body_left",   new Region(16, 16, 4, 12));

        /* 右臂 (4×12 面) */
        LAYOUT_64.put("right_arm",   new Region(40, 16, 4, 12));
        LAYOUT_64.put("right_arm_end", new Region(44, 16, 4, 12));

        /* 左臂 (1.8+) */
        LAYOUT_64.put("left_arm",    new Region(32, 48, 4, 12));
        LAYOUT_64.put("left_arm_end", new Region(48, 48, 4, 12));

        /* 右腿 (4×12 面) */
        LAYOUT_64.put("right_leg",   new Region(0, 16, 4, 12));
        LAYOUT_64.put("right_leg_end", new Region(12, 16, 4, 12));

        /* 左腿 (1.8+) */
        LAYOUT_64.put("left_leg",    new Region(16, 48, 4, 12));
        LAYOUT_64.put("left_leg_end", new Region(0, 48, 4, 12));

        /* 右臂 overlay (slim 无) */
        LAYOUT_64.put("right_arm_overlay", new Region(40, 32, 4, 12));
        LAYOUT_64.put("left_arm_overlay",  new Region(48, 48, 4, 12));
        LAYOUT_64.put("right_leg_overlay", new Region(0, 32, 4, 12));
        LAYOUT_64.put("left_leg_overlay",  new Region(0, 48, 4, 12));
        LAYOUT_64.put("body_overlay",      new Region(16, 32, 8, 12));
    }

    /** 64×32 旧版布局（仅上半层） */
    public static final Map<String, Region> LAYOUT_64x32 = new LinkedHashMap<>();

    static
    {
        LAYOUT_64x32.put("head_top",    new Region(8, 0, 8, 8));
        LAYOUT_64x32.put("head_bottom", new Region(16, 0, 8, 8));
        LAYOUT_64x32.put("head_right",  new Region(0, 8, 8, 8));
        LAYOUT_64x32.put("head_front",  new Region(8, 8, 8, 8));
        LAYOUT_64x32.put("head_left",   new Region(16, 8, 8, 8));
        LAYOUT_64x32.put("head_back",   new Region(24, 8, 8, 8));
        LAYOUT_64x32.put("body_front",  new Region(16, 16, 8, 12));
        LAYOUT_64x32.put("body_back",   new Region(32, 16, 8, 12));
        LAYOUT_64x32.put("right_arm",   new Region(40, 16, 4, 12));
        LAYOUT_64x32.put("right_leg",   new Region(0, 16, 4, 12));
    }

    /** 左右镜像映射：painting on one side mirrors to the other. */
    public static final Map<String, String> MIRROR = Map.ofEntries(
        Map.entry("left_arm", "right_arm"),
        Map.entry("right_arm", "left_arm"),
        Map.entry("left_arm_end", "right_arm_end"),
        Map.entry("right_arm_end", "left_arm_end"),
        Map.entry("left_leg", "right_leg"),
        Map.entry("right_leg", "left_leg"),
        Map.entry("left_leg_end", "right_leg_end"),
        Map.entry("right_leg_end", "left_leg_end")
    );

    /**
     * 计算镜像像素坐标：给定源区域内的像素 → 对侧区域的对应像素。
     * 纯数学，确定性。
     */
    public static int mirrorPixelX(Region source, Region mirror, int localX)
    {
        return mirror.width - 1 - localX;
    }

    /** 获取皮肤宽度（64 或 128）。 */
    public static int skinWidth(int textureWidth)
    {
        return textureWidth >= 128 ? 128 : 64;
    }

    /** 是否旧版窄幅布局。 */
    public static boolean isLegacy(int textureHeight)
    {
        return textureHeight == 32;
    }
}
