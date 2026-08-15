package com.supplymind.agent;

import com.supplymind.agent.application.AgentResponseVerifier;
import com.supplymind.agent.evidence.EvidencePackV1;
import com.supplymind.agent.llm.LLMService;
import com.supplymind.agent.orchestration.AgentOrchestrator;
import com.supplymind.agent.support.Day6R2Fixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DAY6 Round3 M3 STRICT structured claims contract: Phase B accepts ONLY the JSON envelope.
 * Plain free text, malformed JSON, missing/empty claims and a single bad claim reject the WHOLE
 * draft -> JAVA_TEMPLATE. Every number in a claim must be supported by the facts THAT claim
 * references; sourceNames[]/businessDates[] are verified explicitly.
 */
class Day6FinalStageStrictEnvelopeAttackTest {

    @TempDir
    Path temp;

    @Test
    void b_plainFreeTextEvenWithValuesAndRefsIsRejectedAsMalformed() {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "free-text");
        AgentOrchestrator.AgentResult result = fixture.orchestrator(
                request -> LLMService.LLMResponse.success("当前值 123.45678901 fact-0"),
                new AgentResponseVerifier(List.of())).answer(fixture.formalHistoryQuery());
        assertTrue(result.degraded());
        assertTrue(result.degradeReason().startsWith("MODEL_RESPONSE_REJECTED:MALFORMED_STRUCTURED_RESPONSE"),
                "reason=" + result.degradeReason());
        assertEquals("JAVA_TEMPLATE", result.report().generatedBy());
    }

    @Test
    void c_malformedJsonIsRejectedAsMalformed() {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "malformed-json");
        AgentOrchestrator.AgentResult result = fixture.orchestrator(
                request -> LLMService.LLMResponse.success("{not json"),
                new AgentResponseVerifier(List.of())).answer(fixture.formalHistoryQuery());
        assertTrue(result.degraded());
        assertTrue(result.degradeReason().contains("MALFORMED_STRUCTURED_RESPONSE"));
        assertEquals("JAVA_TEMPLATE", result.report().generatedBy());
    }

    @Test
    void d_missingOrEmptyClaimsAreRejectedAsMalformed() {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "no-claims");
        for (String envelope : List.of("{\"answer\":\"ok\"}", "{\"answer\":\"ok\",\"claims\":[]}")) {
            AgentOrchestrator.AgentResult result = fixture.orchestrator(
                    request -> LLMService.LLMResponse.success(envelope),
                    new AgentResponseVerifier(List.of())).answer(fixture.formalHistoryQuery());
            assertTrue(result.degraded(), "envelope must be rejected: " + envelope);
            assertTrue(result.degradeReason().contains("MALFORMED_STRUCTURED_RESPONSE"),
                    "reason=" + result.degradeReason());
            assertEquals("JAVA_TEMPLATE", result.report().generatedBy());
        }
    }

    @Test
    void e_oneBadClaimRejectsTheWholeDraftEvenWithGoodClaims() {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "mixed-claims");
        // A good claim (references fact-0) plus a claim without any reference: the whole draft
        // must be rejected - a bad claim is never skipped.
        AgentOrchestrator.AgentResult result = fixture.orchestrator(
                request -> LLMService.LLMResponse.success(
                        "{\"answer\":\"ok\",\"claims\":["
                                + "{\"claimId\":\"c1\",\"text\":\"ok\",\"factIds\":[\"fact-0\"],\"evidenceRefs\":[]},"
                                + "{\"claimId\":\"c2\",\"text\":\"ok\",\"factIds\":[],\"evidenceRefs\":[]}]}"),
                new AgentResponseVerifier(List.of())).answer(fixture.formalHistoryQuery());
        assertTrue(result.degraded());
        assertTrue(result.degradeReason().contains("MALFORMED_STRUCTURED_RESPONSE"),
                "reason=" + result.degradeReason());
        assertEquals("JAVA_TEMPLATE", result.report().generatedBy());
    }

    @Test
    void f_claimWithTwoValuesReferencingOnlyOneFactIsRejected() {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "two-values-one-fact");
        String[] pair = differentValuePair(fixture);
        // The second stated value is NOT supported by the referenced fact: the per-number
        // binding must reject the claim even though the first value is supported.
        AgentOrchestrator.AgentResult result = fixture.orchestrator(
                request -> LLMService.LLMResponse.success(
                        "{\"answer\":\"ok\",\"claims\":[{\"claimId\":\"c1\","
                                + "\"text\":\"值为 " + pair[0] + " 和 999999.999\","
                                + "\"factIds\":[\"" + pair[2] + "\"],\"evidenceRefs\":[]}]}"),
                new AgentResponseVerifier(List.of())).answer(fixture.formalHistoryQuery());
        assertTrue(result.degraded(),
                "a claim stating value B must not pass because value A is supported by its fact");
        assertTrue(result.degradeReason().contains("UNSUPPORTED_CLAIM_REFERENCE"),
                "reason=" + result.degradeReason());
        assertEquals("JAVA_TEMPLATE", result.report().generatedBy());
    }

    @Test
    void globalFactsNeverHelpAnUnreferencedClaim() {
        // Unit-level per-number isolation: value B EXISTS in the global fact set (fact-1) but the
        // claim only references fact-A - the global fact must never help the claim pass.
        AgentResponseVerifier verifier = new AgentResponseVerifier(List.of());
        EvidencePackV1 pack = new EvidencePackV1("AGENT-EVIDENCE-SCHEMA-V1", "pack-1", "req-1",
                "FORMAL", "q", Day6R2Fixture.AT,
                new EvidencePackV1.Scope(List.of(Day6R2Fixture.ITEM), "2026-08-10", null, null,
                        "Asia/Shanghai"),
                List.of(), List.of(
                        fact("fact-0", "1.00000000"),
                        fact("fact-1", "2.00000000")),
                List.of(), List.of(), List.of(), List.of());
        com.supplymind.agent.application.ModelDraftV1 draft =
                new com.supplymind.agent.application.ModelDraftV1("r1", "ok", List.of(
                        new com.supplymind.agent.application.ModelClaimV1(
                                "c1", "值为 1.00000000 和 2.00000000",
                                List.of("fact-0"), List.of())));
        com.supplymind.agent.application.AgentResponseVerifier.Verification v =
                verifier.verify(draft, pack);
        assertFalse(v.verified(),
                "value B supported only by a GLOBAL unreferenced fact must not help the claim");
        assertTrue("UNSUPPORTED_CLAIM_REFERENCE".equals(v.reason()), "reason=" + v.reason());
    }

    private static EvidencePackV1.Fact fact(String factId, String value) {
        return new EvidencePackV1.Fact(factId, "history.query", Day6R2Fixture.ITEM,
                "2026-08-10", null, null, value, "CNY/1 USD", "CNY", "true", "VERIFIED",
                "pboc-basic-validation-v1", "arithmetic-mean-v1", "weekday-asia-shanghai-v1",
                List.of("1"), "source", "fp", List.of("processed/daily/x.csv"));
    }

    @Test
    void g_claimWithTwoValuesReferencingBothFactsPasses() {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "two-values-two-facts");
        String[] pair = differentValuePair(fixture);
        AgentOrchestrator.AgentResult result = fixture.orchestrator(
                request -> LLMService.LLMResponse.success(
                        "{\"answer\":\"ok\",\"claims\":[{\"claimId\":\"c1\","
                                + "\"text\":\"值为 " + pair[0] + " 和 " + pair[1] + "\","
                                + "\"factIds\":[\"" + pair[2] + "\",\"" + pair[3] + "\"],"
                                + "\"evidenceRefs\":[]}]}"),
                new AgentResponseVerifier(List.of())).answer(fixture.formalHistoryQuery());
        assertFalse(result.degraded(),
                "each stated value supported by its own referenced fact must pass (reason="
                        + result.degradeReason() + ", pair=" + String.join(",", pair) + ")");
        assertEquals("LLM", result.report().generatedBy());
    }

    @Test
    void h_answerCorrectButClaimWrongRejectsWholeDraft() {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "claim-wrong");
        AgentOrchestrator.AgentResult result = fixture.orchestrator(
                request -> LLMService.LLMResponse.success(
                        "{\"answer\":\"ok\",\"claims\":[{\"claimId\":\"c1\","
                                + "\"text\":\"值为 999999.999\",\"factIds\":[\"fact-0\"],\"evidenceRefs\":[]}]}"),
                new AgentResponseVerifier(List.of())).answer(fixture.formalHistoryQuery());
        assertTrue(result.degraded(), "a wrong claim must reject even when the answer is fine");
        assertTrue(result.degradeReason().contains("UNSUPPORTED_CLAIM_REFERENCE"),
                "reason=" + result.degradeReason());
        assertEquals("JAVA_TEMPLATE", result.report().generatedBy());
    }

    @Test
    void i_claimCorrectButAnswerAddsFabricatedFactsRejectsWholeDraft() {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "answer-fabrication");
        AgentOrchestrator.AgentResult result = fixture.orchestrator(
                request -> LLMService.LLMResponse.success(
                        "{\"answer\":\"值为 999999.999\",\"claims\":[{\"claimId\":\"c1\","
                                + "\"text\":\"ok\",\"factIds\":[\"fact-0\"],\"evidenceRefs\":[]}]}"),
                new AgentResponseVerifier(List.of())).answer(fixture.formalHistoryQuery());
        assertTrue(result.degraded(),
                "an answer that adds a fabricated business fact must never be persisted");
        assertTrue(result.degradeReason().contains("FABRICATED_NUMBER")
                        || result.degradeReason().contains("UNSUPPORTED_CLAIM_REFERENCE"),
                "reason=" + result.degradeReason());
        assertEquals("JAVA_TEMPLATE", result.report().generatedBy());
        assertFalse(result.report().claims().toString().contains("999999.999"));
    }

    @Test
    void j_unknownSourceNameInStructuredClaimIsRejected() {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "unknown-source");
        AgentOrchestrator.AgentResult result = fixture.orchestrator(
                request -> LLMService.LLMResponse.success(
                        "{\"answer\":\"ok\",\"claims\":[{\"claimId\":\"c1\",\"text\":\"ok\","
                                + "\"factIds\":[\"fact-0\"],\"evidenceRefs\":[],"
                                + "\"sourceNames\":[\"fabricated source\"],\"businessDates\":[]}]}"),
                new AgentResponseVerifier(List.of())).answer(fixture.formalHistoryQuery());
        assertTrue(result.degraded(), "an unknown sourceName must reject the claim");
        assertTrue(result.degradeReason().contains("UNSUPPORTED_CLAIM_REFERENCE"),
                "reason=" + result.degradeReason());
        assertEquals("JAVA_TEMPLATE", result.report().generatedBy());
    }

    @Test
    void k_businessDateNotSupportedByReferencedFactIsRejected() {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "unsupported-date");
        AgentOrchestrator.AgentResult result = fixture.orchestrator(
                request -> LLMService.LLMResponse.success(
                        "{\"answer\":\"ok\",\"claims\":[{\"claimId\":\"c1\",\"text\":\"ok\","
                                + "\"factIds\":[\"fact-0\"],\"evidenceRefs\":[],"
                                + "\"sourceNames\":[],\"businessDates\":[\"2026-07-01\"]}]}"),
                new AgentResponseVerifier(List.of())).answer(fixture.formalHistoryQuery());
        assertTrue(result.degraded(),
                "a businessDate the referenced facts do not cover must reject the claim");
        assertTrue(result.degradeReason().contains("UNSUPPORTED_CLAIM_REFERENCE"),
                "reason=" + result.degradeReason());
        assertEquals("JAVA_TEMPLATE", result.report().generatedBy());
    }

    /** Returns [valueA, valueB, factIdA, factIdB] - two DIFFERENT fact values when present. */
    private static String[] differentValuePair(Day6R2Fixture fixture) {
        java.util.concurrent.atomic.AtomicReference<String[]> captured = new java.util.concurrent.atomic.AtomicReference<>();
        fixture.orchestrator(request -> {
            if (request.facts().size() >= 1) {
                LLMService.LlmFact first = request.facts().get(0);
                for (int index = 1; index < request.facts().size(); index++) {
                    LLMService.LlmFact other = request.facts().get(index);
                    if (!other.value().equals(first.value())) {
                        captured.set(new String[]{first.value(), other.value(),
                                "fact-0", "fact-" + index});
                        break;
                    }
                }
                if (captured.get() == null) {
                    // Single fact (or all-equal facts): both stated values are supported by fact-0.
                    captured.set(new String[]{first.value(), first.value(), "fact-0", "fact-0"});
                }
            }
            return LLMService.LLMResponse.success("{\"answer\":\"ok\",\"claims\":[]}");
        }, new AgentResponseVerifier(List.of())).answer(fixture.formalHistoryQuery());
        return captured.get() != null ? captured.get()
                : new String[]{"123.45678901", "123.45678901", "fact-0", "fact-0"};
    }
}
