package com.supplymind.agent;

import com.supplymind.agent.application.AgentResponseVerifier;
import com.supplymind.agent.llm.LLMService;
import com.supplymind.agent.orchestration.AgentOrchestrator;
import com.supplymind.agent.report.AgentReportV1;
import com.supplymind.agent.support.Day6R2Fixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DAY6 final stage M3: the PRODUCTION Phase B path prefers the strict structured claims
 * envelope; each claim is verified individually (value B must be supported by the fact it
 * references - never by an unrelated reference), and only verified structured claims are
 * persisted into the AgentReport.
 */
class Day6FinalStageStructuredClaimsAttackTest {

    @TempDir
    Path temp;

    @Test
    void structuredClaimsAreAcceptedAndPersistedAsReportClaims() {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "structured-ok");
        AgentOrchestrator.AgentResult result = fixture.orchestrator(
                request -> LLMService.LLMResponse.success(envelope(request, firstFactValue(request))),
                new AgentResponseVerifier(List.of())).answer(fixture.formalHistoryQuery());

        assertFalse(result.degraded(), "a valid structured claims envelope must be accepted");
        assertEquals("LLM", result.report().generatedBy());
        assertFalse(result.report().claims().isEmpty(),
                "the verified structured claims must be persisted in the AgentReport");
        for (AgentReportV1.Claim claim : result.report().claims()) {
            assertFalse(claim.evidenceRefs().isEmpty(),
                    "a persisted structured claim must carry evidenceRefs");
        }
        assertTrue(result.report().claims().stream()
                        .anyMatch(claim -> claim.text().startsWith("当前值")),
                "the persisted claim must be the structured claim text");
    }

    @Test
    void phaseBReceivesExactFactIdentityAndBusinessMetadataFromTheEvidencePack() {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "structured-fact-contract");
        AtomicReference<LLMService.LlmFact> captured = new AtomicReference<>();
        AgentOrchestrator.AgentResult result = fixture.orchestrator(request -> {
            if (!request.toolCallingEnabled() && !request.facts().isEmpty()) {
                captured.set(request.facts().get(0));
            }
            return LLMService.LLMResponse.success(envelope(request, firstFactValue(request)));
        }, new AgentResponseVerifier(List.of())).answer(fixture.formalHistoryQuery());

        assertFalse(result.degraded());
        LLMService.LlmFact fact = captured.get();
        assertEquals("fact-0", fact.factId());
        assertFalse(fact.itemId().isBlank());
        assertFalse(fact.unit().isBlank());
        assertFalse(fact.currency().isBlank());
        assertFalse(fact.actualSourceName().isBlank());
        assertFalse(fact.evidenceRef().isBlank());
    }
    @Test
    void structuredClaimWithFabricatedNumberRejectsWholeDraft() {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "structured-fabricated");
        AgentOrchestrator.AgentResult result = fixture.orchestrator(
                request -> LLMService.LLMResponse.success(envelope(request,
                        "999999.999")),
                new AgentResponseVerifier(List.of())).answer(fixture.formalHistoryQuery());

        assertTrue(result.degraded(), "a fabricated number inside a structured claim must reject the draft");
        assertTrue(result.degradeReason() != null
                        && result.degradeReason().startsWith("MODEL_RESPONSE_REJECTED:"),
                "reason=" + result.degradeReason());
        assertTrue(result.degradeReason().contains("UNSUPPORTED_CLAIM_REFERENCE")
                        || result.degradeReason().contains("FABRICATED_NUMBER"),
                "reason=" + result.degradeReason());
        assertEquals("JAVA_TEMPLATE", result.report().generatedBy());
    }

    @Test
    void structuredClaimValueNotSupportedByItsOwnFactRejects() {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "structured-mismatch");
        // The claim references fact-0 but states fact-1's value: the unrelated reference must
        // never validate the claim (M3 cross-fact value swap attack).
        AgentOrchestrator.AgentResult result = fixture.orchestrator(
                request -> LLMService.LLMResponse.success(envelopeWithFact(request,
                        "fact-0", secondFactValue(request))),
                new AgentResponseVerifier(List.of())).answer(fixture.formalHistoryQuery());

        assertTrue(result.degraded(), "a claim stating a value its own fact does not support must reject");
        assertTrue(result.degradeReason() != null
                        && result.degradeReason().startsWith("MODEL_RESPONSE_REJECTED:"),
                "reason=" + result.degradeReason());
        assertTrue(result.degradeReason().contains("UNSUPPORTED_CLAIM_REFERENCE"),
                "reason=" + result.degradeReason());
    }

    @Test
    void structuredClaimWithUnknownFactReferenceRejects() {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "structured-unknown");
        AgentOrchestrator.AgentResult result = fixture.orchestrator(
                request -> LLMService.LLMResponse.success(
                        envelopeWithFact(request, "fact-999", "1.00000000")),
                new AgentResponseVerifier(List.of())).answer(fixture.formalHistoryQuery());

        assertTrue(result.degraded(), "an unknown factId inside a structured claim must reject");
        assertTrue(result.degradeReason() != null
                        && result.degradeReason().contains("UNKNOWN_FACT_REFERENCE"),
                "reason=" + result.degradeReason());
    }

    @Test
    void structuredClaimPercentageIsNeverEquatedToPlainNumber() {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "structured-percent");
        // The fact value is "123.45678901" with unit "CNY/1 USD" - a percentage token must not
        // silently match it.
        AgentOrchestrator.AgentResult result = fixture.orchestrator(
                request -> LLMService.LLMResponse.success(
                        envelopeWithFact(request, "fact-0", firstFactValue(request) + "%")),
                new AgentResponseVerifier(List.of())).answer(fixture.formalHistoryQuery());

        assertTrue(result.degraded(), "a percentage token must not match a non-percentage fact");
        assertTrue(result.degradeReason() != null
                        && result.degradeReason().contains("FABRICATED_NUMBER")
                        || result.degradeReason() != null
                        && result.degradeReason().contains("UNSUPPORTED_CLAIM_REFERENCE"),
                "reason=" + result.degradeReason());
    }

    @Test
    void structuredClaimDateMustBeSupportedByItsFact() {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "structured-date");
        // The referenced fact carries businessDate 2026-08-10; a claim stating a different date
        // must be rejected.
        AgentOrchestrator.AgentResult result = fixture.orchestrator(
                request -> LLMService.LLMResponse.success(envelopeWithFact(request,
                        "fact-0", "2026-08-01 " + firstFactValue(request))),
                new AgentResponseVerifier(List.of())).answer(fixture.formalHistoryQuery());

        assertTrue(result.degraded(), "a claim date unsupported by the referenced fact must reject");
        assertTrue(result.degradeReason() != null
                        && result.degradeReason().contains("UNSUPPORTED_CLAIM_REFERENCE"),
                "reason=" + result.degradeReason());
    }

    @Test
    void freeTextAnswerStillFullyGuardedAgainstFabrication() {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "free-text-guard");
        AgentOrchestrator.AgentResult result = fixture.orchestrator(
                request -> LLMService.LLMResponse.success("当前值 999999.999"),
                new AgentResponseVerifier(List.of())).answer(fixture.formalHistoryQuery());

        assertTrue(result.degraded(), "a free-text answer with a fabricated number must still reject");
        assertTrue(result.degradeReason() != null
                        && result.degradeReason().startsWith("MODEL_RESPONSE_REJECTED:"),
                "reason=" + result.degradeReason());
        assertEquals("JAVA_TEMPLATE", result.report().generatedBy());
    }

    private static String firstFactValue(LLMService.LLMRequest request) {
        return request.facts().isEmpty() ? "1.00000000" : request.facts().get(0).value();
    }

    private static String secondFactValue(LLMService.LLMRequest request) {
        for (LLMService.LlmFact fact : request.facts()) {
            if (!fact.value().equals(firstFactValue(request))) {
                return fact.value();
            }
        }
        return "2.00000000";
    }

    private static String envelope(LLMService.LLMRequest request, String value) {
        return envelopeWithFact(request, "fact-0", value);
    }

    private static String envelopeWithFact(LLMService.LLMRequest request, String factId, String value) {
        return "{\"answer\": \"answer\", \"claims\": [{\"claimId\": \"c1\", \"text\": \"当前值 "
                + value + "\", \"factIds\": [\"" + factId + "\"], \"evidenceRefs\": []}]}";
    }
}
