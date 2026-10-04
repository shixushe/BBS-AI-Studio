package mchorse.bbs_mod.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 通用 .schematic（MCEdit 旧格式）调色板提取器：读取 NBT 结构的 Blocks
 * 字节数组，按旧版数字 ID 映射为现代方块名，输出比例加权调色板。
 * 用户从投影工坊/社区下载的 .schematic 可放入 config/bbs/assets/
 * ai_schematics/ 后由 AiBiomeSkills 的扫描流程自动提取。
 *
 * <p>这部分是静态分析工具——不做世界修改，纯读数据。</p>
 */
public class AiSchematicParser
{
    /** 旧版数字 ID → 现代方块名映射（常见建材子集）。 */
    private static final Map<Integer, String> LEGACY = new LinkedHashMap<>();

    static
    {
        LEGACY.put(1, "stone"); LEGACY.put(2, "grass_block"); LEGACY.put(3, "dirt");
        LEGACY.put(4, "cobblestone"); LEGACY.put(5, "oak_planks"); LEGACY.put(12, "sand");
        LEGACY.put(17, "oak_log"); LEGACY.put(18, "oak_leaves"); LEGACY.put(20, "glass");
        LEGACY.put(24, "sandstone"); LEGACY.put(35, "white_wool"); LEGACY.put(44, "stone_slab");
        LEGACY.put(45, "bricks"); LEGACY.put(48, "mossy_cobblestone"); LEGACY.put(50, "torch");
        LEGACY.put(54, "chest"); LEGACY.put(65, "ladder"); LEGACY.put(67, "stone_stairs");
        LEGACY.put(85, "oak_fence"); LEGACY.put(89, "glowstone");
        LEGACY.put(95, "white_stained_glass"); LEGACY.put(96, "oak_trapdoor");
        LEGACY.put(98, "stone_bricks"); LEGACY.put(101, "iron_bars");
        LEGACY.put(109, "stone_brick_stairs"); LEGACY.put(139, "cobblestone_wall");
        LEGACY.put(155, "quartz_block"); LEGACY.put(159, "white_terracotta");
        LEGACY.put(171, "white_carpet"); LEGACY.put(172, "terracotta");
        LEGACY.put(173, "coal_block");
    }

    /**
     * 从旧版 .schematic 的 Blocks 字节数组提取调色板。
     *
     * @param blocks    Blocks 字节数组
     * @param minRatio  最小比例阈值（0-1），低于此的材质不输出
     */
    public static JsonArray extractPalette(byte[] blocks, float minRatio)
    {
        Map<Integer, Integer> counter = new LinkedHashMap<>();
        int total = 0;

        for (byte b : blocks)
        {
            int id = b & 0xFF;

            if (id == 0)
            {
                continue;
            }

            counter.merge(id, 1, Integer::sum);
            total++;
        }

        JsonArray palette = new JsonArray();

        var sorted = counter.entrySet().stream()
            .sorted((a, b) -> b.getValue() - a.getValue())
            .toList();

        for (var entry : sorted)
        {
            float ratio = (float) entry.getValue() / Math.max(1, total);

            if (ratio < minRatio)
            {
                break;
            }

            var item = new JsonObject();
            String name = LEGACY.get(entry.getKey());

            if (name != null)
            {
                item.addProperty("block", "minecraft:" + name);
            }
            else
            {
                item.addProperty("legacy_id", entry.getKey());
            }

            item.addProperty("count", entry.getValue());
            item.addProperty("ratio", Math.round(ratio * 1000F) / 1000F);
            palette.add(item);
        }

        return palette;
    }
}
