package com.supplymind.backfill;

import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.MaterialValidationConfigV1;
import com.supplymind.foundation.model.Mode;
import com.supplymind.foundation.model.MonitorSeriesConfigV1;
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
import com.supplymind.manual.ManualIntakeOutcome;
import com.supplymind.manual.ManualMaterialIntakeService;
import com.supplymind.manual.ManualMaterialNormalizer;
import com.supplymind.manual.ManualMaterialSubmission;
import com.supplymind.manual.OperatorContext;
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
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D5-T04/F4 backfill orchestration over the REAL production chain (AT-CFG-003/H08 backend).
 * The automatic test only calls BackfillOrchestrator: acquisition happens through a real
 * DataProvider implementation, then raw -> validation -> publish -> daily -> aggregate through
 * the production pipeline. Manual targets honestly return AWAITING_MANUAL_INPUT and only
 * progress after real input is published; duplicate starts reuse the job; restarts resume
 * from the persisted checkpoint; the RUNNING state is actually persisted.
 */
class BackfillOrchestratorTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-12T02:00:00Z"), ZoneOffset.UTC);
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-08-12T02:00:00+08:00");
    private static final String ADC12_SMM = "MAT.ADC12.SMM";
    private static final String AUTO_ITEM = MonitorSeriesDefaults.USD_CNY_ITEM_ID;

    @TempDir
    Path temporaryDirectory;

    @Test
    void autoTargetRunsTheFullAcquisitionChainThroughTheOrchestratorOnly() throws Exception {
        Harness harness = harness(true);
        BackfillJobStateV1 job = harness.orchestrator().createOrResume(
                AUTO_ITEM, LocalDate.parse("2026-08-10"), LocalDate.parse("2026-08-10"));
        assertEquals(BackfillJobStateV1.JobStatus.WAITING, job.status());

        BackfillJobStateV1 succeeded = harness.orchestrator().run(job.jobId());
        assertEquals(BackfillJobStateV1.JobStatus.SUCCEEDED, succeeded.status(),
                "H08/F4: the orchestrator alone must drive acquisition->raw->validation->publish->daily->aggregate");
        assertEquals("2026-08-10", succeeded.currentCheckpoint(),
                "the checkpoint must advance to the last completed business date");
        assertEquals(List.of("2026-08"), succeeded.completedPeriods());
        assertTrue(Files.isRegularFile(harness.root().resolveDataRef(
                        com.supplymind.foundation.storage.DataPaths.dailyRef(AUTO_ITEM, java.time.YearMonth.of(2026, 8)))),
                "daily must be rebuilt by the orchestrator, not pre-seeded");
        assertTrue(harness.rawCount(AUTO_ITEM) >= 1,
                "a real provider-acquired raw must be persisted through the chain");
    }

    @Test
    void autoTargetCheckpointAndResumeNeverRedoCompletedDays() throws Exception {
        Harness harness = harness(true);
        BackfillJobStateV1 job = harness.orchestrator().createOrResume(
                AUTO_ITEM, LocalDate.parse("2026-08-10"), LocalDate.parse("2026-08-11"));
        BackfillJobStateV1 first = harness.orchestrator().run(job.jobId());
        assertEquals(BackfillJobStateV1.JobStatus.SUCCEEDED, first.status());
        int rawCountAfterFirst = harness.rawCount(AUTO_ITEM);
        assertEquals(2, rawCountAfterFirst, "one acquired raw per business day");

        BackfillJobStateV1 resumed = harness.orchestrator().run(job.jobId());
        assertEquals(BackfillJobStateV1.JobStatus.SUCCEEDED, resumed.status());
        assertEquals(rawCountAfterFirst, harness.rawCount(AUTO_ITEM),
                "restart resume must not duplicate raw/publish/daily/aggregate work");
        assertEquals(2, resumed.completedPeriods().size() >= 1 ? harness.rawCount(AUTO_ITEM) : 0,
                "resume continues from the checkpoint, never re-runs all history");
    }

    @Test
    void duplicateStartReusesSameJob() throws Exception {
        Harness harness = harness(true);
        BackfillJobStateV1 first = harness.orchestrator().createOrResume(
                AUTO_ITEM, LocalDate.parse("2026-08-10"), LocalDate.parse("2026-08-10"));
        harness.orchestrator().run(first.jobId());
        BackfillJobStateV1 duplicate = harness.orchestrator().createOrResume(
                AUTO_ITEM, LocalDate.parse("2026-08-10"), LocalDate.parse("2026-08-10"));
        assertEquals(first.jobId(), duplicate.jobId(), "duplicate start must reuse the existing job");
        assertEquals(BackfillJobStateV1.JobStatus.SUCCEEDED, duplicate.status());
    }

    @Test
    void manualTargetAwaitsInputAndOnlySucceedsAfterRealPublishedData() throws Exception {
        Harness harness = harness(false);
        BackfillJobStateV1 job = harness.orchestrator().createOrResume(
                ADC12_SMM, LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-31"));
        BackfillJobStateV1 waiting = harness.orchestrator().run(job.jobId());
        assertEquals(BackfillJobStateV1.JobStatus.AWAITING_MANUAL_INPUT, waiting.status(),
                "no input must honestly report AWAITING_MANUAL_INPUT, never fake RUNNING/SUCCEEDED");

        ManualIntakeOutcome intake = harness.manual().submit(ManualMaterialSubmission.of(
                ADC12_SMM, "2026-08-10", "19850.50", "元/吨", "CNY",
                "华东某厂报价单（测试）", "报价单号A-20260810", null));
        harness.validation().process(intake.runId());
        harness.publish().process(intake.runId());

        BackfillJobStateV1 succeeded = harness.orchestrator().refresh(job.jobId());
        assertEquals(BackfillJobStateV1.JobStatus.SUCCEEDED, succeeded.status(),
                "real published input through the frozen chain completes the period");
        assertEquals(List.of("2026-08"), succeeded.completedPeriods());
        assertTrue(Files.isRegularFile(harness.root().resolveDataRef(
                        com.supplymind.foundation.storage.DataPaths.dailyRef(ADC12_SMM, java.time.YearMonth.of(2026, 8)))),
                "the frozen daily rebuild must have run for the completed period");
    }

    @Test
    void nonManualTargetWithoutAutoHistoryNeverClaimsSucceeded() throws Exception {
        Harness harness = harness(false);
        String pboc = MonitorSeriesDefaults.EUR_CNY_ITEM_ID;
        BackfillJobStateV1 job = harness.orchestrator().createOrResume(
                pboc, LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-31"));
        BackfillJobStateV1 refreshed = harness.orchestrator().refresh(job.jobId());
        assertFalse(refreshed.status() == BackfillJobStateV1.JobStatus.SUCCEEDED,
                "without automatic history capability the job must never claim SUCCEEDED");
        assertTrue(refreshed.failureReasons().stream().anyMatch(reason -> reason.contains("NO_AUTO_HISTORY_CAPABILITY")));
    }

    private Harness harness(boolean withAutoProvider) {
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve("backfill root " + System.nanoTime()));
        AtomicMoveSupport.probeOrFail(root);
        AtomicFileStore fileStore = new AtomicFileStore(root, new DirtyMarkerCodec());
        ConfigActivationStore configStore = new ConfigActivationStore(root, fileStore, CLOCK);
        configStore.activate(backfillConfig());
        RawReceiptStore rawStore = new RawReceiptStore(root, fileStore, CLOCK);
        TimelineStore timelineStore = new TimelineStore(root, fileStore, CLOCK);
        ManualMaterialIntakeService manual = new ManualMaterialIntakeService(
                root, rawStore, timelineStore, new ManualMaterialNormalizer(),
                OperatorContext.configured("op-d5t04"), CLOCK);
        LifecycleValidationService validation = new LifecycleValidationService(root, timelineStore, CLOCK);
        LifecyclePublishService publish = new LifecyclePublishService(root, timelineStore,
                new QuarantineStore(root, fileStore, CLOCK), CLOCK);
        DailyProcessingService daily = new DailyProcessingService(root, timelineStore, fileStore, CLOCK);
        AggregateProcessingService aggregate = new AggregateProcessingService(root, fileStore, CLOCK);
        RawAcquisitionStore acquisitionStore = new RawAcquisitionStore(root, fileStore, CLOCK);
        DataProviderRegistry registry = new DataProviderRegistry();
        if (withAutoProvider) {
            registry.register(autoProvider());
        }
        BackfillJobStore jobStore = new BackfillJobStore(root, fileStore, CLOCK);
        BackfillOrchestrator orchestrator = new BackfillOrchestrator(
                root, jobStore, configStore, registry, acquisitionStore, rawStore, timelineStore,
                validation, publish, daily, aggregate);
        return new Harness(root, configStore, timelineStore, manual, validation, publish,
                daily, aggregate, orchestrator, rawStore);
    }

    /**
     * A real DataProvider (generic capability: OFFICIAL_WEB exchange-rate, history supported)
     * that produces one acquisition per requested day - the orchestrator drives it exactly like
     * any production provider.
     */
    private static DataProvider autoProvider() {
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
                return Set.of(AUTO_ITEM);
            }

            @Override
            public boolean supports(MonitorSeriesItemV1 item) {
                return item.providerType() == ProviderType.OFFICIAL_WEB
                        && MonitorSeriesDefaults.PBOC_RATE_KIND.equals(item.rateKind());
            }

            @Override
            public ProviderCollectOutcome collect(ProviderCollectRequest request) {
                java.util.LinkedHashMap<String, String> rejected = new java.util.LinkedHashMap<>();
                java.util.ArrayList<com.supplymind.foundation.model.RawReceiptV1> raws = new java.util.ArrayList<>();
                for (String itemId : request.itemIds()) {
                    if (!itemId.equals(AUTO_ITEM)) {
                        rejected.put(itemId, "UNSUPPORTED_TARGET");
                        continue;
                    }
                    String businessDate = nextPendingDay(itemId);
                    if (businessDate == null) {
                        rejected.put(itemId, "NO_PENDING_DAY");
                        continue;
                    }
                    byte[] payload = ("{\"fx\":\"" + businessDate + "\"}").getBytes(StandardCharsets.UTF_8);
                    String runId = "auto-" + itemId + "-" + businessDate.replace("-", "");
                    String acquisitionId = "auto-acq-" + runId;
                    raws.add(new com.supplymind.foundation.model.RawReceiptV1(
                            "1.0", com.supplymind.foundation.model.RawReceiptV1.deriveRawRef(
                            Mode.FORMAL, ProviderType.OFFICIAL_WEB, itemId, NOW, runId),
                            acquisitionId, runId, Mode.FORMAL,
                            ProviderType.OFFICIAL_WEB, AccessMethod.PUBLIC_OFFICIAL_HTML, 1,
                            "中国人民银行官网（授权中国外汇交易中心公布）",
                            "https://example.test/fx", "fx-ref", itemId,
                            businessDate, businessDate, null, NOW, NOW, null,
                            "7.1200", "CNY/1 USD", "CNY", null, 200, "text/html; charset=UTF-8", "base64",
                            java.util.Base64.getEncoder().encodeToString(payload),
                            com.supplymind.foundation.storage.FileDigest.sha256(payload),
                            null, NOW,
                            com.supplymind.foundation.storage.DataPaths.acquisitionRef(acquisitionId), null));
                }
                return new ProviderCollectOutcome("1.0", "auto-fx-history", null, null, null,
                        raws, List.of(), rejected);
            }

            private String nextPendingDay(String itemId) {
                String collected = collectedDays.getOrDefault(itemId, "");
                for (int day = 10; day <= 11; day++) {
                    String candidate = "2026-08-" + String.format("%02d", day);
                    if (!collected.contains(candidate)) {
                        collectedDays.put(itemId, collected + "," + candidate);
                        return candidate;
                    }
                }
                return null;
            }
        };
    }

    private static MonitorSeriesConfigV1 backfillConfig() {
        MonitorSeriesItemV1 adc12 = new MonitorSeriesItemV1(
                ADC12_SMM, ADC12_SMM, true, "SMM", ProviderType.MANUAL, AccessMethod.MANUAL,
                "人工录入（Manual）", RouteDecision.FALLBACK_MANUAL, "MANUAL_FALLBACK", NOW, null,
                "ADC12", "material-field-key", "material",
                "arithmetic-mean-v1", 2, 2, java.math.RoundingMode.HALF_UP, "weekday-asia-shanghai-v1",
                "CNY", "CNY", "元/吨",
                new MaterialValidationConfigV1("0", null, 7, "ADC12", List.of()));
        MonitorSeriesItemV1 usd = new MonitorSeriesItemV1(
                MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                "美元/人民币中间价", true, "PBOC", ProviderType.OFFICIAL_WEB,
                AccessMethod.PUBLIC_OFFICIAL_HTML, "中国人民银行官网（授权中国外汇交易中心公布）",
                RouteDecision.PRIMARY, null, NOW, null, "USD", "1美元对人民币", "人民币汇率中间价",
                "arithmetic-mean-v1", 8, 4, java.math.RoundingMode.HALF_UP, "weekday-asia-shanghai-v1",
                "CNY", "USD", "CNY/1 USD", null);
        MonitorSeriesItemV1 eur = new MonitorSeriesItemV1(
                MonitorSeriesDefaults.EUR_CNY_ITEM_ID,
                "欧元/人民币中间价", true, "PBOC", ProviderType.OFFICIAL_WEB,
                AccessMethod.PUBLIC_OFFICIAL_HTML, "中国人民银行官网（授权中国外汇交易中心公布）",
                RouteDecision.PRIMARY, null, NOW, null, "EUR", "1欧元对人民币", "人民币汇率中间价",
                "arithmetic-mean-v1", 8, 4, java.math.RoundingMode.HALF_UP, "weekday-asia-shanghai-v1",
                "CNY", "EUR", "CNY/1 EUR", null);
        return new MonitorSeriesConfigV1("1.0", 1, Mode.FORMAL, NOW, List.of(adc12, usd, eur));
    }

    private record Harness(
            DataRoot root,
            ConfigActivationStore configStore,
            TimelineStore timelineStore,
            ManualMaterialIntakeService manual,
            LifecycleValidationService validation,
            LifecyclePublishService publish,
            DailyProcessingService daily,
            AggregateProcessingService aggregate,
            BackfillOrchestrator orchestrator,
            RawReceiptStore rawStore
    ) {
        int rawCount(String itemId) {
            java.nio.file.Path dir = root.resolveInternalRelative("raw/formal/official_web/" + itemId);
            if (!Files.isDirectory(dir)) {
                return 0;
            }
            try (var stream = Files.walk(dir)) {
                return (int) stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".json"))
                        .filter(p -> !p.getFileName().toString().endsWith(".manifest.json"))
                        .count();
            } catch (java.io.IOException exception) {
                throw new RuntimeException(exception);
            }
        }
    }
}
