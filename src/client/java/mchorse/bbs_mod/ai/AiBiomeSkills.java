package mchorse.bbs_mod.ai;

import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 原版村庄结构调色板技能：从原版客户端 jar 的 221 栋村庄房屋提炼的
 * 各生物群系建筑材料分布（运行时引用，不重分发原始数据）。
 * 注入建筑提示词让 LLM 按群系风格选材。
 */
public class AiBiomeSkills
{
    private static Map<String, List<String>> biomeWalls;
    private static List<String> biomeNames;

    public static synchronized void load()
    {
        if (biomeWalls != null)
        {
            return;
        }

        biomeWalls = new LinkedHashMap<>();
        biomeNames = new ArrayList<>();

        try
        {
            InputStream stream = AiBiomeSkills.class.getResourceAsStream("/ai_skills/vanilla_palettes.json");

            if (stream == null)
            {
                return;
            }

            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            stream.close();

            var root = JsonParser.parseString(json).getAsJsonObject();

            if (!root.has("biome_styles"))
            {
                return;
            }

            var biomes = root.getAsJsonObject("biome_styles");

            for (var biomeName : biomes.keySet())
            {
                var biomeData = biomes.getAsJsonObject(biomeName);
                var materials = biomeData.getAsJsonArray("wall_materials");

                List<String> walls = new ArrayList<>();

                for (var m : materials)
                {
                    walls.add(m.getAsString());
                }

                biomeWalls.put(biomeName, walls);
                biomeNames.add(biomeName);
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    /** 匹配生物群系（主题文本包含群系名则命中）。 */
    public static List<String> match(String theme)
    {
        load();

        String lower = theme == null ? "" : theme.toLowerCase();

        for (var entry : biomeWalls.entrySet())
        {
            if (lower.contains(entry.getKey()))
            {
                return entry.getValue();
            }
        }

        return List.of();
    }

    public static List<String> biomeNames()
    {
        load();

        return biomeNames;
    }

    /** 格式化的提示词注入块（列出全部群系+主要建材）。 */
    public static String summary()
    {
        load();
        loadWalls();

        if (biomeWalls.isEmpty())
        {
            return "";
        }

        StringBuilder sb = new StringBuilder("\n\n原版村庄风格参考（按群系选材更协调）:");

        for (var entry : biomeWallSummary.entrySet())
        {
            sb.append("\n  ").append(entry.getKey()).append(": ").append(entry.getValue());
        }

        return sb.toString();
    }

    private static Map<String, String> biomeWallSummary;

    private static void loadWalls()
    {
        if (biomeWallSummary != null)
        {
            return;
        }

        biomeWallSummary = new LinkedHashMap<>();

        var root = getRoot();

        if (root == null || !root.has("biomes"))
        {
            return;
        }

        for (var biomeKey : root.getAsJsonObject("biomes").keySet())
        {
            var biomeData = root.getAsJsonObject("biomes").getAsJsonObject(biomeKey);
            var palette = biomeData.getAsJsonArray("palette");
            List<String> materials = new ArrayList<>();

            for (var p : palette)
            {
                var po = p.getAsJsonObject();
                String block = po.get("block").getAsString();

                if (block.contains("wall") || block.contains("planks") || block.contains("log")
                    || block.contains("stone") || block.contains("terracotta") || block.contains("fence"))
                {
                    materials.add(block.replace("minecraft:", ""));
                }
            }

            if (!materials.isEmpty())
            {
                biomeWallSummary.put(biomeKey, String.join(", ", materials.subList(0, Math.min(4, materials.size()))));
            }
        }
    }

    private static com.google.gson.JsonObject getRoot()
    {
        try
        {
            InputStream stream = AiBiomeSkills.class.getResourceAsStream("/ai_skills/vanilla_palettes.json");

            if (stream == null)
            {
                return null;
            }

            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            stream.close();

            return com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        }
        catch (Exception e)
        {
            return null;
        }
    }
}
