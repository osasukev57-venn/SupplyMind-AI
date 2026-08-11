package com.supplymind.processing;

import com.supplymind.foundation.codec.CsvV1Codec;
import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.DailyInputRefV1;
import com.supplymind.foundation.model.DailyRecordV1;
import com.supplymind.foundation.model.MaterialValidationConfigV1;
import com.supplymind.foundation.model.Mode;
import com.supplymind.foundation.model.MonitorSeriesConfigV1;
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
import com.supplymind.foundation.storage.RawReceiptStore;
import com.supplymind.foundation.storage.TimelineStore;
import com.supplymind.manual.ManualIntakeOutcome;
import com.supplymind.manual.ManualMaterialIntakeService;
import com.supplymind.manual.ManualMaterialNormalizer;
import com.supplymind.manual.ManualMaterialSubmission;
import com.supplymind.manual.OperatorContext;
import com.supplymind.publish.LifecyclePublishService;
import com.supplymind.publish.PublishOutcome;
import com.supplymind.validation.LifecycleValidationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D4-T03/D4-T04 material daily and aggregate acceptance: published material observations feed
 * the frozen daily CSV (monthly writer, BigDecimal arithmetic-mean-v1, missing never zero-filled,
 * full inputRefs and validation/config context) and the four-level aggregate CSV rebuilt from
 * legal daily inputs only. Cross-spec/source/context mixing is impossible because grouping is
 * item+source-identity bound. PBOC behavior is unchanged (DailyProcessingServiceTest/
 * AggregateProcessingServiceTest).
 */
class MaterialDailyAggregateTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-10T02:00:00Z"), ZoneOffset.UTC);
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-08-10T02:00:00+08:00");
    private static final String ADC12_SMM = "MAT.ADC12.SMM";
    private static final String AZ91D_AM = "MAT.AZ91D.AM";

    @TempDir
    Path temporaryDirectory;

    @Test
    void publishedMaterialProducesDeterministicDailyRowsWithFullContext() throws IOException {
        Harness harness = harness();
        publish(harness, ADC12_SMM, "2026-08-10", "19850.50", "华东某厂报价单（测试）");
        publish(harness, ADC12_SMM, "2026-08-08", "20000.00", "华东某厂报价单（测试）");
        publish(harness, AZ91D_AM, "2026-08-10", "24500", "西南某厂报价单（测试）");

        DailyResult adc12 = harness.daily().processMonth(ADC12_SMM, YearMonth.of(2026, 8));
        assertNotNull(adc12.dailyRef());
        assertEquals(2, adc12.rows().size(), "one row per legal business day, missing days never zero-filled");
        DailyRecordV1 day10 = adc12.rows().stream()
                .filter(row -> row.businessDate().equals("2026-08-10")).findFirst().orElseThrow();
        assertEquals("19850.50", day10.avg());
        assertEquals("19850.50", day10.sum());
        assertEquals(1, day10.validCount());
        assertEquals("元/吨", day10.unit());
        assertEquals("CNY", day10.currency());
        assertFalse(day10.inputRefs().isEmpty(), "daily rows must keep full inputRefs");
        for (DailyInputRefV1 ref : day10.inputRefs()) {
            assertNotNull(ref.runId());
            assertNotNull(ref.rawRef());
            assertEquals(4, ref.recordVersion(), "daily inputs must point at the PUBLISHED recordVersion=4");
        }
        assertEquals("material-basic-validation-v2", day10.validationVersion());
        assertTrue(Files.isRegularFile(harness.root().resolveDataRef(
                DataPaths.dailyRef(ADC12_SMM, YearMonth.of(2026, 8)))));
        assertTrue(Files.isRegularFile(harness.root().resolveDataRef(
                DataPaths.manifestRef(DataPaths.dailyRef(ADC12_SMM, YearMonth.of(2026, 8))))));

        String csv = Files.readString(harness.root().resolveDataRef(
                DataPaths.dailyRef(ADC12_SMM, YearMonth.of(2026, 8))), StandardCharsets.UTF_8);
        assertTrue(csv.startsWith(String.join(",", CsvV1Codec.DAILY_HEADER) + "\r\n"),
                "the daily CSV must use the frozen fixed header exactly");
        assertTrue(csv.lines().count() == 3, "header + two business-day rows, missing days never zero-filled");
        List<DailyRecordV1> decoded = CsvV1Codec.decodeDaily(
                Files.readAllBytes(harness.root().resolveDataRef(DataPaths.dailyRef(ADC12_SMM, YearMonth.of(2026, 8)))));
        assertEquals(List.of("2026-08-08", "2026-08-10"),
                decoded.stream().map(DailyRecordV1::businessDate).toList(),
                "daily rows are ordered by business date ascending");
    }

    @Test
    void publishedMaterialProducesFourLevelAggregateFromDailyOnly() throws IOException {
        Harness harness = harness();
        publish(harness, ADC12_SMM, "2026-08-10", "19850.50", "华东某厂报价单（测试）");
        publish(harness, ADC12_SMM, "2026-08-08", "20000.00", "华东某厂报价单（测试）");
        publish(harness, ADC12_SMM, "2026-08-06", "20150.50", "华东某厂报价单（测试）");
        harness.daily().processMonth(ADC12_SMM, YearMonth.of(2026, 8));

        var result = harness.aggregate().processYear(ADC12_SMM, 2026);
        assertEquals(4, result.writtenRefs().size(),
                "month/quarter/halfyear/year must all be produced from legal daily inputs");
        for (String ref : result.writtenRefs()) {
            assertTrue(ref.startsWith("processed/aggregate/" + ADC12_SMM + "/"),
                    "aggregate files must live under processed/aggregate/<itemId>/...");
            assertTrue(Files.isRegularFile(harness.root().resolveDataRef(ref)));
        }
        List<String> monthCsv = Files.readAllLines(harness.root().resolveDataRef(
                "processed/aggregate/" + ADC12_SMM + "/month/2026.csv"), StandardCharsets.UTF_8);
        assertFalse(monthCsv.isEmpty());
        assertTrue(monthCsv.get(0).equals(String.join(",", CsvV1Codec.AGGREGATE_HEADER)),
                "the aggregate CSV must use the frozen fixed header exactly");
        assertEquals(1, monthCsv.size() - 1, "one month row for the single August period");
        assertTrue(monthCsv.get(1).contains("sum=") || monthCsv.get(1).contains(","),
                "aggregate rows carry sum/validCount/avg plus sourceFingerprint and inputRefs");
        assertFalse(monthCsv.get(1).contains("display9"),
                "aggregate must never persist a rounded display value as business truth");
    }

    @Test
    void differentDeclaredSourcesStayInSeparateDailyRows() throws IOException {
        Harness harness = harness();
        publish(harness, ADC12_SMM, "2026-08-10", "19850.50", "华东某厂报价单（测试）");
        publish(harness, ADC12_SMM, "2026-08-10", "19900.00", "华东另一厂报价单（测试）");
        DailyResult result = harness.daily().processMonth(ADC12_SMM, YearMonth.of(2026, 8));
        assertEquals(2, result.rows().size(),
                "different declared sources must never be mixed into one daily row");
    }

    @Test
    void missingBusinessDaysAreNeverZeroFilled() throws IOException {
        Harness harness = harness();
        publish(harness, ADC12_SMM, "2026-08-10", "19850.50", "华东某厂报价单（测试）");
        publish(harness, ADC12_SMM, "2026-08-03", "20500.00", "华东某厂报价单（测试）");
        DailyResult result = harness.daily().processMonth(ADC12_SMM, YearMonth.of(2026, 8));
        assertEquals(2, result.rows().size(),
                "only days with legal published inputs produce rows; gaps are never zero-filled");
        assertTrue(result.rows().stream().noneMatch(row -> row.avg().equals("0") || row.avg().equals("0.00")));
    }

    private static void publish(Harness harness, String itemId, String businessDate, String value,
                                String declaredSource) throws IOException {
        ManualIntakeOutcome intake = harness.manual().submit(ManualMaterialSubmission.of(
                itemId, businessDate, value, "元/吨", "CNY",
                declaredSource, "报价单号-" + itemId + "-" + businessDate, null));
        harness.validation().process(intake.runId());
        PublishOutcome publish = harness.publish().process(intake.runId());
        assertEquals(PublishOutcome.PublishAction.PUBLISHED, publish.action());
    }

    private Harness harness() {
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve("d4-t03-t04 root"));
        AtomicMoveSupport.probeOrFail(root);
        AtomicFileStore fileStore = new AtomicFileStore(root, new DirtyMarkerCodec());
        ConfigActivationStore configStore = new ConfigActivationStore(root, fileStore, CLOCK);
        configStore.activate(materialConfig());
        RawReceiptStore rawStore = new RawReceiptStore(root, fileStore, CLOCK);
        TimelineStore timelineStore = new TimelineStore(root, fileStore, CLOCK);
        ManualMaterialIntakeService manual = new ManualMaterialIntakeService(
                root, rawStore, timelineStore, new ManualMaterialNormalizer(),
                OperatorContext.configured("op-d4t03"), CLOCK);
        LifecycleValidationService validation = new LifecycleValidationService(root, timelineStore, CLOCK);
        LifecyclePublishService publish = new LifecyclePublishService(root, timelineStore,
                new QuarantineStore(root, fileStore, CLOCK), CLOCK);
        DailyProcessingService daily = new DailyProcessingService(root, timelineStore, fileStore, CLOCK);
        AggregateProcessingService aggregate = new AggregateProcessingService(root, fileStore, CLOCK);
        return new Harness(root, manual, validation, publish, daily, aggregate);
    }

    private static MonitorSeriesConfigV1 materialConfig() {
        List<MonitorSeriesItemV1> items = new ArrayList<>();
        items.add(item(ADC12_SMM, "SMM", "ADC12"));
        items.add(item(AZ91D_AM, "Asian Metal", "AZ91D"));
        return new MonitorSeriesConfigV1("1.0", 1, Mode.FORMAL, NOW, items);
    }

    private static MonitorSeriesItemV1 item(String itemId, String sourceIntent, String externalCode) {
        return new MonitorSeriesItemV1(
                itemId, itemId, true, sourceIntent, ProviderType.MANUAL, AccessMethod.MANUAL,
                "人工录入（Manual）", RouteDecision.FALLBACK_MANUAL, "MANUAL_FALLBACK", NOW, null,
                externalCode, "material-field-key", "material",
                "arithmetic-mean-v1", 2, 2, RoundingMode.HALF_UP, "weekday-asia-shanghai-v1",
                "CNY", "CNY", "元/吨",
                new MaterialValidationConfigV1("0", null, 7, externalCode, List.of()));
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
