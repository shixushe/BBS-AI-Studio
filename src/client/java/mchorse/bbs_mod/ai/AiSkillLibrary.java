package mchorse.bbs_mod.ai;

import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.data.DataToString;
import mchorse.bbs_mod.utils.pose.Pose;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 技能库（spec §10.9 能力暴露的动作侧）：加载模型的 poses.json 手工姿势
 * （作者姿态，如「无奈摊手」「赞美太阳」），以 {@code @名字} 形式暴露给计划
 * ——LLM 在 beat.pose 里引用 {@code @摊手}，求解器直接套用作者调好的姿势，
 * 不再由姿态库猜角度。
 *
 * <p>姿势数据格式与 {@link Pose#fromData(MapType)} 完全一致
 * （{"pose":{骨骼:变换}}），骨骼名即模型真实骨骼名——对同族模型零适配。
 * animations（真人动画时间线）由离线蒸馏器提炼成节拍模式（后续轮次）。</p>
 */
public class AiSkillLibrary
{
    private static final Map<String, Map<String, Pose>> BY_MODEL = new LinkedHashMap<>();
    private static final Map<String, Long> LOADED_AT = new LinkedHashMap<>();

    /** poses.json 读取候选位置（模型 id 相对 config 资产根）。 */
    private static File posesFile(String modelId)
    {
        if (modelId == null || modelId.isEmpty())
        {
            return null;
        }

        String safe = modelId.replace("\\", "/");

        while (safe.startsWith("/"))
        {
            safe = safe.substring(1);
        }

        if (safe.contains(".."))
        {
            return null;
        }

        String[] bases = {
            "config/bbs/assets/models",
            "bbs/assets/models"
        };

        for (String base : bases)
        {
            File file = new File(base, safe + "/poses.json");

            if (file.isFile())
            {
                return file;
            }
        }

        return null;
    }

    /**
     * 模型的技能姿势：{@code 名字 → Pose}。缓存 30 秒——poses.json 由用户在
     * 模型编辑器维护，短缓存避免反复读盘又不至于长期过期。
     */
    public static synchronized Map<String, Pose> posesForModel(String modelId)
    {
        String key = modelId == null ? "" : modelId;
        long now = System.currentTimeMillis();
        Long at = LOADED_AT.get(key);

        if (at != null && now - at < 30_000L && BY_MODEL.containsKey(key))
        {
            return BY_MODEL.get(key);
        }

        Map<String, Pose> poses = new LinkedHashMap<>();

        File file = posesFile(key);

        if (file != null && file.isFile())
        {
            try
            {
                String json = Files.readString(file.toPath());
                MapType map = DataToString.mapFromString(json);

                if (map != null)
                {
                    for (String name : map.keys())
                    {
                        MapType entry = map.getMap(name);

                        if (!entry.has("pose"))
                        {
                            continue;
                        }

                        Pose pose = new Pose();

                        pose.fromData(entry);

                        if (!pose.isEmpty())
                        {
                            poses.put(name, pose);
                        }
                    }
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }

        BY_MODEL.put(key, poses);
        LOADED_AT.put(key, now);

        return poses;
    }

    /** 按名字取技能姿势（名字可带或不带 @ 前缀）；无则 null。 */
    public static Pose get(String modelId, String name)
    {
        if (name == null || !name.startsWith("@"))
        {
            return null;
        }

        return posesForModel(modelId).get(name.substring(1));
    }

    /**
     * 意图→姿势名映射表：帮助 LLM 按语义选对 @姿势。
     * 每条 = 意图关键词列表 → 推荐姿势名列表。
     */
    public static final Map<String, List<String>> INTENT_MAP = buildIntentMap();

    private static Map<String, List<String>> buildIntentMap()
    {
        Map<String, List<String>> map = new LinkedHashMap<>();

        map.put("开心 可爱 卖萌", List.of("可爱姿势1", "可爱姿势2"));
        map.put("悲伤 哭泣 崩溃 难过", List.of("大哭崩溃", "极度悲伤", "委屈"));
        map.put("害羞 害臊", List.of("害羞姿势1", "害羞姿势2"));
        map.put("无奈 投降 无语", List.of("无奈摊手", "浑身不自在"));
        map.put("疑惑 困惑 不解", List.of("疑惑"));
        map.put("自信 沉稳 冷静", List.of("沉稳站姿"));
        map.put("登场 出场 亮相 夸张", List.of("闪亮登场姿势1（当当！）", "闪亮登场姿势2", "潮人Dab手势"));
        map.put("赞美 敬畏 崇拜", List.of("赞美太阳"));
        map.put("亲昵 牵手 浪漫", List.of("牵住我的手"));
        map.put("挥手 打招呼 再见", List.of("挥手姿势1", "挥手姿势2"));
        map.put("鞠躬 敬礼 道歉 感谢", List.of("鞠躬姿势1", "鞠躬姿势2"));
        map.put("坐下 休息", List.of("坐姿"));
        map.put("睡觉 睡着 休息", List.of("熟睡姿态"));
        map.put("思考 想想 犹豫", List.of("思考姿态1", "思考姿态2", "思考姿态3"));
        map.put("抱胸 不满 抗议", List.of("双臂抱胸"));
        map.put("张开 拥抱 张开双臂", List.of("双臂张开"));
        map.put("搬运 扛 拿", List.of("搬运"));
        map.put("蹲 蹲下 蹲着", List.of("蹲下"));
        map.put("站 起身 起来", List.of("抬头起身", "沉稳站姿"));
        map.put("道具 手持 武器", List.of("各类随身道具动作"));

        return map;
    }

    /** 格式化的意图映射提示词（只包含当前模型已有的姿势）。 */
    public static String intentPrompt(String modelId)
    {
        var available = posesForModel(modelId);

        if (available.isEmpty())
        {
            return "";
        }

        StringBuilder sb = new StringBuilder("\n预设姿势意图对照表（选 @姿势名 填入 beat.pose）:\n");

        for (var entry : INTENT_MAP.entrySet())
        {
            List<String> valid = new ArrayList<>();

            for (String poseName : entry.getValue())
            {
                if (available.containsKey(poseName))
                {
                    valid.add("@" + poseName);
                }
            }

            if (!valid.isEmpty())
            {
                sb.append("  ").append(entry.getKey()).append(" → ").append(String.join(" / ", valid)).append("\n");
            }
        }

        return sb.toString();
    }

    /** 排序后的技能姿势名（注入提示词用）。 */
    public static List<String> names(String modelId)
    {
        List<String> names = new ArrayList<>(posesForModel(modelId).keySet());

        Collections.sort(names);

        return names;
    }
}
