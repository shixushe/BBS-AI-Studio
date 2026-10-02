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
 * Google Gemini adapter (copilot spec section 3 L1):
 * {@code POST {base}/v1beta/models/{model}:generateContent} with the key in
 * the {@code x-goog-api-key} header (kept out of the URL so it can never leak
 * into a log line). System text rides on {@code systemInstruction}; the reply
 * text lives at {@code candidates[0].content.parts[0].text}.
 */
public class GeminiBackend implements AiTextBackend
{
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Override
    public String id()
    {
        return "gemini";
    }

    @Override
    public AiChatResponse chat(AiChatRequest request) throws AiException
    {
        String baseUrl = AiSettings.baseUrl.get().trim();
        String apiKey = AiSettings.apiKey.get().trim();
        String model = AiSettings.model.get().trim();

        if (baseUrl.isEmpty() || apiKey.isEmpty() || model.isEmpty())
        {
            throw new AiException(Type.NOT_CONFIGURED, "Gemini backend is not configured (base URL, API key or model is missing)");
        }

        if (baseUrl.endsWith("/"))
        {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        MapType body = new MapType();

        if (request.system != null && !request.system.isEmpty())
        {
            MapType systemInstruction = new MapType();
            ListType systemParts = new ListType();
            MapType systemPart = new MapType();

            systemPart.putString("text", request.system);
            systemParts.add(systemPart);
            systemInstruction.put("parts", systemParts);
            body.put("systemInstruction", systemInstruction);
        }

        ListType contents = new ListType();
        MapType turn = new MapType();
        ListType parts = new ListType();
        MapType part = new MapType();

        part.putString("text", request.user == null ? "" : request.user);
        parts.add(part);
        turn.putString("role", "user");
        turn.put("parts", parts);
        contents.add(turn);
        body.put("contents", contents);

        MapType config = new MapType();

        config.putFloat("temperature", request.temperature);

        if (request.maxTokens > 0)
        {
            config.putInt("maxOutputTokens", request.maxTokens);
        }

        body.put("generationConfig", config);

        HttpRequest httpRequest;

        try
        {
            httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1beta/models/" + model + ":generateContent"))
                .timeout(Duration.ofMillis(AiSettings.timeoutMs.get()))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey)
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
            throw new AiException(Type.PARSE, "Gemini reply is malformed JSON");
        }

        BaseType candidates = map.get("candidates");

        if (!BaseType.isList(candidates) || candidates.asList().isEmpty())
        {
            throw new AiException(Type.PARSE, "Gemini reply has no candidates");
        }

        MapType first = candidates.asList().get(0).isMap() ? candidates.asList().get(0).asMap() : null;
        BaseType parts = first != null && first.has("content") && first.get("content").isMap()
            ? first.get("content").asMap().get("parts")
            : null;

        if (!BaseType.isList(parts) || parts.asList().isEmpty())
        {
            throw new AiException(Type.PARSE, "Gemini reply has no text parts");
        }

        StringBuilder text = new StringBuilder();

        for (int i = 0; i < parts.asList().size(); i++)
        {
            BaseType piece = parts.asList().get(i);

            if (BaseType.isMap(piece) && piece.asMap().has("text"))
            {
                text.append(piece.asMap().getString("text"));
            }
        }

        int prompt = 0;
        int completion = 0;

        if (map.has("usageMetadata") && map.get("usageMetadata").isMap())
        {
            MapType usage = map.get("usageMetadata").asMap();

            prompt = usage.getInt("promptTokenCount");
            completion = usage.getInt("candidatesTokenCount");
        }

        return new AiChatResponse(text.toString(), first != null && first.has("model") ? first.getString("model") : fallbackModel, prompt, completion);
    }
}
