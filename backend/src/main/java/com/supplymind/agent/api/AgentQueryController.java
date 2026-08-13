package com.supplymind.agent.api;

import com.supplymind.agent.evidence.EvidencePackV1;
import com.supplymind.agent.orchestration.AgentOrchestrator;
import com.supplymind.agent.report.AgentReportV1;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * D6-T04 minimal Agent backend API. POST /api/agent/query returns a structured Agent response
 * with evidence pack and report ref. Errors are structured HTTP responses - never 500 with a
 * raw stack trace.
 */
@RestController
@RequestMapping("/api/agent")
public class AgentQueryController {

    private final AgentOrchestrator orchestrator;

    public AgentQueryController(AgentOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping("/query")
    public ResponseEntity<?> query(@RequestBody Map<String, String> body) {
        if (body == null || body.get("question") == null || body.get("question").isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "REJECTED",
                    "message", "question is required"));
        }
        AgentOrchestrator.AgentQueryInput input = new AgentOrchestrator.AgentQueryInput(
                body.get("question"),
                body.get("itemId"),
                body.get("startDate"),
                body.get("endDate"),
                body.get("grain"),
                body.get("periodStart"),
                body.get("periodEnd"),
                body.get("month"),
                body.get("businessDate"),
                body.getOrDefault("mode", "FORMAL"));
        try {
            AgentOrchestrator.AgentResult result = orchestrator.answer(input);
            return ResponseEntity.ok(toResponse(result));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "REJECTED",
                    "message", exception.getMessage()));
        } catch (RuntimeException exception) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "UNAVAILABLE",
                    "message", "agent pipeline unavailable"));
        }
    }

    private static AgentQueryResponse toResponse(AgentOrchestrator.AgentResult result) {
        EvidencePackV1 pack = result.evidencePack();
        List<AgentQueryResponse.ToolExecutionView> executions = new ArrayList<>();
        for (EvidencePackV1.ToolExecution execution : pack.toolExecutions()) {
            executions.add(new AgentQueryResponse.ToolExecutionView(
                    execution.invocationIndex(), execution.toolName(), execution.toolVersion(),
                    execution.readOnly(), String.valueOf(execution.input()),
                    String.valueOf(execution.output()), execution.status().name(),
                    execution.evidenceRefs()));
        }
        List<String> evidenceRefs = pack.evidenceRefs().stream()
                .map(EvidencePackV1.EvidenceRefEntry::ref).toList();
        return new AgentQueryResponse(
                pack.requestId(),
                result.report() == null ? null : result.report().claims().isEmpty()
                        ? null : result.report().claims().get(0).text(),
                result.llmResponse().status().name(),
                result.report() != null && result.report().degraded(),
                result.report() == null ? null : result.report().degradeReason(),
                executions,
                evidenceRefs,
                result.reportRef(),
                result.report() == null ? List.of() : result.report().factsSummary().stream()
                        .map(fact -> new AgentQueryResponse.FactView(
                                fact.factId(), fact.statement(), fact.value(),
                                fact.businessDate(), fact.period(), fact.validationStatus()))
                        .toList());
    }
}
