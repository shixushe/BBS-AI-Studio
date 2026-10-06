package mchorse.bbs_mod.ai;

import mchorse.bbs_mod.data.DataToString;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.FloatType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.data.types.StringType;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 作者动作模板：把模型自带 animations（作者调的真人动画时间线）转成
 * v2 直写节拍模板，注入生成提示词——LLM 先原样套用再按用户要求修改，
 * 不再从零编骨骼值。这就是"套动作模板然后在模板上改"的落地。
 *
 * <p>作者片段格式（model.bbs.json 顶层 animations）：名字 →
 * {groups: {骨骼: {rotate: [[秒, 插值, x度, y度, z度], ...]}}, duration: 秒}。
 * 转换：所有骨骼关键帧时间的并集 → 每个时间一拍（tick = 秒×20×时间缩放），
 * 每拍给该时刻全部骨骼的最近值；步行/跑步家族按步频规格压缩时间并预填
 * move 累计位移。纯字符串进出，无 MC 依赖，可离线测试。</p>
 */
public class AiMotionTemplates
{
    /** 步行/跑步家族的时间压缩：作者循环 1 秒 ≈ 20 tick（10 tick/步），
     * 压到一半才是规格的 4~5 tick/步。 */
    private static final float GAIT_TIME_SCALE = 0.5F;

    /** 每步 0.9 格（±32° 腿摆 × 0.75 格腿长的物理步幅） */
    private static final float GAIT_STRIDE = 0.9F;

    /** 一次注入的模板上限 */
    private static final int MAX_TEMPLATES = 2;

    /** 请求情绪词 → 片段名偏好词（命中 +2；片段带未请求情绪词 -2） */
    private static final String[][] MOOD_PAIRS = {
        {"沮丧", "垂头丧气"},
        {"难过", "垂头丧气"},
        {"伤心", "垂头丧气"},
        {"失落", "垂头丧气"},
        {"活力", "活力"},
        {"精神", "活力"},
        {"开心", "活力"},
        {"平静", "平静"},
        {"普通", "普通"}
    };

    /** 模板匹配关键词 → 片段族（normalize 后 contains 匹配） */
    private static final Map<String, List<String>> FAMILY_KEYWORDS = Map.of(
        "walk", List.of("走", "步行", "散步", "walk", "stroll"),
        "run", List.of("跑", "run", "冲刺", "逃"),
        "breath", List.of("呼吸", "待机", "站着", "站立", "发呆", "idle", "休息"),
        "talk", List.of("说话", "聊天", "对话", "讲话", "talk"),
        "pose", List.of("摆动作", "亮相", "展示", "登场", "摆个")
    );

    /**
     * 从模型 JSON 提取命中脚本的动作模板注入块；无模型/无命中返回空串。
     */
    public static String promptBlock(File modelJson, String script)
    {
        if (modelJson == null || !modelJson.isFile() || script == null || script.isBlank())
        {
            return "";
        }

        try
        {
            return fromModelJson(java.nio.file.Files.readString(modelJson.toPath(), StandardCharsets.UTF_8), script);
        }
        catch (Exception e)
        {
            return "";
        }
    }

    /**
     * 纯逻辑入口：模型 JSON 文本 + 用户脚本 → 提示词注入块。
     */
    public static String fromModelJson(String json, String script)
    {
        MapType root = DataToString.mapFromString(json);

        if (root == null || !BaseType.isMap(root.get("animations")) || script == null || script.isBlank())
        {
            return "";
        }

        MapType animations = root.get("animations").asMap();
        String normalizedScript = normalize(script);

        List<String> wantedFamilies = new ArrayList<>();

        for (Map.Entry<String, List<String>> entry : FAMILY_KEYWORDS.entrySet())
        {
            for (String keyword : entry.getValue())
            {
                if (normalizedScript.contains(normalize(keyword)))
                {
                    wantedFamilies.add(entry.getKey());

                    break;
                }
            }
        }

        if (wantedFamilies.isEmpty())
        {
            return "";
        }

        StringBuilder block = new StringBuilder();
        int injected = 0;

        /* 同族片段按情绪匹配排序：请求的情绪词出现在片段名里 +2，
         * 片段带请求没提的情绪词 -2（走路不再默认命中"垂头丧气"），
         * 其余保持原顺序 */
        java.util.List<String> names = new ArrayList<>();

        for (String name : animations.keys())
        {
            if (familyOf(name) != null && wantedFamilies.contains(familyOf(name)))
            {
                names.add(name);
            }
        }

        String normalizedLower = normalizedScript;

        names.sort((a, b) -> clipScore(b, normalizedLower) - clipScore(a, normalizedLower));

        for (String name : names)
        {
            if (injected >= MAX_TEMPLATES)
            {
                break;
            }

            MapType clip = BaseType.isMap(animations.get(name)) ? animations.get(name).asMap() : null;

            if (clip == null)
            {
                continue;
            }

            ListType beats = clipToBeats(name, clip);

            if (beats == null)
            {
                continue;
            }

            if (block.length() == 0)
            {
                block.append("\n\n动作模板（作者真实动画数据，当前请求命中——第一步原样套用以下节拍，")
                    .append("第二步再按用户要求修改幅度/情绪/速度/方向/时长；模板没覆盖的部分按思考流程补写，输出仍是完整 v2 计划）：");
            }

            block.append("\n【").append(name.trim()).append("】beats=")
                .append(compact(beats))
                .append("\n套用要点：move 是沿面朝方向的累计位移（每步 +")
                .append(GAIT_STRIDE)
                .append(" 格）；后续步继续交替并保持每步 ±5% 幅度差；结尾补 6~10 tick 静止收势拍。");
            injected++;
        }

        return block.toString();
    }

    /** jsonLike 但零缩进——DataToString.toString(base, true) 是四空格 pretty，塞提示词太占行 */
    private static String compact(ListType beats)
    {
        mchorse.bbs_mod.data.DataStringifier stringifier = new mchorse.bbs_mod.data.DataStringifier();

        stringifier.jsonLike();
        stringifier.indent = "";

        return stringifier.toString(beats);
    }

    /** 片段族归属：按作者命名习惯归类（IK走路 是 IK 演示，不当模板） */
    private static String familyOf(String clipName)
    {
        String name = normalize(clipName);

        if (name.isEmpty() || name.startsWith("ik"))
        {
            return null;
        }

        if (name.contains("跑"))
        {
            return "run";
        }

        if (name.contains("走"))
        {
            return "walk";
        }

        if (name.contains("呼吸"))
        {
            return "breath";
        }

        if (name.contains("idle"))
        {
            return "talk";
        }

        if (name.contains("摆动作"))
        {
            return "pose";
        }

        return null;
    }

    private static boolean isGait(String clipName)
    {
        String family = familyOf(clipName);

        return "walk".equals(family) || "run".equals(family);
    }

    private static String normalize(String value)
    {
        return value == null ? "" : value.toLowerCase().replace(" ", "").replace("\u3000", "");
    }

    /** 片段名与请求的情绪匹配分：请求情绪词命中片段名 +2，片段带未请求
     * 情绪词 -2，平淡名（平静/普通）在无情绪请求时 +1 */
    private static int clipScore(String clipName, String normalizedScript)
    {
        String name = normalize(clipName);
        int score = 0;

        for (String[] pair : MOOD_PAIRS)
        {
            boolean requested = normalizedScript.contains(pair[0]);
            boolean named = name.contains(pair[1]);
            boolean neutralBase = pair[1].equals("平静") || pair[1].equals("普通");

            if (requested && named)
            {
                score += 2;
            }

            /* 平静/普通是中性基准名不是情绪——不带负分，只吃中性加成 */
            if (!requested && named && !neutralBase)
            {
                score -= 2;
            }
        }

        if ((name.contains("平静") || name.contains("普通")) && !hasAnyMood(normalizedScript))
        {
            score += 3;
        }

        return score;
    }

    private static boolean hasAnyMood(String normalizedScript)
    {
        for (String[] pair : MOOD_PAIRS)
        {
            if (normalizedScript.contains(pair[0]))
            {
                return true;
            }
        }

        return false;
    }

    /**
     * 一个作者片段 → v2 节拍数组（tick 严格递增、每拍全骨骼值、
     * 步行/跑步预填 move）。作者骨骼名原样保留（求解器认真实名）。
     */
    static ListType clipToBeats(String clipName, MapType clip)
    {
        if (clip == null || !BaseType.isMap(clip.get("groups")))
        {
            return null;
        }

        MapType groups = clip.get("groups").asMap();
        boolean gait = isGait(clipName);

        /* 采集全部骨骼的关键帧时间并集 + 每根骨骼的时间线 */
        TreeSet<Double> times = new TreeSet<>();
        Map<String, List<double[]>> tracks = new LinkedHashMap<>();

        for (String bone : groups.keys())
        {
            MapType channel = groups.getMap(bone);
            BaseType rotate = channel == null ? null : channel.get("rotate");

            if (!BaseType.isList(rotate))
            {
                continue;
            }

            List<double[]> frames = new ArrayList<>();

            for (int i = 0; i < rotate.asList().size(); i++)
            {
                BaseType frame = rotate.asList().get(i);

                /* [秒, 插值, x度, y度, z度] */
                if (!BaseType.isList(frame) || frame.asList().size() < 5)
                {
                    continue;
                }

                ListType f = frame.asList();
                double[] parsed = new double[] {
                    f.getDouble(0), f.getDouble(2), f.getDouble(3), f.getDouble(4)
                };

                frames.add(parsed);
                times.add(parsed[0]);
            }

            if (!frames.isEmpty())
            {
                tracks.put(bone, frames);
            }
        }

        if (times.isEmpty())
        {
            return null;
        }

        double spanSeconds = Math.max(clip.getFloat("duration", 1F), times.last());
        int steps = Math.max(1, (int) Math.round(spanSeconds * 20F * GAIT_TIME_SCALE / 5F));
        ListType beats = new ListType();
        int lastTick = Integer.MIN_VALUE;
        int index = 0;

        for (double time : times)
        {
            int tick = (int) Math.round(time * 20F * (gait ? GAIT_TIME_SCALE : 1F));

            if (tick <= lastTick)
            {
                continue;
            }

            lastTick = tick;

            MapType beat = new MapType();

            beat.putInt("tick", tick);

            if (gait)
            {
                /* 步行/跑步：偶数拍触地、奇数拍过渡；move 沿时间线性累计 */
                beat.putString("phase", index % 2 == 0 ? "contact" : "passing");
                beat.put("move", vec3(round1((float) (time / spanSeconds) * steps * GAIT_STRIDE), 0F, 0F));
            }
            else if (index == 0)
            {
                beat.putString("phase", "anticipation");
            }
            else if (index == times.size() - 1)
            {
                beat.putString("phase", "follow_through");
            }
            else
            {
                beat.putString("phase", "hold");
            }

            ListType intents = new ListType();

            intents.add(new StringType("ease_in_out"));
            beat.put("intents", intents);

            MapType pose = new MapType();

            for (Map.Entry<String, List<double[]>> entry : tracks.entrySet())
            {
                double[] frame = nearest(entry.getValue(), time);

                if (frame != null)
                {
                    MapType boneData = new MapType();

                    boneData.put("r", vec3((float) frame[1], (float) frame[2], (float) frame[3]));
                    pose.put(entry.getKey(), boneData);
                }
            }

            beat.put("pose", pose);
            beats.add(beat);
            index++;
        }

        return beats;
    }

    /** 该骨骼在 time 时刻的最近关键帧（作者骨骼关键帧时间基本对齐） */
    private static double[] nearest(List<double[]> frames, double time)
    {
        double[] best = null;
        double bestDistance = Double.MAX_VALUE;

        for (double[] frame : frames)
        {
            double distance = Math.abs(frame[0] - time);

            if (distance < bestDistance - 1e-9)
            {
                best = frame;
                bestDistance = distance;
            }
        }

        return best != null && bestDistance <= 0.05 ? best : null;
    }

    private static ListType vec3(float x, float y, float z)
    {
        ListType list = new ListType();

        list.add(new FloatType(x));
        list.add(new FloatType(y));
        list.add(new FloatType(z));

        return list;
    }

    private static float round1(float value)
    {
        return Math.round(value * 10F) / 10F;
    }
}
