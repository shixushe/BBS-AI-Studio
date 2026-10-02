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

/**
 * Claude adapter (copilot spec section 3 L1): the Messages API is shaped
 * differently from OpenAI's - system is a top-level field, auth is
 * {@code x-api-key} plus {@code anthropic-version}, and the reply text lives
 * at {@code content[0].text}. max_tokens is REQUIRED by the API, so it
 * defaults to 2048 when the request leaves it unset.
 */
public class AnthropicBackend implements AiTextBackend
{
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Override
    public String id()
    {
        return "anthropic";
    }

    @Override
    public AiChatResponse chat(AiChatRequest request) throws AiException
    {
        String baseUrl = AiSettings.baseUrl.get().trim();
        String apiKey = AiSettings.apiKey.get().trim();
        String model = AiSettings.model.get().trim();

        if (baseUrl.isEmpty() || apiKey.isEmpty() || model.isEmpty())
        {
            throw new AiException(Type.NOT_CONFIGURED, "Anthropic backend is not configured (base URL, API key or model is missing)");
        }

        if (baseUrl.endsWith("/"))
        {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        MapType body = new MapType();

        body.putString("model", model);
        body.putInt("max_tokens", request.maxTokens > 0 ? request.maxTokens : 2048);
        body.putString("system", request.system == null ? "" : request.system);

        ListType messages = new ListType();
        MapType user = new MapType();

        user.putString("role", "user");
        user.putString("content", request.user == null ? "" : request.user);
        messages.add(user);
        body.put("messages", messages);
        body.putFloat("temperature", request.temperature);

        HttpRequest httpRequest;

        try
        {
            httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/messages"))
                .timeout(Duration.ofMillis(AiSettings.timeoutMs.get()))
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
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

            throw new AiException(timeout ? Type.TIMEOUT : Type.NETWORK, timeout ? "请求超时：服务在超时时间内没有响应——检查网络/代理或延长超时" : "Network failure: " + e.getClass().getSimpleName());
        }

        if (response.statusCode() != 200)
        {
            throw AiException.fromHttp(response.statusCode(), response.headers().firstValue("Retry-After").orElse(null), response.body());
        }

        return this.parse(response.body(), model);
    }

    private AiChatResponse parse(String body, String fallbackModel) throws AiException
    {
        MapType map = DataToString.mapFromString(body);

        if (map == null)
        {
            throw new AiException(Type.PARSE, "Anthropic reply is malformed JSON");
        }

        BaseType content = map.get("content");

        if (!BaseType.isList(content) || content.asList().isEmpty())
        {
            throw new AiException(Type.PARSE, "Anthropic reply has no content");
        }

        StringBuilder text = new StringBuilder();
        int prompt = 0;
        int completion = 0;

        for (int i = 0; i < content.asList().size(); i++)
        {
            BaseType block = content.asList().get(i);

            if (BaseType.isMap(block) && block.asMap().getString("type", "").equals("text"))
            {
                text.append(block.asMap().getString("content"));
            }
        }

        if (map.has("usage") && map.get("usage").isMap())
        {
            MapType usage = map.get("usage").asMap();

            prompt = usage.getInt("input_tokens");
            completion = usage.getInt("output_tokens");
        }

        return new AiChatResponse(text.toString(), map.getString("model", fallbackModel), prompt, completion);
    }
}
