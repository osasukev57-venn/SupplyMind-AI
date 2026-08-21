package com.supplymind.agent.infrastructure.springai;

import com.supplymind.agent.llm.LLMService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringAiPromptContractTest {

    @Test
    void phaseBPublishesExactOpaqueFactAndEvidenceIdentifiersWithBusinessMetadata() {
        String evidenceRef = "processed/daily/MAT.ADC12.SMM/2026-08.csv";
        LLMService.LlmFact fact = new LLMService.LlmFact(
                "fact-7",
                "history.query",
                "MAT.ADC12.SMM",
                "22970.00",
                "元/吨",
                "CNY",
                "2026-08-20",
                "2026-08-20",
                "VERIFIED",
                "上海期货交易所公开基准",
                evidenceRef
        );
        LLMService.LLMRequest request = new LLMService.LLMRequest(
                "req-1", "说明已发布事实", "FORMAL", List.of(fact), List.of(evidenceRef), false);

        String prompt = SpringAiLlmService.buildPrompt(request);

        assertTrue(prompt.contains("\"factId\":\"fact-7\""), prompt);
        assertTrue(prompt.contains("\"itemId\":\"MAT.ADC12.SMM\""), prompt);
        assertTrue(prompt.contains("\"unit\":\"元/吨\""), prompt);
        assertTrue(prompt.contains("\"currency\":\"CNY\""), prompt);
        assertTrue(prompt.contains("\"actualSourceName\":\"上海期货交易所公开基准\""), prompt);
        assertTrue(prompt.contains("Allowed factIds: [\"fact-7\"]"), prompt);
        assertTrue(prompt.contains("Allowed evidenceRefs: [\"" + evidenceRef + "\"]"), prompt);
        assertTrue(prompt.contains("copy it exactly and never renumber it"), prompt);
        assertFalse(prompt.contains("Allowed factIds: [\"fact-0\"]"), prompt);
    }
}
