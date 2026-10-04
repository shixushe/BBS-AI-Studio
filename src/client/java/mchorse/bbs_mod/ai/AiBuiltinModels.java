package mchorse.bbs_mod.ai;

import net.minecraft.client.MinecraftClient;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

/**
 * Star 3.6 内置模型安装器：首次启动把随 jar 打包的模型解包到
 * config/bbs/assets/models/star36/<变体>/（BBS 的文件夹模型通道，读取开销
 * 低）。绝不把模型放进 mod jar 资产——InternalAssetsSourcePack 的 zip 重扫
 * 会把渲染线程饿死（第三十九轮的冻结教训）。已存在的文件一律跳过，用户的
 * 手工修改不受影响。
 */
public class AiBuiltinModels
{
    /** 变体 → 随包文件清单（纹理名为作者原文件名，引用保持相对一致）。 */
    /** 原始中文文件夹名（与模型 bbs.json 引用一致）。 */
    private static final String[][] VARIANTS = {
        {"star人物模型细胳膊 弯曲处缝隙优化"},
        {"star人物模型细胳膊 自带眼睛"},
        {"star人物模型细胳膊 女性"},
        {"star人物模型细胳膊 弯曲处焊接"},
        {"star人物模型细胳膊  3D 弯曲缝隙优化"},
        {"star人物模型粗胳膊 弯曲处缝隙优化"},
        {"star人物模型粗胳膊 自带眼睛"},
        {"star人物模型粗胳膊 弯曲处焊接"},
        {"star人物模型粗胳膊  3D 弯曲缝隙优化"},
        {"star bbs 眼睛模型"}
    };

    private static final String[] COMMON_FILES = {
        "model.bbs.json", "config.json", "constraints_presets.json",
        "ik_presets.json", "physics_presets.json", "poses.json",
        "艾利克斯-Alex-黑丝.png"
    };

    public static void install()
    {
        try
        {
            File base = new File(MinecraftClient.getInstance().runDirectory,
                "config/bbs/assets/models/Star bbs fs  人物模型3.6");

            int copied = 0;

            for (String[] variant : VARIANTS)
            {
                String folder = variant[0];

                for (String file : COMMON_FILES)
                {
                    File target = new File(base, folder + "/" + file);
                    String resource = "/ai_models/star36/" + folder + "/" + file;

                    if (target.isFile())
                    {
                        continue;
                    }

                    try (InputStream stream = AiBuiltinModels.class.getResourceAsStream(resource))
                    {
                        if (stream == null)
                        {
                            continue;
                        }

                        target.getParentFile().mkdirs();
                        Files.copy(stream, target.toPath());
                        copied++;
                    }
                }
            }

            if (copied > 0)
            {
                System.out.println("[BBS AI] Installed " + copied + " builtin Star 3.6 model files into config assets");
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }
}
