package com.supplymind.agent.infrastructure.springai;

import com.supplymind.agent.tool.ToolStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * F3/M4 request-scoped SupplyMind ToolExecutionLedger: records every tool execution that the
 * Spring AI ChatClient lifecycle performs, with its status. The ledger belongs to the
 * SupplyMind trusted application layer (never model output). Before a model response may become
 * a formal success, the orchestration layer checks the ledger: any UNKNOWN/REJECTED/FAILED
 * execution forces deterministic fallback.
 */
public final class ToolExecutionLedger {

    private final Map<String, Execution> executions = new ConcurrentHashMap<>();

    public void record(String toolName, ToolStatus status, String reasonCode) {
        executions.put(toolName, new Execution(toolName, status, reasonCode));
    }

    public List<Execution> all() {
        List<Execution> list = new ArrayList<>(executions.values());
        list.sort(java.util.Comparator.comparing(Execution::toolName));
        return List.copyOf(list);
    }

    public boolean hasFailure() {
        return executions.values().stream().anyMatch(execution ->
                execution.status() == ToolStatus.REJECTED);
    }

    public record Execution(String toolName, ToolStatus status, String reasonCode) {
        public Execution {
            Objects.requireNonNull(toolName, "toolName");
            Objects.requireNonNull(status, "status");
        }
    }
}
