package mchorse.bbs_mod.ai;

/**
 * One assistant reply. Token counts are best effort - local gateways
 * (Ollama / LM Studio / vLLM) sometimes omit usage entirely.
 */
public class AiChatResponse
{
    public final String content;

    public final String model;

    public final int promptTokens;

    public final int completionTokens;

    /** 思维链（GLM reasoning_content）：仅思考型模型且开启思考时才有内容 */
    public final String reasoning;

    public AiChatResponse(String content, String model, int promptTokens, int completionTokens)
    {
        this(content, model, promptTokens, completionTokens, "");
    }

    public AiChatResponse(String content, String model, int promptTokens, int completionTokens, String reasoning)
    {
        this.content = content == null ? "" : content;
        this.model = model == null ? "" : model;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.reasoning = reasoning == null ? "" : reasoning;
    }
}
