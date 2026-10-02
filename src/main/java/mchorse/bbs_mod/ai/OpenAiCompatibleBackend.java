package mchorse.bbs_mod.ai;

import mchorse.bbs_mod.ai.AiException.Type;
import mchorse.bbs_mod.data.DataToString;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * The default backend: every provider exposing OpenAI's /chat/completions
 * shape (OpenAI, DeepSeek, Qwen, Kimi, GLM, SiliconFlow, OpenRouter, Ollama,
 * LM Studio, vLLM, ...) works by filling baseUrl + key + model in
 * {@link AiSettings}. Provider specific adapters exist only for protocols
 * that differ (Anthropic Messages, Gemini generateContent).
 *
 * <p>Network is JDK {@link HttpClient} only (same as {@code CDNAssetSyncService}
 * / {@code PlayerSkins}) - no Minecraft network stack, per the AI layer's
 * version independence rules.</p>
 */
public class OpenAiCompatibleBackend implements AiTextBackend
{
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Override
    public String id()
    {
        return "openai_compatible";
    }

    private static int timeoutMsSeconds()
    {
        return Math.max(1, mchorse.bbs_mod.ai.AiSettings.timeoutMs.get() / 1000);
    }

    @Override
    public AiChatResponse chat(AiChatRequest request) throws AiException
    {
        String baseUrl = AiSettings.baseUrl.get().trim();
        String apiKey = AiSettings.apiKey.get().trim();
        String model = AiSettings.model.get().trim().toLowerCase(); /* 模型 id 全小写：GLM 等按大小写敏感校验 */

        if (baseUrl.isEmpty() || model.isEmpty() || apiKey.isEmpty())
        {
            throw new AiException(Type.NOT_CONFIGURED, "AI 尚未配置：B 键打开设置 → AI → 供应商选 GLM/DeepSeek 等，填入 API 密钥后重试");
        }

        if (baseUrl.endsWith("/"))
        {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        MapType body = new MapType();

        body.putString("model", model);
        body.put("messages", this.messages(request));
        body.putFloat("temperature", request.temperature);

        if (request.maxTokens > 0)
        {
            body.putInt("max_tokens", request.maxTokens);
        }

        /* GLM 思考模式按代次处理：4.x 可以显式 disabled（要的是确定性 JSON，关掉
         * 提速）；5.x 官方限制思考只能开启——发 disabled 会被 400 拒收，只能用
         * reasoning_effort=low 把推理强度调到最低档。仅 glm 供应商发送这些字段。 */
        if ("glm".equals(AiSettings.provider.get().trim().toLowerCase()) && !AiSettings.thinking.get())
        {
            String glmModel = model.toLowerCase();

            /* flash 变体本身不思考，任何思考参数都可能被拒收，什么都不发 */
            if (!glmModel.contains("flash"))
            {
                if (glmModel.startsWith("glm-5") || glmModel.startsWith("glm5"))
                {
                    /* GLM-5.x 思考强制开启，只能调低推理强度（5.3 限 low/high/max） */
                    body.putString("reasoning_effort", "low");
                }
                else
                {
                    MapType thinking = new MapType();

                    thinking.putString("type", "disabled");
                    body.put("thinking", thinking);
                }
            }
        }

        /* JSON mode is opt-in: plenty of OpenAI-compatible gateways reject
         * response_format outright, so it only goes out when the user (or a
         * capability probe) has confirmed the model accepts it. */
        if (request.jsonMode && AiSettings.supportsJsonMode.get())
        {
            MapType format = new MapType();

            format.putString("type", "json_object");
            body.put("response_format", format);
        }

        HttpRequest httpRequest;

        try
        {
            httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .timeout(Duration.ofMillis(AiSettings.timeoutMs.get()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(DataToString.toString(body, true)))
                .build();
        }
        catch (Exception e)
        {
            throw new AiException(Type.NOT_CONFIGURED, "Invalid base URL: " + e.getMessage());
        }

        HttpResponse<String> response;

        try
        {
            response = this.http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();

            throw new AiException(Type.CANCELLED, "Request cancelled");
        }
        catch (Exception e)
        {
            boolean timeout = e.getClass().getSimpleName().contains("Timeout");
            String host = URI.create(baseUrl + "/chat/completions").getHost();

            if (timeout)
            {
                throw new AiException(Type.TIMEOUT, "请求超时：" + timeoutMsSeconds() + " 秒内没有收到 " + host
                    + " 的响应——检查网络/代理，或在 设置→AI 里换供应商并延长超时");
            }

            throw new AiException(Type.NETWORK, "网络错误：" + host + " 连接失败（" + e.getClass().getSimpleName()
                + "）——该地址可能不可直连，换国内供应商或填代理地址");
        }

        if (response.statusCode() != 200)
        {
            throw AiException.fromHttp(response.statusCode(), response.headers().firstValue("Retry-After").orElse(null), response.body());
        }

        return this.parse(response.body(), model);
    }

    private ListType messages(AiChatRequest request)
    {
        ListType messages = new ListType();
        MapType system = new MapType();
        MapType user = new MapType();

        system.putString("role", "system");
        system.putString("content", request.system == null ? "" : request.system);
        messages.add(system);

        user.putString("role", "user");
        user.putString("content", request.user == null ? "" : request.user);
        messages.add(user);

        return messages;
    }

    private AiChatResponse parse(String body, String fallbackModel) throws AiException
    {
        MapType map = DataToString.mapFromString(body);

        if (map == null)
        {
            throw new AiException(Type.PARSE, "Backend returned malformed JSON");
        }

        BaseType choices = map.get("choices");

        if (!BaseType.isList(choices) || choices.asList().isEmpty())
        {
            /* Some gateways wrap errors in a 200 response */
            BaseType error = map.get("error");

            if (error != null && BaseType.isMap(error))
            {
                throw new AiException(Type.UNKNOWN, "Backend error: " + error.asMap().getString("message"));
            }

            throw new AiException(Type.PARSE, "Response has no choices");
        }

        ListType list = choices.asList();
        MapType first = list.get(0).isMap() ? list.get(0).asMap() : null;

        if (first == null || !first.has("message"))
        {
            throw new AiException(Type.PARSE, "Response choice has no message");
        }

        MapType message = first.get("message").isMap() ? first.get("message").asMap() : null;

        if (message == null)
        {
            throw new AiException(Type.PARSE, "Response message is malformed");
        }

        String content = message.getString("content");
        String model = first.has("model") ? first.getString("model") : fallbackModel;

        /* finish_reason 里的失败态不能放过去：sensitive 是安全拦截、network_error
         * 是服务端推理异常、length 说明输出被 max_tokens 截断（JSON 方案会被截半，
         * 下游只会报一个莫名其妙的格式错误）。GLM 文档枚举这三种失败态。 */
        String finish = first.has("finish_reason") ? first.getString("finish_reason") : "";

        if (content == null || content.isEmpty())
        {
            if ("sensitive".equals(finish))
            {
                throw new AiException(Type.CONTENT_REJECTED, "内容被安全审核拦截（finish_reason=sensitive）——换个说法再试");
            }

            if ("network_error".equals(finish))
            {
                throw new AiException(Type.NETWORK, "服务端推理异常（finish_reason=network_error）——稍后重试");
            }

            throw new AiException(Type.PARSE, "模型返回了空内容" + (finish.isEmpty() ? "" : "（finish_reason=" + finish + "）"));
        }

        if ("length".equals(finish))
        {
            throw new AiException(Type.CONTEXT_OVERFLOW, "输出达到 max_tokens 上限被截断（finish_reason=length）——在 设置→AI 里调大最大输出");
        }

        int prompt = 0;
        int completion = 0;

        if (map.has("usage") && map.get("usage").isMap())
        {
            MapType usage = map.get("usage").asMap();

            prompt = usage.getInt("prompt_tokens");
            completion = usage.getInt("completion_tokens");
        }

        return new AiChatResponse(content, model, prompt, completion, message.getString("reasoning_content"));
    }
}
