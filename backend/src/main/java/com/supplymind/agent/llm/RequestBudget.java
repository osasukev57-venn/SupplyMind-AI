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
    private static final ThreadLocal<RequestBudget> CLOUD_CONTEXT = new ThreadLocal<>();
    private final java.util.concurrent.atomic.AtomicBoolean cancelled = AgentCancellation.current();
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
        if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new Exhausted("REQUEST_CANCELLED");
        if (nanoTime.getAsLong() - started >= totalLimit)
            throw new Exhausted("AGENT_REQUEST_BUDGET_EXHAUSTED");
    }
    public LLMService.LLMResponse analyze(LLMService.Port port, LLMService.LLMRequest request) {
        requireRemaining();
        long remaining = cloudLimit - (nanoTime.getAsLong() - started);
        if (remaining <= 0) return failure("REQUEST_BUDGET_EXHAUSTED");
        Future<LLMService.LLMResponse> future;
        try { future = CLOUD.submit(() -> {
            CLOUD_CONTEXT.set(this);
            try { requireRemaining(); return port.analyze(request); } finally { CLOUD_CONTEXT.remove(); }
        }); }
        catch (RejectedExecutionException ex) { return failure("CLOUD_CAPACITY_EXHAUSTED"); }
        try {
            var response = await(future, cloudLimit);
            return nanoTime.getAsLong() - started >= cloudLimit ? failure("REQUEST_BUDGET_EXHAUSTED") : response;
        }
        catch (Exhausted ex) { future.cancel(true); throw ex; }
        catch (TimeoutException ex) { future.cancel(true); return failure("REQUEST_BUDGET_EXHAUSTED"); }
        catch (InterruptedException ex) {
            future.cancel(true); Thread.currentThread().interrupt(); return failure("REQUEST_INTERRUPTED");
        } catch (ExecutionException ex) { return failure("CLOUD_EXECUTION_FAILED"); }
    }
    /** Read-only tools are bounded too; expired work cannot reach report persistence. */
    public <T> T readOnly(Callable<T> work) {
        requireRemaining();
        Future<T> future;
        try { future=CLOUD.submit(work); }
        catch(RejectedExecutionException ex){throw new Exhausted("READ_CAPACITY_EXHAUSTED");}
        try { return await(future, Math.min(cloudLimit, Duration.ofSeconds(30).toNanos())); }
        catch(InterruptedException ex){future.cancel(true);Thread.currentThread().interrupt();throw new Exhausted("REQUEST_CANCELLED");}
        catch(TimeoutException ex){future.cancel(true);throw new Exhausted("LOCAL_FACTS_TIMEOUT");}
        catch(Exhausted ex){future.cancel(true);throw ex;}
        catch(ExecutionException ex){if(ex.getCause() instanceof RuntimeException runtime)throw runtime;throw new IllegalStateException("READ_FAILED",ex.getCause());}
    }
    private <T> T await(Future<T> future,long limit) throws InterruptedException,ExecutionException,TimeoutException {
        while(true) {
            requireRemaining();long remaining=limit-(nanoTime.getAsLong()-started);
            if(remaining<=0)throw new TimeoutException();
            try {return future.get(Math.min(remaining,TimeUnit.MILLISECONDS.toNanos(100)),TimeUnit.NANOSECONDS);}
            catch(TimeoutException waiting){if(limit-(nanoTime.getAsLong()-started)<=0)throw waiting;}
        }
    }
    public static Duration cloudHttpTimeout(Duration configured) {
        var budget=CLOUD_CONTEXT.get(); if(budget==null)return configured;
        budget.requireRemaining();long remaining=budget.cloudLimit-(budget.nanoTime.getAsLong()-budget.started);
        if(remaining<=0)throw new Exhausted("CLOUD_DEADLINE_EXHAUSTED");
        return Duration.ofNanos(Math.min(configured.toNanos(),remaining));
    }
    public static final class Exhausted extends IllegalStateException {
        public Exhausted(String code){super(code);}
    }
    private static LLMService.LLMResponse failure(String reason) {
        return LLMService.LLMResponse.failure(LLMService.LLMStatus.UNAVAILABLE, reason);
    }
}
