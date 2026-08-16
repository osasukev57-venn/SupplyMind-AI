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
            SourceView source,
            QualityView quality,
            String warningSummary
    ) {
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
            List<String> missingRefs,
            List<String> corruptRefs,
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

    public record MetricsResponse(
            String itemId,
            String grain,
            int fromYear,
            int toYear,
            List<MetricRow> rows,
            List<String> missingRefs,
            List<String> corruptRefs
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
            List<String> evidenceMissingRefs,
            List<String> evidenceCorruptRefs
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
            boolean stale
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
