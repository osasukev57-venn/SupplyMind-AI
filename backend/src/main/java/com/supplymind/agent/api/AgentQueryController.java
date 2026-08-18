package com.supplymind.agent.api;

import com.supplymind.agent.evidence.EvidencePackV1;
import com.supplymind.agent.orchestration.AgentOrchestrator;
import com.supplymind.agent.report.AgentReportV1;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
            return ResponseEntity.ok(AgentQueryResponse.of(result));
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
}
