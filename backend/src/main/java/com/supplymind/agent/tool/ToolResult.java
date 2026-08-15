package com.supplymind.agent.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * D6-T01 versioned Tool Result contract (AGENT-EVIDENCE-SCHEMA-V1 §2.1). Every tool returns a
 * structured result: status is SUCCESS / NO_DATA / REJECTED (never raw exceptions), inputSummary
 * records what was asked, result holds only deterministic business values (BigDecimal strings
 * from production services), and evidenceRefs are dataRoot-relative refs verified by the
 * application layer. Spring AI only sees this DTO - never a stack trace.
 */
public record ToolResult(
        String toolName,
        String toolVersion,
        String requestId,
        ToolStatus status,
        String inputSummary,
        Map<String, Object> result,
        List<String> notices,
        List<String> evidenceRefs,
        Lineage lineage,
        Map<String, Lineage> evidenceLineageByRef
) {
    public ToolResult {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName is required");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId is required");
        }
        result = result == null ? Map.of() : Map.copyOf(result);
        notices = notices == null ? List.of() : List.copyOf(notices);
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        evidenceLineageByRef = evidenceLineageByRef == null
                ? Map.of() : Map.copyOf(evidenceLineageByRef);
    }

    public static ToolResult success(
            String toolName, String toolVersion, String requestId, String inputSummary,
            Map<String, Object> result, List<String> evidenceRefs, List<String> notices
    ) {
        return new ToolResult(toolName, toolVersion, requestId, ToolStatus.SUCCESS,
                inputSummary, result, notices, evidenceRefs, null, Map.of());
    }

    public static ToolResult success(
            String toolName, String toolVersion, String requestId, String inputSummary,
            Map<String, Object> result, List<String> evidenceRefs, List<String> notices, Lineage lineage
    ) {
        return new ToolResult(toolName, toolVersion, requestId, ToolStatus.SUCCESS,
                inputSummary, result, notices, evidenceRefs, lineage, Map.of());
    }

    public static ToolResult success(
            String toolName, String toolVersion, String requestId, String inputSummary,
            Map<String, Object> result, List<String> evidenceRefs, List<String> notices,
            Lineage lineage, Map<String, Lineage> evidenceLineageByRef
    ) {
        return new ToolResult(toolName, toolVersion, requestId, ToolStatus.SUCCESS,
                inputSummary, result, notices, evidenceRefs, lineage,
                new LinkedHashMap<>(evidenceLineageByRef));
    }

    public static ToolResult rejected(
            String toolName, String toolVersion, String requestId, String reason
    ) {
        return new ToolResult(toolName, toolVersion, requestId, ToolStatus.REJECTED,
                reason, Map.of(), List.of(), List.of(), null, Map.of());
    }

    public static ToolResult noData(
            String toolName, String toolVersion, String requestId, String inputSummary, String reason
    ) {
        return new ToolResult(toolName, toolVersion, requestId, ToolStatus.NO_DATA,
                inputSummary, Map.of("reason", reason), List.of(), List.of(), null, Map.of());
    }

    /** F4: real production lineage carried by the Tool Result (never placeholders). */
    public record Lineage(
            String calculationVersion,
            String calendarVersion,
            List<String> configVersions,
            String actualSourceName,
            String sourceFingerprint,
            String validationVersion
    ) {
        public Lineage {
            configVersions = configVersions == null ? List.of() : List.copyOf(configVersions);
        }

        /**
         * Explicit per-ref fail-closed marker. A missing map key permits tool-level fallback;
         * a present key with this empty lineage records heterogeneous lineage and forbids it.
         */
        public static Lineage ambiguous() {
            return new Lineage(null, null, List.of(), null, null, null);
        }
    }
}
