package com.supplymind.localimport;

import java.util.List;

/**
 * D3-T05 import result: file-level structural failure fails the whole import closed;
 * otherwise each accepted row yields an intake outcome and each invalid row is recorded as an
 * explicit row error (frozen output "导入raw、逐行错误"). Rows only ever reach intake
 * (RECEIVED+PENDING); they are never auto-verified or auto-published.
 */
public record LocalImportResult(
        String fileError,
        List<RowOutcome> accepted,
        List<LocalImportCsvParser.RowError> rowErrors
) {
    public LocalImportResult {
        accepted = List.copyOf(accepted == null ? List.of() : accepted);
        rowErrors = List.copyOf(rowErrors == null ? List.of() : rowErrors);
    }

    public boolean fileFailed() {
        return fileError != null;
    }

    public record RowOutcome(
            int rowNumber,
            String runId,
            String rawRef,
            String timelineRef,
            com.supplymind.foundation.model.ProcessingStage processingStage,
            com.supplymind.foundation.model.ValidationStatus validationStatus,
            ImportMode mode
    ) {
    }

    public enum ImportMode {
        NEW,
        IDEMPOTENT_REUSE
    }
}
