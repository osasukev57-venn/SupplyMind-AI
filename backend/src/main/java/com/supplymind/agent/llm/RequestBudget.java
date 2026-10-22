package com.supplymind.agent.llm;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** One monotonic deadline per request. Only read-only model work runs on the bounded workers. */
public final class RequestBudget {
    private static final ExecutorService CLOUD = new ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), runnable -> {
                Thread thread = new Thread(runnable, "agent-cloud-budget");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final LongSupplier nanoTime;
    private final long started;
    private final long cloudLimit;
    private final long totalLimit;

    public RequestBudget() { this(System::nanoTime, Duration.ofSeconds(75), Duration.ofSeconds(85)); }
    RequestBudget(LongSupplier nanoTime, Duration cloudLimit, Duration totalLimit) {
        this.nanoTime = nanoTime;
        this.started = nanoTime.getAsLong();
        this.cloudLimit = cloudLimit.toNanos();
        this.totalLimit = totalLimit.toNanos();
    }
    public void requireRemaining() {
        if (nanoTime.getAsLong() - started >= totalLimit)
            throw new IllegalStateException("AGENT_REQUEST_BUDGET_EXHAUSTED");
    }
    public LLMService.LLMResponse analyze(LLMService.Port port, LLMService.LLMRequest request) {
        long remaining = cloudLimit - (nanoTime.getAsLong() - started);
        if (remaining <= 0) return failure("REQUEST_BUDGET_EXHAUSTED");
        Future<LLMService.LLMResponse> future;
        try { future = CLOUD.submit(() -> port.analyze(request)); }
        catch (RejectedExecutionException ex) { return failure("CLOUD_CAPACITY_EXHAUSTED"); }
        try {
            var response = future.get(remaining, TimeUnit.NANOSECONDS);
            return nanoTime.getAsLong() - started >= cloudLimit ? failure("REQUEST_BUDGET_EXHAUSTED") : response;
        }
        catch (TimeoutException ex) { future.cancel(true); return failure("REQUEST_BUDGET_EXHAUSTED"); }
        catch (InterruptedException ex) {
            future.cancel(true); Thread.currentThread().interrupt(); return failure("REQUEST_INTERRUPTED");
        } catch (ExecutionException ex) { return failure("CLOUD_EXECUTION_FAILED"); }
    }
    private static LLMService.LLMResponse failure(String reason) {
        return LLMService.LLMResponse.failure(LLMService.LLMStatus.UNAVAILABLE, reason);
    }
}
