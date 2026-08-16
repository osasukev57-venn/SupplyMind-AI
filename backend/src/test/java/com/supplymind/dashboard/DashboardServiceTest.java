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
        assertTrue(history.missingRefs().isEmpty());
        assertEquals("2026-08-10", history.dataThrough());
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
        assertTrue(quality.evidenceMissingRefs().isEmpty());
        assertTrue(quality.evidenceCorruptRefs().isEmpty());
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
    void historyMissingPeriodIsHonest() throws Exception {
        Harness harness = harness();
        publish(harness, pbocRaw("run-dash-missing-001", "6.7904", "2026-08-10"));

        DashboardV1.HistoryResponse history = harness.dashboard().history(
                MonitorSeriesDefaults.USD_CNY_ITEM_ID, "2026-01-01", "2026-01-31");

        assertTrue(history.points().isEmpty(), "missing months must not be interpolated");
        assertFalse(history.missingRefs().isEmpty(), "the missing daily file is reported");
        assertNull(history.dataThrough());
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
        DailyRecordV1 row = new DailyRecordV1("1.0", "2026-08-10", MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                ProviderType.OFFICIAL_WEB, SOURCE_NAME, AccessMethod.PUBLIC_OFFICIAL_HTML,
                ProcessingStage.PUBLISHED, ValidationStatus.VERIFIED, "pboc-basic-validation-v1",
                List.of(1), "arithmetic-mean-v1", 8, 4, RoundingMode.HALF_UP,
                "weekday-asia-shanghai-v1", avg, 1, avg, 1, 0, true, "CNY", "CNY/1 USD",
                List.of(new DailyInputRefV1("run-dash-history-001",
                        "raw/formal/official_web/" + MonitorSeriesDefaults.USD_CNY_ITEM_ID
                                + "/2026/08/run-dash-history-001.json", 4)),
                RECEIVED_AT, null);
        byte[] data = CsvV1Codec.encodeDaily(List.of(row));
        ManifestV1 manifest = ManifestFactory.csv(ref, data, 1, "2026-08-10", "2026-08-10",
                List.of("run-dash-history-001"), RECEIVED_AT);
        Path target = harness.root.resolveDataRef(ref);
        Files.createDirectories(target.getParent());
        Files.write(target, data);
        Files.write(harness.root.resolveDataRef(DataPaths.manifestRef(ref)),
                JsonV1Codec.encodeFile(manifest));
    }

    private static void writeAggregate(Harness harness, String start, String end, String avg)
            throws Exception {
        String ref = DataPaths.aggregateRef(MonitorSeriesDefaults.USD_CNY_ITEM_ID, "month", 2026);
        String fingerprint = com.supplymind.foundation.model.CanonicalJsonV1.sha256LowerHex(
                com.supplymind.foundation.model.CanonicalJsonV1.sourceIdentity(
                        ProviderType.OFFICIAL_WEB, SOURCE_NAME, AccessMethod.PUBLIC_OFFICIAL_HTML));
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
                List.of("run-dash-history-001"), RECEIVED_AT);
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
        DashboardService dashboard = new DashboardService(root, configs, query, history, FIXED_CLOCK);
        return new Harness(root, fileStore, rawStore, timelineStore, validation, publish, query, dashboard);
    }

    private record Harness(
            DataRoot root,
            AtomicFileStore files,
            RawReceiptStore rawStore,
            TimelineStore timeline,
            LifecycleValidationService validation,
            LifecyclePublishService publish,
            PublishedQueryService published,
            DashboardService dashboard
    ) {
    }
}
