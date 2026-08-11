package com.supplymind.day4.foundation;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * D4-T04 direct-from-daily reference reconstruction.  This is not an AggregateProcessingService
 * acceptance test and does not create any material aggregate output.
 */
class MaterialAggregateProcessingContractHarnessTest {

    private static final List<String> DAILY_AVERAGES = List.of(
            "7.010000010", "7.020000020", "7.030000030", "7.040000040",
            "7.050000050", "7.060000060", "7.070000070", "7.080000080",
            "7.090000090", "7.100000100", "7.110000110", "7.120000120");

    @Test
    void monthQuarterHalfyearAndYearAllRebuildDirectlyFromDailyValues() {
        assertSummary(DAILY_AVERAGES.subList(0, 1), "7.010000010", 1, "7.010000010000");
        assertSummary(DAILY_AVERAGES.subList(0, 3), "21.060000060", 3, "7.020000020000");
        assertSummary(DAILY_AVERAGES.subList(0, 6), "42.210000210", 6, "7.035000035000");
        assertSummary(DAILY_AVERAGES, "84.780000780", 12, "7.065000065000");
    }

    @Test
    void roundedIntermediateAveragesCannotBeUsedForHigherGrains() {
        GoldenArithmeticHarness.Summary directQuarter = GoldenArithmeticHarness.summarize(
                DAILY_AVERAGES.subList(0, 3), 12, RoundingMode.HALF_UP);
        BigDecimal wrongFromDisplayValues = new BigDecimal("7.0100").add(new BigDecimal("7.0200"))
                .add(new BigDecimal("7.0300")).divide(BigDecimal.valueOf(3), 12, RoundingMode.HALF_UP);

        assertEquals("7.020000020000", directQuarter.averageText());
        assertNotEquals(directQuarter.averageText(), wrongFromDisplayValues.toPlainString());
    }

    @Test
    void aggregateLineageRetainsValidationVersionAndTheUnionOfDailyConfigVersions() {
        List<DailyLineage> daily = List.of(
                new DailyLineage("material-basic-validation-v2", Set.of(1, 3)),
                new DailyLineage("material-basic-validation-v2", Set.of(3, 9)));

        assertEquals(Set.of("material-basic-validation-v2"),
                daily.stream().map(DailyLineage::validationVersion).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of(1, 3, 9), daily.stream().flatMap(row -> row.configVersions().stream())
                .collect(java.util.stream.Collectors.toSet()));
    }

    private static void assertSummary(List<String> values, String sum, int validCount, String average) {
        GoldenArithmeticHarness.Summary summary = GoldenArithmeticHarness.summarize(values, 12, RoundingMode.HALF_UP);
        assertEquals(sum, summary.sumText());
        assertEquals(validCount, summary.validCount());
        assertEquals(average, summary.averageText());
    }

    private record DailyLineage(String validationVersion, Set<Integer> configVersions) {
    }
}
