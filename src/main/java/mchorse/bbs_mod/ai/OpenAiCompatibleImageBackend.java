package mchorse.bbs_mod.ai;

import mchorse.bbs_mod.ai.AiException.Type;
import mchorse.bbs_mod.data.DataToString;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.utils.resources.Pixels;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

/**
 * OpenAI-compatible images adapter (default, same rationale as the text
 * side): POST {baseUrl}/images/generations, base64 PNG back. Works for
 * OpenAI, and for any gateway exposing the same shape.
 */
public class OpenAiCompatibleImageBackend implements AiImageBackend
{
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Override
    public Pixels generate(String prompt, Pixels reference, int width, int height) throws AiException
    {
        String baseUrl = AiSettings.baseUrl.get().trim();
        String apiKey = AiSettings.apiKey.get().trim();
        String model = AiSettings.imageModel.get().trim();

        if (baseUrl.isEmpty() || apiKey.isEmpty() || model.isEmpty())
        {
            throw new AiException(Type.NOT_CONFIGURED, "Image backend is not configured (image model is missing)");
        }

        if (baseUrl.endsWith("/"))
        {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        MapType body = new MapType();

        body.putString("model", model);
        body.putString("prompt", prompt);
        body.putString("response_format", "b64_json");
        body.putInt("n", 1);

        String size = AiSettings.imageSize.get().trim();

        if (!size.isEmpty())
        {
            body.putString("size", size);
        }

        HttpRequest request;

        try
        {
            request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/images/generations"))
                .timeout(Duration.ofMillis(Math.max(AiSettings.timeoutMs.get(), 60000)))
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
            response = this.http.send(request, HttpResponse.BodyHandlers.ofString());
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

        return this.parse(response.body());
    }

    @Override
    public Pixels edit(String prompt, Pixels base, int x, int y, int w, int h, String repaintPrompt) throws AiException
    {
        /* OpenAI images/edits endpoint: the base image + a transparent mask
         * where alpha=0 means "repaint this pixel". The composited result is
         * returned as full pixels; the caller writes it back. */
        String baseUrl = AiSettings.baseUrl.get().trim();
        String apiKey = AiSettings.apiKey.get().trim();
        String model = AiSettings.imageModel.get().trim();

        if (baseUrl.isEmpty() || apiKey.isEmpty() || model.isEmpty())
        {
            throw new AiException(Type.NOT_CONFIGURED, "Image backend not configured");
        }

        /* Build the mask: fully transparent OUTSIDE the edit region, opaque inside */
        Pixels mask = mchorse.bbs_mod.utils.resources.Pixels.fromSize(base.width, base.height);

        for (int py = y; py < y + h && py < base.height; py++)
        {
            for (int px = x; px < x + w && px < base.width; px++)
            {
                mask.setColor(px, py, new mchorse.bbs_mod.utils.colors.Color().set(0, 0, 0, 0));
            }
        }

        /* For b64_json response_format: send base + mask as multipart is the
         * standard but requires multipart encoding. JSON with data URLs is
         * the OpenAI-compatible simplification that most gateways accept. */
        MapType body = new MapType();

        body.putString("model", model);
        body.putString("prompt", repaintPrompt + " (repaint only the masked region)");
        body.putString("response_format", "b64_json");
        body.putString("size", base.width + "x" + base.height);

        /* TODO: multipart form upload for base + mask images when a gateway
         * needs the real images/edits endpoint. Most OpenAI-compatible
         * gateways only expose images/generations, so the practical inpaint
         * flow is: crop the region, generate with a regional prompt,
         * composite back locally. This is implemented in the caller. */

        /* For now: delegate to generate with the regional prompt, then the
         * caller composites the result back into the base texture. */
        return this.generate(repaintPrompt, base, base.width, base.height);
    }

    private Pixels parse(String body) throws AiException
    {
        MapType map = DataToString.mapFromString(body);

        if (map == null)
        {
            throw new AiException(Type.PARSE, "Image backend returned malformed JSON");
        }

        BaseType data = map.get("data");

        if (!BaseType.isList(data) || data.asList().isEmpty())
        {
            throw new AiException(Type.PARSE, "Image response has no data");
        }

        ListType list = data.asList();
        MapType first = list.get(0).isMap() ? list.get(0).asMap() : null;

        if (first == null || !first.has("b64_json"))
        {
            throw new AiException(Type.PARSE, "Image response is missing b64_json");
        }

        try
        {
            byte[] png = Base64.getDecoder().decode(first.getString("b64_json"));

            return Pixels.fromPNGStream(new ByteArrayInputStream(png));
        }
        catch (Exception e)
        {
            throw new AiException(Type.PARSE, "Image payload failed to decode: " + e.getClass().getSimpleName());
        }
    }
}
