package com.supplymind.backfill;

import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.MaterialValidationConfigV1;
import com.supplymind.foundation.model.Mode;
import com.supplymind.foundation.model.MonitorSeriesConfigV1;
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
import com.supplymind.manual.ManualIntakeOutcome;
import com.supplymind.manual.ManualMaterialIntakeService;
import com.supplymind.manual.ManualMaterialNormalizer;
import com.supplymind.manual.ManualMaterialSubmission;
import com.supplymind.manual.OperatorContext;
import com.supplymind.processing.AggregateProcessingService;
import com.supplymind.processing.DailyProcessingService;
import com.supplymind.publish.LifecyclePublishService;
import com.supplymind.validation.LifecycleValidationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D5-T04 backfill orchestration (H08): manual-route targets honestly enter
 * AWAITING_MANUAL_INPUT and only SUCCEEDED after real published input flows through the
 * frozen chain; partial ranges become PARTIAL_SUCCESS; duplicate starts reuse the same job;
 * restarts resume from the persisted checkpoint; targets without auto history capability
 * never claim SUCCEEDED.
 */
class BackfillOrchestratorTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-10T02:00:00Z"), ZoneOffset.UTC);
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-08-10T02:00:00+08:00");
    private static final String ADC12_SMM = "MAT.ADC12.SMM";

    @TempDir
    Path temporaryDirectory;

    @Test
    void manualTargetAwaitsInputAndSucceedsOnlyAfterRealPublishedData() throws Exception {
        Harness harness = harness();
        BackfillJobStateV1 job = harness.orchestrator().createOrResume(
                ADC12_SMM, LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-31"));
        assertEquals(BackfillJobStateV1.JobStatus.WAITING, job.status());

        BackfillJobStateV1 waiting = harness.orchestrator().refresh(job.jobId());
        assertEquals(BackfillJobStateV1.JobStatus.AWAITING_MANUAL_INPUT, waiting.status(),
                "no input must honestly report AWAITING_MANUAL_INPUT, never fake success");
        assertTrue(waiting.completedPeriods().isEmpty());
        assertFalse(waiting.failureReasons().isEmpty());

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
    void partialRangeIsPartialSuccess() throws Exception {
        Harness harness = harness();
        BackfillJobStateV1 job = harness.orchestrator().createOrResume(
                ADC12_SMM, LocalDate.parse("2026-08-01"), LocalDate.parse("2026-09-30"));
        ManualIntakeOutcome intake = harness.manual().submit(ManualMaterialSubmission.of(
                ADC12_SMM, "2026-08-10", "19850.50", "元/吨", "CNY",
                "华东某厂报价单（测试）", "报价单号A-20260810", null));
        harness.validation().process(intake.runId());
        harness.publish().process(intake.runId());
        BackfillJobStateV1 partial = harness.orchestrator().refresh(job.jobId());
        assertEquals(BackfillJobStateV1.JobStatus.PARTIAL_SUCCESS, partial.status(),
                "only August is complete; September is still awaiting manual input");
        assertEquals(List.of("2026-08"), partial.completedPeriods());
    }

    @Test
    void duplicateStartReusesSameJobAndNeverReproducesHistory() throws Exception {
        Harness harness = harness();
        BackfillJobStateV1 first = harness.orchestrator().createOrResume(
                ADC12_SMM, LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-31"));
        ManualIntakeOutcome intake = harness.manual().submit(ManualMaterialSubmission.of(
                ADC12_SMM, "2026-08-10", "19850.50", "元/吨", "CNY",
                "华东某厂报价单（测试）", "报价单号A-20260810", null));
        harness.validation().process(intake.runId());
        harness.publish().process(intake.runId());
        harness.orchestrator().refresh(first.jobId());

        BackfillJobStateV1 duplicate = harness.orchestrator().createOrResume(
                ADC12_SMM, LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-31"));
        assertEquals(first.jobId(), duplicate.jobId(), "duplicate start must reuse the existing job");
        assertEquals(BackfillJobStateV1.JobStatus.SUCCEEDED, duplicate.status());
    }

    @Test
    void restartResumesFromPersistedCheckpoint() throws Exception {
        Harness harness = harness();
        BackfillJobStateV1 job = harness.orchestrator().createOrResume(
                ADC12_SMM, LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-31"));
        ManualIntakeOutcome intake = harness.manual().submit(ManualMaterialSubmission.of(
                ADC12_SMM, "2026-08-10", "19850.50", "元/吨", "CNY",
                "华东某厂报价单（测试）", "报价单号A-20260810", null));
        harness.validation().process(intake.runId());
        harness.publish().process(intake.runId());
        harness.orchestrator().refresh(job.jobId());

        BackfillOrchestrator restarted = new BackfillOrchestrator(
                harness.root(), new BackfillJobStore(harness.root(),
                        new AtomicFileStore(harness.root(), new DirtyMarkerCodec()), CLOCK),
                harness.configStore(), harness.timelineStore(), harness.daily(), harness.aggregate());
        BackfillJobStateV1 resumed = restarted.createOrResume(
                ADC12_SMM, LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-31"));
        assertEquals(BackfillJobStateV1.JobStatus.SUCCEEDED, resumed.status(),
                "a restart must resume from the persisted checkpoint without re-production");
        assertEquals(List.of("2026-08"), resumed.completedPeriods());
    }

    @Test
    void nonManualTargetWithoutAutoHistoryNeverClaimsSucceeded() throws Exception {
        Harness harness = harness();
        String pboc = com.supplymind.foundation.model.MonitorSeriesDefaults.USD_CNY_ITEM_ID;
        BackfillJobStateV1 job = harness.orchestrator().createOrResume(
                pboc, LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-31"));
        BackfillJobStateV1 refreshed = harness.orchestrator().refresh(job.jobId());
        assertFalse(refreshed.status() == BackfillJobStateV1.JobStatus.SUCCEEDED,
                "without automatic history capability the job must never claim SUCCEEDED");
        assertTrue(refreshed.failureReasons().stream().anyMatch(reason -> reason.contains("NO_AUTO_HISTORY_CAPABILITY")));
    }

    private Harness harness() {
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
        BackfillJobStore jobStore = new BackfillJobStore(root, fileStore, CLOCK);
        BackfillOrchestrator orchestrator = new BackfillOrchestrator(
                root, jobStore, configStore, timelineStore, daily, aggregate);
        return new Harness(root, configStore, timelineStore, manual, validation, publish, daily, aggregate, orchestrator);
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
                com.supplymind.foundation.model.MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                "美元/人民币中间价", true, "PBOC", ProviderType.OFFICIAL_WEB,
                AccessMethod.PUBLIC_OFFICIAL_HTML, "中国人民银行官网（授权中国外汇交易中心公布）",
                RouteDecision.PRIMARY, null, NOW, null, "USD", "1美元对人民币", "人民币汇率中间价",
                "arithmetic-mean-v1", 8, 4, java.math.RoundingMode.HALF_UP, "weekday-asia-shanghai-v1",
                "CNY", "USD", "CNY/1 USD", null);
        return new MonitorSeriesConfigV1("1.0", 1, Mode.FORMAL, NOW, List.of(adc12, usd));
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
            BackfillOrchestrator orchestrator
    ) {
    }
}
