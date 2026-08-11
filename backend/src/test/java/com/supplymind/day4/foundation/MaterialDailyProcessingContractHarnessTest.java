package com.supplymind.day4.foundation;

import org.junit.jupiter.api.Test;

import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * D4-T03 grouping and precision oracle for material inputs.  It models only frozen grouping keys
 * and arithmetic expectations; no material daily file is produced or declared accepted.
 */
class MaterialDailyProcessingContractHarnessTest {

    private static final CalculationContext CONTEXT = new CalculationContext(
            "arithmetic-mean-v1", 12, 9, "HALF_UP", "golden-calendar-v1");

    @Test
    void crossSpecSourceAndCalculationContextAreForbiddenFromTheSameDailyGroup() {
        List<MaterialDailyInput> inputs = List.of(
                input("ADC12", "manual-source-a", CONTEXT, "100.1", true),
                input("AZ91D", "manual-source-a", CONTEXT, "100.2", true),
                input("ADC12", "manual-source-b", CONTEXT, "100.3", true),
                input("ADC12", "manual-source-a", new CalculationContext(
                        "arithmetic-mean-v2", 12, 9, "HALF_UP", "golden-calendar-v1"), "100.4", true));

        Map<DailyGroupKey, List<MaterialDailyInput>> groups = inputs.stream()
                .collect(Collectors.groupingBy(MaterialDailyInput::groupKey));

        assertEquals(4, groups.size(), "cross-spec, cross-source, and cross-context inputs must split");
        assertFalse(groups.values().stream().anyMatch(group -> group.size() > 1));
    }

    @Test
    void missingIsNotZeroAndNoPrematureDisplayRoundingCanEnterTheDailySummary() {
        List<MaterialDailyInput> inputs = List.of(
                input("ADC12", "manual-source-a", CONTEXT, "100.1", true),
                input("ADC12", "manual-source-a", CONTEXT, "100.2", true),
                input("ADC12", "manual-source-a", CONTEXT, "0", false));
        List<String> validValues = inputs.stream().filter(MaterialDailyInput::publishedAndVerified)
                .map(MaterialDailyInput::value).toList();

        GoldenArithmeticHarness.Summary summary = GoldenArithmeticHarness.summarize(
                validValues, CONTEXT.calculationScale(), RoundingMode.valueOf(CONTEXT.roundingMode()));

        assertEquals("200.3", summary.sumText(), "missing input is excluded, never added as zero");
        assertEquals(2, summary.validCount());
        assertEquals("100.150000000000", summary.averageText());

        GoldenArithmeticHarness.Summary precision = GoldenArithmeticHarness.summarize(
                List.of("7.010000010", "7.020000020", "7.030000030"),
                CONTEXT.calculationScale(), RoundingMode.valueOf(CONTEXT.roundingMode()));
        assertEquals("21.060000060", precision.sumText());
        assertEquals("7.020000020000", precision.averageText());
        assertEquals("7.020000020", precision.average().setScale(CONTEXT.displayScale(), RoundingMode.HALF_UP).toPlainString(),
                "display rounding is a distinct boundary value and must not replace the persisted calculation result");
    }

    private static MaterialDailyInput input(
            String canonicalSpecCode,
            String actualSourceName,
            CalculationContext context,
            String value,
            boolean publishedAndVerified
    ) {
        return new MaterialDailyInput("MAT.ANY", canonicalSpecCode, "manual", actualSourceName, "manual",
                "material-basic-validation-v2", context, value, publishedAndVerified);
    }

    private record MaterialDailyInput(
            String itemId,
            String canonicalSpecCode,
            String providerType,
            String actualSourceName,
            String accessMethod,
            String validationVersion,
            CalculationContext context,
            String value,
            boolean publishedAndVerified
    ) {
        DailyGroupKey groupKey() {
            return new DailyGroupKey(itemId, canonicalSpecCode, providerType, actualSourceName,
                    accessMethod, validationVersion, context);
        }
    }

    private record DailyGroupKey(
            String itemId,
            String canonicalSpecCode,
            String providerType,
            String actualSourceName,
            String accessMethod,
            String validationVersion,
            CalculationContext context
    ) {
    }

    private record CalculationContext(
            String calculationVersion,
            int calculationScale,
            int displayScale,
            String roundingMode,
            String calendarVersion
    ) {
    }
}
