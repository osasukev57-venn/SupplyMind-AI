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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DAY6 Round4 M3: full-field claim guards. The secret guard scans EVERY persistable field
 * (answer, claimId, text, factIds, evidenceRefs, sourceNames, businessDates); numbers are
 * POSITION-AWARE (only tokens outside an ISO date span are business numbers; a standalone 20
 * must be backed by a referenced fact value); source declarations must be closed via
 * sourceNames[] and backed by the referenced facts.
 */
class Day6FinalStageClaimFieldGuardAttackTest {

    private static final String SECRET = "sk-super-secret-r4";

    @TempDir
    Path temp;

    @Test
    void secretInClaimTextRejectsWithZeroPersistence() throws Exception {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "claim-secret");
        AgentOrchestrator.AgentResult result = fixture.orchestrator(
                request -> LLMService.LLMResponse.success(
                        "{\"answer\":\"safe summary\",\"claims\":[{\"claimId\":\"c1\","
                                + "\"text\":\"secret " + SECRET + " here\","
                                + "\"factIds\":[\"fact-0\"],\"evidenceRefs\":[]}]}"),
                new AgentResponseVerifier(List.of(SECRET))).answer(fixture.formalHistoryQuery());

        assertTrue(result.degraded(), "a secret in claim.text must reject the whole draft");
        assertTrue(result.degradeReason().contains("MODEL_RESPONSE_REJECTED:SECRET_INJECTION"),
                "reason=" + result.degradeReason());
        assertEquals("JAVA_TEMPLATE", result.report().generatedBy());
        String persisted = Files.readString(fixture.root().resolveDataRef(result.reportRef()),
                StandardCharsets.UTF_8);
        assertFalse(persisted.contains(SECRET), "the secret must never reach disk");
    }

    @Test
    void secretInSourceNamesOrBusinessDatesRejects() {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "field-secret");
        for (String field : List.of(
                "\"sourceNames\":[\"" + SECRET + "\"],\"businessDates\":[]",
                "\"sourceNames\":[],\"businessDates\":[\"" + SECRET + "\"]")) {
            AgentOrchestrator.AgentResult result = fixture.orchestrator(
                    request -> LLMService.LLMResponse.success(
                            "{\"answer\":\"ok\",\"claims\":[{\"claimId\":\"c1\",\"text\":\"ok\","
                                    + "\"factIds\":[\"fact-0\"],\"evidenceRefs\":[],"
                                    + field + "}]}"),
                    new AgentResponseVerifier(List.of(SECRET))).answer(fixture.formalHistoryQuery());
            assertTrue(result.degraded(), "secret in a structured field must reject: " + field);
            assertTrue(result.degradeReason().contains("MODEL_RESPONSE_REJECTED:SECRET_INJECTION"),
                    "reason=" + result.degradeReason() + " for " + field);
            assertEquals("JAVA_TEMPLATE", result.report().generatedBy());
        }
    }

    @Test
    void standaloneNumberNextToSupportedDateMustBeFactBacked() {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "standalone-20");
        // fact-0 value is 123.45678901 on 2026-08-10: the date is supported, the standalone 20
        // is a BUSINESS number and must be supported by the referenced fact VALUE - it is not.
        AgentOrchestrator.AgentResult result = fixture.orchestrator(
                request -> LLMService.LLMResponse.success(
                        "{\"answer\":\"ok\",\"claims\":[{\"claimId\":\"c1\","
                                + "\"text\":\"值为20，日期2026-08-10\","
                                + "\"factIds\":[\"fact-0\"],\"evidenceRefs\":[]}]}"),
                new AgentResponseVerifier(List.of())).answer(fixture.formalHistoryQuery());

        assertTrue(result.degraded(),
                "a standalone 20 must be supported by the referenced fact VALUE, not exempted by the date");
        assertTrue(result.degradeReason().contains("UNSUPPORTED_CLAIM_REFERENCE")
                        || result.degradeReason().contains("FABRICATED_NUMBER"),
                "reason=" + result.degradeReason());
        assertEquals("JAVA_TEMPLATE", result.report().generatedBy());
    }

    @Test
    void dateTokenFragmentsAreNotBusinessNumbers() {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "date-fragments");
        // 2026/08/10 are date-token fragments, not business numbers; 123.45678901 is the fact
        // value and the date is covered by the referenced fact -> the claim passes.
        AgentOrchestrator.AgentResult result = fixture.orchestrator(
                request -> LLMService.LLMResponse.success(
                        "{\"answer\":\"ok\",\"claims\":[{\"claimId\":\"c1\","
                                + "\"text\":\"日期2026-08-10 值 123.45678901\","
                                + "\"factIds\":[\"fact-0\"],\"evidenceRefs\":[]}]}"),
                new AgentResponseVerifier(List.of())).answer(fixture.formalHistoryQuery());

        assertFalse(result.degraded(),
                "date fragments must not be treated as business numbers (reason="
                        + result.degradeReason() + ")");
        assertEquals("LLM", result.report().generatedBy());
    }

    @Test
    void fabricatedSourceDeclarationWithEmptySourceNamesRejects() {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "fabricated-source");
        AgentOrchestrator.AgentResult result = fixture.orchestrator(
                request -> LLMService.LLMResponse.success(
                        "{\"answer\":\"ok\",\"claims\":[{\"claimId\":\"c1\","
                                + "\"text\":\"来源：fabricated source 提供数据\","
                                + "\"factIds\":[\"fact-0\"],\"evidenceRefs\":[],"
                                + "\"sourceNames\":[],\"businessDates\":[]}]}"),
                new AgentResponseVerifier(List.of())).answer(fixture.formalHistoryQuery());

        assertTrue(result.degraded(),
                "a fabricated source declaration with empty sourceNames must fail closed");
        assertTrue(result.degradeReason().contains("UNSUPPORTED_CLAIM_REFERENCE"),
                "reason=" + result.degradeReason());
        assertEquals("JAVA_TEMPLATE", result.report().generatedBy());
    }

    @Test
    void realSourceDeclarationClosedInSourceNamesPasses() {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "real-source");
        // The known source name appears in the text AND is declared in sourceNames, backed by
        // the referenced fact -> the claim passes.
        AgentOrchestrator.AgentResult result = fixture.orchestrator(
                request -> LLMService.LLMResponse.success(
                        "{\"answer\":\"ok\",\"claims\":[{\"claimId\":\"c1\","
                                + "\"text\":\"数据来自 " + Day6R2Fixture.SOURCE + "，值 123.45678901\","
                                + "\"factIds\":[\"fact-0\"],\"evidenceRefs\":[],"
                                + "\"sourceNames\":[\"" + Day6R2Fixture.SOURCE + "\"],"
                                + "\"businessDates\":[]}]}"),
                new AgentResponseVerifier(List.of())).answer(fixture.formalHistoryQuery());

        assertFalse(result.degraded(),
                "a real source declaration closed in sourceNames and backed by the fact must pass (reason="
                        + result.degradeReason() + ")");
        assertEquals("LLM", result.report().generatedBy());
    }
}
