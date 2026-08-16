package com.supplymind.dashboard;

import com.supplymind.config.ConfigManagementService;
import com.supplymind.dashboard.api.DashboardV1;
import com.supplymind.foundation.codec.CsvV1Codec;
import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.CandidateV1;
import com.supplymind.foundation.model.DailyInputRefV1;
import com.supplymind.foundation.model.DailyRecordV1;
import com.supplymind.foundation.model.ManifestV1;
import com.supplymind.foundation.model.Mode;
import com.supplymind.foundation.model.MonitorSeriesDefaults;
import com.supplymind.foundation.model.ProcessingStage;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.RawReceiptV1;
import com.supplymind.foundation.model.SchemaV1;
import com.supplymind.foundation.model.ValidationStatus;
import com.supplymind.foundation.storage.AtomicFileStore;
import com.supplymind.foundation.storage.AtomicMoveSupport;
import com.supplymind.foundation.storage.ConfigActivationStore;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.DirtyMarkerCodec;
import com.supplymind.foundation.storage.ManifestFactory;
import com.supplymind.foundation.storage.QuarantineStore;
import com.supplymind.foundation.storage.RawAcquisitionStore;
import com.supplymind.foundation.storage.RawReceiptStore;
import com.supplymind.foundation.storage.TimelineStore;
import com.supplymind.history.HistoryQueryService;
import com.supplymind.provider.DataProviderRegistry;
import com.supplymind.publish.LifecyclePublishService;
import com.supplymind.publish.PublishedQueryService;
import com.supplymind.validation.LifecycleValidationService;
import com.supplymind.validation.PbocCandidateStandardizer;
import com.supplymind.warning.WarningRecordV1;
import com.supplymind.warning.WarningService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D7 DashboardService: controller -> service -> EXISTING services (published query, history
 * query, config management, manifest-verified warning files). All business values/statuses are
 * computed in Java; the DTOs never leak file models.
 */
class DashboardServiceTest {

    private static final ZoneId SHANGHAI = DataPaths.SHANGHAI;
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-08-10T01:02:00Z"), SHANGHAI);
    private static final OffsetDateTime RECEIVED_AT = OffsetDateTime.ofInstant(
            Instant.parse("2026-08-10T01:00:00Z"), SHANGHAI);
    private static final String SOURCE_NAME = MonitorSeriesDefaults.PBOC_SOURCE_NAME;

    @TempDir
    Path temporaryDirectory;

    @Test
    void overviewShowsPublishedValueSourceAndQuality() throws Exception {
        Harness harness = harness();
        publish(harness, pbocRaw("run-dash-overview-001", "6.7904", "2026-08-10"));

        DashboardV1.OverviewResponse overview = harness.dashboard().overview();

        DashboardV1.ItemCard usd = overview.items().stream()
                .filter(card -> card.itemId().equals(MonitorSeriesDefaults.USD_CNY_ITEM_ID))
                .findFirst().orElseThrow();
        assertEquals("6.7904", usd.latestValue(), "the exact BigDecimal string must be shown");
        assertEquals("2026-08-10", usd.businessDate());
        assertEquals("CNY/1 USD", usd.unit());
        assertNotNull(usd.source());
        assertEquals("official_web", usd.source().providerType());
        assertEquals(SOURCE_NAME, usd.source().actualSourceName());
        assertEquals("VERIFIED", usd.quality().status());
        assertEquals("pboc-basic-validation-v1", usd.quality().validationVersion());
        assertFalse(usd.quality().stale());
        assertNotNull(usd.quality().updatedAt());

        DashboardV1.ItemCard eur = overview.items().stream()
                .filter(card -> card.itemId().equals(MonitorSeriesDefaults.EUR_CNY_ITEM_ID))
                .findFirst().orElseThrow();
        assertEquals("NO_DATA", eur.quality().status(),
                "an item without any published record must be honest NO_DATA, never a fake value");
        assertNull(eur.latestValue());
    }

    @Test
    void historyReturnsExactDailyPointsWithSource() throws Exception {
        Harness harness = harness();
        publish(harness, pbocRaw("run-dash-history-001", "6.7904", "2026-08-10"));
        writeDaily(harness, "6.79040000");

        DashboardV1.HistoryResponse history = harness.dashboard().history(
                MonitorSeriesDefaults.USD_CNY_ITEM_ID, "2026-08-01", "2026-08-31");

        assertEquals(1, history.points().size());
        DashboardV1.HistoryPoint point = history.points().get(0);
        assertEquals("2026-08-10", point.businessDate());
        assertEquals("6.79040000", point.value(), "daily avg string passes through unchanged");
        assertEquals(SOURCE_NAME, point.actualSourceName());
        assertEquals("VERIFIED", point.validationStatus());
        assertTrue(history.evidenceIssues().isEmpty());
        assertEquals("2026-08-10", history.dataThrough());
        assertNotNull(history.chart());
        assertEquals(1, history.chart().points().size(),
                "the backend supplies the chart coordinates - the browser never computes them");
        assertEquals("2026-08-10 6.79040000", history.chart().points().get(0).label());
    }

    @Test
    void metricsReturnsAggregateRows() throws Exception {
        Harness harness = harness();
        publish(harness, pbocRaw("run-dash-metrics-001", "6.7904", "2026-08-10"));
        writeDaily(harness, "6.79040000");
        writeAggregate(harness, "2026-08-01", "2026-08-31", "6.79040000");

        DashboardV1.MetricsResponse metrics = harness.dashboard().metrics(
                MonitorSeriesDefaults.USD_CNY_ITEM_ID, "month", 2026, 2026);

        assertEquals(1, metrics.rows().size());
        DashboardV1.MetricRow row = metrics.rows().get(0);
        assertEquals("2026-08-01", row.periodStart());
        assertEquals("2026-08-31", row.periodEnd());
        assertEquals("6.79040000", row.value());
        assertEquals(SOURCE_NAME, row.actualSourceName());
        assertEquals("VERIFIED", row.validationStatus());
    }

    @Test
    void qualityShowsRowsWarningsAndEvidenceStatus() throws Exception {
        Harness harness = harness();
        publish(harness, pbocRaw("run-dash-quality-001", "6.7904", "2026-08-10"));
        writeDaily(harness, "6.79040000");
        writeWarning(harness, MonitorSeriesDefaults.USD_CNY_ITEM_ID);

        DashboardV1.QualityResponse quality = harness.dashboard().quality(
                MonitorSeriesDefaults.USD_CNY_ITEM_ID, "2026-08-01", "2026-08-31");

        assertEquals("VERIFIED", quality.latestStatus());
        assertEquals(1, quality.rows().size());
        assertEquals("VERIFIED", quality.rows().get(0).validationStatus());
        assertEquals(1, quality.warnings().size());
        assertEquals("dash-test-warning", quality.warnings().get(0).warningId());
        assertEquals("HIGH", quality.warnings().get(0).riskLevel());
        assertTrue(quality.evidenceIssues().isEmpty());
    }

    @Test
    void sourcesShowsRoutesAndHonestPendingEntries() throws Exception {
        Harness harness = harness();

        DashboardV1.SourcesResponse sources = harness.dashboard().sources();

        assertFalse(sources.items().isEmpty());
        DashboardV1.SourceItem usd = sources.items().stream()
                .filter(item -> item.itemId().equals(MonitorSeriesDefaults.USD_CNY_ITEM_ID))
                .findFirst().orElseThrow();
        assertEquals(SOURCE_NAME, usd.actualSourceName());
        assertEquals("official_web", usd.providerType());
        assertNotNull(usd.routeDecision());
        assertEquals("PENDING", sources.manualEntry().status(),
                "the manual HTTP entry is a Day8 contract - never fake completion");
        assertEquals("PENDING", sources.importEntry().status(),
                "the import HTTP entry is a Day8 contract - never fake completion");
    }

    @Test
    void historyMissingPeriodIsHonestAsBusinessReference() throws Exception {
        Harness harness = harness();
        publish(harness, pbocRaw("run-dash-missing-001", "6.7904", "2026-08-10"));

        DashboardV1.HistoryResponse history = harness.dashboard().history(
                MonitorSeriesDefaults.USD_CNY_ITEM_ID, "2026-01-01", "2026-01-31");

        assertTrue(history.points().isEmpty(), "missing months must not be interpolated");
        assertFalse(history.evidenceIssues().isEmpty(), "the missing period is reported");
        DashboardV1.EvidenceIssue issue = history.evidenceIssues().get(0);
        assertEquals("MISSING", issue.status());
        assertTrue(issue.periods().contains("2026-01"),
                "the issue is a BUSINESS period reference, never an internal CSV path");
        assertFalse(issue.periods().get(0).contains("processed/"),
                "internal CSV paths must never leave the backend");
        assertNull(history.dataThrough());
    }

    @Test
    void staleBoundaryIsRealAtThirtyDays() throws Exception {
        Harness harness = harness();
        publish(harness, pbocRaw("run-dash-stale-001", "6.7904", "2026-08-10"));
        // FIXED_CLOCK reference date is 2026-08-10: a business date exactly 30 days earlier
        // (2026-07-11) is NOT stale; 31 days earlier (2026-07-10) IS stale (DEC-051 rule).
        String dailyRef = DataPaths.dailyRef(MonitorSeriesDefaults.USD_CNY_ITEM_ID, YearMonth.of(2026, 7));
        List<DailyRecordV1> rows = List.of(
                dailyRow("2026-07-11", "6.70000000", "run-dash-stale-001"),
                dailyRow("2026-07-10", "6.69000000", "run-dash-stale-001"));
        byte[] data = CsvV1Codec.encodeDaily(rows);
        ManifestV1 manifest = ManifestFactory.csv(dailyRef, data, 2, "2026-07-10", "2026-07-11",
                List.of("run-dash-stale-001"), RECEIVED_AT);
        Path target = harness.root.resolveDataRef(dailyRef);
        Files.createDirectories(target.getParent());
        Files.write(target, data);
        Files.write(harness.root.resolveDataRef(DataPaths.manifestRef(dailyRef)),
                JsonV1Codec.encodeFile(manifest));

        DashboardV1.QualityResponse quality = harness.dashboard.quality(
                MonitorSeriesDefaults.USD_CNY_ITEM_ID, "2026-07-01", "2026-08-31");

        DashboardV1.QualityRow boundary = quality.rows().stream()
                .filter(row -> row.businessDate().equals("2026-07-11")).findFirst().orElseThrow();
        DashboardV1.QualityRow past = quality.rows().stream()
                .filter(row -> row.businessDate().equals("2026-07-10")).findFirst().orElseThrow();
        assertFalse(boundary.stale(), "30 days back is the boundary - NOT stale");
        assertTrue(past.stale(), "31 days back IS stale");
    }

    private static DailyRecordV1 dailyRow(String businessDate, String avg, String runId) {
        return new DailyRecordV1("1.0", businessDate, MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                ProviderType.OFFICIAL_WEB, SOURCE_NAME, AccessMethod.PUBLIC_OFFICIAL_HTML,
                ProcessingStage.PUBLISHED, ValidationStatus.VERIFIED, "pboc-basic-validation-v1",
                List.of(1), "arithmetic-mean-v1", 8, 4, RoundingMode.HALF_UP,
                "weekday-asia-shanghai-v1", avg, 1, avg, 1, 0, true, "CNY", "CNY/1 USD",
                List.of(new DailyInputRefV1(runId,
                        "raw/formal/official_web/" + MonitorSeriesDefaults.USD_CNY_ITEM_ID
                                + "/2026/08/" + runId + ".json", 4)),
                RECEIVED_AT, null);
    }

    @Test
    void completenessIsBackendComputed() throws Exception {
        Harness harness = harness();
        publish(harness, pbocRaw("run-dash-comp-001", "6.7904", "2026-08-10"));
        String dailyRef = DataPaths.dailyRef(MonitorSeriesDefaults.USD_CNY_ITEM_ID, YearMonth.of(2026, 8));
        writeDailyAt(harness, dailyRef, "2026-08-10", "6.79040000", "run-dash-comp-001");

        DashboardV1.OverviewResponse overview = harness.dashboard.overview();
        DashboardV1.ItemCard usd = overview.items().stream()
                .filter(card -> card.itemId().equals(MonitorSeriesDefaults.USD_CNY_ITEM_ID))
                .findFirst().orElseThrow();
        assertEquals("1.000000000000", usd.completeness(),
                "completeness is a backend-computed decimal string");
    }

    @Test
    void aggregateSummarySelectsLatestValidRecordAcrossYears() throws Exception {
        Harness harness = harness();
        publish(harness, pbocRaw("run-dash-agg-001", "6.7904", "2026-08-10"));
        // 2024 has a valid aggregate; 2025 and 2026 have NONE - the summary must still pick 2024.
        String dailyRef = DataPaths.dailyRef(MonitorSeriesDefaults.USD_CNY_ITEM_ID, YearMonth.of(2026, 8));
        writeDailyAt(harness, dailyRef, "2026-08-10", "6.79040000", "run-dash-agg-001");
        writeAggregate(harness, "2024-01-01", "2024-01-31", "6.50000000", 2024, "run-dash-agg-001");

        DashboardV1.OverviewResponse overview = harness.dashboard.overview();
        DashboardV1.ItemCard usd = overview.items().stream()
                .filter(card -> card.itemId().equals(MonitorSeriesDefaults.USD_CNY_ITEM_ID))
                .findFirst().orElseThrow();
        assertNotNull(usd.aggregateSummary(),
                "an older valid aggregate must not be shadowed by empty current years");
        assertEquals("2024-01-01", usd.aggregateSummary().periodStart());
        assertEquals("6.50000000", usd.aggregateSummary().value());
    }

    @Test
    void crossYearMetricsAndHistoryPassRealYears() throws Exception {
        Harness harness = harness();
        publish(harness, pbocRaw("run-dash-cross-001", "6.7904", "2026-08-10"));
        String dailyRef24 = DataPaths.dailyRef(MonitorSeriesDefaults.USD_CNY_ITEM_ID, YearMonth.of(2024, 12));
        String dailyRef26 = DataPaths.dailyRef(MonitorSeriesDefaults.USD_CNY_ITEM_ID, YearMonth.of(2026, 8));
        writeDailyAt(harness, dailyRef24, "2024-12-20", "6.10000000", "run-dash-cross-001");
        writeDailyAt(harness, dailyRef26, "2026-08-10", "6.79040000", "run-dash-cross-001");
        writeAggregate(harness, "2024-12-01", "2024-12-31", "6.10000000", 2024, "run-dash-cross-001");
        writeAggregate(harness, "2026-08-01", "2026-08-31", "6.79040000", 2026, "run-dash-cross-001");

        DashboardV1.MetricsResponse metrics = harness.dashboard.metrics(
                MonitorSeriesDefaults.USD_CNY_ITEM_ID, "month", 2024, 2026);
        assertEquals(2024, metrics.fromYear(), "the real fromYear must be passed through");
        assertEquals(2026, metrics.toYear(), "the real toYear must be passed through");
        assertEquals(2, metrics.rows().size(), "aggregates from both years are returned");

        DashboardV1.HistoryResponse history = harness.dashboard.history(
                MonitorSeriesDefaults.USD_CNY_ITEM_ID, "2024-12-01", "2026-08-31");
        assertEquals(2, history.points().size(), "daily points span both years");
        assertTrue(history.points().stream()
                        .anyMatch(point -> point.businessDate().equals("2024-12-20")),
                "the 2024 point is present in the cross-year history");
    }

    @Test
    void manualPendingReturnsStructuredPendingAndNeverPersists() throws Exception {
        Harness harness = harness();

        DashboardV1.ManualPendingResponse pending = harness.dashboard.manualPending(
                MonitorSeriesDefaults.USD_CNY_ITEM_ID, "operator source", "2026-08-10",
                "6.7904", "CNY/1 USD");
        assertEquals("PENDING", pending.status());
        assertEquals(MonitorSeriesDefaults.USD_CNY_ITEM_ID, pending.itemId());
        assertEquals("operator source", pending.source(),
                "source is part of the frozen pending contract");
        assertEquals("CNY/1 USD", pending.unit(),
                "unit is part of the frozen pending contract");
        assertEquals("2026-08-10", pending.businessDate());
        assertEquals("6.7904", pending.value());
        assertFalse(pending.message().isBlank());

        assertThrows(IllegalArgumentException.class,
                () -> harness.dashboard.manualPending(MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                        null, "2026-08-10", "6.7904", "CNY/1 USD"),
                "a submission without a source is an invalid request");
        assertThrows(IllegalArgumentException.class,
                () -> harness.dashboard.manualPending(MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                        "source", "2026-08-10", "6.7904", null),
                "a submission without a unit is an invalid request");
        assertThrows(IllegalArgumentException.class,
                () -> harness.dashboard.manualPending(MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                        "source", "2026-08-10", null, "CNY/1 USD"),
                "a submission without a value is an invalid request");
    }

    @Test
    void importCsvIsReallyParsedAndXlsxIsExplicitlyRejected() throws Exception {
        Harness harness = harness();
        // Frozen CSV schema: itemId, source, businessDate, value, unit.
        String csv = "itemId,source,businessDate,value,unit\n"
                + MonitorSeriesDefaults.USD_CNY_ITEM_ID + ",source A,2026-08-10,6.7904,CNY/1 USD\n"
                + "badrow\n"
                + "FX.OTHER,source B,2026-08-12,7.0,CNY/1 USD\n"
                + MonitorSeriesDefaults.USD_CNY_ITEM_ID + ",,2026-08-13,8.0,CNY/1 USD\n"
                + MonitorSeriesDefaults.USD_CNY_ITEM_ID + ",source C,2026-08-14,9.0,\n";
        DashboardV1.ImportResponse response = harness.dashboard.importPending("rows.csv",
                csv.getBytes(StandardCharsets.UTF_8));
        assertEquals("PENDING", response.status());
        assertEquals(2, response.previewRows().size(), "valid rows are really parsed");
        assertEquals(3, response.rowErrors().size(),
                "missing source/unit rows must enter rowErrors, never PENDING preview");
        assertEquals(3, response.rowErrors().get(0).rowNumber());
        assertEquals("来源为空", response.rowErrors().get(1).message());
        assertEquals("单位为空", response.rowErrors().get(2).message());
        assertFalse(response.message().isBlank());

        DashboardV1.ImportResponse xlsx = harness.dashboard.importPending("book.xlsx",
                new byte[]{1, 2, 3});
        assertEquals("REJECTED", xlsx.status(),
                "xlsx real parsing is a Day8 boundary - never pretended");
    }

    @Test
    void brokenWarningFileNeverAbortsTheScan() throws Exception {
        Harness harness = harness();
        publish(harness, pbocRaw("run-dash-warn-001", "6.7904", "2026-08-10"));
        writeDaily(harness, "6.79040000");
        writeWarning(harness, MonitorSeriesDefaults.USD_CNY_ITEM_ID);
        // A broken warning file (no manifest) sits next to the valid one - it must be SKIPPED.
        Path brokenDir = harness.root.resolveInternalRelative("warning").resolve("2026-08");
        Files.createDirectories(brokenDir);
        Files.write(brokenDir.resolve("broken.json"),
                "this is not a warning record".getBytes(StandardCharsets.UTF_8));

        List<WarningRecordV1> recent = harness.warnings.findRecent(
                MonitorSeriesDefaults.USD_CNY_ITEM_ID, 3);

        assertEquals(1, recent.size(),
                "the valid warning must still be returned when a neighbour file is broken");
        assertEquals("dash-test-warning", recent.get(0).warningId());
    }

    // ---- fixture ----

    private static void publish(Harness harness, RawReceiptV1 raw) throws Exception {
        new RawAcquisitionStore(harness.root, harness.files, FIXED_CLOCK)
                .store(com.supplymind.foundation.model.DomainFixtures.acquisitionFor(raw));
        harness.rawStore.store(raw);
        harness.timeline.createInitial(raw.runId(), raw.rawRef(), raw.receivedAt());
        harness.validation.process(raw.runId());
        harness.publish.process(raw.runId());
    }

    private static RawReceiptV1 pbocRaw(String runId, String value, String businessDate) {
        byte[] payload = ("test/contract fixture PBOC-shaped page - NOT REAL PBOC - " + runId)
                .getBytes(StandardCharsets.UTF_8);
        return new RawReceiptV1(
                SchemaV1.VERSION,
                RawReceiptV1.deriveRawRef(Mode.FORMAL, ProviderType.OFFICIAL_WEB,
                        MonitorSeriesDefaults.USD_CNY_ITEM_ID, RECEIVED_AT, runId),
                "acq-" + runId,
                runId,
                Mode.FORMAL,
                ProviderType.OFFICIAL_WEB,
                AccessMethod.PUBLIC_OFFICIAL_HTML,
                1,
                SOURCE_NAME,
                "https://example.test/pbc/fixture",
                "test fixture",
                MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                businessDate,
                businessDate,
                "2026-08-10 09:25:38",
                RECEIVED_AT.plusMinutes(30),
                RECEIVED_AT,
                null,
                value,
                "CNY/1 USD",
                "CNY",
                null,
                200,
                "text/html",
                "base64",
                Base64.getEncoder().encodeToString(payload),
                JsonV1Codec.sha256LowerHex(payload),
                "1美元对人民币",
                RECEIVED_AT,
                DataPaths.acquisitionRef("acq-" + runId),
                null
        );
    }

    private static void writeDaily(Harness harness, String avg) throws Exception {
        String ref = DataPaths.dailyRef(MonitorSeriesDefaults.USD_CNY_ITEM_ID, YearMonth.of(2026, 8));
        writeDailyAt(harness, ref, "2026-08-10", avg, "run-dash-history-001");
    }

    private static void writeDailyAt(Harness harness, String ref, String businessDate,
                                     String avg, String runId) throws Exception {
        DailyRecordV1 row = new DailyRecordV1("1.0", businessDate, MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                ProviderType.OFFICIAL_WEB, SOURCE_NAME, AccessMethod.PUBLIC_OFFICIAL_HTML,
                ProcessingStage.PUBLISHED, ValidationStatus.VERIFIED, "pboc-basic-validation-v1",
                List.of(1), "arithmetic-mean-v1", 8, 4, RoundingMode.HALF_UP,
                "weekday-asia-shanghai-v1", avg, 1, avg, 1, 0, true, "CNY", "CNY/1 USD",
                List.of(new DailyInputRefV1(runId,
                        "raw/formal/official_web/" + MonitorSeriesDefaults.USD_CNY_ITEM_ID
                                + "/2026/08/" + runId + ".json", 4)),
                RECEIVED_AT, null);
        byte[] data = CsvV1Codec.encodeDaily(List.of(row));
        ManifestV1 manifest = ManifestFactory.csv(ref, data, 1, businessDate, businessDate,
                List.of(runId), RECEIVED_AT);
        Path target = harness.root.resolveDataRef(ref);
        Files.createDirectories(target.getParent());
        Files.write(target, data);
        Files.write(harness.root.resolveDataRef(DataPaths.manifestRef(ref)),
                JsonV1Codec.encodeFile(manifest));
    }

    private static void writeAggregate(Harness harness, String start, String end, String avg)
            throws Exception {
        writeAggregate(harness, start, end, avg, 2026, "run-dash-history-001");
    }

    private static void writeAggregate(Harness harness, String start, String end, String avg,
                                       int year, String runId) throws Exception {
        String ref = DataPaths.aggregateRef(MonitorSeriesDefaults.USD_CNY_ITEM_ID, "month", year);
        String fingerprint = com.supplymind.foundation.model.CanonicalJsonV1.sha256LowerHex(
                com.supplymind.foundation.model.CanonicalJsonV1.sourceIdentity(
                        ProviderType.OFFICIAL_WEB, SOURCE_NAME, AccessMethod.PUBLIC_OFFICIAL_HTML));
        // The aggregate inputRef targets the persisted 2026-08 daily fixture (manifest-valid).
        String dailyRef = DataPaths.dailyRef(MonitorSeriesDefaults.USD_CNY_ITEM_ID, YearMonth.of(2026, 8));
        String dailyHash = JsonV1Codec.decodeFile(Files.readAllBytes(
                harness.root.resolveDataRef(DataPaths.manifestRef(dailyRef))), ManifestV1.class).fileSha256();
        com.supplymind.foundation.model.AggregateRecordV1 row =
                new com.supplymind.foundation.model.AggregateRecordV1(
                        "1.0", com.supplymind.foundation.model.AggregateGrain.MONTH, start, end,
                        MonitorSeriesDefaults.USD_CNY_ITEM_ID, ProviderType.OFFICIAL_WEB, SOURCE_NAME,
                        AccessMethod.PUBLIC_OFFICIAL_HTML, ValidationStatus.VERIFIED,
                        "pboc-basic-validation-v1", List.of(1), "arithmetic-mean-v1",
                        8, 4, RoundingMode.HALF_UP, "weekday-asia-shanghai-v1", avg, 1, avg, avg, avg,
                        1, 0, true, com.supplymind.foundation.model.QualityStatus.COMPLETE,
                        "CNY", "CNY/1 USD", fingerprint,
                        List.of(new com.supplymind.foundation.model.AggregateInputRefV1(
                                dailyRef, "2026-08-10", "pboc-basic-validation-v1", dailyHash)),
                        RECEIVED_AT, null);
        byte[] data = CsvV1Codec.encodeAggregate(List.of(row));
        ManifestV1 manifest = ManifestFactory.csv(ref, data, 1, start, end,
                List.of(runId), RECEIVED_AT);
        Path target = harness.root.resolveDataRef(ref);
        Files.createDirectories(target.getParent());
        Files.write(target, data);
        Files.write(harness.root.resolveDataRef(DataPaths.manifestRef(ref)),
                JsonV1Codec.encodeFile(manifest));
    }

    private static void writeWarning(Harness harness, String itemId) throws Exception {
        String ref = DataPaths.warningRef(YearMonth.of(2026, 8), "dash-test-warning");
        WarningRecordV1 warning = new WarningRecordV1(
                "1.0", "dash-test-warning", "demo-rule", "demo-v1", itemId, "month",
                "2026-08-01", "2026-08-31", null, "5.00000000", "7.00000000", "6.00000000",
                WarningRecordV1.RiskLevel.HIGH,
                List.of(DataPaths.dailyRef(MonitorSeriesDefaults.USD_CNY_ITEM_ID, YearMonth.of(2026, 8))),
                "PUBLISHED_VERIFIED", RECEIVED_AT,
                com.supplymind.foundation.model.CanonicalJsonV1.sha256LowerHex("dash-test-warning"),
                true, "test warning");
        byte[] data = JsonV1Codec.encodeFile(warning);
        ManifestV1 manifest = ManifestFactory.json(ref, data, List.of(), RECEIVED_AT);
        Path target = harness.root.resolveDataRef(ref);
        Files.createDirectories(target.getParent());
        Files.write(target, data);
        Files.write(harness.root.resolveDataRef(DataPaths.manifestRef(ref)),
                JsonV1Codec.encodeFile(manifest));
    }

    private Harness harness() {
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve("d7 dashboard root"));
        AtomicMoveSupport.probeOrFail(root);
        AtomicFileStore fileStore = new AtomicFileStore(root, new DirtyMarkerCodec());
        new ConfigActivationStore(root, fileStore, FIXED_CLOCK).ensureInitialDefault();
        RawReceiptStore rawStore = new RawReceiptStore(root, fileStore, FIXED_CLOCK);
        TimelineStore timelineStore = new TimelineStore(root, fileStore, FIXED_CLOCK);
        LifecycleValidationService validation =
                new LifecycleValidationService(root, timelineStore, FIXED_CLOCK);
        QuarantineStore quarantineStore = new QuarantineStore(root, fileStore, FIXED_CLOCK);
        LifecyclePublishService publish =
                new LifecyclePublishService(root, timelineStore, quarantineStore, FIXED_CLOCK);
        PublishedQueryService query = new PublishedQueryService(root, timelineStore, FIXED_CLOCK);
        ConfigManagementService configs = new ConfigManagementService(
                new ConfigActivationStore(root, fileStore, FIXED_CLOCK), new DataProviderRegistry());
        HistoryQueryService history = new HistoryQueryService(root);
        WarningService warnings = new WarningService(root,
                new com.supplymind.warning.WarningStore(root, fileStore, FIXED_CLOCK),
                FIXED_CLOCK, history);
        DashboardService dashboard = new DashboardService(configs, query, history, warnings, FIXED_CLOCK);
        return new Harness(root, fileStore, rawStore, timelineStore, validation, publish, query,
                dashboard, warnings);
    }

    private record Harness(
            DataRoot root,
            AtomicFileStore files,
            RawReceiptStore rawStore,
            TimelineStore timeline,
            LifecycleValidationService validation,
            LifecyclePublishService publish,
            PublishedQueryService published,
            DashboardService dashboard,
            WarningService warnings
    ) {
    }
}
