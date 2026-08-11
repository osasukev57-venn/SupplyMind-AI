package com.supplymind.day4.foundation;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * AT-AGG-001..003 fixture-side reconstruction oracle.  It establishes expected inputs for future
 * D4-T03/T04 tests; it does not call or certify unimplemented material processing services.
 */
class AtAggDay4ContractHarnessTest {

    private static final List<String> EUR_MONTHLY_DAILY = List.of(
            "7.010000010", "7.020000020", "7.030000030", "7.040000040",
            "7.050000050", "7.060000060", "7.070000070", "7.080000080",
            "7.090000090", "7.100000100", "7.110000110", "7.120000120");

    @Test
    void gd01EurQuarterHalfyearAndYearRebuildDirectlyFromDailyValues() {
        assertSummary(EUR_MONTHLY_DAILY.subList(0, 3), "21.060000060", 3, "7.020000020000");
        assertSummary(EUR_MONTHLY_DAILY.subList(3, 6), "21.150000150", 3, "7.050000050000");
        assertSummary(EUR_MONTHLY_DAILY.subList(6, 9), "21.240000240", 3, "7.080000080000");
        assertSummary(EUR_MONTHLY_DAILY.subList(9, 12), "21.330000330", 3, "7.110000110000");
        assertSummary(EUR_MONTHLY_DAILY.subList(0, 6), "42.210000210", 6, "7.035000035000");
        assertSummary(EUR_MONTHLY_DAILY.subList(6, 12), "42.570000570", 6, "7.095000095000");
        assertSummary(EUR_MONTHLY_DAILY, "84.780000780", 12, "7.065000065000");
    }

    @Test
    void gd01Az91dExpectedValuesUseExactScaleAndAreDerivedFromTheSameDailyFoundation() {
        List<String> azDaily = EUR_MONTHLY_DAILY.stream()
                .map(value -> GoldenArithmeticHarness.decimal(value).multiply(BigDecimal.valueOf(1000))
                        .add(BigDecimal.valueOf(12000)).toPlainString())
                .toList();

        assertSummary(azDaily.subList(0, 3), "57060.000060000", 3, "19020.000020000000");
        assertSummary(azDaily.subList(3, 6), "57150.000150000", 3, "19050.000050000000");
        assertSummary(azDaily.subList(0, 6), "114210.000210000", 6, "19035.000035000000");
        assertSummary(azDaily, "228780.000780000", 12, "19065.000065000000");
    }

    @Test
    void shuffledDailyInputsHaveTheSameGoldenRebuildAndMissingDoesNotAddZeroWeight() {
        List<String> shuffled = new ArrayList<>(EUR_MONTHLY_DAILY);
        Collections.shuffle(shuffled, new java.util.Random(20260811L));
        GoldenArithmeticHarness.Summary direct = GoldenArithmeticHarness.summarize(
                EUR_MONTHLY_DAILY, 12, RoundingMode.HALF_UP);
        GoldenArithmeticHarness.Summary replay = GoldenArithmeticHarness.summarize(
                shuffled, 12, RoundingMode.HALF_UP);
        GoldenArithmeticHarness.Summary onlyPresent = GoldenArithmeticHarness.summarize(
                List.of("7.010000010", "7.020000020"), 12, RoundingMode.HALF_UP);

        assertEquals(direct, replay, "input order must not affect a direct daily rebuild");
        assertEquals(2, onlyPresent.validCount());
        assertEquals("7.015000015000", onlyPresent.averageText());
        assertNotEquals("4.676666676667", onlyPresent.averageText(),
                "missing daily data is not an implicit third zero value");
    }

    private static void assertSummary(List<String> values, String sum, int count, String average) {
        GoldenArithmeticHarness.Summary summary = GoldenArithmeticHarness.summarize(values, 12, RoundingMode.HALF_UP);
        assertEquals(sum, summary.sumText());
        assertEquals(count, summary.validCount());
        assertEquals(average, summary.averageText());
    }
}
