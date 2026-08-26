package com.supplymind.agent;

import com.supplymind.agent.application.AgentResponseVerifier;
import com.supplymind.agent.application.ModelDraftV1;
import com.supplymind.agent.evidence.EvidencePackV1;
import com.supplymind.agent.llm.LLMService;
import com.supplymind.agent.orchestration.AgentOrchestrator;
import com.supplymind.agent.support.Day6R2Fixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
                    {"answerTemplate":"最新情况：{{%s}}。月度表现：{{%s}}。数据质量：{{%s}}，{{%s}}。",
                     "usedFactIds":["%s","%s","%s","%s"]}
                    """.formatted(
                    latest.factId(), average.factId(), rowCount.factId(), completeness.factId(),
                    latest.factId(), average.factId(), rowCount.factId(), completeness.factId());
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
                .allMatch(fact -> Day6R2Fixture.ITEM.equals(fact.itemId())),
                "every cloud-visible fact must retain the required authoritative itemId");
        assertTrue(captured.get().facts().stream()
                .anyMatch(fact -> "persisted period average".equals(fact.statement())));
        assertTrue(captured.get().facts().stream()
                .anyMatch(fact -> "Java-computed completeness percentage".equals(fact.statement())
                        && "100%".equals(fact.value())));
        assertFalse(result.degraded(), "valid fact-selected language must pass");
        assertEquals("LLM", result.report().generatedBy());
        LLMService.LlmFact renderedLatest = fact(captured.get(), "latest published value", null);
        LLMService.LlmFact renderedAverage = fact(
                captured.get(), "persisted period average", "2026-08-01~2026-08-31");
        assertTrue(result.report().claims().get(0).text().contains(renderedLatest.value()));
        assertTrue(result.report().claims().get(0).text().contains(renderedAverage.value()));
        assertFalse(result.report().claims().get(0).text().contains("{{fact-"));
        assertEquals(1, result.report().claims().size());
        assertTrue(result.report().claims().stream()
                .allMatch(claim -> !claim.evidenceRefs().isEmpty()),
                "Java must bind evidence from cited factIds");
    }

    @Test
    void templateMayUseNaturalTrendWordingWhenSelectedJavaFactSupportsIt() throws Exception {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "supported-trend-prose");
        EvidencePackV1.Fact base = fixture.verifiedEvidencePack("req-supported-trend").facts().get(0);
        EvidencePackV1.Fact upward = new EvidencePackV1.Fact(
                "fact-trend", "history.trend.direction", base.itemId(), null,
                "2026-08-01", "2026-08-24", "UPWARD", "direction", base.currency(),
                base.qualityStatus(), base.validationStatus(), base.validationVersion(),
                base.calculationVersion(), base.calendarVersion(), base.configVersions(),
                base.actualSourceName(), base.sourceFingerprint(), base.evidenceRefs());
        var method = AgentOrchestrator.class.getDeclaredMethod(
                "controlledShellSemanticsSupported", String.class, List.class);
        method.setAccessible(true);

        assertEquals(true, method.invoke(null, "从已发布数据看，整体呈上升趋势。", List.of(upward)));
        assertEquals(false, method.invoke(null, "从已发布数据看，整体呈下降趋势。", List.of(upward)));
        assertEquals(false, method.invoke(null, "整体呈上升趋势。", List.of(base)));
    }
    @Test
    void templateCannotSmuggleAnUnbackedBusinessNumber() {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "template-number-attack");
        AgentOrchestrator orchestrator = fixture.orchestrator(request -> {
            LLMService.LlmFact latest = fact(request, "latest published value", null);
            return LLMService.LLMResponse.success("""
                    {"answerTemplate":"{{%s}}，并额外声称数值为 999999.999。",
                     "usedFactIds":["%s"]}
                    """.formatted(latest.factId(), latest.factId()));
        }, new AgentResponseVerifier(List.of()));

        AgentOrchestrator.AgentResult result = orchestrator.answer(new AgentOrchestrator.AgentQueryInput(
                "说明最新值", Day6R2Fixture.ITEM, null, null, null,
                null, null, null, null, "FORMAL"));

        assertTrue(result.degraded());
        assertEquals("MODEL_RESPONSE_REJECTED:INVALID_FACT_TEMPLATE", result.degradeReason());
        assertEquals("JAVA_TEMPLATE", result.report().generatedBy());
    }

    @Test
    void javaRenderedWarningIdentifierIsNotMisreadAsFabricatedNumbers() {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "rendered-warning-id");
        EvidencePackV1 base = fixture.verifiedEvidencePack("req-rendered-warning");
        EvidencePackV1.Fact original = base.facts().get(0);
        EvidencePackV1.Fact warningId = new EvidencePackV1.Fact(
                "fact-warning-id", "warning.id", original.itemId(), original.businessDate(),
                original.periodStart(), original.periodEnd(),
                "a8d41a2647344a7cd4d6c0a1095e55dc", "id", original.currency(),
                original.qualityStatus(), original.validationStatus(), original.validationVersion(),
                original.calculationVersion(), original.calendarVersion(), original.configVersions(),
                original.actualSourceName(), original.sourceFingerprint(), original.evidenceRefs());
        EvidencePackV1 pack = new EvidencePackV1(
                base.schemaVersion(), base.evidencePackId(), base.requestId(), base.mode(),
                base.question(), base.createdAt(), base.scope(), base.toolExecutions(),
                List.of(warningId), base.evidenceRefs(), base.warnings(), base.notices(),
                base.limitations());
        ModelDraftV1 rendered = new ModelDraftV1(
                base.requestId(), "预警编号为a8d41a2647344a7cd4d6c0a1095e55dc",
                List.of(warningId.factId()), List.of());

        AgentResponseVerifier.Verification result = new AgentResponseVerifier(List.of())
                .verifyJavaRenderedFactTemplate(rendered, pack);

        assertTrue(result.verified(),
                "Java-rendered immutable identifiers must not be split into fabricated numbers");
    }

    @Test
    void templateShellCannotStateTrendOutsideJavaPlaceholder() {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "template-trend-attack");
        AgentOrchestrator orchestrator = fixture.orchestrator(request -> {
            LLMService.LlmFact latest = fact(request, "latest published value", null);
            return LLMService.LLMResponse.success("""
                    {"answerTemplate":"{{%s}}，整体呈下降趋势。",
                     "usedFactIds":["%s"]}
                    """.formatted(latest.factId(), latest.factId()));
        }, new AgentResponseVerifier(List.of()));

        AgentOrchestrator.AgentResult result = orchestrator.answer(new AgentOrchestrator.AgentQueryInput(
                "说明最新值", Day6R2Fixture.ITEM, null, null, null,
                null, null, null, null, "FORMAL"));

        assertTrue(result.degraded());
        assertEquals("MODEL_RESPONSE_REJECTED:INVALID_FACT_TEMPLATE", result.degradeReason());
        assertEquals("JAVA_TEMPLATE", result.report().generatedBy());
    }

    @Test
    void templateFactSetMustExactlyMatchUsedFactIds() {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "template-ref-attack");
        AgentOrchestrator orchestrator = fixture.orchestrator(request -> {
            LLMService.LlmFact latest = fact(request, "latest published value", null);
            return LLMService.LLMResponse.success("""
                    {"answerTemplate":"{{%s}}。",
                     "usedFactIds":["%s","fact-unknown"]}
                    """.formatted(latest.factId(), latest.factId()));
        }, new AgentResponseVerifier(List.of()));

        AgentOrchestrator.AgentResult result = orchestrator.answer(new AgentOrchestrator.AgentQueryInput(
                "说明最新值", Day6R2Fixture.ITEM, null, null, null,
                null, null, null, null, "FORMAL"));

        assertTrue(result.degraded());
        assertEquals("MODEL_RESPONSE_REJECTED:INVALID_FACT_TEMPLATE", result.degradeReason());
        assertEquals("JAVA_TEMPLATE", result.report().generatedBy());
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


    @Test
    void exactItemWithoutDatesUsesCurrentShanghaiMonthAndBuildsFacts() {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "default-month");
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<LLMService.LLMRequest> captured = new AtomicReference<>();
        Clock clock = Clock.fixed(Instant.parse("2026-08-24T03:00:00Z"), ZoneOffset.UTC);
        AgentOrchestrator orchestrator = fixture.orchestrator(request -> {
            calls.incrementAndGet();
            captured.set(request);
            return LLMService.LLMResponse.failure(LLMService.LLMStatus.UNAVAILABLE, "test-fallback");
        }, new AgentResponseVerifier(List.of()), clock);

        AgentOrchestrator.AgentResult result = orchestrator.answer(
                new AgentOrchestrator.AgentQueryInput(
                        Day6R2Fixture.ITEM, Day6R2Fixture.ITEM,
                        null, null, null, null, null, null, null, "FORMAL"));

        assertEquals(1, calls.get(), "current-month facts must reach the cloud explanation phase");
        assertNotNull(captured.get());
        assertTrue(captured.get().facts().stream()
                .anyMatch(fact -> "latest published value".equals(fact.statement())));
        assertTrue(captured.get().facts().stream()
                .allMatch(fact -> Day6R2Fixture.ITEM.equals(fact.itemId())),
                "every cloud-visible fact must retain the required authoritative itemId");
        assertTrue(result.evidencePack().toolExecutions().stream()
                .anyMatch(tool -> "history.query".equals(tool.toolName())
                        && String.valueOf(tool.input()).contains("range=2026-08-01..2026-08-24")));
        assertFalse(result.degradeReason().startsWith("STANDARD_FACTS_NOT_READY:"));
        String fallbackText = result.report().claims().get(0).text();
        assertTrue(fallbackText.contains("最新值"));
        assertFalse(fallbackText.contains("raw/"), "raw evidence paths belong in evidenceRefs, not prose");
        assertFalse(fallbackText.contains("published daily value"), "fallback must not enumerate internal fact types");
        assertTrue(fallbackText.length() < 700, "fallback must stay readable instead of dumping all facts");
    }

    @Test
    void omittedItemIdCanResolveAnExactItemMentionBeforeHistoryQuery() {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "question-resolve");
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<LLMService.LLMRequest> captured = new AtomicReference<>();
        Clock clock = Clock.fixed(Instant.parse("2026-08-24T03:00:00Z"), ZoneOffset.UTC);
        AgentOrchestrator orchestrator = fixture.orchestrator(request -> {
            calls.incrementAndGet();
            captured.set(request);
            return LLMService.LLMResponse.failure(LLMService.LLMStatus.UNAVAILABLE, "test-fallback");
        }, new AgentResponseVerifier(List.of()), clock);

        AgentOrchestrator.AgentResult result = orchestrator.answer(
                new AgentOrchestrator.AgentQueryInput(
                        "分析 " + Day6R2Fixture.ITEM, null,
                        null, null, null, null, null, null, null, "FORMAL"));

        assertEquals(1, calls.get());
        assertNotNull(captured.get());
        assertTrue(captured.get().facts().stream()
                .anyMatch(fact -> "latest published value".equals(fact.statement())));
        assertTrue(captured.get().facts().stream()
                .allMatch(fact -> Day6R2Fixture.ITEM.equals(fact.itemId())),
                "every cloud-visible fact must retain the required authoritative itemId");
        assertEquals(List.of(Day6R2Fixture.ITEM), result.evidencePack().scope().itemIds());
        assertFalse(result.degradeReason().startsWith("STANDARD_FACTS_NOT_READY:"));
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
