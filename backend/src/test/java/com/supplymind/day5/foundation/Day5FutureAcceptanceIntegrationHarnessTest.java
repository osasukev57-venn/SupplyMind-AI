package com.supplymind.day5.foundation;

import com.supplymind.backfill.BackfillJobStateV1;
import com.supplymind.backfill.BackfillJobStore;
import com.supplymind.backfill.BackfillOrchestrator;
import com.supplymind.config.ConfigManagementService;
import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.MaterialValidationConfigV1;
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
import com.supplymind.foundation.storage.RawReceiptStore;
import com.supplymind.foundation.storage.TimeStateStore;
import com.supplymind.foundation.storage.TimelineStore;
import com.supplymind.history.HistoryQueryService;
import com.supplymind.manual.ManualDataProvider;
import com.supplymind.manual.ManualIntakeOutcome;
import com.supplymind.manual.ManualMaterialIntakeService;
import com.supplymind.manual.ManualMaterialNormalizer;
import com.supplymind.manual.ManualMaterialSubmission;
import com.supplymind.manual.OperatorContext;
import com.supplymind.processing.AggregateProcessingService;
import com.supplymind.processing.DailyProcessingService;
import com.supplymind.provider.DataProviderRegistry;
import com.supplymind.publish.LifecyclePublishService;
import com.supplymind.rotation.TimeRotationService;
import com.supplymind.validation.LifecycleValidationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
 * H05-H09 end-to-end acceptance entry points, now bound to the real Day-5 production chain
 * (D5-T01 rotation, D5-T02 history query, D5-T03 configuration, D5-T04 backfill). Only
 * AT-TIME-003/004 (physical system time) remains pending in Day5TimeContractHarnessTest.
 */
class Day5FutureAcceptanceIntegrationHarnessTest {

    private static final String NEW_ITEM = "MAT.HARNESS.ADC12.SMM";

    @TempDir
    Path temporaryDirectory;

    @Test
    void h05PhysicalRotationAcceptance() {
        Harness harness = harness();
        assertTrue(harness.rotation().check(at("2026-08-31T23:00:00+08:00")).firstRun());
        TimeRotationService.RotationCheckResult rolled =
                harness.rotation().check(at("2026-09-01T00:30:00+08:00"));
        assertTrue(rolled.monthRolled(), "H05 backend: the month boundary rolls the rotation state");
    }

    @Test
    void h06CrossYearMergeDedupeAndSortAcceptance() throws Exception {
        Harness harness = harness();
        harness.writeDaily(2025, 12, "2025-12-30");
        harness.writeDaily(2026, 1, "2026-01-02");
        var result = harness.history().queryDaily(
                NEW_ITEM, LocalDate.parse("2025-12-01"), LocalDate.parse("2026-01-31"));
        assertEquals(2, result.rows().size());
        assertEquals(List.of("2025-12-30", "2026-01-02"),
                result.rows().stream().map(row -> row.businessDate()).toList(),
                "H06 backend: cross-year merge is sorted and deterministic");
        assertTrue(result.missingRefs().isEmpty());
    }

    @Test
    void h07ConfigurationDrivenTargetAddAcceptance() {
        Harness harness = harness();
        harness.management().addItem(harness.newItem());
        assertEquals(2, harness.management().active().configVersion());
        assertTrue(harness.management().active().requireItem(NEW_ITEM).enabled(),
                "H07 backend: a new target is pure configuration, no Java business code change");
    }

    @Test
    void h08CurrentAndBackfillAcceptance() throws Exception {
        Harness harness = harness();
        harness.management().addItem(harness.newItem());
        BackfillJobStateV1 job = harness.backfill().createOrResume(
                NEW_ITEM, LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-31"));
        assertEquals(BackfillJobStateV1.JobStatus.AWAITING_MANUAL_INPUT,
                harness.backfill().refresh(job.jobId()).status(),
                "H08 backend: manual target honestly waits instead of faking success");
        ManualIntakeOutcome intake = harness.manual().submit(ManualMaterialSubmission.of(
                NEW_ITEM, "2026-08-10", "21000.00", "元/吨", "CNY",
                "华东某厂报价单（测试）", "报价单号A-20260810", null));
        harness.validation().process(intake.runId());
        harness.publish().process(intake.runId());
        assertEquals(BackfillJobStateV1.JobStatus.SUCCEEDED,
                harness.backfill().refresh(job.jobId()).status(),
                "H08 backend: current data flows through validation/publish/daily to complete the backfill");
    }

    @Test
    void h09HideShowAndHistoryRetentionAcceptance() throws Exception {
        Harness harness = harness();
        harness.management().addItem(harness.newItem());
        harness.writeDaily(2026, 8, "2026-08-10");
        harness.management().setEnabled(NEW_ITEM, false);
        assertFalse(harness.management().active().requireItem(NEW_ITEM).enabled());
        var history = harness.history().queryDaily(
                NEW_ITEM, LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-31"));
        assertEquals(1, history.rows().size(),
                "H09 backend: hiding a target never deletes its history");
    }

    private static OffsetDateTime at(String text) {
        return OffsetDateTime.parse(text);
    }

    private Harness harness() {
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve(
                "day5 harness root " + System.nanoTime()));
        AtomicMoveSupport.probeOrFail(root);
        AtomicFileStore fileStore = new AtomicFileStore(root, new DirtyMarkerCodec());
        Clock clock = Clock.fixed(Instant.parse("2026-08-10T02:00:00Z"), ZoneOffset.UTC);
        ConfigActivationStore configStore = new ConfigActivationStore(root, fileStore, clock);
        configStore.activate(MonitorSeriesDefaults.initialDay3(
                OffsetDateTime.parse("2026-08-11T10:00:00+08:00")));
        RawReceiptStore rawStore = new RawReceiptStore(root, fileStore, clock);
        TimelineStore timelineStore = new TimelineStore(root, fileStore, clock);
        ManualMaterialIntakeService manual = new ManualMaterialIntakeService(
                root, rawStore, timelineStore, new ManualMaterialNormalizer(),
                OperatorContext.configured("op-day5-harness"), clock);
        LifecycleValidationService validation = new LifecycleValidationService(root, timelineStore, clock);
        LifecyclePublishService publish = new LifecyclePublishService(root, timelineStore,
                new QuarantineStore(root, fileStore, clock), clock);
        DailyProcessingService daily = new DailyProcessingService(root, timelineStore, fileStore, clock);
        AggregateProcessingService aggregate = new AggregateProcessingService(root, fileStore, clock);
        DataProviderRegistry registry = new DataProviderRegistry();
        registry.register(new ManualDataProvider(() -> Set.of(
                MonitorSeriesDefaults.ADC12_SMM_ITEM_ID, MonitorSeriesDefaults.ADC12_AM_ITEM_ID,
                MonitorSeriesDefaults.AZ91D_SMM_ITEM_ID, MonitorSeriesDefaults.AZ91D_AM_ITEM_ID,
                NEW_ITEM)));
        registry.register(new com.supplymind.provider.DataProvider() {
            @Override
            public com.supplymind.provider.ProviderSourceProfile profile() {
                return com.supplymind.provider.ProviderSourceProfile.of(
                        "pboc-official-web", ProviderType.OFFICIAL_WEB,
                        AccessMethod.PUBLIC_OFFICIAL_HTML, "中国人民银行官网（授权中国外汇交易中心公布）",
                        "https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/index.html", true, false);
            }

            @Override
            public Set<String> supportedItemIds() {
                return Set.of(MonitorSeriesDefaults.USD_CNY_ITEM_ID, MonitorSeriesDefaults.EUR_CNY_ITEM_ID);
            }

            @Override
            public com.supplymind.provider.ProviderCollectOutcome collect(
                    com.supplymind.provider.ProviderCollectRequest request) {
                return com.supplymind.provider.ProviderCollectOutcome.rejectedOnly("pboc-official-web", Map.of());
            }
        });
        ConfigManagementService management = new ConfigManagementService(configStore, registry);
        BackfillOrchestrator backfill = new BackfillOrchestrator(
                root, new BackfillJobStore(root, fileStore, clock), configStore,
                timelineStore, daily, aggregate);
        HistoryQueryService history = new HistoryQueryService(root);
        TimeRotationService rotation = new TimeRotationService(new TimeStateStore(root, fileStore, clock));
        return new Harness(root, fileStore, management, manual, validation, publish,
                daily, aggregate, backfill, history, rotation);
    }

    private record Harness(
            DataRoot root,
            AtomicFileStore fileStore,
            ConfigManagementService management,
            ManualMaterialIntakeService manual,
            LifecycleValidationService validation,
            LifecyclePublishService publish,
            DailyProcessingService daily,
            AggregateProcessingService aggregate,
            BackfillOrchestrator backfill,
            HistoryQueryService history,
            TimeRotationService rotation
    ) {
        void writeDaily(int year, int month, String businessDate) throws Exception {
            var row = new com.supplymind.foundation.model.DailyRecordV1(
                    "1.0", businessDate, NEW_ITEM, ProviderType.MANUAL,
                    "人工录入（Manual）", AccessMethod.MANUAL,
                    com.supplymind.foundation.model.ProcessingStage.PUBLISHED,
                    com.supplymind.foundation.model.ValidationStatus.VERIFIED,
                    "material-basic-validation-v2", List.of(1), "arithmetic-mean-v1", 2, 2,
                    java.math.RoundingMode.HALF_UP, "weekday-asia-shanghai-v1",
                    "21000.00", 1, "21000.00", 22, 21, false, "CNY", "元/吨",
                    List.of(new com.supplymind.foundation.model.DailyInputRefV1(
                            "run-harness-" + businessDate,
                            "raw/formal/manual/" + NEW_ITEM + "/2026/08/run-harness-" + businessDate + ".json", 4)),
                    OffsetDateTime.parse("2026-08-10T09:00:00+08:00"), "ADC12");
            byte[] csv = com.supplymind.foundation.codec.CsvV1Codec.encodeDaily(List.of(row));
            String ref = com.supplymind.foundation.storage.DataPaths.dailyRef(
                    NEW_ITEM, java.time.YearMonth.of(year, month));
            var manifest = com.supplymind.foundation.storage.ManifestFactory.csv(
                    ref, csv, 1, businessDate, businessDate,
                    List.of("run-harness-" + businessDate),
                    OffsetDateTime.parse("2026-08-10T10:00:00+08:00"));
            fileStore.commit("day5-harness-" + ref.replace("/", "-").replace(".", "-"),
                    com.supplymind.foundation.storage.DirtyTransactionType.SINGLE_FILE,
                    OffsetDateTime.parse("2026-08-10T10:00:00+08:00"),
                    List.of(new com.supplymind.foundation.storage.FileTransactionTarget(
                            com.supplymind.foundation.storage.DirtyTargetRole.BUSINESS_FILE,
                            ref, csv, com.supplymind.foundation.codec.JsonV1Codec.encodeFile(manifest), false)));
        }

        MonitorSeriesItemV1 newItem() {
            return new MonitorSeriesItemV1(
                    NEW_ITEM, "HARNESS-ADC12（演示新增）", true, "SMM", ProviderType.MANUAL, AccessMethod.MANUAL,
                    "人工录入（Manual）", RouteDecision.FALLBACK_MANUAL, "MANUAL_FALLBACK",
                    OffsetDateTime.parse("2026-08-11T10:00:00+08:00"), null,
                    "ADC12", "material-field-key", "material",
                    "arithmetic-mean-v1", 2, 2, java.math.RoundingMode.HALF_UP, "weekday-asia-shanghai-v1",
                    "CNY", "CNY", "元/吨",
                    new MaterialValidationConfigV1("0", null, 7, "ADC12", List.of()));
        }
    }
}
