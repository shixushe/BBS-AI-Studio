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

    /** 排序后的技能姿势名（注入提示词用）。 */
    public static List<String> names(String modelId)
    {
        List<String> names = new ArrayList<>(posesForModel(modelId).keySet());

        Collections.sort(names);

        return names;
    }
}
