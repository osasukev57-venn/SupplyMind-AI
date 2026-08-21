package com.supplymind.day4.foundation;

import com.supplymind.foundation.model.DailyRecordV1;
import com.supplymind.foundation.model.MaterialValidationConfigV1;
import com.supplymind.foundation.model.Mode;
import com.supplymind.foundation.model.MonitorSeriesConfigV1;
import com.supplymind.foundation.model.MonitorSeriesDefaults;
import com.supplymind.foundation.model.MonitorSeriesItemV1;
import com.supplymind.foundation.model.ProviderType;
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
import com.supplymind.processing.DailyResult;
import com.supplymind.publish.LifecyclePublishService;
import com.supplymind.publish.PublishOutcome;
import com.supplymind.validation.LifecycleValidationService;
import com.supplymind.validation.MaterialCandidateValidator;
import com.supplymind.validation.MaterialCandidateValidatorV2;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Day-4 stage subcase entry points (DEC-058): AT-SRC-005-D4, AT-SRC-007-D4 and AT-SRC-008-D4
 * execute the real production chain end-to-end (Manual intake -> material-basic-validation-v2
 * -> PUBLISHED -> daily -> aggregate), exercising only the frozen production configuration
 * (MonitorSeriesDefaults.initialDay3) and never fabricating material results.
 */
class FutureMaterialDay4ContractTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-10T02:00:00Z"), ZoneOffset.UTC);
    private static final String MANUAL_PRIMARY = MonitorSeriesDefaults.AZ91D_AM_ITEM_ID;
    private static final String MANUAL_SECONDARY = MonitorSeriesDefaults.AZ91D_SMM_ITEM_ID;

    @TempDir
    Path temporaryDirectory;

    @Test
    void atSrc005D4RequiresNonSyntheticMaterialRawToVerifiedFileChain() throws IOException {
        Harness harness = harness();
        ManualIntakeOutcome intake = harness.manual().submit(ManualMaterialSubmission.of(
                MANUAL_PRIMARY, "2026-08-10", "19850.50", "元/吨", "CNY",
                "华东某厂报价单（测试）", "报价单号A-20260810", null));
        assertEquals(MaterialCandidateValidatorV2.VALIDATION_VERSION, "material-basic-validation-v2");
        assertFalse(MaterialCandidateValidator.VALIDATION_VERSION.equals(
                        MaterialCandidateValidatorV2.VALIDATION_VERSION),
                "the historical v1 version must stay distinct");

        var validated = harness.validation().process(intake.runId());
        assertEquals("material-basic-validation-v2", validated.validationVersion());
        var published = harness.publish().process(intake.runId());
        assertEquals(PublishOutcome.PublishAction.PUBLISHED, published.action(),
                "AT-SRC-005-D4: a non-synthetic Manual material raw must reach the verified PUBLISHED file chain");
    }

    @Test
    void atSrc007D4RequiresManualMaterialValidationPublicationDailyAndAggregate() throws IOException {
        Harness harness = harness();
        String runId = publish(harness, MANUAL_PRIMARY, "2026-08-10", "19850.50", "华东某厂报价单（测试）");
        publish(harness, MANUAL_PRIMARY, "2026-08-08", "20000.00", "华东某厂报价单（测试）");
        publish(harness, MANUAL_SECONDARY, "2026-08-10", "24500", "西南某厂报价单（测试）");
        assertNotNull(runId);

        DailyResult daily = harness.daily().processMonth(MANUAL_PRIMARY, YearMonth.of(2026, 8));
        assertEquals(2, daily.rows().size(),
                "AT-SRC-007-D4: PUBLISHED+VERIFIED-class Manual material must feed daily rows");
        for (DailyRecordV1 row : daily.rows()) {
            assertEquals("material-basic-validation-v2", row.validationVersion());
            assertFalse(row.inputRefs().isEmpty());
        }
        var aggregate = harness.aggregate().processYear(MANUAL_PRIMARY, 2026);
        assertEquals(4, aggregate.writtenRefs().size(),
                "AT-SRC-007-D4: month/quarter/halfyear/year must be produced from legal daily inputs");
    }

    @Test
    void atSrc008D4RequiresMaterialDailyAndAggregateProvenanceReconciliation() throws IOException {
        Harness harness = harness();
        publish(harness, MANUAL_PRIMARY, "2026-08-10", "19850.50", "华东某厂报价单（测试）");
        publish(harness, MANUAL_PRIMARY, "2026-08-10", "19900.00", "华东另一厂报价单（测试）");

        DailyResult daily = harness.daily().processMonth(MANUAL_PRIMARY, YearMonth.of(2026, 8));
        assertEquals(2, daily.rows().size(),
                "AT-SRC-008-D4: different declared sources must stay in separate daily rows");
        assertTrue(daily.rows().stream().map(DailyRecordV1::actualSourceName).distinct().count() == 2,
                "daily rows must carry the real per-row actualSourceName");

        var aggregate = harness.aggregate().processYear(MANUAL_PRIMARY, 2026);
        List<String> monthRows = java.nio.file.Files.readAllLines(harness.root().resolveDataRef(
                "processed/aggregate/" + MANUAL_PRIMARY + "/month/2026.csv"), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(3, monthRows.size(),
                "header + two source-distinct aggregate rows; cross-source mixing is forbidden");
        assertFalse(monthRows.get(1).equals(monthRows.get(2)),
                "source-identity-distinct periods must never be merged");
    }

    private String publish(Harness harness, String itemId, String businessDate, String value,
                           String declaredSource) throws IOException {
        ManualIntakeOutcome intake = harness.manual().submit(ManualMaterialSubmission.of(
                itemId, businessDate, value, "元/吨", "CNY",
                declaredSource, "报价单号-" + itemId + "-" + businessDate, null));
        harness.validation().process(intake.runId());
        PublishOutcome published = harness.publish().process(intake.runId());
        assertEquals(PublishOutcome.PublishAction.PUBLISHED, published.action());
        return intake.runId();
    }

    private Harness harness() {
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve("day4-subcase root"));
        AtomicMoveSupport.probeOrFail(root);
        AtomicFileStore fileStore = new AtomicFileStore(root, new DirtyMarkerCodec());
        ConfigActivationStore configStore = new ConfigActivationStore(root, fileStore, CLOCK);
        configStore.activate(MonitorSeriesDefaults.initialDay3(OffsetDateTime.parse("2026-08-11T12:00:00+08:00")));
        RawReceiptStore rawStore = new RawReceiptStore(root, fileStore, CLOCK);
        TimelineStore timelineStore = new TimelineStore(root, fileStore, CLOCK);
        ManualMaterialIntakeService manual = new ManualMaterialIntakeService(
                root, rawStore, timelineStore, new ManualMaterialNormalizer(),
                OperatorContext.configured("op-day4"), CLOCK);
        LifecycleValidationService validation = new LifecycleValidationService(root, timelineStore, CLOCK);
        LifecyclePublishService publish = new LifecyclePublishService(root, timelineStore,
                new QuarantineStore(root, fileStore, CLOCK), CLOCK);
        DailyProcessingService daily = new DailyProcessingService(root, timelineStore, fileStore, CLOCK);
        AggregateProcessingService aggregate = new AggregateProcessingService(root, fileStore, CLOCK);
        return new Harness(root, manual, validation, publish, daily, aggregate);
    }

    private record Harness(
            DataRoot root,
            ManualMaterialIntakeService manual,
            LifecycleValidationService validation,
            LifecyclePublishService publish,
            DailyProcessingService daily,
            AggregateProcessingService aggregate
    ) {
    }
}
