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

    @Test void slowReadOnlyToolTimesOutWithoutProducingFacts() throws Exception {
        var stopped=new CountDownLatch(1);long start=System.nanoTime();
        var budget=new RequestBudget(System::nanoTime,Duration.ofMillis(150),Duration.ofSeconds(2));
        var failure=assertThrows(RequestBudget.Exhausted.class,()->budget.readOnly(()->{
            try{new CountDownLatch(1).await();}catch(InterruptedException ex){stopped.countDown();}return "late unverified facts";
        }));
        assertEquals("LOCAL_FACTS_TIMEOUT",failure.getMessage());assertTrue(stopped.await(2,TimeUnit.SECONDS));
        assertTrue(System.nanoTime()-start<Duration.ofSeconds(2).toNanos());
        System.out.println("BUDGET_SLOW_TOOL_MS="+TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));
    }
    @Test void cancellationStopsWaitingAndForbidsPersistenceAdmission() throws Exception {
        String token="cancel-request-00000001";AgentCancellation.begin(token);
        try {
            var budget=new RequestBudget();var entered=new CountDownLatch(1);var stopped=new CountDownLatch(1);
            Thread canceller=new Thread(()->{try{entered.await();AgentCancellation.cancel(token);}catch(InterruptedException ex){Thread.currentThread().interrupt();}});
            canceller.start();
            assertThrows(RequestBudget.Exhausted.class,()->budget.readOnly(()->{entered.countDown();try{new CountDownLatch(1).await();}catch(InterruptedException ex){stopped.countDown();}return "late";}));
            assertTrue(stopped.await(2,TimeUnit.SECONDS));canceller.join(2000);assertFalse(canceller.isAlive());
            assertThrows(RequestBudget.Exhausted.class,budget::requireRemaining);
        }finally{AgentCancellation.end(token);}
    }
    @Test void httpTimeoutReceivesOnlyRemainingCloudBudget() {
        var time=new AtomicLong();var budget=new RequestBudget(time::get,Duration.ofSeconds(75),Duration.ofSeconds(85));
        time.set(Duration.ofSeconds(20).toNanos());
        var observed=new java.util.concurrent.atomic.AtomicReference<Duration>();
        budget.analyze(r->{observed.set(RequestBudget.cloudHttpTimeout(Duration.ofSeconds(90)));return LLMService.LLMResponse.success("ok");},request);
        assertEquals(Duration.ofSeconds(55),observed.get());
    }

    @Test void productionBudgetSlowModelReturnsSavedLocalReportWithinNinetySeconds() throws Exception {
        var fixture=com.supplymind.agent.support.Day6R2Fixture.create(temp,"full-budget");
        var calls=new java.util.concurrent.atomic.AtomicInteger();var interrupted=new CountDownLatch(1);
        var orchestrator=fixture.orchestrator(r->{calls.incrementAndGet();try{new CountDownLatch(1).await();}
            catch(InterruptedException ex){interrupted.countDown();}return LLMService.LLMResponse.success("late result");},
            new com.supplymind.agent.application.AgentResponseVerifier(List.of()),com.supplymind.agent.support.Day6R2Fixture.CLOCK);
        long started=System.nanoTime();
        var result=orchestrator.answer(new com.supplymind.agent.orchestration.AgentOrchestrator.AgentQueryInput(
            "请根据 2026-08-10 至 2026-08-10 的数据说明最新值和月均值",com.supplymind.agent.support.Day6R2Fixture.ITEM,
            null,null,null,null,null,null,null,"FORMAL"));
        long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
        assertEquals(1,calls.get());assertTrue(interrupted.await(2,TimeUnit.SECONDS));
        assertEquals("REQUEST_BUDGET_EXHAUSTED",result.degradeReason());assertEquals("JAVA_TEMPLATE",result.report().generatedBy());
        assertTrue(fixture.reportStore().read(result.reportRef()).ok());
        assertTrue(elapsed>=74000 && elapsed<90000,"actual request response ms="+elapsed);
        System.out.println("BUDGET_PRODUCTION_SLOW_MODEL_RESPONSE_MS="+elapsed);
    }
}
