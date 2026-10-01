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
                return new AiException(Type.CONTENT_REJECTED, "Content rejected (" + statusCode + ")", -1L, snippet);
            }

            if (lower.contains("context") || lower.contains("maximum") || lower.contains("too long") || lower.contains("too many tokens"))
            {
                return new AiException(Type.CONTEXT_OVERFLOW, "Context too long (" + statusCode + ")", -1L, snippet);
            }
        }

        return new AiException(Type.UNKNOWN, "Request failed (" + statusCode + ")", retryAfter, snippet);
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
