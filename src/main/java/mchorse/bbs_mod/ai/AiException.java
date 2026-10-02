package mchorse.bbs_mod.ai;

/**
 * Typed failure of an AI backend call. The five user-facing categories
 * (auth / rate limit / timeout / context overflow / content rejected) get
 * distinct treatments in the UI, so they must never collapse into one
 * generic "request failed" - see the AI copilot spec, section 3 L1.
 */
public class AiException extends Exception
{
    public enum Type
    {
        NOT_CONFIGURED, AUTH, RATE_LIMIT, TIMEOUT, CONTEXT_OVERFLOW, CONTENT_REJECTED, NETWORK, PARSE, CANCELLED, UNKNOWN
    }

    public final Type type;

    /** Server-provided Retry-After in milliseconds, or -1 when absent. */
    public final long retryAfterMs;

    /** Short response body excerpt kept for diagnostics - never contains the API key. */
    public final String detail;

    public AiException(Type type, String message)
    {
        this(type, message, -1L, null);
    }

    public AiException(Type type, String message, long retryAfterMs, String detail)
    {
        super(message);

        this.type = type;
        this.retryAfterMs = retryAfterMs;
        this.detail = detail;
    }

    public boolean isRetryable()
    {
        return this.type == Type.RATE_LIMIT || this.type == Type.TIMEOUT || this.type == Type.NETWORK;
    }

    /**
     * Map an HTTP status (plus its Retry-After header and a body snippet) to the
     * user-facing failure category. Pure and deterministic so it can be unit tested.
     */
    public static AiException fromHttp(int statusCode, String retryAfterHeader, String body)
    {
        String snippet = snippet(body);
        long retryAfter = parseRetryAfter(retryAfterHeader);

        switch (statusCode)
        {
            case 401:
            case 403:
                return new AiException(Type.AUTH, "Authentication failed (" + statusCode + ")", -1L, snippet);
            case 408:
                return new AiException(Type.TIMEOUT, "Request timed out server-side (408)", -1L, snippet);
            case 413:
                return new AiException(Type.CONTEXT_OVERFLOW, "Request too large (413)", -1L, snippet);
            case 429:
                return new AiException(Type.RATE_LIMIT, "Rate limited (429)", retryAfter, snippet);
        }

        if (statusCode >= 500)
        {
            return new AiException(Type.NETWORK, "Server error (" + statusCode + ")", retryAfter, snippet);
        }

        if (statusCode == 400 || statusCode == 422)
        {
            String lower = (body == null ? "" : body).toLowerCase();

            if (lower.contains("content_filter") || lower.contains("content policy") || lower.contains("content_policy"))
            {
                return new AiException(Type.CONTENT_REJECTED, "内容被拒绝（" + statusCode + "）" + detail(body), -1L, snippet);
            }

            if (lower.contains("context") || lower.contains("maximum") || lower.contains("too long") || lower.contains("too many tokens"))
            {
                return new AiException(Type.CONTEXT_OVERFLOW, "上下文过长（" + statusCode + "）" + detail(body), -1L, snippet);
            }
        }

        /* 供应商的错误体（error.message）往往写明真实原因（模型不存在/参数非法），
         * 直接拼进主消息，别让用户只看到一个状态码 */
        return new AiException(Type.UNKNOWN, "请求失败（" + statusCode + "）" + detail(body), retryAfter, snippet);
    }

    /**
     * Pull the vendor's error.message (OpenAI/GLM style {"error":{...}} or flat
     * {"message":...}) out of an error body so it can travel in the main
     * message instead of hiding in the debug-only snippet.
     */
    private static String detail(String body)
    {
        String text = body == null ? "" : body.trim();

        if (text.isEmpty() || text.length() > 2000 || text.charAt(0) != '{')
        {
            return text.isEmpty() ? "" : "：" + text;
        }

        try
        {
            mchorse.bbs_mod.data.types.MapType map = mchorse.bbs_mod.data.DataToString.mapFromString(text);

            if (map != null)
            {
                if (map.has("error") && map.get("error").isMap())
                {
                    String message = map.get("error").asMap().getString("message");

                    if (!message.isEmpty())
                    {
                        return "：" + message;
                    }
                }

                if (map.has("message"))
                {
                    String message = map.getString("message");

                    if (!message.isEmpty())
                    {
                        return "：" + message;
                    }
                }
            }
        }
        catch (Exception ignored)
        {}

        return "";
    }

    private static long parseRetryAfter(String header)
    {
        if (header == null || header.isEmpty())
        {
            return -1L;
        }

        try
        {
            /* Seconds per HTTP spec; some gateways send millis - values over an
             * hour are treated as an HTTP date we don't bother parsing. */
            long value = Long.parseLong(header.trim());

            return value > 3600 ? -1L : value * 1000L;
        }
        catch (NumberFormatException e)
        {
            return -1L;
        }
    }

    private static String snippet(String body)
    {
        if (body == null)
        {
            return "";
        }

        String flat = body.replaceAll("\\s+", " ").trim();

        return flat.length() > 200 ? flat.substring(0, 200) : flat;
    }
}
