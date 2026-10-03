package mchorse.bbs_mod.ai;

import mchorse.bbs_mod.ai.plan.AnimationPlan;
import net.minecraft.client.MinecraftClient;

import java.util.function.Consumer;

/**
 * Plan requests with the spec's one-shot repair: a reply that fails the strict
 * {@link AnimationPlan} validation is retried ONCE with the violation spelled
 * back to the model; a second failure surfaces the readable error instead of
 * a silent fallback (copilot spec section 3 L1).
 *
 * <p>Both callbacks are marshalled onto the render thread: {@link AiClient}
 * invokes them from its background "BBS AI" thread, and UI consumers mutate
 * chat bubbles there — touching those lists while the render thread iterates
 * them is a guaranteed ConcurrentModificationException.</p>
 */
public class AiPlans
{
    public static void generatePlan(AiChatRequest request, java.util.function.BiConsumer<AnimationPlan, AiChatResponse> onSuccess, Consumer<AiException> onError)
    {
        attempt(request, onSuccess, onError, 0);
    }

    private static void attempt(AiChatRequest request, java.util.function.BiConsumer<AnimationPlan, AiChatResponse> onSuccess, Consumer<AiException> onError, int attempt)
    {
        AiClient.get().chat(request, (response) ->
            MinecraftClient.getInstance().execute(() -> accept(request, onSuccess, onError, attempt, response)),
            (error) -> MinecraftClient.getInstance().execute(() -> onError.accept(error)));
    }

    /** Runs on the render thread (marshalled by {@link #attempt}). */
    private static void accept(AiChatRequest request, java.util.function.BiConsumer<AnimationPlan, AiChatResponse> onSuccess, Consumer<AiException> onError, int attempt, AiChatResponse response)
    {
        try
        {
            onSuccess.accept(AnimationPlan.parse(response.content), response);
        }
        catch (AiException e)
        {
            if (attempt == 0)
            {
                AiChatRequest retry = new AiChatRequest(request.system, request.user
                    + "\n\nYour previous reply was rejected: " + e.getMessage()
                    + ". Reply again with ONLY the corrected JSON object.");

                retry.temperature(request.temperature);
                retry.maxTokens(request.maxTokens);
                retry.json(request.jsonMode);
                attempt(retry, onSuccess, onError, 1);

                return;
            }

            onError.accept(e);
        }
    }
}
