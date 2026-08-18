package com.supplymind.config;

import com.supplymind.backfill.BackfillJobQueryService;
import com.supplymind.backfill.BackfillJobStore;
import com.supplymind.backfill.BackfillOrchestrator;
import com.supplymind.config.api.ConfigV1;
import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.MonitorSeriesDefaults;
import com.supplymind.foundation.model.MonitorSeriesItemV1;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.RouteDecision;
import com.supplymind.foundation.storage.AtomicFileStore;
import com.supplymind.foundation.storage.AtomicMoveSupport;
import com.supplymind.foundation.storage.ConfigActivationStore;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.DirtyMarkerCodec;
import com.supplymind.foundation.storage.QuarantineStore;
import com.supplymind.foundation.storage.RawAcquisitionStore;
import com.supplymind.foundation.storage.RawReceiptStore;
import com.supplymind.foundation.storage.TimelineStore;
import com.supplymind.processing.AggregateProcessingService;
import com.supplymind.processing.DailyProcessingService;
import com.supplymind.provider.DataProviderRegistry;
import com.supplymind.provider.ProviderCollectRequest;
import com.supplymind.provider.pboc.PbocAnnouncementParser;
import com.supplymind.provider.pboc.PbocHttpResponse;
import com.supplymind.provider.pboc.PbocHttpTransport;
import com.supplymind.provider.pboc.PbocOfficialWebDataProvider;
import com.supplymind.publish.LifecyclePublishService;
import com.supplymind.validation.LifecycleValidationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1 real production-chain attack tests: the CURRENT-value acquisition is a DISTINCT semantic
 * entry from history backfill. Using the REAL PbocOfficialWebDataProvider (profile
 * current=true/history=false) with a stubbed HTTP transport and a dynamically configured GBP
 * target, these tests prove that workflow ADD/REPLACE issues a REAL
 * ProviderCollectRequest.current (never a fake one-day backfill job), persists the GBP raw/
 * timeline and runs validation->publish->daily->aggregate; history backfill honestly fails
 * closed on a provider without history capability; Manual targets reach AWAITING_MANUAL_INPUT.
 * No fake supportsHistoryData=true provider is used as the production conclusion.
 */
class CurrentIntakeAttackTest {

    private static final String FIXTURE_ROOT = "contracts/v1/d1-t04-pboc/";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-17T02:00:00Z"), ZoneOffset.UTC);
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-08-17T02:00:00+08:00");
    private static final URI LIST_URI = PbocOfficialWebDataProvider.ANNOUNCEMENT_LIST_URI;
    private static final URI DETAIL_URI = URI.create(
            "https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/fixture-announcement-20260810.html");
    private static final String GBP_ITEM = "FX.GBP.CNY.PBOC_MID";
    private static final String GBP_ANCHOR = "1\u82f1\u9551\u5bf9\u4eba\u6c11\u5e01";
    private static final String GBP_DISPLAY = "\u82f1\u9551/\u4eba\u6c11\u5e01\u4e2d\u95f4\u4ef7";
    private static final String PBOC_SOURCE_NAME =
            "\u4e2d\u56fd\u4eba\u6c11\u94f6\u884c\u5b98\u7f51\uff08\u6388\u6743\u4e2d\u56fd\u5916\u6c47\u4ea4\u6613\u4e2d\u5fc3\u516c\u5e03\uff09";

    @TempDir
    Path temporaryDirectory;

    @Test
    void addWithoutBackfillRangeIssuesARealCurrentRequestAndPersistsGbpThroughTheChain() throws Exception {
        Harness harness = harness("announcement-detail-normal-with-gbp.html");
        int beforeRequests = harness.transport().requestedUris().size();

        ConfigV1.WorkflowResult result = harness.workflow().addItem(gbpRequest(null, null));

        // M1: the CURRENT acquisition really ran through provider.collect(CURRENT).
        assertEquals(beforeRequests + 2, harness.transport().requestedUris().size(),
                "one workflow invocation must perform exactly one list+detail CURRENT acquisition");
        assertNotNull(result.currentIntake(), "the workflow exposes the CURRENT intake outcome");
        assertEquals("SUCCEEDED", result.currentIntake().status(),
                "PBOC has current capability: CURRENT must succeed");
        assertEquals(GBP_ITEM, result.currentIntake().itemId());
        assertTrue(result.currentIntake().rawCount() >= 1,
                "at least the GBP raw was persisted through the real provider");
        // M1: no backfill range -> exactly zero backfill jobs (CURRENT is not a backfill).
        assertEquals(0, result.backfillJobs().size(),
                "no backfill range means no history job - CURRENT is a distinct entry");

        // The real chain must have persisted the GBP raw/timeline and produced daily.
        assertTrue(Files.isRegularFile(harness.root().resolveDataRef(
                        DataPaths.dailyRef(GBP_ITEM, java.time.YearMonth.of(2026, 8)))),
                "daily is really rebuilt for the current acquisition");
        assertTrue(harness.rawCount(GBP_ITEM) >= 1,
                "the GBP raw was really persisted by the provider's collect");
    }

    @Test
    void addWithBackfillRangeRunsCurrentSucceededAndHistoryHonestlyFailsClosedOnNoHistoryCapability() throws Exception {
        Harness harness = harness("announcement-detail-normal-with-gbp.html");

        ConfigV1.WorkflowResult result = harness.workflow().addItem(
                gbpRequest("2026-08-01", "2026-08-17"));

        assertEquals(List.of(LIST_URI, DETAIL_URI), harness.transport().requestedUris(),
                "history orchestration must not replay the CURRENT HTTP acquisition");
        assertEquals("SUCCEEDED", result.currentIntake().status(),
                "CURRENT succeeds - it only consults supportsCurrentData");
        assertEquals(1, result.backfillJobs().size(),
                "a full range creates one HISTORY backfill job");
        ConfigV1.BackfillJobView history = result.backfillJobs().get(0);
        assertFalse("SUCCEEDED".equals(history.status()),
                "PBOC supportsHistoryData=false: history must NEVER claim SUCCEEDED");
        assertTrue(history.status().equals("AWAITING_MANUAL_INPUT")
                        || history.status().equals("PARTIAL_SUCCESS")
                        || history.status().equals("FAILED"),
                "history honestly fails closed on a provider without history capability: "
                        + history.status());
    }

    @Test
    void manualAddHonestlyReportsAwaitingManualInputForCurrent() throws Exception {
        Harness harness = harness("announcement-detail-normal-with-gbp.html");

        ConfigV1.WorkflowResult result = harness.workflow().addItem(
                manualRequest("MAT.MANUAL.CURRENT.001", "SMM", "ADC12", null, null));

        assertNotNull(result.currentIntake());
        assertEquals("AWAITING_MANUAL_INPUT", result.currentIntake().status(),
                "a Manual target honestly reports AWAITING_MANUAL_INPUT for CURRENT");
        assertEquals(0, result.currentIntake().rawCount());
        assertEquals(0, result.backfillJobs().size());
    }

    @Test
    void replaceRunsCurrentThroughTheRealProviderAndKeepsHistoryJobCount() throws Exception {
        Harness harness = harness("announcement-detail-normal-with-gbp.html");
        // replaceItem on a Manual old item: the REPLACEMENT drives the intake chain.
        // For a real-PBOC replacement we must use a replacement that is an OFFICIAL_WEB target.
        ConfigV1.ReplaceItemRequest fxReplace = new ConfigV1.ReplaceItemRequest(
                MonitorSeriesDefaults.AZ91D_SMM_ITEM_ID,
                gbpRequest("2026-08-01", "2026-08-17"));

        ConfigV1.WorkflowResult result = harness.workflow().replaceItem(fxReplace);

        assertEquals(List.of(LIST_URI, DETAIL_URI), harness.transport().requestedUris(),
                "REPLACE must execute the replacement CURRENT acquisition exactly once");
        assertEquals("SUCCEEDED", result.currentIntake().status(),
                "the replacement's CURRENT acquisition really ran through the real provider");
        assertEquals(1, result.backfillJobs().size());
        assertFalse("SUCCEEDED".equals(result.backfillJobs().get(0).status()),
                "history stays honest (no history capability)");
    }

    @Test
    void currentRequestIsIssuedWithCurrentCollectionModeNeverADisguisedHistoryJob() throws Exception {
        Harness harness = harness("announcement-detail-normal-with-gbp.html");
        ConfigV1.WorkflowResult result = harness.workflow().addItem(gbpRequest(null, null));

        assertNotNull(result.currentIntake());
        assertEquals("SUCCEEDED", result.currentIntake().status());
        assertEquals(List.of(LIST_URI, DETAIL_URI), harness.transport().requestedUris(),
                "CURRENT is exactly one list+detail provider call, not two repeated acquisitions");
        // The provider contract: a CURRENT request carries no history dates.
        assertEquals(0, result.backfillJobs().size(),
                "CURRENT is not a one-day backfill job - no runtime/jobs file is created for it");
        // No backfill job file was written (CURRENT never creates a fake one-day job).
        Path jobsDir = harness.root().resolveInternalRelative("runtime/jobs/active");
        if (Files.isDirectory(jobsDir)) {
            try (var stream = Files.list(jobsDir)) {
                assertTrue(stream.noneMatch(path -> path.getFileName().toString().startsWith("backfill-")),
                        "no fake one-day backfill job exists for the CURRENT acquisition");
            }
        }
    }

    // ---- fixture ----

    private Harness harness(String detailFixture) throws Exception {
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve("current intake root " + System.nanoTime()));
        AtomicMoveSupport.probeOrFail(root);
        AtomicFileStore fileStore = new AtomicFileStore(root, new DirtyMarkerCodec());
        ConfigActivationStore configStore = new ConfigActivationStore(root, fileStore, CLOCK);
        configStore.ensureInitialDefault();
        RawReceiptStore rawStore = new RawReceiptStore(root, fileStore, CLOCK);
        TimelineStore timelineStore = new TimelineStore(root, fileStore, CLOCK);
        LifecycleValidationService validation = new LifecycleValidationService(root, timelineStore, CLOCK);
        LifecyclePublishService publish = new LifecyclePublishService(root, timelineStore,
                new QuarantineStore(root, fileStore, CLOCK), CLOCK);
        DailyProcessingService daily = new DailyProcessingService(root, timelineStore, fileStore, CLOCK);
        AggregateProcessingService aggregate = new AggregateProcessingService(root, fileStore, CLOCK);
        RawAcquisitionStore acquisitionStore = new RawAcquisitionStore(root, fileStore, CLOCK);

        byte[] listEntity = fixtureBytes("announcement-list-normal.html");
        byte[] detailEntity = fixtureBytes(detailFixture);
        StubTransport transport = new StubTransport(Map.of(
                LIST_URI, new PbocHttpResponse(LIST_URI, 200, "text/html; charset=UTF-8", listEntity),
                DETAIL_URI, new PbocHttpResponse(DETAIL_URI, 200, "text/html; charset=UTF-8", detailEntity)));

        DataProviderRegistry registry = new DataProviderRegistry();
        PbocOfficialWebDataProvider provider = new PbocOfficialWebDataProvider(
                root, rawStore, acquisitionStore, fileStore, CLOCK,
                transport, new PbocAnnouncementParser(), event -> { });
        registry.register(provider);
        registry.register(new com.supplymind.manual.ManualDataProvider(() -> Set.of(
                MonitorSeriesDefaults.ADC12_SMM_ITEM_ID, MonitorSeriesDefaults.ADC12_AM_ITEM_ID,
                MonitorSeriesDefaults.AZ91D_SMM_ITEM_ID, MonitorSeriesDefaults.AZ91D_AM_ITEM_ID)));

        BackfillJobStore jobStore = new BackfillJobStore(root, fileStore, CLOCK);
        BackfillOrchestrator orchestrator = new BackfillOrchestrator(
                root, jobStore, configStore, registry, acquisitionStore, rawStore, timelineStore,
                validation, publish, daily, aggregate);
        ConfigManagementService configs = new ConfigManagementService(configStore, registry);
        DynamicConfigWorkflowService workflow = new DynamicConfigWorkflowService(
                configs, orchestrator, jobStore,
                new BackfillJobQueryService(root), new ConfigHistoryQueryService(root), registry, CLOCK);
        return new Harness(root, provider, transport, workflow);
    }

    private static ConfigV1.AddItemRequest gbpRequest(String backfillFrom, String backfillTo) {
        return new ConfigV1.AddItemRequest(
                GBP_ITEM, GBP_DISPLAY, "PBOC",
                ProviderType.OFFICIAL_WEB.wireValue(), AccessMethod.PUBLIC_OFFICIAL_HTML.wireValue(),
                PBOC_SOURCE_NAME, RouteDecision.PRIMARY.wireValue(), null,
                "GBP", GBP_ANCHOR, MonitorSeriesDefaults.PBOC_RATE_KIND,
                MonitorSeriesDefaults.CALCULATION_VERSION, 8, 4, "HALF_UP",
                MonitorSeriesDefaults.CALENDAR_VERSION, "CNY", "GBP", "CNY/1 GBP",
                null, backfillFrom, backfillTo);
    }

    private static ConfigV1.AddItemRequest manualRequest(
            String itemId, String sourceIntent, String externalCode,
            String backfillFrom, String backfillTo
    ) {
        return new ConfigV1.AddItemRequest(
                itemId, "\u4eba\u5de5\u5f55\u5165\u6807\u7684", sourceIntent,
                ProviderType.MANUAL.wireValue(), AccessMethod.MANUAL.wireValue(),
                "\u4eba\u5de5\u5f55\u5165\uff08Manual\uff09",
                RouteDecision.FALLBACK_MANUAL.wireValue(), "MANUAL_FALLBACK",
                externalCode, "material-field-key", "material",
                "arithmetic-mean-v1", 2, 2, "HALF_UP", "weekday-asia-shanghai-v1",
                "CNY", "CNY", "\u5143/\u5428",
                new ConfigV1.MaterialValidationRequest("0", null, 7, externalCode, List.of()),
                backfillFrom, backfillTo);
    }

    private byte[] fixtureBytes(String name) throws IOException {
        try (InputStream stream = Objects.requireNonNull(
                getClass().getClassLoader().getResourceAsStream(FIXTURE_ROOT + name),
                () -> "Missing fixture " + name)) {
            return stream.readAllBytes();
        }
    }

    private record Harness(
            DataRoot root,
            PbocOfficialWebDataProvider provider,
            StubTransport transport,
            DynamicConfigWorkflowService workflow
    ) {
        int rawCount(String itemId) throws IOException {
            Path dir = root.resolveInternalRelative("raw/formal/official_web/" + itemId);
            if (!Files.isDirectory(dir)) {
                return 0;
            }
            try (var stream = Files.walk(dir)) {
                return (int) stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".json"))
                        .filter(p -> !p.getFileName().toString().endsWith(".manifest.json"))
                        .count();
            }
        }
    }

    private static final class StubTransport implements PbocHttpTransport {
        private final Map<URI, PbocHttpResponse> responses;
        private final List<URI> requestedUris = new ArrayList<>();

        StubTransport(Map<URI, PbocHttpResponse> responses) {
            this.responses = responses;
        }

        @Override
        public PbocHttpResponse get(URI uri) {
            requestedUris.add(uri);
            PbocHttpResponse response = responses.get(uri);
            if (response == null) {
                throw new AssertionError("Unexpected stub request: " + uri);
            }
            return response;
        }

        List<URI> requestedUris() {
            return List.copyOf(requestedUris);
        }
    }
}
