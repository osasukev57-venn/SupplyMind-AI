package com.supplymind.agent.evidence;

import com.supplymind.agent.tool.ToolResult;
import com.supplymind.agent.tool.ToolStatus;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * D6-T02 EvidencePackV1 strictly per AGENT-EVIDENCE-SCHEMA-V1. Owned by SupplyMind; Spring AI,
 * ChatClient, memory and tool transcripts are never evidence. Every fact-carrying element
 * references verified evidenceRefs.
 */
public record EvidencePackV1(
        String schemaVersion,
        String evidencePackId,
        String requestId,
        String mode,
        String question,
        OffsetDateTime createdAt,
        Scope scope,
        List<ToolExecution> toolExecutions,
        List<Fact> facts,
        List<EvidenceRefEntry> evidenceRefs,
        List<String> warnings,
        List<String> notices,
        List<String> limitations
) {
    public EvidencePackV1 {
        if (!"AGENT-EVIDENCE-SCHEMA-V1".equals(schemaVersion) && !"AGENT-EVIDENCE-SCHEMA-V2".equals(schemaVersion)) {
            throw new IllegalArgumentException("schemaVersion must be AGENT-EVIDENCE-SCHEMA-V1");
        }
        if (evidencePackId == null || evidencePackId.isBlank()) {
            throw new IllegalArgumentException("evidencePackId is required");
        }
        if (mode == null || mode.isBlank()) {
            throw new IllegalArgumentException("mode is required (FORMAL or DEMO)");
        }
        toolExecutions = toolExecutions == null ? List.of() : List.copyOf(toolExecutions);
        facts = facts == null ? List.of() : List.copyOf(facts);
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        notices = notices == null ? List.of() : List.copyOf(notices);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
        if ("AGENT-EVIDENCE-SCHEMA-V1".equals(schemaVersion)
                && (facts.stream().anyMatch(f->f.rowEvidenceId()!=null) || evidenceRefs.stream().anyMatch(e->!e.rowEvidence().isEmpty())))
            throw new IllegalArgumentException("V1 cannot carry row evidence");
    }

    public record Scope(
            List<String> itemIds,
            String businessDate,
            String periodStart,
            String periodEnd,
            String timezone
    ) {
        public Scope {
            itemIds = itemIds == null ? List.of() : List.copyOf(itemIds);
        }
    }

    public record ToolExecution(
            int invocationIndex,
            String toolName,
            String toolVersion,
            boolean readOnly,
            Object input,
            Object output,
            ToolStatus status,
            List<String> evidenceRefs
    ) {
        public ToolExecution {
            evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        }
    }

    public record Fact(
            String factId,
            String factType,
            String itemId,
            String businessDate,
            String periodStart,
            String periodEnd,
            String value,
            String unit,
            String currency,
            String qualityStatus,
            String validationStatus,
            String validationVersion,
            String calculationVersion,
            String calendarVersion,
            List<String> configVersions,
            String actualSourceName,
            String sourceFingerprint,
            List<String> evidenceRefs,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String rowEvidenceId
    ) {
        public Fact(String factId,String factType,String itemId,String businessDate,String periodStart,String periodEnd,String value,String unit,String currency,String qualityStatus,String validationStatus,String validationVersion,String calculationVersion,String calendarVersion,List<String> configVersions,String actualSourceName,String sourceFingerprint,List<String> evidenceRefs) {
            this(factId,factType,itemId,businessDate,periodStart,periodEnd,value,unit,currency,qualityStatus,validationStatus,validationVersion,calculationVersion,calendarVersion,configVersions,actualSourceName,sourceFingerprint,evidenceRefs,null);
        }
        public Fact {
            configVersions = configVersions == null ? List.of() : List.copyOf(configVersions);
            evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        }
    }

    public record EvidenceRefEntry(
            String evidenceRefId,
            String refType,
            String ref,
            String sha256,
            EvidenceStatus status,
            String reasonCode,
            String runId,
            String rawRef,
            String publishRef,
            String businessDate,
            String periodStart,
            String periodEnd,
            String validationVersion,
            String calculationVersion,
            String calendarVersion,
            List<String> configVersions,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY) List<AggregateRowEvidenceV1> rowEvidence
    ) {
        public EvidenceRefEntry(String evidenceRefId,String refType,String ref,String sha256,EvidenceStatus status,String reasonCode,String runId,String rawRef,String publishRef,String businessDate,String businessStart,String businessEnd,String validationVersion,String calculationVersion,String calendarVersion,List<String> configVersions) {
            this(evidenceRefId,refType,ref,sha256,status,reasonCode,runId,rawRef,publishRef,businessDate,businessStart,businessEnd,validationVersion,calculationVersion,calendarVersion,configVersions,List.of());
        }
        public EvidenceRefEntry {
            rowEvidence = rowEvidence == null ? List.of() : List.copyOf(rowEvidence);
            configVersions = configVersions == null ? List.of() : List.copyOf(configVersions);
            if (status == null) {
                throw new IllegalArgumentException("evidence status is required");
            }
        }
    }
}
