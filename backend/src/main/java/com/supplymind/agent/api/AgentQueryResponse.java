package com.supplymind.agent.api;

import java.util.List;

/** D6-T04 Agent API structured response. */
public record AgentQueryResponse(
        String requestId,
        String answer,
        String llmStatus,
        boolean degraded,
        String degradeReason,
        List<ToolExecutionView> toolTrace,
        List<String> evidenceRefs,
        String reportRef,
        List<FactView> facts
) {
    public record ToolExecutionView(
            int invocationIndex,
            String toolName,
            String toolVersion,
            boolean readOnly,
            String input,
            String output,
            String status,
            List<String> evidenceRefs
    ) {
        public ToolExecutionView {
            evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        }
    }

    public record FactView(
            String factId,
            String statement,
            String value,
            String businessDate,
            String period,
            String validationStatus
    ) {
    }
}
