package com.supplymind.dashboard;

import com.supplymind.config.ConfigManagementService;
import com.supplymind.dashboard.api.DashboardV1;
import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.MonitorSeriesConfigV1;
import com.supplymind.foundation.model.MonitorSeriesItemV1;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.ManifestVerifier;
import com.supplymind.history.HistoryQueryService;
import com.supplymind.publish.PublishedQueryService;
import com.supplymind.publish.PublishedRecord;
import com.supplymind.routing.MaterialRoutePlanService;
import com.supplymind.warning.WarningRecordV1;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * D7 read-only dashboard service. Controller -> DashboardService -> existing services; the
 * service never opens CSVs itself - it reuses HistoryQueryService, PublishedQueryService,
 * ConfigManagementService and MaterialRoutePlanService, and reads warning evidence only through
 * manifest-verified warning files (the WarningStore is write/exists only). All status
 * derivation happens HERE in Java; the Vue layer only renders the returned strings.
 */
public final class DashboardService {

    private static final int WARNING_SCAN_MONTHS_BACK = 3;

    private final DataRoot dataRoot;
    private final ConfigManagementService configs;
    private final PublishedQueryService published;
    private final HistoryQueryService history;
    private final Clock clock;

    public DashboardService(
            DataRoot dataRoot,
            ConfigManagementService configs,
            PublishedQueryService published,
            HistoryQueryService history,
            Clock clock
    ) {
        this.dataRoot = Objects.requireNonNull(dataRoot, "dataRoot");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.published = Objects.requireNonNull(published, "published");
        this.history = Objects.requireNonNull(history, "history");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public DashboardV1.OverviewResponse overview() {
        MonitorSeriesConfigV1 config = configs.active();
        List<DashboardV1.ItemCard> cards = new ArrayList<>();
        for (MonitorSeriesItemV1 item : config.items()) {
            if (!item.enabled()) {
                continue;
            }
            cards.add(itemCard(item));
        }
        List<String> warnings = new ArrayList<>();
        for (MonitorSeriesItemV1 item : config.items()) {
            if (item.enabled()) {
                String summary = warningSummary(item.itemId());
                if (summary != null) {
                    warnings.add(summary);
                }
            }
        }
        return new DashboardV1.OverviewResponse(
                config.mode() == null ? null : config.mode().name(),
                List.copyOf(cards), List.copyOf(warnings));
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
                    latest.validationVersion() == null ? null : "VERIFIED",
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
                source, quality, warningSummary(item.itemId()));
    }

    public DashboardV1.HistoryResponse history(String itemId, String fromDate, String toDate) {
        Objects.requireNonNull(itemId, "itemId");
        LocalDate from = LocalDate.parse(fromDate);
        LocalDate to = LocalDate.parse(toDate);
        HistoryQueryService.DailyHistoryResult result = history.queryDaily(itemId, from, to);
        List<DashboardV1.HistoryPoint> points = new ArrayList<>();
        for (var row : result.rows()) {
            points.add(new DashboardV1.HistoryPoint(
                    row.businessDate(), row.avg(), row.unit(), row.actualSourceName(),
                    row.validationStatus() == null ? null : row.validationStatus().wireValue(),
                    row.validationVersion()));
        }
        String dataThrough = points.isEmpty() ? null
                : points.stream().max(Comparator.comparing(DashboardV1.HistoryPoint::businessDate))
                .orElseThrow().businessDate();
        return new DashboardV1.HistoryResponse(
                itemId, fromDate, toDate, List.copyOf(points),
                List.copyOf(result.missingRefs()), List.copyOf(result.corruptRefs()), dataThrough);
    }

    public DashboardV1.MetricsResponse metrics(String itemId, String grain, int fromYear, int toYear) {
        HistoryQueryService.AggregateHistoryResult result = history.queryAggregate(itemId, grain, fromYear, toYear);
        List<DashboardV1.MetricRow> rows = new ArrayList<>();
        for (var row : result.rows()) {
            rows.add(new DashboardV1.MetricRow(
                    row.periodStart(), row.periodEnd(), row.avg(), row.unit(), row.actualSourceName(),
                    row.validationStatus() == null ? null : row.validationStatus().wireValue(),
                    row.validationVersion()));
        }
        return new DashboardV1.MetricsResponse(
                itemId, grain, fromYear, toYear, List.copyOf(rows),
                List.copyOf(result.missingRefs()), List.copyOf(result.corruptRefs()));
    }

    public DashboardV1.QualityResponse quality(String itemId, String fromDate, String toDate) {
        LocalDate from = LocalDate.parse(fromDate);
        LocalDate to = LocalDate.parse(toDate);
        HistoryQueryService.DailyHistoryResult result = history.queryDaily(itemId, from, to);
        List<DashboardV1.QualityRow> rows = new ArrayList<>();
        for (var row : result.rows()) {
            rows.add(new DashboardV1.QualityRow(
                    row.businessDate(), row.avg(), row.unit(), row.actualSourceName(),
                    row.providerType() == null ? null : row.providerType().wireValue(),
                    row.accessMethod() == null ? null : row.accessMethod().wireValue(),
                    row.validationStatus() == null ? null : row.validationStatus().wireValue(),
                    row.validationVersion(),
                    false));
        }
        PublishedRecord latest = published.latestPublished(itemId);
        List<DashboardV1.WarningView> warnings = readWarnings(itemId);
        return new DashboardV1.QualityResponse(
                itemId,
                latest == null ? "NO_DATA" : latest.stale() ? "STALE" : "VERIFIED",
                List.copyOf(rows), List.copyOf(warnings),
                List.copyOf(result.missingRefs()), List.copyOf(result.corruptRefs()));
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
                new DashboardV1.EntryStatus("PENDING", "manual intake HTTP entry is a Day8 contract - use the backend service directly"),
                new DashboardV1.EntryStatus("PENDING", "file import HTTP entry is a Day8 contract - use the backend service directly"));
    }

    private String warningSummary(String itemId) {
        List<DashboardV1.WarningView> warnings = readWarnings(itemId);
        if (warnings.isEmpty()) {
            return null;
        }
        return "warnings: " + warnings.size() + " (latest " + warnings.get(0).riskLevel() + ")";
    }

    /** Reads manifest-verified warning files for one item (newest first, bounded lookback). */
    private List<DashboardV1.WarningView> readWarnings(String itemId) {
        List<DashboardV1.WarningView> warnings = new ArrayList<>();
        YearMonth month = YearMonth.from(OffsetDateTime.now(clock));
        for (int back = 0; back < WARNING_SCAN_MONTHS_BACK; back++) {
            YearMonth target = month.minusMonths(back);
            Path dir = dataRoot.resolveInternalRelative("warning").resolve(target.toString());
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> files = Files.list(dir)) {
                for (Path file : files.toList()) {
                    if (!file.getFileName().toString().endsWith(".json")) {
                        continue;
                    }
                    String ref = "warning/" + target + "/" + file.getFileName();
                    Path manifest = dataRoot.resolveDataRef(DataPaths.manifestRef(ref));
                    if (!Files.isRegularFile(manifest)
                            || !ManifestVerifier.matches(dataRoot, ref, file, manifest)) {
                        continue;
                    }
                    WarningRecordV1 warning = JsonV1Codec.decodeFile(Files.readAllBytes(file),
                            WarningRecordV1.class);
                    if (!itemId.equals(warning.itemId())) {
                        continue;
                    }
                    warnings.add(new DashboardV1.WarningView(
                            warning.warningId(), warning.ruleId(), warning.ruleVersion(),
                            warning.periodStart(), warning.periodEnd(),
                            warning.currentValue(), warning.threshold(),
                            warning.riskLevel() == null ? null : warning.riskLevel().name(),
                            warning.dataStatus(),
                            warning.evaluatedAt() == null ? null : warning.evaluatedAt().toString()));
                }
            } catch (IOException | RuntimeException ignored) {
                // a broken warning file is skipped - the quality page still shows the rest
            }
        }
        warnings.sort(Comparator.comparing(DashboardV1.WarningView::createdAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return warnings;
    }
}
