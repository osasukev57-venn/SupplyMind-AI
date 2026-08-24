package com.supplymind.agent;

import com.supplymind.agent.application.AgentResponseVerifier;
import com.supplymind.agent.llm.LLMService;
import com.supplymind.agent.orchestration.AgentOrchestrator;
import com.supplymind.agent.support.Day6R2Fixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AgentFactFirstPipelineTest {

    @TempDir
    Path temp;

    @Test
    void javaBuildsStandardFactsBeforeSingleCloudExplanationAndAutoBindsEvidence() {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "fact-first");
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<LLMService.LLMRequest> captured = new AtomicReference<>();
        AgentOrchestrator orchestrator = fixture.orchestrator(request -> {
            calls.incrementAndGet();
            captured.set(request);
            assertFalse(request.toolCallingEnabled(), "cloud may only explain Java-owned facts");
            LLMService.LlmFact latest = fact(request, "latest published value", null);
            LLMService.LlmFact average = fact(request, "persisted period average",
                    "2026-08-01~2026-08-31");
            LLMService.LlmFact rowCount = fact(request, "published row count", null);
            LLMService.LlmFact completeness =
                    fact(request, "Java-computed completeness percentage", null);
            String json = """
                    {"answer":"可信事实说明","claims":[
                      {"claimId":"latest","text":"最新已发布值为 %s %s，业务日期为 %s。",
                       "factIds":["%s"],"evidenceRefs":[],"sourceNames":[],"businessDates":["%s"]},
                      {"claimId":"average","text":"持久化月均值为 %s。",
                       "factIds":["%s"],"evidenceRefs":[],"sourceNames":[],"businessDates":[]},
                      {"claimId":"quality","text":"已发布 %s 条，完整率为 %s。",
                       "factIds":["%s","%s"],"evidenceRefs":[],"sourceNames":[],"businessDates":[]},
                      {"claimId":"source","text":"实际来源为 %s。",
                       "factIds":["%s"],"evidenceRefs":[],"sourceNames":["%s"],"businessDates":[]}
                    ]}
                    """.formatted(
                    latest.value(), latest.unit(), latest.businessDate(), latest.factId(),
                    latest.businessDate(), average.value(), average.factId(),
                    rowCount.value(), completeness.value(), rowCount.factId(), completeness.factId(),
                    latest.actualSourceName(), latest.factId(), latest.actualSourceName());
            return LLMService.LLMResponse.success(json);
        }, new AgentResponseVerifier(List.of()));

        AgentOrchestrator.AgentResult result = orchestrator.answer(new AgentOrchestrator.AgentQueryInput(
                "请根据 2026-08-10 至 2026-08-10 的数据说明最新值、单位、月均值、实际来源和数据质量",
                Day6R2Fixture.ITEM, null, null, null, null, null, null, null, "FORMAL"));

        assertEquals(1, calls.get(), "fact-first flow makes exactly one cloud call");
        assertNotNull(captured.get());
        assertTrue(captured.get().facts().stream()
                .anyMatch(fact -> "latest published value".equals(fact.statement())));
        assertTrue(captured.get().facts().stream()
                .anyMatch(fact -> "persisted period average".equals(fact.statement())));
        assertTrue(captured.get().facts().stream()
                .anyMatch(fact -> "Java-computed completeness percentage".equals(fact.statement())
                        && "100%".equals(fact.value())));
        assertFalse(result.degraded(), "valid fact-selected language must pass");
        assertEquals("LLM", result.report().generatedBy());
        assertEquals(4, result.report().claims().size());
        assertTrue(result.report().claims().stream()
                .allMatch(claim -> !claim.evidenceRefs().isEmpty()),
                "Java must bind evidence from cited factIds");
    }

    @Test
    void absentStandardFactsBlockCloudCall() {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "not-ready");
        AtomicInteger calls = new AtomicInteger();
        AgentOrchestrator orchestrator = fixture.orchestrator(request -> {
            calls.incrementAndGet();
            return LLMService.LLMResponse.success("{}");
        }, new AgentResponseVerifier(List.of()));

        AgentOrchestrator.AgentResult result = orchestrator.answer(new AgentOrchestrator.AgentQueryInput(
                "说明 2025-01-01 至 2025-01-02 的最新值",
                Day6R2Fixture.ITEM, null, null, null, null, null, null, null, "FORMAL"));

        assertEquals(0, calls.get(), "no verified standard fact means no cloud request");
        assertTrue(result.degraded());
        assertTrue(result.degradeReason().startsWith("STANDARD_FACTS_NOT_READY:"));
        assertEquals("JAVA_TEMPLATE", result.report().generatedBy());
    }

    private static LLMService.LlmFact fact(
            LLMService.LLMRequest request, String statement, String period
    ) {
        return request.facts().stream()
                .filter(fact -> statement.equals(fact.statement()))
                .filter(fact -> period == null || period.equals(fact.period()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing standard fact: " + statement));
    }
}
