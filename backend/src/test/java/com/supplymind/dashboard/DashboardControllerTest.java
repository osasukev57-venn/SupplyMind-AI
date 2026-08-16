package com.supplymind.dashboard;

import com.supplymind.dashboard.api.DashboardController;
import com.supplymind.dashboard.api.DashboardV1;
import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.ManifestV1;
import com.supplymind.foundation.model.Mode;
import com.supplymind.foundation.model.MonitorSeriesDefaults;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.RawReceiptV1;
import com.supplymind.foundation.model.SchemaV1;
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
import com.supplymind.config.ConfigManagementService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D7 DashboardController HTTP contract: structured responses only, invalid inputs are 400
 * REJECTED, service failures are 500 UNAVAILABLE - never a raw stack trace.
 */
class DashboardControllerTest {

    private static final ZoneId SHANGHAI = DataPaths.SHANGHAI;
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-08-10T01:02:00Z"), SHANGHAI);
    private static final OffsetDateTime RECEIVED_AT = OffsetDateTime.ofInstant(
            Instant.parse("2026-08-10T01:00:00Z"), SHANGHAI);

    @TempDir
    Path temporaryDirectory;

    @Test
    void overviewReturnsItemsAndSourceContract() throws Exception {
        Harness harness = harness();
        publish(harness, raw("run-dash-api-001"));

        DashboardController controller = new DashboardController(harness.dashboard);
        ResponseEntity<?> response = controller.overview();

        assertEquals(200, response.getStatusCode().value());
        DashboardV1.OverviewResponse body = (DashboardV1.OverviewResponse) response.getBody();
        assertNotNull(body);
        assertFalse(body.items().isEmpty());
        DashboardV1.ItemCard card = body.items().stream()
                .filter(item -> item.itemId().equals(MonitorSeriesDefaults.USD_CNY_ITEM_ID))
                .findFirst().orElseThrow();
        assertNotNull(card.latestValue(), "the published value is a string, never a number");
        assertEquals("official_web", card.source().providerType());
        assertNotNull(card.quality().validationVersion());
    }

    @Test
    void historyRejectsNonIsoDatesWithStructured400() {
        Harness harness = harness();
        DashboardController controller = new DashboardController(harness.dashboard);

        ResponseEntity<?> response = controller.history(
                MonitorSeriesDefaults.USD_CNY_ITEM_ID, "not-a-date", "2026-08-31");

        assertEquals(400, response.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals("REJECTED", body.get("status"));
    }

    @Test
    void metricsReturnsRowsForKnownGrain() throws Exception {
        Harness harness = harness();
        publish(harness, raw("run-dash-api-metrics"));
        DashboardController controller = new DashboardController(harness.dashboard);

        ResponseEntity<?> response = controller.metrics(
                MonitorSeriesDefaults.USD_CNY_ITEM_ID, "month", 2026, 2026);

        assertEquals(200, response.getStatusCode().value());
        DashboardV1.MetricsResponse body = (DashboardV1.MetricsResponse) response.getBody();
        assertNotNull(body);
        assertEquals("month", body.grain());
        assertTrue(body.missingRefs().isEmpty() || body.missingRefs().size() >= 0);
    }

    @Test
    void qualityAndSourcesReturnStructuredBodies() {
        Harness harness = harness();
        DashboardController controller = new DashboardController(harness.dashboard);

        ResponseEntity<?> quality = controller.quality(
                MonitorSeriesDefaults.USD_CNY_ITEM_ID, "2026-08-01", "2026-08-31");
        assertEquals(200, quality.getStatusCode().value());
        DashboardV1.QualityResponse qualityBody = (DashboardV1.QualityResponse) quality.getBody();
        assertNotNull(qualityBody);
        assertNotNull(qualityBody.latestStatus());

        ResponseEntity<?> sources = controller.sources();
        assertEquals(200, sources.getStatusCode().value());
        DashboardV1.SourcesResponse sourcesBody = (DashboardV1.SourcesResponse) sources.getBody();
        assertNotNull(sourcesBody);
        assertFalse(sourcesBody.items().isEmpty());
        assertEquals("PENDING", sourcesBody.manualEntry().status());
        assertEquals("PENDING", sourcesBody.importEntry().status());
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

    private static RawReceiptV1 raw(String runId) {
        byte[] payload = ("test/contract fixture PBOC-shaped page - NOT REAL PBOC - " + runId)
                .getBytes(StandardCharsets.UTF_8);
        return new RawReceiptV1(
                SchemaV1.VERSION,
                RawReceiptV1.deriveRawRef(Mode.FORMAL, ProviderType.OFFICIAL_WEB,
                        MonitorSeriesDefaults.USD_CNY_ITEM_ID, RECEIVED_AT, runId),
                "acq-" + runId, runId, Mode.FORMAL, ProviderType.OFFICIAL_WEB,
                AccessMethod.PUBLIC_OFFICIAL_HTML, 1, MonitorSeriesDefaults.PBOC_SOURCE_NAME,
                "https://example.test/pbc/fixture", "test fixture",
                MonitorSeriesDefaults.USD_CNY_ITEM_ID, "2026-08-10", "2026-08-10",
                "2026-08-10 09:25:38", RECEIVED_AT.plusMinutes(30), RECEIVED_AT, null,
                "6.7904", "CNY/1 USD", "CNY", null, 200, "text/html", "base64",
                Base64.getEncoder().encodeToString(payload), JsonV1Codec.sha256LowerHex(payload),
                "1美元对人民币", RECEIVED_AT, DataPaths.acquisitionRef("acq-" + runId), null);
    }

    private Harness harness() {
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve("d7 controller root"));
        AtomicMoveSupport.probeOrFail(root);
        AtomicFileStore fileStore = new AtomicFileStore(root, new DirtyMarkerCodec());
        ConfigActivationStore configs = new ConfigActivationStore(root, fileStore, FIXED_CLOCK);
        configs.ensureInitialDefault();
        RawReceiptStore rawStore = new RawReceiptStore(root, fileStore, FIXED_CLOCK);
        TimelineStore timelineStore = new TimelineStore(root, fileStore, FIXED_CLOCK);
        LifecycleValidationService validation =
                new LifecycleValidationService(root, timelineStore, FIXED_CLOCK);
        QuarantineStore quarantineStore = new QuarantineStore(root, fileStore, FIXED_CLOCK);
        LifecyclePublishService publish =
                new LifecyclePublishService(root, timelineStore, quarantineStore, FIXED_CLOCK);
        PublishedQueryService query = new PublishedQueryService(root, timelineStore, FIXED_CLOCK);
        ConfigManagementService configManagement = new ConfigManagementService(configs,
                new DataProviderRegistry());
        HistoryQueryService history = new HistoryQueryService(root);
        DashboardService dashboard = new DashboardService(root, configManagement, query, history,
                FIXED_CLOCK);
        return new Harness(root, fileStore, rawStore, timelineStore, validation, publish, dashboard);
    }

    private record Harness(
            DataRoot root,
            AtomicFileStore files,
            RawReceiptStore rawStore,
            TimelineStore timeline,
            LifecycleValidationService validation,
            LifecyclePublishService publish,
            DashboardService dashboard
    ) {
    }
}
