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
    public static ValueBoolean supportsVision;
    public static ValueBoolean supportsTools;
    public static ValueBoolean supportsJsonMode;

    public static void register(SettingsBuilder builder)
    {
        builder.category("ai", Icons.KEY_CAP);

        provider = builder.getString("provider", "openai_compatible");
        baseUrl = builder.getString("base_url", "https://api.openai.com/v1");
        apiKey = builder.getString("api_key", "");
        model = builder.getString("model", "");
        stream = builder.getBoolean("stream", false);
        jsonMode = builder.getBoolean("json_mode", false);
        temperature = builder.getFloat("temperature", 0.7F, 0F, 2F);
        timeoutMs = builder.getInt("timeout_ms", 30000, 1000, 300000);
        maxRetries = builder.getInt("max_retries", 1, 0, 5);
        supportsVision = builder.getBoolean("supports_vision", false);
        supportsTools = builder.getBoolean("supports_tools", false);
        supportsJsonMode = builder.getBoolean("supports_json_mode", false);
    }

    public static boolean isConfigured()
    {
        return !baseUrl.get().trim().isEmpty()
            && !apiKey.get().trim().isEmpty()
            && !model.get().trim().isEmpty();
    }

    /**
     * Backend for the currently selected provider. Unknown provider strings
     * fall back to the OpenAI compatible adapter - new vendors then work with
     * zero code, which is the whole point of the default adapter.
     */
    public static AiTextBackend createBackend()
    {
        return new OpenAiCompatibleBackend();
    }
}
