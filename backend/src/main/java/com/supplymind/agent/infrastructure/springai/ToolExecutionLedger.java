package com.supplymind.agent.infrastructure.springai;

import com.supplymind.agent.tool.ToolResult;
import com.supplymind.agent.tool.ToolStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * F3/M4/M1 request-scoped SupplyMind ToolExecutionLedger: records every tool execution that the
 * Spring AI ChatClient lifecycle performs, with its full ToolResult (output, evidenceRefs,
 * lineage) and status. The ledger belongs to the SupplyMind trusted application layer (never
 * model output). The orchestration layer builds the formal EvidencePack from the ledger's real
 * ToolResults (M1) and checks the ledger before a model response may become a formal success
 * (M4): any UNKNOWN/REJECTED/FAILED execution forces deterministic fallback.
 */
public final class ToolExecutionLedger {

    private final Map<String, Execution> executions = new ConcurrentHashMap<>();

    public void record(String toolName, ToolStatus status, String reasonCode) {
        executions.put(toolName, new Execution(toolName, status, reasonCode, null));
    }

    public void record(String toolName, ToolStatus status, String reasonCode, ToolResult result) {
        executions.put(toolName, new Execution(toolName, status, reasonCode, result));
    }

    public List<Execution> all() {
        List<Execution> list = new ArrayList<>(executions.values());
        list.sort(java.util.Comparator.comparing(Execution::toolName));
        return List.copyOf(list);
    }

    /** M1: the full ToolResults actually executed by the model-selected tool calls. */
    public List<ToolResult> executedToolResults() {
        List<ToolResult> results = new ArrayList<>();
        for (Execution execution : executions.values()) {
            if (execution.result() != null) {
                results.add(execution.result());
            }
        }
        results.sort(java.util.Comparator.comparing(ToolResult::toolName));
        return List.copyOf(results);
    }

    public boolean hasFailure() {
        return executions.values().stream().anyMatch(execution ->
                execution.status() == ToolStatus.REJECTED);
    }

    public record Execution(String toolName, ToolStatus status, String reasonCode, ToolResult result) {
        public Execution {
            Objects.requireNonNull(toolName, "toolName");
            Objects.requireNonNull(status, "status");
        }
    }
}
