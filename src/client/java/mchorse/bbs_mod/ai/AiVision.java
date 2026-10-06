package mchorse.bbs_mod.ai;

import mchorse.bbs_mod.utils.resources.Pixels;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * 视觉校验：把生成的动画在关键 tick 截几帧发给视觉模型质检
 * （滑步/穿模/关节反折/姿势是否符合要求），结论回填聊天流。
 * 仅当 用户在设置里开启 supports_vision 且后端是 OpenAI 兼容协议
 * （GLM-4V 系列 / GPT-4o / Qwen-VL 等都走这个形状）时可用。
 *
 * <p>线程：独立单 worker（与 AiClient 的聊天线程分离），结果经
 * MinecraftClient.execute 封送回渲染线程——沿用聊天流的封送纪律。</p>
 */
public class AiVision
{
    private static java.util.concurrent.ExecutorService worker;

    /** 帧尾待执行的抓帧任务（MinecraftClientMixin 在 render() 尾部调用 onFrameEnd） */
    private static final java.util.concurrent.atomic.AtomicReference<Runnable> PENDING_GRAB =
        new java.util.concurrent.atomic.AtomicReference<>();

    /** 帧尾钩子：整帧已绘制完、尚未 swap——唯一能拍到真实画面的时机 */
    public static void onFrameEnd()
    {
        Runnable grab = PENDING_GRAB.getAndSet(null);

        if (grab != null)
        {
            grab.run();
        }
    }

    /** 登记一个帧尾抓帧任务（渲染线程任意时刻调用，下一帧尾执行） */
    public static void onFrameEndOnce(Runnable grab)
    {
        PENDING_GRAB.set(grab);
    }

    public static boolean available()
    {
        return AiSettings.isConfigured()
            && AiSettings.supportsVision.get()
            && AiSettings.createBackend() instanceof OpenAiCompatibleBackend;
    }

    /**
     * 异步质检。onDone 收 (verdict, error)；两者恰有一个非空，且都在
     * 渲染线程上回调。
     */
    public static void verify(String script, List<Pixels> frames, BiConsumer<String, String> onDone)
    {
        if (!available() || frames == null || frames.isEmpty())
        {
            onDone.accept(null, "视觉校验不可用（未配置后端或未开启 supports_vision）");

            return;
        }

        List<String> base64 = new java.util.ArrayList<>();

        for (Pixels frame : frames)
        {
            String png = toBase64Png(frame);

            if (png != null)
            {
                base64.add(png);
            }
        }

        if (base64.isEmpty())
        {
            onDone.accept(null, "帧编码失败");

            return;
        }

        /* 调试落盘：抓到的帧同时写 ai_shots/vision_*.png——黑帧排查用 */
        for (int i = 0; i < frames.size(); i++)
        {
            try
            {
                java.awt.image.BufferedImage debug = new java.awt.image.BufferedImage(
                    frames.get(i).width, frames.get(i).height, java.awt.image.BufferedImage.TYPE_INT_ARGB);

                for (int y = 0; y < frames.get(i).height; y++)
                {
                    for (int x = 0; x < frames.get(i).width; x++)
                    {
                        debug.setRGB(x, y, frames.get(i).getColor(x, y).getARGBColor());
                    }
                }

                javax.imageio.ImageIO.write(debug, "png", new java.io.File(
                    mchorse.bbs_mod.BBSMod.getSettingsFolder(), "ai_shots/vision_" + System.currentTimeMillis() + "_" + i + ".png"));
            }
            catch (Exception ignored)
            {}
        }

        String system = "你是 Minecraft 动画质检员。给你同一动画按时间顺序截取的几帧渲染图，"
            + "检查并只报告确实可见的问题：①人物是否完整可见（无黑屏/穿地/断肢）；"
            + "②关节是否明显反折或扭曲（特别注意肘/膝弯的方向）；"
            + "③步行类动作：手臂摆动方向是否与同侧腿相反（对侧协调）——"
            + "若某侧手臂与同侧腿朝同一方向前后摆动，回答 ARMS_REVERSED 并说明是哪侧；"
            + "④几帧之间的姿势是否有合理变化（不是完全静止）；"
            + "⑤姿势是否符合用户要求的动作。用中文列出问题，每条一行；没有任何问题只回答 PASS。";

        String user = "用户要求的动作：" + (script == null || script.isBlank() ? "(未提供)" : script)
            + "\n共 " + base64.size() + " 帧，按时间顺序。请质检。";

        synchronized (AiVision.class)
        {
            if (worker == null)
            {
                worker = java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "BBS AI Vision");

                    thread.setDaemon(true);

                    return thread;
                });
            }
        }

        final List<String> payload = base64;

        worker.submit(() ->
        {
            try
            {
                AiChatRequest request = new AiChatRequest(system, user);

                request.maxTokens(0);
                request.temperature(0.2F);

                AiChatResponse response = ((OpenAiCompatibleBackend) AiSettings.createBackend())
                    .chatWithImages(request, payload);

                net.minecraft.client.MinecraftClient.getInstance().execute(() ->
                    onDone.accept(response.content == null ? "" : response.content.trim(), null));
            }
            catch (Exception e)
            {
                String message = e.getMessage() == null || e.getMessage().isEmpty()
                    ? e.getClass().getSimpleName() : e.getMessage();

                net.minecraft.client.MinecraftClient.getInstance().execute(() -> onDone.accept(null, message));
            }
        });
    }

    /** Pixels → PNG base64（复用采集面板同款 ARGB 逐像素搬运） */
    private static String toBase64Png(Pixels pixels)
    {
        try
        {
            java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(
                pixels.width, pixels.height, java.awt.image.BufferedImage.TYPE_INT_ARGB);

            for (int y = 0; y < pixels.height; y++)
            {
                for (int x = 0; x < pixels.width; x++)
                {
                    image.setRGB(x, y, pixels.getColor(x, y).getARGBColor());
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();

            javax.imageio.ImageIO.write(image, "png", out);

            return Base64.getEncoder().encodeToString(out.toByteArray());
        }
        catch (Exception e)
        {
            return null;
        }
    }
}
