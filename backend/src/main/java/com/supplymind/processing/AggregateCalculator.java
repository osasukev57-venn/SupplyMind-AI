package com.supplymind.processing;

import com.supplymind.foundation.model.AggregateGrain;
import com.supplymind.foundation.model.AggregateInputRefV1;
import com.supplymind.foundation.model.AggregateRecordV1;
import com.supplymind.foundation.model.CanonicalJsonV1;
import com.supplymind.foundation.model.DailyRecordV1;
import com.supplymind.foundation.model.QualityStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Frozen multi-period aggregation (总计划 8.4.5 / CALCULATION-RULES / C34): every grain is
 * recomputed directly from the valid daily avg strings of the same calculation context;
 * aggregate sum is the exact sum of those daily avgs, validCount is the daily row count,
 * avg divides once at calculationScale/roundingMode, min/max are the exact min/max of the
 * participating daily avgs. Monthly averages and displayScale results are never read as input.
 * expectedCount comes from the frozen calendarVersion over periodStart..periodEnd.
 * calculatedAt is supplied by the caller (its deterministic semantics are a separate decision).
 */
public final class AggregateCalculator {

    private AggregateCalculator() {
    }

    public static List<AggregateRecordV1> calculate(
            AggregateGrain grain,
            String periodStart,
            String periodEnd,
            List<AggregateInput> inputs,
            OffsetDateTime calculatedAt
    ) {
        Objects.requireNonNull(grain, "grain");
        Objects.requireNonNull(periodStart, "periodStart");
        Objects.requireNonNull(periodEnd, "periodEnd");
        Objects.requireNonNull(inputs, "inputs");
        Objects.requireNonNull(calculatedAt, "calculatedAt");
        LocalDate start = LocalDate.parse(periodStart);
        LocalDate end = LocalDate.parse(periodEnd);
        List<AggregateInput> inPeriod = inputs.stream()
                .filter(input -> {
                    LocalDate businessDate = LocalDate.parse(input.dailyRow().businessDate());
                    return !businessDate.isBefore(start) && !businessDate.isAfter(end);
                })
                .toList();
        List<AggregateInput> canonical = new ArrayList<>(inPeriod);
        canonical.sort(Comparator.comparing((AggregateInput input) -> input.dailyRow().businessDate())
                .thenComparing(input -> input.dailyRow().inputRefs().isEmpty()
                        ? "" : input.dailyRow().inputRefs().get(0).runId()));
        Map<AggregateGroupKey, List<AggregateInput>> groups = new LinkedHashMap<>();
        for (AggregateInput input : canonical) {
            groups.computeIfAbsent(AggregateGroupKey.of(grain, periodStart, periodEnd, input.dailyRow()),
                    ignored -> new ArrayList<>()).add(input);
        }
        List<AggregateRecordV1> rows = new ArrayList<>();
        for (List<AggregateInput> group : groups.values()) {
            rows.add(calculateRow(grain, periodStart, periodEnd, group, calculatedAt));
        }
        return List.copyOf(rows);
    }

    private static AggregateRecordV1 calculateRow(
            AggregateGrain grain,
            String periodStart,
            String periodEnd,
            List<AggregateInput> inputs,
            OffsetDateTime calculatedAt
    ) {
        DailyRecordV1 first = inputs.get(0).dailyRow();
        BigDecimal sum = BigDecimal.ZERO;
        BigDecimal min = null;
        BigDecimal max = null;
        for (AggregateInput input : inputs) {
            BigDecimal dailyAvg = new BigDecimal(input.dailyRow().avg());
            sum = sum.add(dailyAvg);
            min = min == null ? dailyAvg : dailyAvg.min(min);
            max = max == null ? dailyAvg : dailyAvg.max(max);
        }
        int validCount = inputs.size();
        BigDecimal avg = sum.divide(BigDecimal.valueOf(validCount),
                first.calculationScale(), first.roundingMode());
        int expectedCount = ExpectedBusinessDayCounter.expectedCount(
                first.calendarVersion(), LocalDate.parse(periodStart), LocalDate.parse(periodEnd));
        int missingCount = Math.max(expectedCount - validCount, 0);
        boolean complete = validCount >= expectedCount;
        List<Integer> configVersions = inputs.stream()
                .map(input -> input.dailyRow().configVersions())
                .flatMap(List::stream)
                .distinct()
                .sorted()
                .toList();
        List<AggregateInputRefV1> inputRefs = inputs.stream()
                .map(input -> new AggregateInputRefV1(
                        input.dailyFileRef(),
                        input.dailyRow().businessDate(),
                        input.dailyRow().validationVersion(),
                        input.dailyFileSha256()))
                .sorted(AggregateInputRefV1.ORDER)
                .toList();
        String sourceFingerprint = CanonicalJsonV1.sha256LowerHex(
                CanonicalJsonV1.sourceIdentity(
                        first.providerType(), first.actualSourceName(), first.accessMethod()));
        return new AggregateRecordV1(
                "1.0",
                grain,
                periodStart,
                periodEnd,
                first.itemId(),
                first.providerType(),
                first.actualSourceName(),
                first.accessMethod(),
                first.validationStatus(),
                first.validationVersion(),
                configVersions,
                first.calculationVersion(),
                first.calculationScale(),
                first.displayScale(),
                first.roundingMode(),
                first.calendarVersion(),
                sum.toPlainString(),
                validCount,
                avg.toPlainString(),
                min.toPlainString(),
                max.toPlainString(),
                expectedCount,
                missingCount,
                complete,
                complete ? QualityStatus.COMPLETE : QualityStatus.INCOMPLETE,
                first.currency(),
                first.unit(),
                sourceFingerprint,
                inputRefs,
                calculatedAt
        );
    }

    private record AggregateGroupKey(
            AggregateGrain grain,
            String periodStart,
            String periodEnd,
            String itemId,
            com.supplymind.foundation.model.ProviderType providerType,
            String actualSourceName,
            com.supplymind.foundation.model.AccessMethod accessMethod,
            com.supplymind.foundation.model.ValidationStatus validationStatus,
            String validationVersion,
            String currency,
            String unit,
            String calculationVersion,
            int calculationScale,
            int displayScale,
            java.math.RoundingMode roundingMode,
            String calendarVersion
    ) {
        static AggregateGroupKey of(
                AggregateGrain grain, String periodStart, String periodEnd, DailyRecordV1 row) {
            return new AggregateGroupKey(
                    grain, periodStart, periodEnd,
                    row.itemId(), row.providerType(), row.actualSourceName(), row.accessMethod(),
                    row.validationStatus(), row.validationVersion(),
                    row.currency(), row.unit(),
                    row.calculationVersion(), row.calculationScale(), row.displayScale(),
                    row.roundingMode(), row.calendarVersion());
        }
    }
}
