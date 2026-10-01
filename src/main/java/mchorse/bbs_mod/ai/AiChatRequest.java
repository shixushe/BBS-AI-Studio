package mchorse.bbs_mod.ai;

/**
 * A single chat completion request, backend agnostic. M0 keeps it to the
 * system + user pair; tool calling and streaming arrive with later milestones
 * on the same {@link AiTextBackend} contract.
 */
public class AiChatRequest
{
    public final String system;

    public final String user;

    /** Ask the backend for JSON-constrained output. Only sent when the model is known to support it. */
    public boolean jsonMode;

    public float temperature = 0.7F;

    /** Hard cap for a single completion; 0 means "backend default". */
    public int maxTokens;

    public AiChatRequest(String system, String user)
    {
        this.system = system;
        this.user = user;
    }

    public AiChatRequest json(boolean jsonMode)
    {
        this.jsonMode = jsonMode;

        return this;
    }

    public AiChatRequest temperature(float temperature)
    {
        this.temperature = temperature;

        return this;
    }

    public AiChatRequest maxTokens(int maxTokens)
    {
        this.maxTokens = maxTokens;

        return this;
    }
}
