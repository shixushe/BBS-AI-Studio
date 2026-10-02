package mchorse.bbs_mod.ai;

import mchorse.bbs_mod.ai.plan.AnimationPlan;

import java.util.function.Consumer;

/**
 * Plan requests with the spec's one-shot repair: a reply that fails the strict
 * {@link AnimationPlan} validation is retried ONCE with the violation spelled
 * back to the model; a second failure surfaces the readable error instead of
 * a silent fallback (copilot spec section 3 L1).
 */
public class AiPlans
{
    public static void generatePlan(AiChatRequest request, java.util.function.BiConsumer<AnimationPlan, String> onSuccess, Consumer<AiException> onError)
    {
        attempt(request, onSuccess, onError, 0);
    }

    private static void attempt(AiChatRequest request, java.util.function.BiConsumer<AnimationPlan, String> onSuccess, Consumer<AiException> onError, int attempt)
    {
        AiClient.get().chat(request, (response) ->
        {
            try
            {
                onSuccess.accept(AnimationPlan.parse(response.content), response.reasoning);
            }
            catch (AiException e)
            {
                if (attempt == 0)
                {
                    AiChatRequest retry = new AiChatRequest(request.system, request.user
                        + "\n\nYour previous reply was rejected: " + e.getMessage()
                        + ". Reply again with ONLY the corrected JSON object.");

                    retry.temperature(request.temperature);
                    retry.json(request.jsonMode);
                    attempt(retry, onSuccess, onError, 1);

                    return;
                }

                onError.accept(e);
            }
        }, onError);
    }
}
