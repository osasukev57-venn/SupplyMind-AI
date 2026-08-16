package com.supplymind.dashboard;

import com.supplymind.config.ConfigManagementService;
import com.supplymind.dashboard.api.DashboardV1;
import com.supplymind.foundation.model.MonitorSeriesConfigV1;
import com.supplymind.foundation.model.MonitorSeriesItemV1;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.history.HistoryQueryService;
import com.supplymind.publish.PublishedQueryService;
import com.supplymind.publish.PublishedRecord;
import com.supplymind.warning.WarningRecordV1;
import com.supplymind.warning.WarningService;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * D7 read-only dashboard service. Controller -> DashboardService -> existing services; the
 * service never opens business files itself - it reuses HistoryQueryService,
 * PublishedQueryService, ConfigManagementService and WarningService. All status derivation and
 * every chart coordinate happens HERE in Java; the Vue layer only renders the returned
 * strings/coordinates. Evidence references in the DTOs are BUSINESS references (period +
 * status + reason) - internal CSV paths never leave the backend.
 */
public final class DashboardService {

    private static final int WARNING_LOOKBACK_MONTHS = 3;
    private static final int CHART_WIDTH = 640;
    private static final int CHART_HEIGHT = 160;
    private static final int CHART_PAD = 8;
    private static final int MAX_RANGE_DAYS = 3660; // 10 years - same bound as the Agent tools
    private static final int MAX_YEAR_SPAN = 10;
    private static final Pattern DAILY_PERIOD = Pattern.compile("processed/daily/[^/]+/(\\d{4}-\\d{2})\\.csv");
    private static final Pattern AGGREGATE_PERIOD = Pattern.compile(
            "processed/aggregate/[^/]+/(month|quarter|halfyear|year)/(\\d{4})\\.csv");

    private final ConfigManagementService configs;
    private final PublishedQueryService published;
    private final HistoryQueryService history;
    private final WarningService warnings;
    private final Clock clock;

    public DashboardService(
            ConfigManagementService configs,
            PublishedQueryService published,
            HistoryQueryService history,
            WarningService warnings,
            Clock clock
    ) {
        this.configs = Objects.requireNonNull(configs, "configs");
        this.published = Objects.requireNonNull(published, "published");
        this.history = Objects.requireNonNull(history, "history");
        this.warnings = Objects.requireNonNull(warnings, "warnings");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public DashboardV1.OverviewResponse overview() {
        MonitorSeriesConfigV1 config = configs.active();
        List<DashboardV1.ItemCard> cards = new ArrayList<>();
        List<String> warningLines = new ArrayList<>();
        for (MonitorSeriesItemV1 item : config.items()) {
            if (!item.enabled()) {
                continue;
            }
            cards.add(itemCard(item));
            String summary = warningSummary(item.itemId());
            if (summary != null) {
                warningLines.add(summary);
            }
        }
        return new DashboardV1.OverviewResponse(
                config.mode() == null ? null : config.mode().name(),
                List.copyOf(cards), List.copyOf(warningLines));
    }

    private DashboardV1.ItemCard itemCard(MonitorSeriesItemV1 item) {
        PublishedRecord latest = published.latestPublished(item.itemId());
        DashboardV1.QualityView quality;
        String value = null;
        String businessDate = null;
        String dataThrough = null;
        if (latest == null) {
            quality = new DashboardV1.QualityView("NO_DATA", null, null, false, null);
        } else {
            value = latest.value();
            businessDate = latest.businessDate();
            dataThrough = latest.businessDate();
            quality = new DashboardV1.QualityView(
                    latest.stale() ? "STALE" : "VERIFIED",
                    "VERIFIED",
                    latest.validationVersion(),
                    latest.stale(),
                    latest.publishedAt() == null ? null : latest.publishedAt().toString());
        }
        DashboardV1.SourceView source = new DashboardV1.SourceView(
                item.providerType() == null ? null : item.providerType().wireValue(),
                item.accessMethod() == null ? null : item.accessMethod().wireValue(),
                item.actualSourceName(),
                item.routeDecision() == null ? null : item.routeDecision().name(),
                item.fallbackReason());
        return new DashboardV1.ItemCard(
                item.itemId(), item.displayName(), item.enabled(),
                value, businessDate, item.unit(), item.currency(), dataThrough,
                latestCompleteness(item.itemId()),
                source, quality, warningSummary(item.itemId()),
                aggregateSummary(item.itemId()));
    }

    /**
     * D7: completeness of the item's LATEST daily row (backend-computed; the browser never
     * derives it). Missing daily data yields null - never a fabricated number.
     */
    private String latestCompleteness(String itemId) {
        LocalDate today = OffsetDateTime.now(clock).atZoneSameInstant(DataPaths.SHANGHAI).toLocalDate();
        LocalDate monthStart = today.withDayOfMonth(1);
        var rows = history.queryDaily(itemId, monthStart, today).rows();
        if (rows.isEmpty()) {
            return null;
        }
        var latest = rows.stream()
                .max(Comparator.comparing(com.supplymind.foundation.model.DailyRecordV1::businessDate))
                .orElseThrow();
        return completenessOf(latest);
    }

    /**
     * D7: the latest VALID aggregate record - selected across years (current year, then
     * previous years), never just the max of the current year. Empty years are skipped.
     */
    private DashboardV1.AggregateSummary aggregateSummary(String itemId) {
        int currentYear = OffsetDateTime.now(clock).atZoneSameInstant(DataPaths.SHANGHAI).getYear();
        for (int year = currentYear; year >= currentYear - 2; year--) {
            var rows = history.queryAggregate(itemId, "month", year, year).rows();
            if (rows.isEmpty()) {
                continue; // a year without aggregates must not block an older valid record
            }
            var latest = rows.stream()
                    .max(Comparator.comparing(com.supplymind.foundation.model.AggregateRecordV1::periodStart))
                    .orElseThrow();
            return new DashboardV1.AggregateSummary(
                    "month", latest.periodStart(), latest.periodEnd(),
                    latest.avg(), latest.unit());
        }
        return null;
    }

    public DashboardV1.HistoryResponse history(String itemId, String fromDate, String toDate) {
        requireKnownItem(itemId);
        LocalDate from = LocalDate.parse(fromDate);
        LocalDate to = LocalDate.parse(toDate);
        requireRange(from, to);
        HistoryQueryService.DailyHistoryResult result = history.queryDaily(itemId, from, to);
        List<DashboardV1.HistoryPoint> points = new ArrayList<>();
        for (var row : result.rows()) {
            points.add(new DashboardV1.HistoryPoint(
                    row.businessDate(), row.avg(), row.unit(), row.actualSourceName(),
                    row.validationStatus() == null ? null : row.validationStatus().wireValue(),
                    row.validationVersion()));
        }
        List<String> periods = result.missingRefs().stream()
                .map(DashboardService::dailyPeriodOf)
                .filter(Objects::nonNull)
                .toList();
        DashboardV1.EvidenceIssue missing = periods.isEmpty() ? null
                : new DashboardV1.EvidenceIssue(List.copyOf(periods), "MISSING",
                "daily file(s) not found for the requested period");
        DashboardV1.EvidenceIssue corrupt = result.corruptRefs().isEmpty() ? null
                : new DashboardV1.EvidenceIssue(
                result.corruptRefs().stream().map(DashboardService::dailyPeriodOf)
                        .filter(Objects::nonNull).toList(),
                "CORRUPT", "daily file(s) failed manifest or decode verification");
        List<DashboardV1.EvidenceIssue> issues = new ArrayList<>();
        if (missing != null && !missing.periods().isEmpty()) {
            issues.add(missing);
        }
        if (corrupt != null && !corrupt.periods().isEmpty()) {
            issues.add(corrupt);
        }
        return new DashboardV1.HistoryResponse(
                itemId, fromDate, toDate, List.copyOf(points),
                chartOf(points), List.copyOf(issues),
                points.isEmpty() ? null
                        : points.stream().max(Comparator.comparing(DashboardV1.HistoryPoint::businessDate))
                        .orElseThrow().businessDate());
    }

    public DashboardV1.MetricsResponse metrics(String itemId, String grain, int fromYear, int toYear) {
        requireKnownItem(itemId);
        if (fromYear > toYear) {
            throw new IllegalArgumentException("fromYear must not be after toYear");
        }
        if (toYear - fromYear >= MAX_YEAR_SPAN) {
            throw new IllegalArgumentException("year range too large (max " + MAX_YEAR_SPAN + " years)");
        }
        HistoryQueryService.AggregateHistoryResult result = history.queryAggregate(itemId, grain, fromYear, toYear);
        List<DashboardV1.MetricRow> rows = new ArrayList<>();
        for (var row : result.rows()) {
            rows.add(new DashboardV1.MetricRow(
                    row.periodStart(), row.periodEnd(), row.avg(), row.unit(), row.actualSourceName(),
                    row.validationStatus() == null ? null : row.validationStatus().wireValue(),
                    row.validationVersion()));
        }
        List<DashboardV1.EvidenceIssue> issues = new ArrayList<>();
        List<String> missing = result.missingRefs().stream()
                .map(ref -> aggregatePeriodOf(ref, grain))
                .filter(Objects::nonNull).toList();
        if (!missing.isEmpty()) {
            issues.add(new DashboardV1.EvidenceIssue(missing, "MISSING",
                    "aggregate file(s) not found for the requested period"));
        }
        List<String> corrupt = result.corruptRefs().stream()
                .map(ref -> aggregatePeriodOf(ref, grain))
                .filter(Objects::nonNull).toList();
        if (!corrupt.isEmpty()) {
            issues.add(new DashboardV1.EvidenceIssue(corrupt, "CORRUPT",
                    "aggregate file(s) failed manifest or decode verification"));
        }
        return new DashboardV1.MetricsResponse(
                itemId, grain, fromYear, toYear, List.copyOf(rows), List.copyOf(issues));
    }

    public DashboardV1.QualityResponse quality(String itemId, String fromDate, String toDate) {
        requireKnownItem(itemId);
        LocalDate from = LocalDate.parse(fromDate);
        LocalDate to = LocalDate.parse(toDate);
        requireRange(from, to);
        HistoryQueryService.DailyHistoryResult result = history.queryDaily(itemId, from, to);
        LocalDate referenceDate = OffsetDateTime.now(clock).atZoneSameInstant(DataPaths.SHANGHAI)
                .toLocalDate();
        List<DashboardV1.QualityRow> rows = new ArrayList<>();
        for (var row : result.rows()) {
            rows.add(new DashboardV1.QualityRow(
                    row.businessDate(), row.avg(), row.unit(), row.actualSourceName(),
                    row.providerType() == null ? null : row.providerType().wireValue(),
                    row.accessMethod() == null ? null : row.accessMethod().wireValue(),
                    row.validationStatus() == null ? null : row.validationStatus().wireValue(),
                    row.validationVersion(),
                    isStale(row.businessDate(), referenceDate),
                    completenessOf(row)));
        }
        PublishedRecord latest = published.latestPublished(itemId);
        List<DashboardV1.WarningView> warningViews = new ArrayList<>();
        for (WarningRecordV1 warning : warnings.findRecent(itemId, WARNING_LOOKBACK_MONTHS)) {
            warningViews.add(new DashboardV1.WarningView(
                    warning.warningId(), warning.ruleId(), warning.ruleVersion(),
                    warning.periodStart(), warning.periodEnd(),
                    warning.currentValue(), warning.threshold(),
                    warning.riskLevel() == null ? null : warning.riskLevel().name(),
                    warning.dataStatus(),
                    warning.evaluatedAt() == null ? null : warning.evaluatedAt().toString()));
        }
        List<DashboardV1.EvidenceIssue> issues = new ArrayList<>();
        List<String> missing = result.missingRefs().stream()
                .map(DashboardService::dailyPeriodOf).filter(Objects::nonNull).toList();
        if (!missing.isEmpty()) {
            issues.add(new DashboardV1.EvidenceIssue(missing, "MISSING",
                    "daily file(s) not found for the requested period"));
        }
        List<String> corrupt = result.corruptRefs().stream()
                .map(DashboardService::dailyPeriodOf).filter(Objects::nonNull).toList();
        if (!corrupt.isEmpty()) {
            issues.add(new DashboardV1.EvidenceIssue(corrupt, "CORRUPT",
                    "daily file(s) failed manifest or decode verification"));
        }
        return new DashboardV1.QualityResponse(
                itemId,
                latest == null ? "NO_DATA" : latest.stale() ? "STALE" : "VERIFIED",
                List.copyOf(rows), List.copyOf(warningViews), List.copyOf(issues));
    }

    public DashboardV1.SourcesResponse sources() {
        MonitorSeriesConfigV1 config = configs.active();
        List<DashboardV1.SourceItem> items = new ArrayList<>();
        for (MonitorSeriesItemV1 item : config.items()) {
            items.add(new DashboardV1.SourceItem(
                    item.itemId(), item.displayName(), item.enabled(),
                    item.sourceIntent(),
                    item.providerType() == null ? null : item.providerType().wireValue(),
                    item.accessMethod() == null ? null : item.accessMethod().wireValue(),
                    item.actualSourceName(),
                    item.routeDecision() == null ? null : item.routeDecision().name(),
                    item.fallbackReason(),
                    item.routeEffectiveAt() == null ? null : item.routeEffectiveAt().toString()));
        }
        return new DashboardV1.SourcesResponse(
                config.mode() == null ? null : config.mode().name(),
                List.copyOf(items),
                new DashboardV1.EntryStatus("PENDING", "manual intake HTTP entry accepts submissions into PENDING (Day8 write boundary)"),
                new DashboardV1.EntryStatus("PENDING", "file import HTTP entry accepts submissions into PENDING (Day8 write boundary)"));
    }

    /**
     * D7 M1: manual intake accept-into-PENDING. The submission is VALIDATED (known itemId,
     * required source/businessDate/value/unit) but NOT persisted - the formal write is a Day8
     * boundary. The structured PENDING response is what the frontend displays.
     */
    public DashboardV1.ManualPendingResponse manualPending(
            String itemId, String source, String businessDate, String value, String unit
    ) {
        requireKnownItem(itemId);
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source is required");
        }
        if (unit == null || unit.isBlank()) {
            throw new IllegalArgumentException("unit is required");
        }
        if (businessDate == null || businessDate.isBlank()) {
            throw new IllegalArgumentException("businessDate is required");
        }
        LocalDate.parse(businessDate); // malformed dates are invalid requests
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("value is required");
        }
        return new DashboardV1.ManualPendingResponse(
                "PENDING", itemId, source, unit, businessDate, value,
                "manual intake accepted as PENDING - the formal write is a Day8 boundary, nothing persisted");
    }

    /**
     * D7 M1: file import accept-into-PENDING / preview. CSV files are REALLY parsed by the
     * backend (per-row preview + errors, no business-value computation); a format the backend
     * cannot really parse (xlsx) is REJECTED explicitly - never pretended.
     */
    public DashboardV1.ImportResponse importPending(String fileName, byte[] content) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("fileName is required");
        }
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("file content is required");
        }
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
            return new DashboardV1.ImportResponse(
                    "REJECTED",
                    "xlsx real parsing is a Day8 write boundary - this entry accepts CSV preview only",
                    fileName, List.of(), List.of());
        }
        if (!lower.endsWith(".csv")) {
            return new DashboardV1.ImportResponse(
                    "REJECTED",
                    "unsupported file type - this entry accepts CSV preview only",
                    fileName, List.of(), List.of());
        }
        List<DashboardV1.ImportRow> preview = new ArrayList<>();
        List<DashboardV1.ImportRowError> errors = new ArrayList<>();
        String text = new String(content, java.nio.charset.StandardCharsets.UTF_8);
        String[] lines = text.split("\r?\n");
        for (int index = 0; index < lines.length; index++) {
            if (index == 0 || lines[index].isBlank()) {
                continue; // header + empty lines are not data rows
            }
            String line = lines[index];
            List<String> cells = splitCsvLine(line);
            String error = validateImportRow(cells);
            if (error == null) {
                preview.add(new DashboardV1.ImportRow(index + 1, List.copyOf(cells)));
            } else {
                errors.add(new DashboardV1.ImportRowError(index + 1, error));
            }
        }
        return new DashboardV1.ImportResponse(
                "PENDING",
                "import preview parsed " + preview.size() + " rows with " + errors.size()
                        + " row errors - the formal write is a Day8 boundary, nothing persisted",
                fileName, List.copyOf(preview), List.copyOf(errors));
    }

    /** D7: form-level import validation only (missing/blank columns) - no business computation. */
    private static String validateImportRow(List<String> cells) {
        // Frozen CSV schema: itemId, source, businessDate, value, unit (5 columns).
        if (cells.size() < 5) {
            return "列数不足（期望 5 列：标的, 来源, 业务日期, 值, 单位）";
        }
        if (cells.get(0).isBlank()) {
            return "标的为空";
        }
        if (cells.get(1).isBlank()) {
            return "来源为空";
        }
        if (cells.get(2).isBlank()) {
            return "业务日期为空";
        }
        if (cells.get(3).isBlank()) {
            return "值为空";
        }
        if (cells.get(4).isBlank()) {
            return "单位为空";
        }
        return null;
    }

    private static List<String> splitCsvLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char ch = line.charAt(index);
            if (ch == '"') {
                quoted = !quoted;
            } else if (ch == ',' && !quoted) {
                cells.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        cells.add(current.toString().trim());
        return cells;
    }

    private String warningSummary(String itemId) {
        List<WarningRecordV1> recent = warnings.findRecent(itemId, WARNING_LOOKBACK_MONTHS);
        if (recent.isEmpty()) {
            return null;
        }
        return "warnings: " + recent.size() + " (latest "
                + (recent.get(0).riskLevel() == null ? "unknown" : recent.get(0).riskLevel().name()) + ")";
    }

    /**
     * D7 fail-closed: an itemId that is not in the active configuration is an invalid request
     * (HTTP 400) - it is never silently answered with empty data.
     */
    private void requireKnownItem(String itemId) {
        Objects.requireNonNull(itemId, "itemId");
        boolean known = configs.active().items().stream()
                .anyMatch(item -> item.itemId().equals(itemId));
        if (!known) {
            throw new IllegalArgumentException("unknown itemId");
        }
    }

    /** D7 fail-closed: from > to and oversized ranges are invalid requests (HTTP 400). */
    private static void requireRange(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from must not be after to");
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(from, to);
        if (days > MAX_RANGE_DAYS) {
            throw new IllegalArgumentException("date range too large (max " + MAX_RANGE_DAYS + " days)");
        }
    }

    /**
     * D7: stale comes from the REAL backend state - the same rule as the published read model
     * (DEC-051): a business date more than 30 calendar days before the reference date is stale.
     * Never hardcoded.
     */
    private static boolean isStale(String businessDate, LocalDate referenceDate) {
        try {
            return LocalDate.parse(businessDate).isBefore(referenceDate.minusDays(30));
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    /**
     * D7: completeness is computed HERE (same semantics as the warning data-quality rule):
     * (expected - missing) / expected, 12 digits HALF_UP - the browser never derives it.
     */
    private static String completenessOf(com.supplymind.foundation.model.DailyRecordV1 row) {
        long expected = row.expectedCount();
        long missing = row.missingCount();
        if (expected <= 0) {
            return "1.000000000000";
        }
        return new java.math.BigDecimal(expected - missing)
                .divide(java.math.BigDecimal.valueOf(expected), 12, java.math.RoundingMode.HALF_UP)
                .toPlainString();
    }

    /**
     * D7: chart coordinates are COMPUTED HERE (fixed display size, min/max scaling) - the Vue
     * layer only renders the polyline. No browser-side math exists anywhere in the frontend.
     */
    private static DashboardV1.Chart chartOf(List<DashboardV1.HistoryPoint> points) {
        List<DashboardV1.ChartPoint> chartPoints = new ArrayList<>();
        if (!points.isEmpty()) {
            double min = points.stream().mapToDouble(p -> Double.parseDouble(p.value())).min().orElse(0);
            double max = points.stream().mapToDouble(p -> Double.parseDouble(p.value())).max().orElse(0);
            double span = max - min;
            if (span == 0) {
                span = 1;
            }
            double usable = CHART_WIDTH - CHART_PAD * 2;
            double step = points.size() == 1 ? 0 : usable / (points.size() - 1);
            for (int index = 0; index < points.size(); index++) {
                DashboardV1.HistoryPoint point = points.get(index);
                double x = CHART_PAD + (points.size() == 1 ? usable / 2 : index * step);
                double y = CHART_HEIGHT - CHART_PAD
                        - ((Double.parseDouble(point.value()) - min) / span) * (CHART_HEIGHT - CHART_PAD * 2);
                chartPoints.add(new DashboardV1.ChartPoint(
                        point.businessDate() + " " + point.value(),
                        new java.math.BigDecimal(Double.toString(x)).setScale(1, java.math.RoundingMode.HALF_UP).toPlainString(),
                        new java.math.BigDecimal(Double.toString(y)).setScale(1, java.math.RoundingMode.HALF_UP).toPlainString()));
            }
        }
        return new DashboardV1.Chart(CHART_WIDTH, CHART_HEIGHT, List.copyOf(chartPoints));
    }

    /** D7: internal CSV paths never leave the backend - a daily ref maps to its business period. */
    private static String dailyPeriodOf(String ref) {
        Matcher matcher = DAILY_PERIOD.matcher(ref);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String aggregatePeriodOf(String ref, String grain) {
        Matcher matcher = AGGREGATE_PERIOD.matcher(ref);
        return matcher.find() ? matcher.group(2) + " " + grain : null;
    }
}
