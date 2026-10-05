package mchorse.bbs_mod.ai.plan;

import mchorse.bbs_mod.ai.AiException;
import mchorse.bbs_mod.ai.curve.PolishKind;
import mchorse.bbs_mod.data.DataToString;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.data.types.NumericType;

import java.util.ArrayList;
import java.util.List;

/**
 * L1's structured output: the language model describes beats, never handles.
 * A beat is one extreme pose at one tick with a phase role and a list of
 * curve intents. Parsing is strict - any value outside the allowed enums, a
 * non-monotonic tick or a spacing mismatch is a contract violation the caller
 * must treat as "retry once, then surface a readable error" (never silently
 * repaired).
 *
 * <p>Coordinate with the copilot spec sections 4.1 (shape) and 2 (iron rule 1:
 * no floating point handles may ever enter through here).</p>
 */
public class AnimationPlan
{
    public static final int VERSION = 1;

    /** v2：LLM 直接输出逐骨骼旋转/平移/缩放（动画师式创作），不再引用姿势名库 */
    public static final int VERSION_DIRECT = 2;

    public int version = VERSION;

    /** Phases of a walk-cycle-ish beat; the model may only use these labels. */
    public static final List<String> PHASES = List.of("contact", "down", "passing", "up", "anticipation", "hold", "follow_through");

    /** Poses the plan may reference; extensible only from this file. */
    public static final List<String> POSES = List.of("crouch", "compress", "rise", "fall", "punch", "kick", "idle", "turn", "walk_step", "land", "reach", "point", "blink", "walk_step_b", "wave", "cheer", "bow", "sit", "run", "sad_walk", "normal_walk", "energetic_walk", "walk_pass");

    public int fps = 20;

    public int totalTicks;

    public String modelHint = "humanoid";

    public boolean boneMapConfirmed;

    public final List<Beat> beats = new ArrayList<>();

    public String notes = "";

    /** 特效请求（宽松解析）：lighting 打光 / particle 粒子，解析失败的条目直接丢弃 */
    public static class Fx
    {
        public int tick;
        public String kind = "";
        public String id = "";
        public float value = 1F;
        public int duration;
    }

    public final List<Fx> fx = new ArrayList<>();

    public static class Beat
    {
        public int index;
        public int tick;
        public String phase;
        public String pose;
        public int spacing;
        public final List<PolishKind> intents = new ArrayList<>();

        /** v2：直写骨骼值（键=泛骨骼名，值={r:[度],t:[格],s:[比]}），与 pose 字符串互斥 */
        public MapType poseObject;

        /** v2：该拍时点相对起点的累计位移（格，[x,y,z]）——行走/移动用 */
        public float[] move;
    }

    /**
     * Parse and validate a plan from the model's raw JSON reply. Throws a
     * PARSE {@link AiException} with the first violation spelled out.
     */
    /** 模型爱把方案包进 markdown 围栏或前后说明文字——截取第一个 { 到
     * 最后一个 } 再解析，围栏/废话不再导致整次生成报废 */
    static String stripToJsonObject(String raw)
    {
        if (raw == null)
        {
            return null;
        }

        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');

        return start >= 0 && end > start ? raw.substring(start, end + 1) : raw;
    }

    public static AnimationPlan parse(String json) throws AiException
    {
        MapType map = DataToString.mapFromString(stripToJsonObject(json));

        if (map == null)
        {
            throw new AiException(AiException.Type.PARSE, "Animation plan is not valid JSON");
        }

        AnimationPlan plan = new AnimationPlan();

        int version = map.getInt("version");

        if (version != VERSION && version != VERSION_DIRECT)
        {
            throw new AiException(AiException.Type.PARSE, "Plan version " + version + " is not supported (expected " + VERSION + " or " + VERSION_DIRECT + ")");
        }

        plan.version = version;
        plan.fps = map.getInt("fps", 20);
        plan.totalTicks = map.getInt("total_ticks");
        plan.modelHint = map.getString("model_hint", "humanoid");

        BaseType character = map.get("character");

        if (character != null && BaseType.isMap(character))
        {
            plan.boneMapConfirmed = character.asMap().getBool("bone_map_confirmed");
        }

        plan.notes = map.getString("notes", "");

        BaseType fxList = map.get("fx");

        if (BaseType.isList(fxList))
        {
            for (int i = 0; i < fxList.asList().size(); i++)
            {
                BaseType entry = fxList.asList().get(i);

                if (!BaseType.isMap(entry))
                {
                    continue;
                }

                MapType fxMap = entry.asMap();
                Fx fx = new Fx();

                fx.tick = fxMap.getInt("tick");
                fx.kind = fxMap.getString("kind", "").trim().toLowerCase();
                fx.id = fxMap.getString("id", "").trim();
                fx.value = fxMap.getFloat("value", 1F);
                fx.duration = Math.max(0, fxMap.getInt("duration", 0));

                if (!fx.kind.isEmpty() && fx.tick >= 0)
                {
                    plan.fx.add(fx);
                }
            }
        }

        BaseType beats = map.get("beats");

        if (!BaseType.isList(beats) || beats.asList().isEmpty())
        {
            throw new AiException(AiException.Type.PARSE, "Plan has no beats");
        }

        ListType beatList = beats.asList();
        int previousTick = Integer.MIN_VALUE;

        for (int i = 0; i < beatList.size(); i++)
        {
            BaseType entry = beatList.get(i);

            if (!BaseType.isMap(entry))
            {
                throw new AiException(AiException.Type.PARSE, "Beat " + i + " is not an object");
            }

            MapType beatMap = entry.asMap();
            Beat beat = new Beat();

            beat.index = beatMap.getInt("index", i);
            beat.tick = beatMap.getInt("tick");
            beat.phase = beatMap.getString("phase", "");
            beat.pose = "";
            beat.spacing = beatMap.getInt("spacing", 0);

            /* v2：pose 可以是对象（直写骨骼值）或字符串（@作者姿势） */
            BaseType poseValue = beatMap.get("pose");

            if (poseValue != null && BaseType.isMap(poseValue))
            {
                beat.poseObject = poseValue.asMap();
            }
            else
            {
                beat.pose = beatMap.getString("pose", "");

                if (version == VERSION && !beat.pose.startsWith("@") && !POSES.contains(beat.pose))
                {
                    throw new AiException(AiException.Type.PARSE, "Beat " + i + " has unknown pose: " + beat.pose);
                }
            }

            /* v2 累计位移（格，相对起点，[x,y,z]） */
            BaseType moveValue = beatMap.get("move");

            if (version >= VERSION_DIRECT && BaseType.isList(moveValue) && moveValue.asList().size() >= 2)
            {
                ListType moveList = moveValue.asList();
                float[] move = new float[3];

                for (int m = 0; m < 3; m++)
                {
                    if (m < moveList.size() && BaseType.isNumeric(moveList.get(m)))
                    {
                        move[m] = ((NumericType) moveList.get(m)).floatValue();
                    }
                }

                beat.move = move;
            }

            if (!PHASES.contains(beat.phase))
            {
                if (version >= VERSION_DIRECT)
                {
                    /* v2 宽容：未知相位归为 hold，不再让整次生成报废 */
                    beat.phase = "hold";
                }
                else
                {
                    throw new AiException(AiException.Type.PARSE, "Beat " + i + " has unknown phase: " + beat.phase);
                }
            }

            if (beat.tick <= previousTick && i > 0)
            {
                throw new AiException(AiException.Type.PARSE, "Beat " + i + " tick " + beat.tick + " is not strictly increasing (previous " + previousTick + ")");
            }

            if (i > 0)
            {
                int gap = beat.tick - previousTick;

                if (version >= VERSION_DIRECT)
                {
                    /* v2 spacing 可省略——给了就必须对 */
                    if (beat.spacing > 0 && beat.spacing != gap)
                    {
                        throw new AiException(AiException.Type.PARSE, "Beat " + i + " spacing " + beat.spacing + " does not match tick gap " + gap);
                    }
                }
                else if (beat.spacing != gap)
                {
                    throw new AiException(AiException.Type.PARSE, "Beat " + i + " spacing " + beat.spacing + " does not match tick gap " + (beat.tick - previousTick));
                }
            }

            BaseType intents = beatMap.get("intents");

            if (BaseType.isList(intents))
            {
                for (int j = 0; j < intents.asList().size(); j++)
                {
                    String label = intents.asList().get(j).isString() ? intents.asList().get(j).asString() : "";
                    PolishKind kind = PolishKind.fromLabel(label);

                    if (kind == null)
                    {
                        /* v2 宽容：未知意图跳过（曲线退默认），v1 保持严格 */
                        if (version >= VERSION_DIRECT)
                        {
                            continue;
                        }

                        throw new AiException(AiException.Type.PARSE, "Beat " + i + " intent " + j + " is not a known label: " + label);
                    }

                    beat.intents.add(kind);
                }
            }

            plan.beats.add(beat);
            previousTick = beat.tick;
        }

        if (plan.totalTicks <= 0)
        {
            throw new AiException(AiException.Type.PARSE, "Plan total_ticks must be positive");
        }

        return plan;
    }

    /** Serialize back to JSON (for beat tables, caching and regression fixtures). */
    public MapType toData()
    {
        MapType map = new MapType();

        map.putInt("version", VERSION);
        map.putInt("fps", this.fps);
        map.putInt("total_ticks", this.totalTicks);
        map.putString("model_hint", this.modelHint);
        map.putString("notes", this.notes);

        MapType character = new MapType();

        character.putBool("bone_map_confirmed", this.boneMapConfirmed);
        map.put("character", character);

        ListType beats = new ListType();

        for (Beat beat : this.beats)
        {
            MapType beatMap = new MapType();

            beatMap.putInt("index", beat.index);
            beatMap.putInt("tick", beat.tick);
            beatMap.putString("phase", beat.phase);
            beatMap.putString("pose", beat.pose);
            beatMap.putInt("spacing", beat.spacing);

            ListType intents = new ListType();

            for (PolishKind kind : beat.intents)
            {
                intents.add(new mchorse.bbs_mod.data.types.StringType(kind.name().toLowerCase()));
            }

            beatMap.put("intents", intents);
            beats.add(beatMap);
        }

        map.put("beats", beats);

        return map;
    }
}
