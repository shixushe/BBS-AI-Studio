package mchorse.bbs_mod.ai;

import mchorse.bbs_mod.settings.SettingsBuilder;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import mchorse.bbs_mod.settings.values.core.ValueString;
import mchorse.bbs_mod.ui.utils.icons.Icons;

/**
 * AI copilot settings, registered under the "ai" category of {@code BBSSettings}.
 *
 * <p>Security contract (copilot spec section 3 L1): the API key lives only in
 * the local settings file. It is never written into films, never logged, never
 * embedded in the client. Default is BYOK - no key ships with the mod.</p>
 *
 * <p>Capability toggles are explicit rather than assumed (same model family
 * varies wildly): a feature that needs an unconfirmed capability either
 * degrades to a path that doesn't use it or disables itself in the UI.</p>
 */
public class AiSettings
{
    public static ValueString provider;
    public static ValueString baseUrl;
    public static ValueString apiKey;
    public static ValueString model;
    public static ValueBoolean stream;
    public static ValueBoolean jsonMode;
    public static ValueFloat temperature;
    public static ValueInt timeoutMs;
    public static ValueInt maxRetries;
    /** 思维链开关：开启后思考型模型（GLM-4.6/5.x 非 flash）保留思考并回传思维链 */
    public static ValueBoolean thinking;

    public static ValueBoolean supportsVision;
    public static ValueBoolean supportsTools;
    public static ValueBoolean supportsJsonMode;
    public static ValueBoolean debugServer;
    public static ValueBoolean aiUVOverlay;
    public static ValueString imageModel;
    public static ValueString imageSize;

    public static void register(SettingsBuilder builder)
    {
        builder.category("ai", Icons.KEY_CAP);

        provider = builder.getString("provider", "openai_compatible");
        baseUrl = builder.getString("base_url", "https://api.openai.com/v1");
        apiKey = builder.getString("api_key", "");
        model = builder.getString("model", "");
        stream = builder.getBoolean("stream", false);
        jsonMode = builder.getBoolean("json_mode", false);
        temperature = builder.getFloat("temperature", 0.7F, 0F, 1F); /* GLM 限 [0,1] */
        timeoutMs = builder.getInt("timeout_ms", 60000, 1000, 300000);
        maxRetries = builder.getInt("max_retries", 1, 0, 5);
        thinking = builder.getBoolean("thinking", false);
        supportsVision = builder.getBoolean("supports_vision", false);
        supportsTools = builder.getBoolean("supports_tools", false);
        supportsJsonMode = builder.getBoolean("supports_json_mode", false);
        debugServer = builder.getBoolean("debug_server", true);
        aiUVOverlay = builder.getBoolean("ai_uv_overlay", true);
        imageModel = builder.getString("image_model", "");
        imageSize = builder.getString("image_size", "1024x1024");
    }

    public static boolean isConfigured()
    {
        return !baseUrl.get().trim().isEmpty()
            && !apiKey.get().trim().isEmpty()
            && !model.get().trim().isEmpty();
    }

    /**
     * One-click base URLs per provider (spec 3 L1: OpenAI-compatible covers
     * most vendors; Anthropic and Gemini speak their own protocols). The
     * settings UI lists these; the user can always type a custom URL instead.
     */
    public static final java.util.Map<String, String> PRESETS = new java.util.LinkedHashMap<>();

    static
    {
        PRESETS.put("openai_compatible", "https://api.openai.com/v1");
        PRESETS.put("deepseek", "https://api.deepseek.com/v1");
        PRESETS.put("qwen", "https://dashscope.aliyuncs.com/compatible-mode/v1");
        PRESETS.put("kimi", "https://api.moonshot.cn/v1");
        PRESETS.put("glm", "https://open.bigmodel.cn/api/paas/v4");
        PRESETS.put("siliconflow", "https://api.siliconflow.cn/v1");
        PRESETS.put("openrouter", "https://openrouter.ai/api/v1");
        PRESETS.put("ollama", "http://localhost:11434/v1");
        PRESETS.put("lmstudio", "http://localhost:1234/v1");
        PRESETS.put("anthropic", "https://api.anthropic.com");
        PRESETS.put("gemini", "https://generativelanguage.googleapis.com");
    }

    /** Fill baseUrl from a provider preset (leaves custom URLs alone). */
    public static void applyPreset(String provider)
    {
        String url = PRESETS.get(provider);

        if (url != null)
        {
            baseUrl.set(url);
        }
    }

    /**
     * Backend for the currently selected provider. OpenAI-compatible is the
     * default adapter - unknown provider strings land there, so new vendors
     * work with zero code. Anthropic and Gemini get dedicated adapters.
     */
    public static AiTextBackend createBackend()
    {
        String selected = provider.get().trim().toLowerCase();

        if (selected.equals("anthropic"))
        {
            return new AnthropicBackend();
        }

        if (selected.equals("gemini"))
        {
            return new GeminiBackend();
        }

        return new OpenAiCompatibleBackend();
    }
}
