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

    @Override
    public AiChatResponse chat(AiChatRequest request) throws AiException
    {
        String baseUrl = AiSettings.baseUrl.get().trim();
        String apiKey = AiSettings.apiKey.get().trim();
        String model = AiSettings.model.get().trim();

        if (baseUrl.isEmpty() || model.isEmpty() || apiKey.isEmpty())
        {
            throw new AiException(Type.NOT_CONFIGURED, "AI backend is not configured (base URL, API key or model is missing)");
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

            throw new AiException(timeout ? Type.TIMEOUT : Type.NETWORK, timeout ? "Request timed out" : "Network failure: " + e.getClass().getSimpleName());
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
        int prompt = 0;
        int completion = 0;

        if (map.has("usage") && map.get("usage").isMap())
        {
            MapType usage = map.get("usage").asMap();

            prompt = usage.getInt("prompt_tokens");
            completion = usage.getInt("completion_tokens");
        }

        return new AiChatResponse(content, model, prompt, completion);
    }
}
