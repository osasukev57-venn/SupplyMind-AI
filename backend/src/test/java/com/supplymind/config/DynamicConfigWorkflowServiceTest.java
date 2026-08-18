package com.supplymind.config;

import com.supplymind.backfill.BackfillJobQueryService;
import com.supplymind.backfill.BackfillJobStateV1;
import com.supplymind.backfill.BackfillJobStore;
import com.supplymind.backfill.BackfillOrchestrator;
import com.supplymind.config.api.ConfigV1;
import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.Mode;
import com.supplymind.foundation.model.MonitorSeriesDefaults;
import com.supplymind.foundation.model.MonitorSeriesItemV1;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.RouteDecision;
import com.supplymind.foundation.storage.AtomicFileStore;
import com.supplymind.foundation.storage.AtomicMoveSupport;
import com.supplymind.foundation.storage.ConfigActivationStore;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.DirtyMarkerCodec;
import com.supplymind.foundation.storage.QuarantineStore;
import com.supplymind.foundation.storage.RawAcquisitionStore;
import com.supplymind.foundation.storage.RawReceiptStore;
import com.supplymind.foundation.storage.TimelineStore;
import com.supplymind.manual.ManualDataProvider;
import com.supplymind.processing.AggregateProcessingService;
import com.supplymind.processing.DailyProcessingService;
import com.supplymind.provider.DataProvider;
import com.supplymind.provider.DataProviderRegistry;
import com.supplymind.provider.ProviderCollectOutcome;
import com.supplymind.provider.ProviderCollectRequest;
import com.supplymind.provider.ProviderSourceProfile;
import com.supplymind.publish.LifecyclePublishService;
import com.supplymind.validation.LifecycleValidationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D8-T01 dynamic config workflow over the REAL production chain (H07/H08/H09 + AT-CFG-001/002/
 * 003/004 + AT-UI-001/002 backend). ADD/ENABLE/DISABLE/REPLACE go through the frozen activation;
 * backfill jobs are REALLY created by the orchestrator; EUR disable keeps history; GBP add is
 * capability-gated and builds daily/aggregate; AZ91D replace creates independent items; manual
 * targets honestly reach AWAITING_MANUAL_INPUT; restarts keep config history/active/job
 * checkpoints consistent. The frontend never submits configVersion/routeEffectiveAt/audit time.
 */
class DynamicConfigWorkflowServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-12T02:00:00Z"), ZoneOffset.UTC);
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-08-12T02:00:00+08:00");
    private static final String GBP_ITEM = "FX.GBP.CNY.PBOC_MID";

    @TempDir
    Path temporaryDirectory;

    @Test
    void disableEuroActivatesNewConfigVersionHidesFromPanelAndKeepsHistory() {
        Harness harness = harness();
        ConfigV1.ConfigView before = harness.workflow().configView();
        assertTrue(before.items().stream()
                .anyMatch(item -> item.itemId().equals(MonitorSeriesDefaults.EUR_CNY_ITEM_ID) && item.enabled()));

        ConfigV1.ConfigView after = harness.workflow().setEnabled(MonitorSeriesDefaults.EUR_CNY_ITEM_ID, false);

        assertEquals(before.configVersion() + 1, after.configVersion(),
                "every toggle is a new atomic activation");
        assertFalse(after.items().stream()
                        .filter(item -> item.itemId().equals(MonitorSeriesDefaults.EUR_CNY_ITEM_ID))
                        .findFirst().orElseThrow().enabled(),
                "EUR/CNY is disabled in the active configuration (panel is configuration-driven)");
        assertTrue(after.items().stream()
                        .anyMatch(item -> item.itemId().equals(MonitorSeriesDefaults.USD_CNY_ITEM_ID) && item.enabled()),
                "USD/CNY stays enabled");

        List<ConfigV1.HistoryEntry> history = harness.workflow().configHistory();
        assertTrue(history.stream().allMatch(ConfigV1.HistoryEntry::verified),
                "every immutable history snapshot verifies against its manifest");
        assertTrue(history.stream().anyMatch(entry -> entry.configVersion() == before.configVersion()),
                "the pre-change snapshot stays readable (old history is never deleted)");

        ConfigV1.ConfigView restarted = newWorkflow(harness.root(), harness.registry()).configView();
        assertEquals(after.configVersion(), restarted.configVersion(), "restart keeps the active config");
        assertFalse(restarted.items().stream()
                        .filter(item -> item.itemId().equals(MonitorSeriesDefaults.EUR_CNY_ITEM_ID))
                        .findFirst().orElseThrow().enabled(),
                "restart keeps the disabled state");
    }

    @Test
    void addGbpIsCapabilityGatedAndCreatesARealBackfillJob() {
        Harness harness = harness();
        int beforeVersion = harness.workflow().configView().configVersion();
        ConfigV1.AddItemRequest request = gbpRequest("2026-08-10", "2026-08-11");

        ConfigV1.WorkflowResult result = harness.workflow().addItem(request);

        assertEquals(beforeVersion + 1, result.config().configVersion(),
                "the ADD activates a new configVersion");
        assertTrue(result.config().items().stream()
                        .anyMatch(item -> item.itemId().equals(GBP_ITEM) && item.enabled()),
                "GBP appears enabled in the panel configuration");
        assertNotNull(result.backfillJobs());
        // M1: CURRENT is a DISTINCT entry (currentIntake); backfillJobs contains only HISTORY.
        assertNotNull(result.currentIntake(), "the workflow exposes the CURRENT intake outcome");
        assertEquals("SUCCEEDED", result.currentIntake().status(),
                "the automatic target's CURRENT acquisition really succeeds through the chain");
        assertEquals(GBP_ITEM, result.currentIntake().itemId());
        assertEquals(1, result.backfillJobs().size(),
                "with a full range exactly one HISTORY backfill job is created");
        ConfigV1.BackfillJobView historyJob = result.backfillJobs().get(0);
        assertEquals(GBP_ITEM, historyJob.itemId());
        assertEquals("2026-08-10", historyJob.fromDate());
        assertEquals("2026-08-11", historyJob.toDate());
        // M1: the backfill is AUTO-RUN by the ADD chain - it is never left WAITING.
        assertEquals("SUCCEEDED", historyJob.status(),
                "the ADD chain auto-runs the backfill through acquisition->validation->publish->daily->aggregate");
        assertTrue(Files.isRegularFile(harness.root().resolveDataRef(
                        com.supplymind.foundation.storage.DataPaths.dailyRef(
                                GBP_ITEM, java.time.YearMonth.of(2026, 8)))),
                "daily is really rebuilt for the new target");
    }

    @Test
    void addItemWithoutProviderCapabilityFailsClosedAndKeepsOldActive() {
        Harness harness = harness();
        ConfigV1.AddItemRequest freePublicGbp = new ConfigV1.AddItemRequest(
                GBP_ITEM, "英镑/人民币中间价（免费源）", "PBOC",
                ProviderType.FREE_PUBLIC.wireValue(), AccessMethod.FREE_PUBLIC_WEB.wireValue(),
                "免费公开源（测试）", RouteDecision.FALLBACK_FREE_PUBLIC.wireValue(), "FREE_PUBLIC_FALLBACK",
                "GBP", "1英镑对人民币", MonitorSeriesDefaults.PBOC_RATE_KIND,
                MonitorSeriesDefaults.CALCULATION_VERSION, 8, 4, "HALF_UP",
                MonitorSeriesDefaults.CALENDAR_VERSION, "CNY", "GBP", "CNY/1 GBP",
                null, null, null);
        assertThrows(com.supplymind.foundation.storage.StorageException.class,
                () -> harness.workflow().addItem(freePublicGbp),
                "provider capability is a server-side gate - the frontend never decides approval");
        assertEquals(1, harness.workflow().configView().configVersion(),
                "a capability-rejected activation leaves the previous active config untouched");
    }

    @Test
    void replaceTwoAz91dIntentsCreatesIndependentItemsAndKeepsAdc12() {
        Harness harness = harness();
        ConfigV1.ReplaceItemRequest smm = replaceRequest(
                MonitorSeriesDefaults.AZ91D_SMM_ITEM_ID,
                "MAT.REPL-01.SMM", "AZ91D替代材料（SMM意图）", "SMM");
        ConfigV1.ReplaceItemRequest am = replaceRequest(
                MonitorSeriesDefaults.AZ91D_AM_ITEM_ID,
                "MAT.REPL-01.AM", "AZ91D替代材料（Asian Metal意图）", "Asian Metal");

        ConfigV1.WorkflowResult afterSmm = harness.workflow().replaceItem(smm);
        ConfigV1.WorkflowResult afterAm = harness.workflow().replaceItem(am);

        List<ConfigV1.ItemView> items = afterAm.config().items();
        assertFalse(items.stream().filter(item -> item.itemId().equals(MonitorSeriesDefaults.AZ91D_SMM_ITEM_ID))
                        .findFirst().orElseThrow().enabled(),
                "old SMM AZ91D is disabled, never deleted");
        assertFalse(items.stream().filter(item -> item.itemId().equals(MonitorSeriesDefaults.AZ91D_AM_ITEM_ID))
                        .findFirst().orElseThrow().enabled(),
                "old Asian Metal AZ91D is disabled, never deleted");
        ConfigV1.ItemView replacedSmm = items.stream()
                .filter(item -> item.itemId().equals("MAT.REPL-01.SMM")).findFirst().orElseThrow();
        ConfigV1.ItemView replacedAm = items.stream()
                .filter(item -> item.itemId().equals("MAT.REPL-01.AM")).findFirst().orElseThrow();
        assertEquals(MonitorSeriesDefaults.AZ91D_SMM_ITEM_ID, replacedSmm.supersedesItemId());
        assertEquals(MonitorSeriesDefaults.AZ91D_AM_ITEM_ID, replacedAm.supersedesItemId());
        assertEquals("SMM", replacedSmm.sourceIntent());
        assertEquals("Asian Metal", replacedAm.sourceIntent());
        assertTrue(items.stream().anyMatch(item -> item.itemId().equals(MonitorSeriesDefaults.ADC12_SMM_ITEM_ID)
                        && item.enabled()), "the two ADC12 sequences stay enabled");
        assertTrue(items.stream().anyMatch(item -> item.itemId().equals(MonitorSeriesDefaults.ADC12_AM_ITEM_ID)
                        && item.enabled()), "both ADC12 sequences stay enabled");

        List<ConfigV1.HistoryEntry> history = harness.workflow().configHistory();
        assertTrue(history.stream().allMatch(ConfigV1.HistoryEntry::verified));
        assertTrue(history.stream().anyMatch(entry -> entry.configVersion() == afterSmm.config().configVersion()),
                "the SMM-replacement snapshot stays readable after the AM replacement");
    }

    @Test
    void manualTargetHonestlyAwaitsInputAndNeverFakesSucceeded() {
        Harness harness = harness();
        ConfigV1.AddItemRequest manual = manualRequest(
                "MAT.MANUAL.NEW.001", "新手工标的", "SMM", "ADC12", "2026-08-01", "2026-08-31");

        ConfigV1.WorkflowResult result = harness.workflow().addItem(manual);

        // M1: CURRENT (distinct entry) + HISTORY both stay honest for a Manual target.
        assertNotNull(result.currentIntake());
        assertEquals("AWAITING_MANUAL_INPUT", result.currentIntake().status(),
                "the CURRENT intake for a Manual target honestly awaits input");
        assertEquals(1, result.backfillJobs().size());
        for (ConfigV1.BackfillJobView job : result.backfillJobs()) {
            assertTrue(job.status().equals("AWAITING_MANUAL_INPUT")
                            || job.status().equals("PARTIAL_SUCCESS")
                            || job.status().equals("FAILED"),
                    "a Manual target without real input must honestly report AWAITING_MANUAL_INPUT"
                            + "/PARTIAL_SUCCESS/FAILED, never fake SUCCEEDED and never left WAITING: "
                            + job.status());
            assertFalse("SUCCEEDED".equals(job.status()), "never fake SUCCEEDED for a manual target");
        }
    }

    @Test
    void addWithoutBackfillRangeStillTriggersTheCurrentAcquisition() {
        Harness harness = harness();
        // M1: current-value acquisition must NOT depend on backfillFrom/backfillTo being filled.
        ConfigV1.AddItemRequest manual = manualRequest(
                "MAT.MANUAL.CURRENT.001", "无范围手工标的", "SMM", "ADC12", null, null);

        ConfigV1.WorkflowResult result = harness.workflow().addItem(manual);

        assertNotNull(result.currentIntake(),
                "a range-less ADD still exposes the CURRENT intake outcome - never absent");
        assertEquals("MAT.MANUAL.CURRENT.001", result.currentIntake().itemId());
        assertEquals("AWAITING_MANUAL_INPUT", result.currentIntake().status(),
                "the current acquisition for a Manual target honestly awaits real input");
        assertEquals(0, result.backfillJobs().size(),
                "no backfill range means no HISTORY job (CURRENT is a distinct entry)");
    }

    @Test
    void replaceWithoutBackfillRangeStillTriggersTheCurrentAcquisition() {
        Harness harness = harness();
        // M1: the REPLACE page no longer sends null/null - but even if a caller omits the range,
        // the current acquisition must still run (no more "0 jobs" replacements).
        ConfigV1.ReplaceItemRequest smm = new ConfigV1.ReplaceItemRequest(
                MonitorSeriesDefaults.AZ91D_SMM_ITEM_ID,
                manualRequest("MAT.REPL-01.SMM", "AZ91D替代材料（SMM意图）", "SMM", "AZ91D", null, null));

        ConfigV1.WorkflowResult result = harness.workflow().replaceItem(smm);

        assertNotNull(result.currentIntake(),
                "a replacement must expose the CURRENT intake outcome");
        assertEquals("MAT.REPL-01.SMM", result.currentIntake().itemId());
        assertEquals("AWAITING_MANUAL_INPUT", result.currentIntake().status(),
                "a Manual replacement honestly reaches AWAITING_MANUAL_INPUT - never fake auto-complete");
        assertEquals(0, result.backfillJobs().size());
    }

    @Test
    void oneSidedBackfillRangeIsRejectedByTheControlledDto() {
        Harness harness = harness();
        assertThrows(com.supplymind.foundation.model.SchemaValidationException.class,
                () -> manualRequest("MAT.MANUAL.ONE.001", "单边手工标的", "SMM", "ADC12",
                        "2026-08-01", null),
                "backfillFrom without backfillTo is rejected (both-or-neither)");
        assertThrows(com.supplymind.foundation.model.SchemaValidationException.class,
                () -> manualRequest("MAT.MANUAL.TWO.001", "单边手工标的", "SMM", "ADC12",
                        null, "2026-08-31"),
                "backfillTo without backfillFrom is rejected (both-or-neither)");
        assertTrue(harness.workflow().configView().configVersion() == 1,
                "a rejected request never touches the active configuration");
    }

    @Test
    void retryReopensFailedJobAndResumesThroughTheRealChain() {
        Harness harness = harness();
        ConfigV1.AddItemRequest manual = manualRequest(
                "MAT.MANUAL.RETRY.001", "重试手工标的", "SMM", "ADC12", "2026-08-01", "2026-08-31");
        ConfigV1.WorkflowResult result = harness.workflow().addItem(manual);
        String jobId = result.backfillJobs().get(result.backfillJobs().size() - 1).jobId();
        harness.workflow().runBackfill(jobId);

        ConfigV1.BackfillJobView retried = harness.workflow().retryBackfill(jobId);
        assertNotNull(retried);
        assertEquals(jobId, retried.jobId(), "retry reuses the same job id");
        assertFalse(retried.failureReasons().isEmpty()
                        || retried.status().equals("SUCCEEDED"),
                "a manual job with no input keeps honest AWAITING_MANUAL_INPUT on retry");
    }

    @Test
    void capabilitiesExposeNoSecretsAndAreConfigurationDriven() {
        Harness harness = harness();
        List<ConfigV1.CapabilityView> capabilities = harness.workflow().capabilities();
        assertFalse(capabilities.isEmpty());
        for (ConfigV1.CapabilityView capability : capabilities) {
            assertNotNull(capability.providerId());
            assertNotNull(capability.providerType());
            assertNotNull(capability.accessMethod());
            assertNotNull(capability.actualSourceName());
            assertFalse(capability.actualSourceName().toLowerCase().contains("token")
                            || capability.actualSourceName().toLowerCase().contains("key")
                            || capability.actualSourceName().toLowerCase().contains("cookie"),
                    "capability DTO must never expose secrets");
            assertFalse(String.valueOf(capability).contains("data-root")
                            || String.valueOf(capability).contains("data\\")
                            || String.valueOf(capability).contains("data/"),
                    "capability DTO must never expose internal paths");
        }
    }

    @Test
    void historyQueryReportsCorruptSnapshotAsExplicitIssue() throws Exception {
        Harness harness = harness();
        List<ConfigV1.HistoryEntry> clean = harness.workflow().configHistory();
        assertTrue(clean.stream().allMatch(ConfigV1.HistoryEntry::verified));
        assertEquals(1, clean.size());

        Path historyDir = harness.root().resolveInternalRelative("config/history");
        try (var stream = Files.list(historyDir)) {
            Path snapshot = stream.filter(p -> p.getFileName().toString().matches("[0-9]+\\.json"))
                    .findFirst().orElseThrow();
            Files.write(snapshot, "{\"tampered\":true}".getBytes(StandardCharsets.UTF_8));
        }
        List<ConfigV1.HistoryEntry> corrupted = harness.workflow().configHistory();
        assertEquals(1, corrupted.size());
        assertFalse(corrupted.get(0).verified(),
                "a tampered snapshot is reported as an explicit verification issue, never silently skipped");
        assertNotNull(corrupted.get(0).message());
    }

    @Test
    void backfillJobListIgnoresNonBackfillFilesInTheSameRuntimeJobsDir() throws Exception {
        Harness harness = harness();
        // D5-T01 time-state shares runtime/jobs/active - it is NOT a backfill job and must never
        // be decoded as one (D8-T04 finding: the raw list() failed 400 on time-state.json).
        Path jobDir = harness.root().resolveInternalRelative("runtime/jobs/active");
        Files.createDirectories(jobDir);
        Files.write(jobDir.resolve("time-state.json"),
                "{\"schemaVersion\":\"1.0\",\"transactionId\":\"x\"}".getBytes(StandardCharsets.UTF_8));

        assertEquals(List.of(), harness.workflow().backfillJobs(),
                "time-state.json and other non-backfill files are excluded from the job list");

        ConfigV1.AddItemRequest manual = manualRequest(
                "MAT.MANUAL.LIST.001", "列表手工标的", "SMM", "ADC12", "2026-08-01", "2026-08-31");
        ConfigV1.WorkflowResult result = harness.workflow().addItem(manual);
        // M1: CURRENT is a distinct entry (not a job); only the HISTORY backfill is a job.
        assertNotNull(result.currentIntake());
        assertEquals("AWAITING_MANUAL_INPUT", result.currentIntake().status());
        assertEquals(1, result.backfillJobs().size(),
                "only the HISTORY backfill range creates a job");
        assertEquals(1, harness.workflow().backfillJobs().size(),
                "the real job is listed alongside the excluded time-state file");
    }

    // ---- request builders ----

    private static ConfigV1.AddItemRequest gbpRequest(String backfillFrom, String backfillTo) {
        return new ConfigV1.AddItemRequest(
                GBP_ITEM, "英镑/人民币中间价", "PBOC",
                ProviderType.OFFICIAL_WEB.wireValue(), AccessMethod.PUBLIC_OFFICIAL_HTML.wireValue(),
                MonitorSeriesDefaults.PBOC_SOURCE_NAME, RouteDecision.PRIMARY.wireValue(), null,
                "GBP", "1英镑对人民币", MonitorSeriesDefaults.PBOC_RATE_KIND,
                MonitorSeriesDefaults.CALCULATION_VERSION, 8, 4, "HALF_UP",
                MonitorSeriesDefaults.CALENDAR_VERSION, "CNY", "GBP", "CNY/1 GBP",
                null, backfillFrom, backfillTo);
    }

    private static ConfigV1.ReplaceItemRequest replaceRequest(
            String oldItemId, String newItemId, String displayName, String sourceIntent
    ) {
        return new ConfigV1.ReplaceItemRequest(oldItemId, new ConfigV1.AddItemRequest(
                newItemId, displayName, sourceIntent,
                ProviderType.MANUAL.wireValue(), AccessMethod.MANUAL.wireValue(),
                "人工录入（Manual）", RouteDecision.FALLBACK_MANUAL.wireValue(), "MANUAL_FALLBACK",
                "AZ91D", "material-field-key", "material",
                "arithmetic-mean-v1", 2, 2, "HALF_UP", "weekday-asia-shanghai-v1",
                "CNY", "CNY", "元/吨",
                new ConfigV1.MaterialValidationRequest("0", null, 7, "AZ91D", List.of()),
                "2026-08-01", "2026-08-31"));
    }

    private static ConfigV1.AddItemRequest manualRequest(
            String itemId, String displayName, String sourceIntent, String externalCode,
            String backfillFrom, String backfillTo
    ) {
        return new ConfigV1.AddItemRequest(
                itemId, displayName, sourceIntent,
                ProviderType.MANUAL.wireValue(), AccessMethod.MANUAL.wireValue(),
                "人工录入（Manual）", RouteDecision.FALLBACK_MANUAL.wireValue(), "MANUAL_FALLBACK",
                externalCode, "material-field-key", "material",
                "arithmetic-mean-v1", 2, 2, "HALF_UP", "weekday-asia-shanghai-v1",
                "CNY", "CNY", "元/吨",
                new ConfigV1.MaterialValidationRequest("0", null, 7, externalCode, List.of()),
                backfillFrom, backfillTo);
    }

    // ---- fixture ----

    private Harness harness() {
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve("d8 workflow root " + System.nanoTime()));
        AtomicMoveSupport.probeOrFail(root);
        DataProviderRegistry registry = new DataProviderRegistry();
        registry.register(new ManualDataProvider(() -> Set.of(
                MonitorSeriesDefaults.ADC12_SMM_ITEM_ID, MonitorSeriesDefaults.ADC12_AM_ITEM_ID,
                MonitorSeriesDefaults.AZ91D_SMM_ITEM_ID, MonitorSeriesDefaults.AZ91D_AM_ITEM_ID)));
        registry.register(autoProvider(root));
        return new Harness(root, registry, newWorkflow(root, registry));
    }

    private DynamicConfigWorkflowService newWorkflow(DataRoot root, DataProviderRegistry registry) {
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
        BackfillJobStore jobStore = new BackfillJobStore(root, fileStore, CLOCK);
        BackfillOrchestrator orchestrator = new BackfillOrchestrator(
                root, jobStore, configStore, registry, acquisitionStore, rawStore, timelineStore,
                validation, publish, daily, aggregate);
        ConfigManagementService configs = new ConfigManagementService(configStore, registry);
        return new DynamicConfigWorkflowService(
                configs, orchestrator, jobStore,
                new BackfillJobQueryService(root), new ConfigHistoryQueryService(root), registry, CLOCK);
    }

    private static DataProvider autoProvider(DataRoot dataRoot) {
        return new DataProvider() {
            private final java.util.Map<String, String> collectedDays =
                    new java.util.concurrent.ConcurrentHashMap<>();

            @Override
            public ProviderSourceProfile profile() {
                return ProviderSourceProfile.of("auto-fx-history", ProviderType.OFFICIAL_WEB,
                        AccessMethod.PUBLIC_OFFICIAL_HTML, "自动汇率历史源（测试）",
                        "https://example.test/fx", true, true);
            }

            @Override
            public Set<String> supportedItemIds() {
                return Set.of(MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                        MonitorSeriesDefaults.EUR_CNY_ITEM_ID, GBP_ITEM);
            }

            @Override
            public boolean supports(MonitorSeriesItemV1 item) {
                return item.providerType() == ProviderType.OFFICIAL_WEB
                        && MonitorSeriesDefaults.PBOC_RATE_KIND.equals(item.rateKind());
            }

            @Override
            public ProviderCollectOutcome collect(ProviderCollectRequest request) {
                int configVersion = activeConfigVersion(dataRoot);
                java.util.LinkedHashMap<String, String> rejected = new java.util.LinkedHashMap<>();
                java.util.ArrayList<com.supplymind.foundation.model.RawReceiptV1> raws =
                        new java.util.ArrayList<>();
                // M1: a HISTORY request covers exactly [historyStartDate..historyEndDate]; a
                // CURRENT request covers the reference day. Each call produces the requested
                // range deterministically (no shared next-day counter), so the auto current
                // acquisition and the auto backfill never starve each other.
                LocalDate from = request.collectionMode() == com.supplymind.provider.CollectionMode.HISTORY
                        ? request.historyStartDate()
                        : LocalDate.parse("2026-08-17");
                LocalDate to = request.collectionMode() == com.supplymind.provider.CollectionMode.HISTORY
                        ? request.historyEndDate()
                        : from;
                for (String itemId : request.itemIds()) {
                    if (!supportedItemIds().contains(itemId)) {
                        rejected.put(itemId, "UNSUPPORTED_TARGET");
                        continue;
                    }
                    for (LocalDate businessDate = from; !businessDate.isAfter(to); businessDate = businessDate.plusDays(1)) {
                        byte[] payload = ("{\"fx\":\"" + businessDate + "\"}").getBytes(StandardCharsets.UTF_8);
                        String runId = "auto-" + itemId + "-" + businessDate.toString().replace("-", "");
                        String acquisitionId = "auto-acq-" + runId;
                        String unit = GBP_ITEM.equals(itemId) ? "CNY/1 GBP" : "CNY/1 USD";
                        raws.add(new com.supplymind.foundation.model.RawReceiptV1(
                                "1.0", com.supplymind.foundation.model.RawReceiptV1.deriveRawRef(
                                Mode.FORMAL, ProviderType.OFFICIAL_WEB, itemId, NOW, runId),
                                acquisitionId, runId, Mode.FORMAL,
                                ProviderType.OFFICIAL_WEB, AccessMethod.PUBLIC_OFFICIAL_HTML, configVersion,
                                "中国人民银行官网（授权中国外汇交易中心公布）",
                                "https://example.test/fx", "fx-ref", itemId,
                                businessDate.toString(), businessDate.toString(), null, NOW, NOW, null,
                                "7.1200", unit, "CNY", null, 200, "text/html; charset=UTF-8", "base64",
                                java.util.Base64.getEncoder().encodeToString(payload),
                                com.supplymind.foundation.storage.FileDigest.sha256(payload),
                                null, NOW,
                                com.supplymind.foundation.storage.DataPaths.acquisitionRef(acquisitionId), null));
                    }
                }
                return new ProviderCollectOutcome("1.0", "auto-fx-history", null, null, null,
                        raws, List.of(), rejected);
            }
        };
    }

    private static int activeConfigVersion(DataRoot dataRoot) {
        try {
            Path active = dataRoot.resolveDataRef(
                    com.supplymind.foundation.storage.DataPaths.configActiveRef());
            String json = new String(Files.readAllBytes(active), StandardCharsets.UTF_8);
            int marker = json.indexOf("\"configVersion\"");
            int colon = json.indexOf(':', marker);
            int end = json.indexOf(',', colon);
            if (end < 0) {
                end = json.indexOf('}', colon);
            }
            return Integer.parseInt(json.substring(colon + 1, end).trim());
        } catch (Exception exception) {
            return 1;
        }
    }

    private record Harness(
            DataRoot root,
            DataProviderRegistry registry,
            DynamicConfigWorkflowService workflow
    ) {
    }
}
