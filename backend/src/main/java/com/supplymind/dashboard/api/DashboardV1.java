package com.supplymind.dashboard.api;

import java.util.List;

/**
 * D7 frozen dashboard DTO contract (AGENT-free, file-model-free). Every value is the exact
 * BigDecimal string the backend produced (DEC-008) - the browser never recomputes business
 * values. Records are deliberately flat and explicit so the JSON field names are the contract.
 */
public final class DashboardV1 {

    private DashboardV1() {
    }

    public record OverviewResponse(
            String mode,
            List<ItemCard> items,
            List<String> warnings
    ) {
    }

    public record ItemCard(
            String itemId,
            String displayName,
            boolean enabled,
            String latestValue,
            String businessDate,
            String unit,
            String currency,
            String dataThrough,
            String completeness,
            SourceView source,
            QualityView quality,
            String warningSummary,
            AggregateSummary aggregateSummary
    ) {
    }

    /** D7: latest valid aggregate record for the item (backend-selected across years). */
    public record AggregateSummary(
            String grain,
            String periodStart,
            String periodEnd,
            String value,
            String unit
    ) {
    }

    /**
     * D7: manual intake accept-into-PENDING contract. The submission goes through the REAL
     * ManualMaterialIntakeService boundary: an immutable raw + RECEIVED/PARSED+PENDING
     * lifecycle timeline are actually created - the runId/rawRef/timelineRef are real evidence.
     */
    public record ManualPendingResponse(
            String status,
            String itemId,
            String source,
            String unit,
            String businessDate,
            String value,
            String runId,
            String rawRef,
            String timelineRef,
            String message
    ) {
    }

    /**
     * D7: file import response. The file is REALLY parsed by the existing LocalImportService
     * boundary (CSV and XLSX): accepted rows are persisted as RECEIVED+PENDING evidence with
     * real runId/rawRef/timelineRef; invalid rows are reported per row. File-level failures are
     * REJECTED explicitly - never pretended.
     */
    public record ImportResponse(
            String status,
            String message,
            String fileName,
            List<ImportRow> acceptedRows,
            List<ImportRowError> rowErrors
    ) {
        public ImportResponse {
            acceptedRows = acceptedRows == null ? List.of() : List.copyOf(acceptedRows);
            rowErrors = rowErrors == null ? List.of() : List.copyOf(rowErrors);
        }
    }

    public record ImportRow(
            int rowNumber,
            String runId,
            String rawRef,
            String timelineRef,
            String processingStage,
            String validationStatus
    ) {
    }

    public record ImportRowError(
            int rowNumber,
            String message
    ) {
    }

    /** D7: synthetic demo entry - REAL deterministic demo generation (never persisted formally). */
    public record SyntheticDemoResponse(
            String status,
            String message,
            List<String> itemIds
    ) {
        public SyntheticDemoResponse {
            itemIds = itemIds == null ? List.of() : List.copyOf(itemIds);
        }
    }

    public record SourceView(
            String providerType,
            String accessMethod,
            String actualSourceName,
            String routeDecision,
            String fallbackReason
    ) {
    }

    public record QualityView(
            String status,
            String validationStatus,
            String validationVersion,
            boolean stale,
            String updatedAt
    ) {
    }

    public record HistoryResponse(
            String itemId,
            String fromDate,
            String toDate,
            List<HistoryPoint> points,
            Chart chart,
            List<EvidenceIssue> evidenceIssues,
            String dataThrough
    ) {
    }

    public record HistoryPoint(
            String businessDate,
            String value,
            String unit,
            String actualSourceName,
            String validationStatus,
            String validationVersion
    ) {
    }

    /**
     * D7: chart display coordinates are computed by the BACKEND (fixed size, min/max scaling);
     * the Vue layer only renders them. label carries the exact businessDate + value string.
     */
    public record Chart(
            int width,
            int height,
            List<ChartPoint> points
    ) {
        public Chart {
            points = points == null ? List.of() : List.copyOf(points);
        }
    }

    public record ChartPoint(
            String label,
            String x,
            String y
    ) {
    }

    /**
     * D7: evidence issues are BUSINESS references (period + status + reason) - internal CSV
     * paths never leave the backend.
     */
    public record EvidenceIssue(
            List<String> periods,
            String status,
            String reason
    ) {
        public EvidenceIssue {
            periods = periods == null ? List.of() : List.copyOf(periods);
        }
    }

    public record MetricsResponse(
            String itemId,
            String grain,
            int fromYear,
            int toYear,
            List<MetricRow> rows,
            List<EvidenceIssue> evidenceIssues
    ) {
    }

    public record MetricRow(
            String periodStart,
            String periodEnd,
            String value,
            String unit,
            String actualSourceName,
            String validationStatus,
            String validationVersion
    ) {
    }

    public record QualityResponse(
            String itemId,
            String latestStatus,
            List<QualityRow> rows,
            List<WarningView> warnings,
            List<EvidenceIssue> evidenceIssues
    ) {
    }

    public record QualityRow(
            String businessDate,
            String value,
            String unit,
            String actualSourceName,
            String providerType,
            String accessMethod,
            String validationStatus,
            String validationVersion,
            boolean stale,
            String completeness
    ) {
    }

    public record WarningView(
            String warningId,
            String ruleId,
            String ruleVersion,
            String periodStart,
            String periodEnd,
            String value,
            String threshold,
            String riskLevel,
            String status,
            String createdAt
    ) {
    }

    public record SourcesResponse(
            String mode,
            List<SourceItem> items,
            EntryStatus manualEntry,
            EntryStatus importEntry
    ) {
    }

    public record SourceItem(
            String itemId,
            String displayName,
            boolean enabled,
            String sourceIntent,
            String providerType,
            String accessMethod,
            String actualSourceName,
            String routeDecision,
            String fallbackReason,
            String routeEffectiveAt
    ) {
    }

    public record EntryStatus(
            String status,
            String message
    ) {
    }
}
