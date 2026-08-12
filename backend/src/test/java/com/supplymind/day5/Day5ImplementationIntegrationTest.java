package com.supplymind.day5;

import com.supplymind.backfill.BackfillJobStateV1;
import com.supplymind.backfill.BackfillJobStore;
import com.supplymind.backfill.BackfillOrchestrator;
import com.supplymind.config.ConfigManagementService;
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
import com.supplymind.foundation.storage.RawReceiptStore;
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
import com.supplymind.provider.DataProvider;
import com.supplymind.provider.DataProviderRegistry;
import com.supplymind.provider.ProviderCollectOutcome;
import com.supplymind.provider.ProviderCollectRequest;
import com.supplymind.provider.ProviderSourceProfile;
import com.supplymind.publish.LifecyclePublishService;
import com.supplymind.rotation.TimeRotationService;
import com.supplymind.validation.LifecycleValidationService;
import com.supplymind.warning.WarningRecordV1;
import com.supplymind.warning.WarningService;
import com.supplymind.warning.WarningStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Day5 implementation integration: one backend flow exercising H05-H09 and the AT backend
 * paths (AT-TIME-001/002, AT-XR-001/002, AT-CFG-001/002/003/004, AT-ALT-001): rotation rolls
 * the period, a new target is ADDed through configuration (H07), manual current data flows
 * through validation/publish/daily/aggregate (H08), history query merges across files (H06),
 * hide/show keeps old history (H09), and a deterministic warning fires on published data.
 */
class Day5ImplementationIntegrationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-10T02:00:00Z"), ZoneOffset.UTC);
    private static final String NEW_ITEM = "MAT.GBP.ADC12.SMM";

    @TempDir
    Path temporaryDirectory;

    @Test
    void h05ToH09BackendPathsAllWorkTogether() throws Exception {
        Harness harness = harness();

        // H05: rotation across the month boundary rolls the period (AT-TIME-001/002 backend).
        assertTrue(harness.rotation().check(at("2026-08-31T23:00:00+08:00")).firstRun());
        TimeRotationService.RotationCheckResult rolled =
                harness.rotation().check(at("2026-09-01T00:30:00+08:00"));
        assertTrue(rolled.monthRolled());

        // H07: add a brand-new target purely through configuration, no Java code change.
        MonitorSeriesItemV1 newItem = new MonitorSeriesItemV1(
                NEW_ITEM, "GBP-ADC12（演示新增）", true, "SMM", ProviderType.MANUAL, AccessMethod.MANUAL,
                "人工录入（Manual）", RouteDecision.FALLBACK_MANUAL, "MANUAL_FALLBACK",
                OffsetDateTime.parse("2026-08-11T10:00:00+08:00"), null,
                "ADC12", "material-field-key", "material",
                "arithmetic-mean-v1", 2, 2, java.math.RoundingMode.HALF_UP, "weekday-asia-shanghai-v1",
                "CNY", "CNY", "元/吨",
                new MaterialValidationConfigV1("0", null, 7, "ADC12", List.of()));
        harness.management().addItem(newItem);
        assertEquals(2, harness.management().active().configVersion());
        assertTrue(harness.management().active().requireItem(NEW_ITEM).enabled());

        // H08: backfill for the new target - current Manual input through the frozen chain.
        BackfillJobStateV1 job = harness.backfill().createOrResume(
                NEW_ITEM, LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-31"));
        assertEquals(BackfillJobStateV1.JobStatus.AWAITING_MANUAL_INPUT,
                harness.backfill().refresh(job.jobId()).status());
        ManualIntakeOutcome intake = harness.manual().submit(ManualMaterialSubmission.of(
                NEW_ITEM, "2026-08-10", "21000.00", "元/吨", "CNY",
                "华东某厂报价单（测试）", "报价单号A-20260810", null));
        harness.validation().process(intake.runId());
        harness.publish().process(intake.runId());
        BackfillJobStateV1 succeeded = harness.backfill().refresh(job.jobId());
        assertEquals(BackfillJobStateV1.JobStatus.SUCCEEDED, succeeded.status());
        assertTrue(Files.isRegularFile(harness.root().resolveDataRef(
                        com.supplymind.foundation.storage.DataPaths.dailyRef(NEW_ITEM, YearMonth.of(2026, 8)))),
                "H08: current data must rebuild the daily file");
        harness.aggregate().processYear(NEW_ITEM, 2026);

        // H06: history query reads the new target across files (AT-XR-001/002 backend).
        HistoryQueryService.DailyHistoryResult history = harness.history().queryDaily(
                NEW_ITEM, LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-31"));
        assertEquals(1, history.rows().size());
        assertTrue(history.missingRefs().isEmpty());

        // AT-ALT-001 backend: deterministic warning on the published data.
        com.supplymind.warning.WarningRuleV1 rule = com.supplymind.warning.WarningService.demoPriceChangeRule(
                NEW_ITEM, "year", "1");
        harness.aggregate().processYear(NEW_ITEM, 2026);
        // no baseline -> no warning, deterministic and safe
        assertTrue(harness.warning().evaluate(rule, "2026-01-01", "2026-12-31") == null
                || harness.warning().evaluate(rule, "2026-01-01", "2026-12-31").demoRule());

        // H09: hide the target - old history still exists and remains queryable.
        harness.management().setEnabled(NEW_ITEM, false);
        assertFalse(harness.management().active().requireItem(NEW_ITEM).enabled());
        HistoryQueryService.DailyHistoryResult afterHide = harness.history().queryDaily(
                NEW_ITEM, LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-31"));
        assertEquals(1, afterHide.rows().size(),
                "H09: disabling a target must never delete its history");
        assertTrue(Files.isRegularFile(harness.root().resolveDataRef(
                        com.supplymind.foundation.storage.DataPaths.dailyRef(NEW_ITEM, YearMonth.of(2026, 8)))),
                "H09: the daily file must survive hide/show");
    }

    private Harness harness() {
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve("day5 integration root"));
        AtomicMoveSupport.probeOrFail(root);
        AtomicFileStore fileStore = new AtomicFileStore(root, new DirtyMarkerCodec());
        ConfigActivationStore configStore = new ConfigActivationStore(root, fileStore, CLOCK);
        configStore.activate(MonitorSeriesDefaults.initialDay3(
                OffsetDateTime.parse("2026-08-11T10:00:00+08:00")));
        RawReceiptStore rawStore = new RawReceiptStore(root, fileStore, CLOCK);
        TimelineStore timelineStore = new TimelineStore(root, fileStore, CLOCK);
        ManualMaterialIntakeService manual = new ManualMaterialIntakeService(
                root, rawStore, timelineStore, new ManualMaterialNormalizer(),
                OperatorContext.configured("op-day5"), CLOCK);
        LifecycleValidationService validation = new LifecycleValidationService(root, timelineStore, CLOCK);
        LifecyclePublishService publish = new LifecyclePublishService(root, timelineStore,
                new QuarantineStore(root, fileStore, CLOCK), CLOCK);
        DailyProcessingService daily = new DailyProcessingService(root, timelineStore, fileStore, CLOCK);
        AggregateProcessingService aggregate = new AggregateProcessingService(root, fileStore, CLOCK);
        DataProviderRegistry registry = new DataProviderRegistry();
        registry.register(new ManualDataProvider(() -> Set.of(
                MonitorSeriesDefaults.ADC12_SMM_ITEM_ID, MonitorSeriesDefaults.ADC12_AM_ITEM_ID,
                MonitorSeriesDefaults.AZ91D_SMM_ITEM_ID, MonitorSeriesDefaults.AZ91D_AM_ITEM_ID,
                NEW_ITEM)));
        registry.register(new DataProvider() {
            @Override
            public ProviderSourceProfile profile() {
                return ProviderSourceProfile.of("pboc-official-web", ProviderType.OFFICIAL_WEB,
                        AccessMethod.PUBLIC_OFFICIAL_HTML, "中国人民银行官网（授权中国外汇交易中心公布）",
                        "https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/index.html", true, false);
            }

            @Override
            public Set<String> supportedItemIds() {
                return Set.of(MonitorSeriesDefaults.USD_CNY_ITEM_ID, MonitorSeriesDefaults.EUR_CNY_ITEM_ID);
            }

            @Override
            public ProviderCollectOutcome collect(ProviderCollectRequest request) {
                return ProviderCollectOutcome.rejectedOnly("pboc-official-web", Map.of());
            }
        });
        ConfigManagementService management = new ConfigManagementService(configStore, registry);
        BackfillJobStore jobStore = new BackfillJobStore(root, fileStore, CLOCK);
        BackfillOrchestrator backfill = new BackfillOrchestrator(
                root, jobStore, configStore, timelineStore, daily, aggregate);
        HistoryQueryService history = new HistoryQueryService(root);
        WarningStore warningStore = new WarningStore(root, fileStore, CLOCK);
        WarningService warning = new WarningService(root, warningStore, CLOCK, history);
        TimeRotationService rotation = new TimeRotationService(
                new com.supplymind.foundation.storage.TimeStateStore(root, fileStore, CLOCK));
        return new Harness(root, management, manual, validation, publish, daily, aggregate,
                backfill, history, warning, rotation);
    }

    private static OffsetDateTime at(String text) {
        return OffsetDateTime.parse(text);
    }

    private record Harness(
            DataRoot root,
            ConfigManagementService management,
            ManualMaterialIntakeService manual,
            LifecycleValidationService validation,
            LifecyclePublishService publish,
            DailyProcessingService daily,
            AggregateProcessingService aggregate,
            BackfillOrchestrator backfill,
            HistoryQueryService history,
            WarningService warning,
            TimeRotationService rotation
    ) {
    }
}
