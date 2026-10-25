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
    public ResponseEntity<?> queryHttp(@RequestBody Map<String,String> body,
            @org.springframework.web.bind.annotation.RequestHeader(value="X-Agent-Request-Token", required=false) String token) {
        if(token==null)return query(body);
        try {
            com.supplymind.agent.llm.AgentCancellation.begin(token);
            try { return query(body); } finally { com.supplymind.agent.llm.AgentCancellation.end(token); }
        } catch(IllegalArgumentException ex) { return ResponseEntity.badRequest().body(Map.of("status","REJECTED","message","请求标识无效或正在使用")); }
    }
    @PostMapping("/cancel")
    public ResponseEntity<?> cancel(@RequestBody Map<String,String> body) {
        try { com.supplymind.agent.llm.AgentCancellation.cancel(body.get("token"));
            return ResponseEntity.accepted().body(Map.of("status","CANCELLATION_REQUESTED"));
        }catch(IllegalArgumentException ex){return ResponseEntity.badRequest().body(Map.of("status","REJECTED"));}
    }
    public ResponseEntity<?> query(Map<String, String> body) {
        if (body == null || body.get("question") == null || body.get("question").isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "REJECTED",
                    "message", "question is required"));
        }
        if (body.get("itemId") == null || body.get("itemId").isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "REJECTED",
                    "message", "itemId is required"));
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
        } catch (com.supplymind.agent.llm.RequestBudget.Exhausted exception) {
            return ResponseEntity.status(504).body(Map.of("status", "TIMEOUT", "reason", exception.getMessage(),
                    "message", "分析已取消或超过处理预算，本次未保存报告；请缩小查询范围后重试"));
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
