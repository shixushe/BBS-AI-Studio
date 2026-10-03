package mchorse.bbs_mod.ai;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * The single entry point for AI calls. Responsibilities: build the request
 * from {@link AiSettings}, run it on a background thread, retry transient
 * failures (honoring {@code Retry-After}), support cancellation, and never
 * block the render thread or a tick.
 *
 * <p>Threading contract: callbacks are invoked on the "BBS AI" worker thread.
 * Whoever touches game state or UI must marshal back to the client thread
 * themselves - the same rule {@code CDNAssetSyncService} follows. Callbacks
 * must not fire HTTP requests of their own.</p>
 */
public class AiClient
{
    private static final AiClient INSTANCE = new AiClient();

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "BBS AI");

        thread.setDaemon(true);

        return thread;
    });

    private volatile Future<?> pending;

    public static AiClient get()
    {
        return INSTANCE;
    }

    /**
     * Fire a chat completion asynchronously. Exactly one of the two callbacks
     * is invoked. Retries only transient failures (rate limit / timeout /
     * network); auth, context overflow and content rejection fail immediately
     * because retrying them just burns the user's quota.
     */
    public void chat(AiChatRequest request, Consumer<AiChatResponse> onSuccess, Consumer<AiException> onError)
    {
        this.start(() -> {
            AiTextBackend backend = AiSettings.createBackend();
            int maxRetries = AiSettings.maxRetries.get();
            AiException failure = null;
            AiChatResponse response = null;

            for (int attempt = 0; attempt <= maxRetries; attempt++)
            {
                try
                {
                    response = backend.chat(request);

                    /* 用量统计：所有成功调用在此汇总（后台线程，同步块安全） */
                    AiSettings.recordUsage(response.promptTokens, response.completionTokens);

                    failure = null;

                    break;
                }
                catch (AiException e)
                {
                    if (e.type == AiException.Type.CANCELLED)
                    {
                        onError.accept(e);

                        return;
                    }

                    failure = e;

                    if (!e.isRetryable() || attempt == maxRetries)
                    {
                        break;
                    }

                    this.sleepBeforeRetry(e);
                }
            }

            if (failure != null)
            {
                onError.accept(failure);
            }
            else if (response != null)
            {
                onSuccess.accept(response);
            }
            else
            {
                onError.accept(new AiException(AiException.Type.UNKNOWN, "Backend returned no response"));
            }
        });
    }

    /**
     * M0 acceptance check: with a configured backend, ask the model to echo a
     * fixed word. Reports NOT_CONFIGURED through the error callback instead of
     * hitting the network.
     */
    public void testConnection(Consumer<AiChatResponse> onSuccess, Consumer<AiException> onError)
    {
        if (!AiSettings.isConfigured())
        {
            onError.accept(new AiException(AiException.Type.NOT_CONFIGURED, "AI backend is not configured"));

            return;
        }

        AiChatRequest request = new AiChatRequest("You are a connectivity test. Reply with the single word: pong", "ping");

        this.chat(request, onSuccess, onError);
    }

    /** Interrupt the in-flight call, if any. Results of a cancelled call are discarded. */
    public void cancel()
    {
        Future<?> future = this.pending;

        if (future != null && !future.isDone())
        {
            future.cancel(true);
        }
    }

    public boolean isBusy()
    {
        Future<?> future = this.pending;

        return future != null && !future.isDone();
    }

    private synchronized void start(Runnable task)
    {
        this.pending = this.executor.submit(task);
    }

    private void sleepBeforeRetry(AiException e)
    {
        try
        {
            long ms = e.retryAfterMs > 0 ? e.retryAfterMs : 1000L;

            Thread.sleep(Math.min(ms, 15000L));
        }
        catch (InterruptedException ie)
        {
            Thread.currentThread().interrupt();
        }
    }

    /** Kept for callers that need the exception cause flattened; not used on the happy path. */
    private static Throwable unwrap(ExecutionException e)
    {
        return e.getCause() != null ? e.getCause() : e;
    }
}
