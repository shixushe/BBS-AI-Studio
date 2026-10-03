package mchorse.bbs_mod.ai.pose;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.data.DataToString;

import java.io.File;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-model AI bone bindings: the generic humanoid bone -> the model's real
 * bone, as the user declared it (the ask dialog, or the model editor's AI
 * bindings tab). Persisted next to the settings so a binding made once keeps
 * answering for every later generation run against the same model.
 *
 * <p>The store key is the model's path with any folder prefix stripped, so the
 * model editor (config id) and replay forms (full model path) land on the same
 * entry.</p>
 */
public class AiBoneBindings
{
    private static final String FILE = "ai_bone_bindings.json";

    private static Map<String, Map<String, String>> bindings;
    private static boolean loaded;

    /** Last path segment, lowercased - the shared identity of a model. */
    public static String normalize(String key)
    {
        if (key == null || key.isEmpty())
        {
            return "";
        }

        String last = key;

        if (last.endsWith(".json"))
        {
            last = last.substring(0, last.length() - ".json".length());
        }

        int slash = Math.max(last.lastIndexOf('/'), last.lastIndexOf('\\'));

        return slash >= 0 ? last.substring(slash + 1) : last;
    }

    /**
     * 内置 Star 3.6 系列的默认 AI 绑定：核心六骨骼精确命中 + 眼睛变体的眼瞳。
     * normalize 取末段（slim_eyes 等），与模型 id 的末段一致。
     */
    private static final Map<String, Map<String, String>> BUILTIN_DEFAULTS = new LinkedHashMap<>();

    static
    {
        Map<String, String> eyes = new LinkedHashMap<>();

        eyes.put("head", "head");
        eyes.put("body", "body");
        eyes.put("left_arm", "left_arm");
        eyes.put("right_arm", "right_arm");
        eyes.put("left_leg", "left_leg");
        eyes.put("right_leg", "right_leg");
        eyes.put("left_eye", "左眼瞳");
        eyes.put("right_eye", "右眼瞳");

        for (String id : new String[] {"slim_eyes", "thick_eyes"})
        {
            BUILTIN_DEFAULTS.put(id, eyes);
        }

        Map<String, String> core = new LinkedHashMap<>();

        core.put("head", "head");
        core.put("body", "body");
        core.put("left_arm", "left_arm");
        core.put("right_arm", "right_arm");
        core.put("left_leg", "left_leg");
        core.put("right_leg", "right_leg");

        for (String id : new String[] {"slim_gapless", "slim_female", "slim_weld", "slim_3d",
            "thick_gapless", "thick_weld", "thick_3d"})
        {
            BUILTIN_DEFAULTS.put(id, core);
        }
    }

    /** The saved bindings for a model, falling back to built-in defaults (Star 3.6). */
    public static Map<String, String> get(String modelKey)
    {
        ensureLoaded();

        Map<String, String> map = bindings.get(normalize(modelKey));

        if (map == null || map.isEmpty())
        {
            Map<String, String> defaults = builtinDefaults(modelKey);

            if (defaults != null)
            {
                return defaults;
            }
        }

        return map == null ? Map.of() : map;
    }

    /**
     * 内置默认绑定（无需加载设置文件，纯静态表）——测试与 UI 预填都可安全调用。
     */
    public static Map<String, String> builtinDefaults(String modelKey)
    {
        return BUILTIN_DEFAULTS.get(normalize(modelKey));
    }

    /** Store and persist one model's generic -> actual map. */
    public static void set(String modelKey, Map<String, String> map)
    {
        /* 丢弃空键和 null 值；空串值是「已添加未选骨骼」的占位，保留它
         * （apply 时 inventory 不含空串，天然不生效），否则添加动作会被吞掉 */
        map.entrySet().removeIf((e) ->
            e.getKey() == null || e.getKey().trim().isEmpty() || e.getValue() == null);

        ensureLoaded();

        String key = normalize(modelKey);

        if (map == null || map.isEmpty())
        {
            bindings.remove(key);
        }
        else
        {
            bindings.put(key, new LinkedHashMap<>(map));
        }

        save();
    }

    /**
     * Fold the saved bindings into a resolver result: a binding whose actual
     * bone really exists resolves its generic bone, removing it from the
     * unresolved list. Mutates and returns {@code result}.
     */
    public static BoneNameResolver.Result apply(String modelKey, Collection<String> inventory, BoneNameResolver.Result result)
    {
        for (Map.Entry<String, String> entry : get(modelKey).entrySet())
        {
            if (!inventory.contains(entry.getValue()))
            {
                continue;
            }

            result.resolved.put(entry.getKey(), BoneNameResolver.confirmed(entry.getKey(), entry.getValue()));
            result.unresolved.remove(entry.getKey());
        }

        return result;
    }

    private static synchronized void ensureLoaded()
    {
        if (loaded)
        {
            return;
        }

        loaded = true;
        bindings = new LinkedHashMap<>();

        try
        {
            File file = BBSMod.getSettingsPath(FILE);
            MapType data = DataToString.mapFromString(mchorse.bbs_mod.utils.IOUtils.readText(file));

            for (String model : data.keys())
            {
                if (!data.has(model, BaseType.TYPE_MAP))
                {
                    continue;
                }

                Map<String, String> map = new LinkedHashMap<>();
                MapType inner = data.getMap(model);

                for (String generic : inner.keys())
                {
                    if (inner.has(generic, BaseType.TYPE_STRING))
                    {
                        map.put(generic, inner.getString(generic));
                    }
                }

                bindings.put(model, map);
            }
        }
        catch (Exception e)
        {
            /* Missing or unreadable file = no bindings yet */
        }
    }

    private static synchronized void save()
    {
        try
        {
            MapType data = new MapType();

            for (Map.Entry<String, Map<String, String>> entry : bindings.entrySet())
            {
                MapType inner = new MapType();

                for (Map.Entry<String, String> binding : entry.getValue().entrySet())
                {
                    inner.putString(binding.getKey(), binding.getValue());
                }

                data.put(entry.getKey(), inner);
            }

            DataToString.writeSilently(BBSMod.getSettingsPath(FILE), data, true);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }
}
