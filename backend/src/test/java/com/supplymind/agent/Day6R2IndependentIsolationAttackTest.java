package com.supplymind.agent;

import com.supplymind.agent.application.AgentResponseVerifier;
import com.supplymind.agent.llm.LLMService;
import com.supplymind.agent.orchestration.AgentOrchestrator;
import com.supplymind.agent.support.Day6R2Fixture;
import com.supplymind.foundation.storage.FileDigest;
import com.supplymind.warning.WarningRecordV1;
import com.supplymind.warning.WarningStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Independent M2 FORMAL/DEMO attack using a persisted, manifest-valid demo warning. */
class Day6R2IndependentIsolationAttackTest {

    @TempDir
    Path temp;

    @Test
    void formalExcludesDemoWarningWhileDemoCanSeeTheSameVerifiedEvidence() {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "demo-isolation");
        new WarningStore(fixture.root(), fixture.files(), Day6R2Fixture.CLOCK).store(new WarningRecordV1(
                "1.0", "d6r2demowarning", "demo-rule", "demo-v1", Day6R2Fixture.ITEM, "month",
                "2026-08-01", "2026-08-31", null, "0.01", "0.02", "0.01", WarningRecordV1.RiskLevel.HIGH,
                List.of(fixture.aggregateRef()), "PUBLISHED_VERIFIED", Day6R2Fixture.AT,
                FileDigest.sha256("d6-r2-demo-warning".getBytes(java.nio.charset.StandardCharsets.UTF_8)), true,
                "test-only demo warning"));

        AtomicReference<LLMService.LLMRequest> formalRequest = new AtomicReference<>();
        AgentOrchestrator.AgentResult formal = fixture.orchestrator(request -> {
            formalRequest.set(request);
            return LLMService.LLMResponse.success("formal response");
        }, new AgentResponseVerifier(List.of())).answer(query("FORMAL"));
        // M2 four-state: the demo warning stays in the FORMAL EvidencePack audit trail but is
        // excluded from facts and from the LLM context; DEMO sees the same verified evidence.
        assertTrue(formal.evidencePack().evidenceRefs().stream()
                        .anyMatch(ref -> ref.ref().startsWith("warning/")),
                "M2 four-state: warning ref stays in the structured audit trail");
        assertFalse(formal.evidencePack().facts().stream()
                .flatMap(fact -> fact.evidenceRefs().stream())
                .anyMatch(ref -> ref.startsWith("warning/")),
                "FORMAL: warning evidence must never reach the facts/LLM context");
        assertFalse(formalRequest.get().evidenceRefs().stream().anyMatch(ref -> ref.startsWith("warning/")));

        AtomicReference<LLMService.LLMRequest> demoRequest = new AtomicReference<>();
        AgentOrchestrator.AgentResult demo = fixture.orchestrator(request -> {
            demoRequest.set(request);
            return LLMService.LLMResponse.success("demo response");
        }, new AgentResponseVerifier(List.of())).answer(query("DEMO"));
        assertTrue(demo.evidencePack().evidenceRefs().stream().anyMatch(ref -> ref.ref().startsWith("warning/")));
        assertTrue(demoRequest.get().evidenceRefs().stream().anyMatch(ref -> ref.startsWith("warning/")));
    }

    private static AgentOrchestrator.AgentQueryInput query(String mode) {
        return new AgentOrchestrator.AgentQueryInput("warning evidence", Day6R2Fixture.ITEM,
                null, null, null, null, null, "2026-08", null, mode);
    }
}
