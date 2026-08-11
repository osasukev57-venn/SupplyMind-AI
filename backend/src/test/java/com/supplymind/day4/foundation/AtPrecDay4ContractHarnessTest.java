package com.supplymind.day4.foundation;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * AT-PREC-001..003 independent arithmetic oracle.  It uses JDK BigDecimal and fixed literals
 * from GD-01/GD-02 only; no production daily or aggregate implementation is invoked here.
 */
class AtPrecDay4ContractHarnessTest {

    @Test
    void gd02LargeAndTinyValuesRoundTripAsPlainStringsWithTrailingZero() {
        BigDecimal large = GoldenArithmeticHarness.decimal("999999999999.123456789");
        BigDecimal tiny = GoldenArithmeticHarness.decimal("0.000000001");
        BigDecimal sum = large.add(tiny);

        assertEquals("999999999999.123456790", sum.toPlainString());
        assertEquals("0.000000001", tiny.toPlainString());
        assertFalse(sum.toPlainString().contains("E"));
        assertFalse(sum.toPlainString().contains("e"));
        assertEquals("100.0", GoldenArithmeticHarness.decimal("100.0").toPlainString(),
                "lexical trailing scale is retained by the decimal contract");
    }

    @Test
    void gd02NonTerminatingAveragePreservesExactSumAndRoundsOnlyAtDivision() {
        GoldenArithmeticHarness.Summary summary = GoldenArithmeticHarness.summarize(
                List.of("100.1", "100.2", "100.2"), 8, RoundingMode.HALF_UP);

        assertEquals("300.5", summary.sumText());
        assertEquals(3, summary.validCount());
        assertEquals("100.16666667", summary.averageText());
    }

    @Test
    void missingIsNeverTreatedAsZeroAndScientificNotationIsRejectedAtTheFixtureBoundary() {
        assertThrows(IllegalArgumentException.class,
                () -> GoldenArithmeticHarness.summarize(List.of(), 12, RoundingMode.HALF_UP));
        assertThrows(IllegalArgumentException.class, () -> GoldenArithmeticHarness.decimal("1E-9"));

        GoldenArithmeticHarness.Summary onlyPublishedDaily = GoldenArithmeticHarness.summarize(
                List.of("7.009000010", "7.011000010"), 12, RoundingMode.HALF_UP);
        assertEquals("14.020000020", onlyPublishedDaily.sumText());
        assertEquals(2, onlyPublishedDaily.validCount());
        assertEquals("7.010000010000", onlyPublishedDaily.averageText());
    }

    @Test
    void gd01QuarterUsesCalculationPrecisionNotDisplayRoundedMonthlyAverages() {
        GoldenArithmeticHarness.Summary direct = GoldenArithmeticHarness.summarize(
                List.of("7.010000010", "7.020000020", "7.030000030"), 12, RoundingMode.HALF_UP);
        BigDecimal wrongDisplayRounded = new BigDecimal("7.0100").add(new BigDecimal("7.0200"))
                .add(new BigDecimal("7.0300")).divide(BigDecimal.valueOf(3), 12, RoundingMode.HALF_UP);

        assertEquals("7.020000020000", direct.averageText());
        assertNotEquals(direct.averageText(), wrongDisplayRounded.toPlainString(),
                "AT-PREC-003: higher aggregates must not use display-rounded monthly values");
    }
}
