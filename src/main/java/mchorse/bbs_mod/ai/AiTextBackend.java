package mchorse.bbs_mod.ai;

/**
 * A chat-completions text backend. Image generation lives on a separate
 * abstraction ({@code AiImageBackend}, M7) because auth, rate limiting, error
 * codes and billing units differ - never merge the two into one interface.
 *
 * <p>Implementations must be thread safe and must never block the calling
 * thread longer than the request timeout: {@link AiClient} invokes them on a
 * background executor only.</p>
 */
public interface AiTextBackend
{
    /** Stable id used by {@code AiSettings.provider} ("openai_compatible", "anthropic", "gemini"). */
    public String id();

    /**
     * Run one chat completion. Throws {@link AiException} with a typed failure
     * instead of returning null; transport details (status codes, headers) are
     * classified inside.
     */
    public AiChatResponse chat(AiChatRequest request) throws AiException;
}
