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
