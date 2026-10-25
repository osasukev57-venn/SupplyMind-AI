package com.supplymind.agent.llm;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
class RequestBudgetTest {
    private final LLMService.LLMRequest request = new LLMService.LLMRequest("q", "question", "FORMAL", List.of(), List.of(), false);
    @Test void elapsedLocalWorkAndRepeatedCallsShareOneBudget() {
        AtomicLong time = new AtomicLong();
        var budget = new RequestBudget(time::get, Duration.ofNanos(75), Duration.ofNanos(85));
        time.set(75);
        LLMService.Port forbidden = r -> { fail("expired request must not call cloud"); return null; };
        assertEquals("REQUEST_BUDGET_EXHAUSTED", budget.analyze(forbidden, request).failureKind());
        assertEquals("REQUEST_BUDGET_EXHAUSTED", budget.analyze(forbidden, request).failureKind());
        budget.requireRemaining(); // local fallback has its reserved time
        time.set(85);
        assertThrows(IllegalStateException.class, budget::requireRemaining);
    }
    @Test void slowModelIsInterruptedAndCannotReturnALateSuccess() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        var budget = new RequestBudget(System::nanoTime, Duration.ofMillis(150), Duration.ofSeconds(2));
        var response = budget.analyze(r -> {
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException ex) { interrupted.countDown(); Thread.currentThread().interrupt(); }
            return LLMService.LLMResponse.success("late");
        }, request);
        assertEquals("REQUEST_BUDGET_EXHAUSTED", response.failureKind());
        assertTrue(interrupted.await(2, TimeUnit.SECONDS));
    }
    @Test void fastModelResultIsPreserved() {
        var response = new RequestBudget().analyze(r -> LLMService.LLMResponse.success("exact"), request);
        assertEquals("exact", response.explanation());
    }

    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temp;
    @Test void timedOutModelProducesOneHonestLocalReport() throws Exception {
        var fixture = com.supplymind.agent.support.Day6R2Fixture.create(temp, "budget-fallback");
        CountDownLatch interrupted = new CountDownLatch(1);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var orchestrator = fixture.orchestrator(r -> new RequestBudget(System::nanoTime,
                Duration.ofMillis(150), Duration.ofSeconds(2)).analyze(slow -> {
                    calls.incrementAndGet();
                    try { new CountDownLatch(1).await(); }
                    catch (InterruptedException ex) { interrupted.countDown(); }
                    return LLMService.LLMResponse.success("late unverified prose");
                }, r), new com.supplymind.agent.application.AgentResponseVerifier(List.of()),
                com.supplymind.agent.support.Day6R2Fixture.CLOCK);
        var result = orchestrator.answer(new com.supplymind.agent.orchestration.AgentOrchestrator.AgentQueryInput(
                "分析历史", com.supplymind.agent.support.Day6R2Fixture.ITEM,
                "2026-08-10", "2026-08-10", "month", "2026-08", "2026-08", null, null, "FORMAL"));
        assertEquals(1, calls.get(), "test must reach the slow cloud branch");
        assertTrue(result.degraded());
        assertEquals("REQUEST_BUDGET_EXHAUSTED", result.degradeReason());
        assertEquals("JAVA_TEMPLATE", result.report().generatedBy());
        assertTrue(interrupted.await(2, TimeUnit.SECONDS));
        try(var paths = java.nio.file.Files.walk(temp)) {
            assertEquals(1, paths.filter(p -> p.toString().replace('\\', '/').contains("/report/") && p.toString().endsWith(".json")
                    && !p.toString().endsWith(".manifest.json")).count());
        }
    }
}
