package mchorse.bbs_mod.ai;

import com.google.gson.JsonParser;
import com.google.gson.JsonArray;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 建筑风格技能库（打包资源 ai_skills/building_styles.json，原创规则数据）。
 * 按主题关键词匹配风格，把调色板与结构提示注入建筑生成提示词——
 * 算法思路来自公开的程序化生成讨论（BSP 细分、立面节奏），数据全部自写。
 */
public class AiBuildSkills
{
    public static class Style
    {
        public String id = "";
        public List<String> aliases = new ArrayList<>();
        public String walls = "";
        public String accent = "";
        public String trim = "";
        public String roof = "";
        public String roofStyle = "";
        public String floor = "";
        public String paletteHint = "";
        public String hints = "";
    }

    private static List<Style> styles;

    public static class Example
    {
        public String theme = "";
        public String note = "";
        public String spec = "";
    }

    /** 主题关键词命中的少样本示例(完整 spec),无命中返回 null。 */
    public static Example matchExample(String theme)
    {
        String lower = theme == null ? "" : theme.toLowerCase();

        try
        {
            InputStream stream = AiBuildSkills.class.getResourceAsStream("/ai_skills/building_styles.json");

            if (stream == null)
            {
                return null;
            }

            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            stream.close();

            var root = JsonParser.parseString(json).getAsJsonObject();

            if (!root.has("examples"))
            {
                return null;
            }

            for (var e : root.getAsJsonArray("examples"))
            {
                var o = e.getAsJsonObject();
                String exampleTheme = o.get("theme").getAsString();
                boolean hit = false;

                for (String word : lower.split("[\s,，。]+"))
                {
                    if (word.length() >= 2 && exampleTheme.toLowerCase().contains(word))
                    {
                        hit = true;

                        break;
                    }
                }

                for (String word : exampleTheme.toLowerCase().split("[\s,，。]+"))
                {
                    if (word.length() >= 2 && lower.contains(word))
                    {
                        hit = true;

                        break;
                    }
                }

                if (hit)
                {
                    Example example = new Example();

                    example.theme = exampleTheme;
                    example.note = o.has("spec_note") ? o.get("spec_note").getAsString() : "";
                    example.spec = o.has("spec") ? o.get("spec").toString() : "";

                    return example;
                }
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        return null;
    }

    public static synchronized List<Style> styles()
    {
        if (styles != null)
        {
            return styles;
        }

        styles = new ArrayList<>();

        try
        {
            InputStream stream = AiBuildSkills.class.getResourceAsStream("/ai_skills/building_styles.json");

            if (stream == null)
            {
                return styles;
            }

            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            stream.close();

            var root = JsonParser.parseString(json).getAsJsonObject();
            var arr = root.getAsJsonArray("styles");

            for (var e : arr)
            {
                var o = e.getAsJsonObject();
                Style style = new Style();

                style.id = o.get("id").getAsString();

                for (var a : o.getAsJsonArray("aliases"))
                {
                    style.aliases.add(a.getAsString());
                }

                style.walls = o.get("walls").getAsString();
                style.accent = o.get("accent").getAsString();
                style.trim = o.get("trim").getAsString();
                style.roof = o.get("roof").getAsString();
                style.roofStyle = o.get("roof_style").getAsString();
                style.floor = o.has("floor") ? o.get("floor").getAsString() : "";
                style.paletteHint = o.has("palette_hint") ? o.get("palette_hint").getAsString() : "";
                style.hints = o.has("hints") ? o.get("hints").getAsString() : "";

                styles.add(style);
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        return styles;
    }
}
