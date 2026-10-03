package mchorse.bbs_mod.ai.plan;

import mchorse.bbs_mod.ai.AiException;
import mchorse.bbs_mod.ai.curve.PolishKind;
import mchorse.bbs_mod.data.DataToString;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;

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

    /** Phases of a walk-cycle-ish beat; the model may only use these labels. */
    public static final List<String> PHASES = List.of("contact", "down", "passing", "up", "anticipation", "hold", "follow_through");

    /** Poses the plan may reference; extensible only from this file. */
    public static final List<String> POSES = List.of("crouch", "compress", "rise", "fall", "punch", "kick", "idle", "turn", "walk_step", "land", "reach", "point", "blink", "walk_step_b");

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
    }

    /**
     * Parse and validate a plan from the model's raw JSON reply. Throws a
     * PARSE {@link AiException} with the first violation spelled out.
     */
    public static AnimationPlan parse(String json) throws AiException
    {
        MapType map = DataToString.mapFromString(json);

        if (map == null)
        {
            throw new AiException(AiException.Type.PARSE, "Animation plan is not valid JSON");
        }

        AnimationPlan plan = new AnimationPlan();

        int version = map.getInt("version");

        if (version != VERSION)
        {
            throw new AiException(AiException.Type.PARSE, "Plan version " + version + " is not supported (expected " + VERSION + ")");
        }

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
            beat.pose = beatMap.getString("pose", "");
            beat.spacing = beatMap.getInt("spacing", 0);

            if (!PHASES.contains(beat.phase))
            {
                throw new AiException(AiException.Type.PARSE, "Beat " + i + " has unknown phase: " + beat.phase);
            }

            if (!POSES.contains(beat.pose))
            {
                throw new AiException(AiException.Type.PARSE, "Beat " + i + " has unknown pose: " + beat.pose);
            }

            if (beat.tick <= previousTick && i > 0)
            {
                throw new AiException(AiException.Type.PARSE, "Beat " + i + " tick " + beat.tick + " is not strictly increasing (previous " + previousTick + ")");
            }

            if (i > 0 && beat.spacing != beat.tick - previousTick)
            {
                throw new AiException(AiException.Type.PARSE, "Beat " + i + " spacing " + beat.spacing + " does not match tick gap " + (beat.tick - previousTick));
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
