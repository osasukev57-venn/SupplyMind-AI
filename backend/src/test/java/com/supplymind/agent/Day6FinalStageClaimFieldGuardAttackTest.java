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

import static org.junit.jupiter.api.Assertions.*;

/** Current cloud contract attacks: answer + usedFactIds; all business content stays Java-backed. */
class Day6FinalStageClaimFieldGuardAttackTest {
    private static final String SECRET = "sk-super-secret-r4";
    @TempDir Path temp;

    @Test
    void secretInAnswerRejectsWithZeroPersistence() throws Exception {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "answer-secret");
        AgentOrchestrator.AgentResult result = fixture.orchestrator(
                request -> LLMService.LLMResponse.success(json("secret " + SECRET, "fact-0")),
                new AgentResponseVerifier(List.of(SECRET))).answer(fixture.formalHistoryQuery());
        assertTrue(result.degraded());
        assertTrue(result.degradeReason().contains("MODEL_RESPONSE_REJECTED:SECRET_INJECTION"));
        String persisted = Files.readString(fixture.root().resolveDataRef(result.reportRef()), StandardCharsets.UTF_8);
        assertFalse(persisted.contains(SECRET));
    }

    @Test
    void secretInUsedFactIdsRejects() {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "fact-id-secret");
        AgentOrchestrator.AgentResult result = fixture.orchestrator(
                request -> LLMService.LLMResponse.success(json("safe", SECRET)),
                new AgentResponseVerifier(List.of(SECRET))).answer(fixture.formalHistoryQuery());
        assertTrue(result.degraded());
        assertTrue(result.degradeReason().contains("MODEL_RESPONSE_REJECTED:SECRET_INJECTION"));
    }

    @Test
    void standaloneNumberNextToSupportedDateMustBeFactBacked() {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "standalone-20");
        AgentOrchestrator.AgentResult result = fixture.orchestrator(
                request -> LLMService.LLMResponse.success(json("值为20，日期2026-08-10", "fact-0")),
                new AgentResponseVerifier(List.of())).answer(fixture.formalHistoryQuery());
        assertTrue(result.degraded());
        assertTrue(result.degradeReason().contains("UNSUPPORTED_CLAIM_REFERENCE"));
    }

    @Test
    void dateTokenFragmentsAreNotBusinessNumbers() {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "date-fragments");
        AgentOrchestrator.AgentResult result = fixture.orchestrator(
                request -> LLMService.LLMResponse.success(
                        json("日期2026-08-10 值 " + request.facts().get(0).value(), "fact-0")),
                new AgentResponseVerifier(List.of())).answer(fixture.formalHistoryQuery());
        assertFalse(result.degraded(), "reason=" + result.degradeReason());
        assertEquals("LLM", result.report().generatedBy());
    }

    @Test
    void fabricatedSourceDeclarationRejects() {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "fabricated-source");
        for (String declaration : List.of("来源：虚构银行。", "source: Fake Bank Ltd.")) {
            AgentOrchestrator.AgentResult result = fixture.orchestrator(
                    request -> LLMService.LLMResponse.success(json(declaration, "fact-0")),
                    new AgentResponseVerifier(List.of())).answer(fixture.formalHistoryQuery());
            assertTrue(result.degraded(), declaration);
            assertTrue(result.degradeReason().contains("UNSUPPORTED_CLAIM_REFERENCE"));
        }
    }

    @Test
    void authoritativeSourcePrefixCannotValidateExtendedFakeSource() {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "source-prefix-extension");
        String declaration = "来源：" + Day6R2Fixture.SOURCE + "伪造分行。";
        AgentOrchestrator.AgentResult result = fixture.orchestrator(
                request -> LLMService.LLMResponse.success(json(declaration, "fact-0")),
                new AgentResponseVerifier(List.of())).answer(fixture.formalHistoryQuery());
        assertTrue(result.degraded());
        assertTrue(result.degradeReason().contains("UNSUPPORTED_CLAIM_REFERENCE"));
    }

    @Test
    void realSourceDeclarationBackedBySelectedFactPasses() {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "real-source");
        AgentOrchestrator.AgentResult result = fixture.orchestrator(
                request -> LLMService.LLMResponse.success(
                        json("数据来自 " + Day6R2Fixture.SOURCE + "，值 " + request.facts().get(0).value(), "fact-0")),
                new AgentResponseVerifier(List.of())).answer(fixture.formalHistoryQuery());
        assertFalse(result.degraded(), "reason=" + result.degradeReason());
        assertEquals("LLM", result.report().generatedBy());
    }

    private static String json(String answer, String factId) {
        return "{\"answer\":\"" + answer + "\",\"usedFactIds\":[\"" + factId + "\"]}";
    }
}