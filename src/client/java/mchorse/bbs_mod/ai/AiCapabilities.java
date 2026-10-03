package mchorse.bbs_mod.ai;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * L0 capability scanner (copilot spec section 3): what THIS installation can
 * actually do, answered from live state — particle assets on disk, lighting
 * property tracks, IK constraints, BBS-ecosystem plugins, curve channels.
 * The result drives the system-prompt injection (so the model only asks for
 * what exists) and the chat receipt (so the user sees what the AI can reach).
 */
public class AiCapabilities
{
    public final List<String> particles = new ArrayList<>();

    /** 模组方块命名空间 → 已注册方块数（排除 minecraft），供建筑提示词注入 */
    public final java.util.LinkedHashMap<String, Integer> modBlocks = new java.util.LinkedHashMap<>();

    /** 每命名空间抽样方块 id（排序后前若干个），给模型 grounding */
    public final List<String> blockSamples = new ArrayList<>();
    public final List<String> plugins = new ArrayList<>();
    public boolean lighting = true;
    public boolean ik;
    public int ikChains;
    public int numericChannels;

    public static AiCapabilities scan(Film film)
    {
        AiCapabilities caps = new AiCapabilities();

        /* 粒子资产：config/bbs/assets/particles/*.json，键=去扩展名的文件名 */
        try
        {
            File dir = new File(BBSMod.getSettingsFolder(), "../assets/particles");
            File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));

            if (files != null)
            {
                for (File file : files)
                {
                    caps.particles.add(file.getName().substring(0, file.getName().length() - ".json".length()));
                }
            }
        }
        catch (Exception e)
        {
            /* 资产目录读不到就当没有粒子资产 */
        }

        /* 插件：BBS 生态 mod 按 id/名称关键字识别 */
        try
        {
            for (var mod : FabricLoader.getInstance().getAllMods())
            {
                String id = (mod.getMetadata().getId() + " " + mod.getMetadata().getName()).toLowerCase();

                for (String keyword : new String[] {"physics", "vfx", "lumen", "ik", "fslovecml", "bbs++", "bbspp", "posecurve"})
                {
                    if (id.contains(keyword))
                    {
                        caps.plugins.add(mod.getMetadata().getId());

                        break;
                    }
                }
            }
        }
        catch (Exception e) {}

        /* 模组方块：扫描注册表，按已加载 mod 的命名空间聚合（排除 minecraft） */
        try
        {
            java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
            java.util.Map<String, List<String>> samples = new java.util.LinkedHashMap<>();

            for (net.minecraft.util.Identifier id : net.minecraft.registry.Registries.BLOCK.getIds())
            {
                String namespace = id.getNamespace();

                if (namespace.equals("minecraft"))
                {
                    continue;
                }

                counts.merge(namespace, 1, Integer::sum);

                List<String> list = samples.computeIfAbsent(namespace, (k) -> new ArrayList<>());

                if (list.size() < 8)
                {
                    list.add(id.toString());
                }
            }

            /* 只保留已加载 mod 的命名空间，按数量降序 */
            List<String> namespaces = new ArrayList<>(counts.keySet());

            namespaces.sort((a, b) -> counts.get(b) - counts.get(a));

            int taken = 0;

            for (String namespace : namespaces)
            {
                caps.modBlocks.put(namespace, counts.get(namespace));
                caps.blockSamples.addAll(samples.getOrDefault(namespace, new ArrayList<>()));

                taken += 1;

                if (taken >= 12 || caps.blockSamples.size() >= 120)
                {
                    break;
                }
            }
        }
        catch (Exception e)
        {
            /* 注册表不可用时跳过（无碍主体功能） */
        }

        /* 灯光：每个表单都有 lighting 属性通道，恒可用；顺带统计数值通道（曲线打磨素材）*/
        try
        {
            for (Replay replay : film.replays.getList())
            {
                for (KeyframeChannel<?> channel : replay.properties.tracks.values())
                {
                    if (mchorse.bbs_mod.ai.curve.CurvePolisher.isPolishable(channel.getFactory()))
                    {
                        caps.numericChannels++;
                    }
                }

                for (KeyframeChannel<?> channel : replay.keyframes.getChannels())
                {
                    if (mchorse.bbs_mod.ai.curve.CurvePolisher.isPolishable(channel.getFactory()))
                    {
                        caps.numericChannels++;
                    }
                }

                if (replay.form.get() instanceof ModelForm modelForm)
                {
                    for (var child : modelForm.bones.getAll())
                    {
                        try
                        {
                            if (modelForm.bones.getBone(child.getId()).constraints.get().isActive())
                            {
                                caps.ik = true;
                                caps.ikChains++;
                            }
                        }
                        catch (Exception ignored)
                        {}
                    }
                }
            }
        }
        catch (Exception e) {}

        return caps;
    }

    /** 一行人话，进制作过程区；空能力如实说"无"。 */
    public String summary()
    {
        return "粒子×" + this.particles.size()
            + (this.particles.isEmpty() ? "(无资产)" : this.particles.toString())
            + " · 灯光通道" + (this.lighting ? "✓" : "✗")
            + " · IK" + (this.ik ? "✓(" + this.ikChains + "链)" : "✗")
            + " · 插件[" + String.join(",", this.plugins) + "]"
            + " · 数值通道×" + this.numericChannels + "(可打磨)";
    }
}
