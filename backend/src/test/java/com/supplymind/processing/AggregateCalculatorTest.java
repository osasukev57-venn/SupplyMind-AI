package com.supplymind.processing;

import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.AggregateGrain;
import com.supplymind.foundation.model.AggregateInputRefV1;
import com.supplymind.foundation.model.AggregateRecordV1;
import com.supplymind.foundation.model.DailyInputRefV1;
import com.supplymind.foundation.model.DailyRecordV1;
import com.supplymind.foundation.model.ProcessingStage;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.QualityStatus;
import com.supplymind.foundation.model.ValidationStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AggregateCalculatorTest {

    private static final String ITEM = "FX.USD.CNY.PBOC_MID";
    private static final String SOURCE = "中国人民银行官网（授权中国外汇交易中心公布）";
    private static final String WEEKDAY = "weekday-asia-shanghai-v1";
    private static final String DAILY_FILE_SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String GOLDEN = "golden-calendar-v1";
    private static final String JAN = "2026-01-01";
    private static final String JAN_END = "2026-01-31";
    private static final String FEB = "2026-02-01";
    private static final String FEB_END = "2026-02-28";
    private static final String Q1 = "2026-01-01";
    private static final String Q1_END = "2026-03-31";
    private static final String H1 = "2026-01-01";
    private static final String H1_END = "2026-06-30";
    private static final String YEAR = "2026-01-01";
    private static final String YEAR_END = "2026-12-31";

    @Test
    void monthAggregationMatchesHandComputedValues() {
        List<AggregateInput> inputs = List.of(
                input(daily("2026-01-05", "6.79040000", 8, 4, ValidationStatus.VERIFIED)),
                input(daily("2026-01-06", "6.80000000", 8, 4, ValidationStatus.VERIFIED)),
                input(daily("2026-01-07", "7.10000000", 8, 4, ValidationStatus.VERIFIED)));

        AggregateRecordV1 row = AggregateCalculator.calculate(
                AggregateGrain.MONTH, JAN, JAN_END, inputs).get(0);

        assertEquals("20.69040000", row.sum(), "sum must be the exact sum of daily avgs");
        assertEquals(3, row.validCount());
        assertEquals("6.89680000", row.avg());
        assertEquals("6.79040000", row.min());
        assertEquals("7.10000000", row.max());
        assertEquals(22, row.expectedCount(), "2026-01 has 22 weekdays");
        assertEquals(19, row.missingCount());
        assertFalse(row.complete());
        assertEquals(QualityStatus.INCOMPLETE, row.qualityStatus());
        assertEquals(List.of(1), row.configVersions());
        assertEquals(3, row.inputRefs().size());
        assertEquals("2026-01-05", row.inputRefs().get(0).businessDate());
        assertEquals("processed/daily/FX.USD.CNY.PBOC_MID/2026-01.csv", row.inputRefs().get(0).dailyFileRef());
        assertEquals("pboc-basic-validation-v1", row.inputRefs().get(0).validationVersion());
        assertEquals(DAILY_FILE_SHA, row.inputRefs().get(0).fileSha256());
        assertEquals("2026-01-06T09:00+08:00", row.calculatedAt().toString(),
                "calculatedAt must be max(daily.updatedAt) of the participating daily rows (DEC-055)");
    }

    @Test
    void calculatedAtIsMaxOfParticipatingDailyUpdatedAt() {
        List<AggregateInput> inputs = List.of(
                input(daily("2026-01-05", "6.79040000", 8, 4, ValidationStatus.VERIFIED,
                        "2026-01-06T08:00+08:00")),
                input(daily("2026-01-06", "6.80000000", 8, 4, ValidationStatus.VERIFIED,
                        "2026-01-06T09:30+08:00")),
                input(daily("2026-01-07", "7.10000000", 8, 4, ValidationStatus.VERIFIED,
                        "2026-01-06T09:00+08:00")));

        AggregateRecordV1 row = AggregateCalculator.calculate(
                AggregateGrain.MONTH, JAN, JAN_END, inputs).get(0);

        assertEquals("2026-01-06T09:30+08:00", row.calculatedAt().toString(),
                "calculatedAt must be the latest daily.updatedAt regardless of input order");
        List<AggregateInput> reversed = new ArrayList<>(inputs);
        Collections.reverse(reversed);
        assertEquals(row.calculatedAt(), AggregateCalculator.calculate(
                AggregateGrain.MONTH, JAN, JAN_END, reversed).get(0).calculatedAt());
    }

    @Test
    void calculatedAtComparesByInstantNotOffsetText() {
        List<AggregateInput> inputs = List.of(
                input(daily("2026-01-05", "6.79040000", 8, 4, ValidationStatus.VERIFIED,
                        "2026-01-06T02:00+08:00")),
                input(daily("2026-01-06", "6.80000000", 8, 4, ValidationStatus.VERIFIED,
                        "2026-01-05T23:00+01:00")));

        AggregateRecordV1 row = AggregateCalculator.calculate(
                AggregateGrain.MONTH, JAN, JAN_END, inputs).get(0);

        assertEquals("2026-01-06T06:00+08:00", row.calculatedAt().toString(),
                "comparison must use Instant and output must be normalized to Asia/Shanghai");
    }

    @Test
    void missingDailyUpdatedAtFailsClosed() {
        org.junit.jupiter.api.Assertions.assertThrows(
                com.supplymind.foundation.model.SchemaValidationException.class,
                () -> daily("2026-01-05", "6.79040000", 8, 4, ValidationStatus.VERIFIED, null),
                "a daily row without a legal updatedAt must fail closed at the model boundary");
    }

    @Test
    void sourceFingerprintMatchesIndependentSha256OfFrozenVector() throws Exception {
        AggregateRecordV1 row = AggregateCalculator.calculate(
                AggregateGrain.MONTH, JAN, JAN_END,
                List.of(input(daily("2026-01-05", "6.79040000", 8, 4, ValidationStatus.VERIFIED)))).get(0);
        String vector = "{\"providerType\":\"official_web\",\"actualSourceName\":\""
                + SOURCE + "\",\"accessMethod\":\"public_official_html\"}";
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(vector.getBytes(StandardCharsets.UTF_8));
        assertEquals(HexFormat.of().formatHex(digest), row.sourceFingerprint());
    }

    @Test
    void quarterHalfyearAndYearRecomputeDirectlyFromDailyAvg() {
        List<AggregateInput> inputs = List.of(
                input(daily("2026-01-05", "6.79040000", 8, 4, ValidationStatus.VERIFIED)),
                input(daily("2026-01-06", "6.80000000", 8, 4, ValidationStatus.VERIFIED)),
                input(daily("2026-02-02", "6.90000000", 8, 4, ValidationStatus.VERIFIED)));

        AggregateRecordV1 quarter = AggregateCalculator.calculate(
                AggregateGrain.QUARTER, Q1, Q1_END, inputs).get(0);
        AggregateRecordV1 halfYear = AggregateCalculator.calculate(
                AggregateGrain.HALFYEAR, H1, H1_END, inputs).get(0);
        AggregateRecordV1 year = AggregateCalculator.calculate(
                AggregateGrain.YEAR, YEAR, YEAR_END, inputs).get(0);

        String exactSum = new BigDecimal("6.79040000").add(new BigDecimal("6.80000000"))
                .add(new BigDecimal("6.90000000")).toPlainString();
        assertEquals(exactSum, quarter.sum());
        assertEquals(exactSum, halfYear.sum());
        assertEquals(exactSum, year.sum());
        assertEquals(3, quarter.validCount());
        String avg = new BigDecimal(exactSum).divide(BigDecimal.valueOf(3), 8, RoundingMode.HALF_UP).toPlainString();
        assertEquals(avg, quarter.avg());
        assertEquals("6.79040000", quarter.min());
        assertEquals("6.90000000", quarter.max());
        assertEquals(64, quarter.expectedCount(), "Q1 2026 has 22+20+22 = 64 weekdays");
        assertEquals(61, quarter.missingCount());
        assertEquals(129, halfYear.expectedCount(), "H1 2026 weekday count");
        assertEquals(261, year.expectedCount(), "2026 has 261 weekdays");
    }

    @Test
    void quarterlyResultDiffersFromAverageOfMonthlyAverages() {
        List<AggregateInput> jan = List.of(
                input(daily("2026-01-05", "6.79040000", 8, 4, ValidationStatus.VERIFIED)),
                input(daily("2026-01-06", "6.80000000", 8, 4, ValidationStatus.VERIFIED)),
                input(daily("2026-01-07", "7.10000000", 8, 4, ValidationStatus.VERIFIED)));
        List<AggregateInput> feb = List.of(
                input(daily("2026-02-02", "6.90000000", 8, 4, ValidationStatus.VERIFIED)));
        AggregateRecordV1 janRow = AggregateCalculator.calculate(
                AggregateGrain.MONTH, JAN, JAN_END, jan).get(0);
        AggregateRecordV1 febRow = AggregateCalculator.calculate(
                AggregateGrain.MONTH, FEB, FEB_END, feb).get(0);
        BigDecimal wrongAvgOfMonthlyAverages = new BigDecimal(janRow.avg())
                .add(new BigDecimal(febRow.avg()))
                .divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP);

        List<AggregateInput> all = new ArrayList<>(jan);
        all.addAll(feb);
        AggregateRecordV1 quarter = AggregateCalculator.calculate(
                AggregateGrain.QUARTER, Q1, Q1_END, all).get(0);

        assertNotEquals(wrongAvgOfMonthlyAverages.toPlainString(), quarter.avg(),
                "quarter must recompute directly from daily avgs, never from monthly averages");
    }

    @Test
    void crossMonthDailyRowsProduceSeparateMonthRowsAndOneQuarterRow() {
        List<AggregateInput> inputs = List.of(
                input(daily("2026-01-05", "6.79040000", 8, 4, ValidationStatus.VERIFIED)),
                input(daily("2026-01-06", "6.80000000", 8, 4, ValidationStatus.VERIFIED)),
                input(daily("2026-02-02", "6.90000000", 8, 4, ValidationStatus.VERIFIED)));

        List<AggregateRecordV1> months = AggregateCalculator.calculate(
                AggregateGrain.MONTH, JAN, JAN_END, inputs);
        List<AggregateRecordV1> febMonths = AggregateCalculator.calculate(
                AggregateGrain.MONTH, FEB, FEB_END, inputs);
        List<AggregateRecordV1> quarters = AggregateCalculator.calculate(
                AggregateGrain.QUARTER, Q1, Q1_END, inputs);

        assertEquals(1, months.size());
        assertEquals(2, months.get(0).validCount());
        assertEquals(1, febMonths.size());
        assertEquals(1, febMonths.get(0).validCount());
        assertEquals(20, febMonths.get(0).expectedCount(), "2026-02 has 20 weekdays");
        assertEquals(1, quarters.size());
        assertEquals(3, quarters.get(0).validCount());
    }

    @Test
    void missingDaysDoNotContributeWeight() {
        List<AggregateInput> inputs = List.of(
                input(daily("2026-01-05", "6.79040000", 8, 4, ValidationStatus.VERIFIED)));

        AggregateRecordV1 row = AggregateCalculator.calculate(
                AggregateGrain.MONTH, JAN, JAN_END, inputs).get(0);

        assertEquals("6.79040000", row.sum(), "only the present daily avg contributes");
        assertEquals(1, row.validCount());
        assertEquals(21, row.missingCount());
    }

    @Test
    void differentValidationConclusionsAndContextsSplitIntoRows() {
        List<AggregateInput> inputs = List.of(
                input(daily("2026-01-05", "6.79040000", 8, 4, ValidationStatus.VERIFIED)),
                input(daily("2026-01-06", "6.80000000", 8, 4, ValidationStatus.VERIFIED_WITH_NOTICE)),
                input(daily("2026-01-07", "7.100000000000", 12, 9, ValidationStatus.VERIFIED)));

        List<AggregateRecordV1> rows = AggregateCalculator.calculate(
                AggregateGrain.MONTH, JAN, JAN_END, inputs);

        assertEquals(3, rows.size(), "different validation conclusions or calculation contexts must split");
        assertTrue(rows.stream().anyMatch(row -> row.validationStatus() == ValidationStatus.VERIFIED
                && row.calculationScale() == 8 && row.validCount() == 1));
        assertTrue(rows.stream().anyMatch(row -> row.validationStatus() == ValidationStatus.VERIFIED_WITH_NOTICE
                && row.validCount() == 1));
        assertTrue(rows.stream().anyMatch(row -> row.calculationScale() == 12 && row.validCount() == 1));
    }

    @Test
    void displayScaleIsNeverReadAsInput() {
        List<AggregateInput> inputs = List.of(
                input(daily("2026-01-05", "6.79040000", 8, 2, ValidationStatus.VERIFIED)),
                input(daily("2026-01-06", "6.80000000", 8, 2, ValidationStatus.VERIFIED)));

        AggregateRecordV1 row = AggregateCalculator.calculate(
                AggregateGrain.MONTH, JAN, JAN_END, inputs).get(0);

        assertEquals("13.59040000", row.sum(),
                "sum must use the full daily avg strings, never a displayScale-truncated value");
        assertEquals("6.79520000", row.avg());
        assertEquals("6.79040000", row.min());
        assertEquals("6.80000000", row.max());
    }

    @Test
    void expectedCountWeekdayBoundariesAcrossMonths() {
        assertEquals(22, ExpectedBusinessDayCounter.expectedCount(
                WEEKDAY, java.time.LocalDate.parse("2026-01-01"), java.time.LocalDate.parse("2026-01-31")));
        assertEquals(20, ExpectedBusinessDayCounter.expectedCount(
                WEEKDAY, java.time.LocalDate.parse("2026-02-01"), java.time.LocalDate.parse("2026-02-28")));
        assertEquals(22, ExpectedBusinessDayCounter.expectedCount(
                WEEKDAY, java.time.LocalDate.parse("2026-03-01"), java.time.LocalDate.parse("2026-03-31")));
        assertEquals(64, ExpectedBusinessDayCounter.expectedCount(
                WEEKDAY, java.time.LocalDate.parse("2026-01-01"), java.time.LocalDate.parse("2026-03-31")));
    }

    @Test
    void goldenCalendarExpectedCountFixture() {
        assertEquals(2, ExpectedBusinessDayCounter.expectedCount(
                GOLDEN, java.time.LocalDate.parse("2026-01-01"), java.time.LocalDate.parse("2026-01-31")),
                "golden-calendar-v1 counts the 10th and 20th of each month");
        assertEquals(4, ExpectedBusinessDayCounter.expectedCount(
                GOLDEN, java.time.LocalDate.parse("2026-01-01"), java.time.LocalDate.parse("2026-02-28")));
    }

    @Test
    void inputOrderDoesNotAffectResult() {
        List<AggregateInput> forward = List.of(
                input(daily("2026-01-05", "6.79040000", 8, 4, ValidationStatus.VERIFIED)),
                input(daily("2026-01-06", "6.80000000", 8, 4, ValidationStatus.VERIFIED)),
                input(daily("2026-01-07", "7.10000000", 8, 4, ValidationStatus.VERIFIED)));
        List<AggregateInput> reversed = new ArrayList<>(forward);
        Collections.reverse(reversed);

        assertEquals(AggregateCalculator.calculate(AggregateGrain.MONTH, JAN, JAN_END, forward),
                AggregateCalculator.calculate(AggregateGrain.MONTH, JAN, JAN_END, reversed),
                "input order must never change the aggregate result");
    }

    private static AggregateInput input(DailyRecordV1 row) {
        return new AggregateInput(row, "processed/daily/FX.USD.CNY.PBOC_MID/2026-01.csv", DAILY_FILE_SHA);
    }

    private static DailyRecordV1 daily(
            String businessDate,
            String avg,
            int calculationScale,
            int displayScale,
            ValidationStatus status
    ) {
        return daily(businessDate, avg, calculationScale, displayScale, status, "2026-01-06T09:00+08:00");
    }

    private static DailyRecordV1 daily(
            String businessDate,
            String avg,
            int calculationScale,
            int displayScale,
            ValidationStatus status,
            String updatedAt
    ) {
        List<DailyInputRefV1> refs = List.of(new DailyInputRefV1(
                "run-" + businessDate,
                "raw/formal/official_web/FX.USD.CNY.PBOC_MID/2026/01/" + businessDate + ".json",
                4));
        return new DailyRecordV1(
                "1.0",
                businessDate,
                ITEM,
                ProviderType.OFFICIAL_WEB,
                SOURCE,
                AccessMethod.PUBLIC_OFFICIAL_HTML,
                ProcessingStage.PUBLISHED,
                status,
                "pboc-basic-validation-v1",
                List.of(1),
                "arithmetic-mean-v1",
                calculationScale,
                displayScale,
                RoundingMode.HALF_UP,
                WEEKDAY,
                avg,
                1,
                avg,
                1,
                0,
                true,
                "CNY",
                "CNY/1 USD",
                refs,
                updatedAt == null ? null : OffsetDateTime.parse(updatedAt));
    }
}
