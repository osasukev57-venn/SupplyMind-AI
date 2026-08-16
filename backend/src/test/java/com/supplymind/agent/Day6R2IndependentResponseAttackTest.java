package com.supplymind.agent;

import com.supplymind.agent.application.AgentResponseVerifier;
import com.supplymind.agent.llm.LLMService;
import com.supplymind.agent.orchestration.AgentOrchestrator;
import com.supplymind.agent.support.Day6R2Fixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Independent M1/M4 attacks against final AgentReport persistence, not just fact summaries. */
class Day6R2IndependentResponseAttackTest {

    @TempDir
    Path temp;

    @Test
    void fabricatedFormalNumberIsRejectedBeforeClaimAnswerAndPersistedReport() throws Exception {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "fabricated");
        // M3 strict contract: the attack must be carried INSIDE the JSON claims envelope - a
        // fabricated number in a claim is rejected claim-by-claim (the plain free-text variant
        // is now rejected earlier as MALFORMED_STRUCTURED_RESPONSE, both fail closed).
        AgentOrchestrator.AgentResult result = fixture.orchestrator(
                request -> LLMService.LLMResponse.success(
                        "{\"answer\":\"summary\",\"claims\":[{\"claimId\":\"c1\","
                                + "\"text\":\"Current value is 999999.999\",\"factIds\":[\"fact-0\"],"
                                + "\"evidenceRefs\":[]}]}"),
                new AgentResponseVerifier(List.of())).answer(fixture.formalHistoryQuery());

        assertTrue(result.degraded());
        assertTrue(result.degradeReason().startsWith("MODEL_RESPONSE_REJECTED:"),
                "reason=" + result.degradeReason());
        assertTrue(result.report().generatedBy().equals("JAVA_TEMPLATE"));
        assertFalse(result.report().claims().stream().anyMatch(claim -> claim.text().contains("999999.999")));
        assertFalse(result.report().factsSummary().stream().anyMatch(fact -> "999999.999".equals(fact.value())));
        String persisted = Files.readString(fixture.root().resolveDataRef(result.reportRef()), StandardCharsets.UTF_8);
        assertFalse(persisted.contains("999999.999"), "fabricated value must never reach persisted report JSON");
    }

    @Test
    void injectedSecretIsRejectedAndNeverReachesEvidenceClaimsAnswerOrReport() throws Exception {
        String secret = "super-secret-value";
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "secret");
        AgentOrchestrator.AgentResult result = fixture.orchestrator(
                request -> LLMService.LLMResponse.success(
                        "{\"answer\":\"Authorization: Bearer " + secret + "\",\"claims\":[{\"claimId\":\"c1\","
                                + "\"text\":\"ok\",\"factIds\":[\"fact-0\"],\"evidenceRefs\":[]}]}"),
                new AgentResponseVerifier(List.of(secret)))
                .answer(fixture.formalHistoryQuery());

        assertTrue(result.degraded());
        assertTrue(result.degradeReason().contains("MODEL_RESPONSE_REJECTED:SECRET_INJECTION"));
        assertFalse(result.report().claims().toString().contains(secret));
        assertFalse(result.report().evidencePack().toString().contains(secret));
        String persisted = Files.readString(fixture.root().resolveDataRef(result.reportRef()), StandardCharsets.UTF_8);
        assertFalse(persisted.contains(secret));
    }

    @Test
    void unknownFactAndEvidenceNamesInModelAnswerCannotBecomeFormalLlmSuccess() {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "unknown-reference");
        AgentOrchestrator.AgentResult result = fixture.orchestrator(
                request -> LLMService.LLMResponse.success(
                        "{\"answer\":\"summary\",\"claims\":[{\"claimId\":\"c1\","
                                + "\"text\":\"I rely on fact-does-not-exist\",\"factIds\":[\"fact-does-not-exist\"],"
                                + "\"evidenceRefs\":[]},{\"claimId\":\"c2\",\"text\":\"x\","
                                + "\"factIds\":[],\"evidenceRefs\":[\"raw/unknown-evidence.json\"]}]}"),
                new AgentResponseVerifier(List.of())).answer(fixture.formalHistoryQuery());

        assertTrue(result.degraded(), "unknown fact/evidence references from a model response require fallback");
        assertTrue(result.degradeReason().startsWith("MODEL_RESPONSE_REJECTED:UNKNOWN_"),
                "reason=" + result.degradeReason());
        assertFalse(result.report().claims().stream().anyMatch(claim ->
                claim.text().contains("fact-does-not-exist") || claim.text().contains("unknown-evidence")));
    }

    @Test
    void rejectedToolArgumentsCannotPairWithARegularLlmReport() {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "invalid-arguments");
        AgentOrchestrator.AgentQueryInput traversal = new AgentOrchestrator.AgentQueryInput("test", "../escape",
                "2026-08-10", "2026-08-10", null, null, null, null, null, "FORMAL");
        AgentOrchestrator.AgentResult result = fixture.orchestrator(success("seemingly valid model answer"),
                new AgentResponseVerifier(List.of())).answer(traversal);

        assertTrue(result.degraded());
        assertTrue(result.degradeReason().contains("TOOL_EXECUTION_REJECTED"));
        assertTrue(result.report().generatedBy().equals("JAVA_TEMPLATE"));
    }

    @Test
    void everyFallbackAnswerContainsOnlyTheVerifiedFixtureFactNotModelDraftResidue() {
        for (String failure : List.of("missing_key", "timeout", "http_5xx", "malformed")) {
            Day6R2Fixture fixture = Day6R2Fixture.create(temp, "fallback-" + failure);
            AgentOrchestrator.AgentResult result = fixture.orchestrator(
                    request -> LLMService.LLMResponse.failure(LLMService.LLMStatus.UNAVAILABLE, failure),
                    new AgentResponseVerifier(List.of())).answer(fixture.formalHistoryQuery());
            String answer = result.report().claims().get(0).text();
            assertTrue(result.degraded(), failure);
            assertTrue(answer.contains("123.45678901"), failure + " must use the deterministic fact");
            assertFalse(answer.contains("999999.999"), failure + " must not retain a model draft");
            assertFalse(answer.contains("super-secret-value"), failure + " must not invent a secret");
        }
    }

    private static LLMService.Port success(String text) {
        return request -> LLMService.LLMResponse.success(text);
    }
}
