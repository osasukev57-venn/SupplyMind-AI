package com.supplymind.processing;

import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.DailyInputRefV1;
import com.supplymind.foundation.model.DailyRecordV1;
import com.supplymind.foundation.model.ProcessingStage;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.ValidationStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Frozen arithmetic-mean-v1 daily calculation (CALCULATION-RULES):
 * exact BigDecimal sum without rounding, avg = sum.divide(validCount, calculationScale, roundingMode),
 * expectedCount=1 per business day, missingCount=max(expectedCount-validCount,0), complete=validCount>=expectedCount.
 * Inputs are grouped by the frozen daily row key; different sources, units, currencies, validation
 * conclusions or calculation contexts never mix. The row updatedAt is DEC-052 deterministic:
 * max(publishedAt) of the group's valid PUBLISHED inputs compared by Instant and normalized to
 * Asia/Shanghai, never the processing clock.
 */
public final class DailyMeanCalculator {

    public static final ZoneOffset ASIA_SHANGHAI_OFFSET = ZoneOffset.ofHours(8);

    private DailyMeanCalculator() {
    }

    public static List<DailyRecordV1> calculate(List<DailyInput> inputs) {
        Objects.requireNonNull(inputs, "inputs");
        List<DailyInput> canonical = new ArrayList<>(inputs);
        canonical.sort(Comparator.comparing(DailyInput::runId));
        Map<DailyGroupKey, List<DailyInput>> groups = new LinkedHashMap<>();
        for (DailyInput input : canonical) {
            groups.computeIfAbsent(DailyGroupKey.of(input), ignored -> new ArrayList<>()).add(input);
        }
        List<DailyRecordV1> rows = new ArrayList<>();
        for (List<DailyInput> group : groups.values()) {
            rows.add(calculateRow(group));
        }
        return List.copyOf(rows);
    }

    /**
     * DEC-052: the deterministic row updatedAt is the latest official publish instant of the
     * group's valid PUBLISHED inputs, normalized to Asia/Shanghai. A missing publishedAt is
     * fail-closed and must never fall back to the processing clock.
     */
    public static OffsetDateTime deterministicUpdatedAt(List<DailyInput> inputs) {
        Objects.requireNonNull(inputs, "inputs");
        if (inputs.isEmpty()) {
            throw new IllegalArgumentException("daily updatedAt requires at least one valid input");
        }
        Instant latest = null;
        for (DailyInput input : inputs) {
            Instant published = input.publishedAt().toInstant();
            if (latest == null || published.isAfter(latest)) {
                latest = published;
            }
        }
        return OffsetDateTime.ofInstant(latest, java.time.ZoneId.from(ASIA_SHANGHAI_OFFSET));
    }

    private static DailyRecordV1 calculateRow(List<DailyInput> inputs) {
        DailyInput first = inputs.get(0);
        BigDecimal sum = BigDecimal.ZERO;
        for (DailyInput input : inputs) {
            sum = sum.add(new BigDecimal(input.value()));
        }
        int validCount = inputs.size();
        BigDecimal avg = sum.divide(BigDecimal.valueOf(validCount),
                first.calculationScale(), first.roundingMode());
        int expectedCount = 1;
        int missingCount = Math.max(expectedCount - validCount, 0);
        boolean complete = validCount >= expectedCount;
        List<Integer> configVersions = inputs.stream()
                .map(DailyInput::configVersion)
                .distinct()
                .sorted()
                .toList();
        List<DailyInputRefV1> inputRefs = inputs.stream()
                .map(input -> new DailyInputRefV1(input.runId(), input.rawRef(), input.recordVersion()))
                .sorted(DailyInputRefV1.ORDER)
                .toList();
        return new DailyRecordV1(
                "1.0",
                first.businessDate(),
                first.itemId(),
                first.providerType(),
                first.actualSourceName(),
                first.accessMethod(),
                ProcessingStage.PUBLISHED,
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
                expectedCount,
                missingCount,
                complete,
                first.currency(),
                first.unit(),
                inputRefs,
                deterministicUpdatedAt(inputs),
                first.canonicalSpecCode()
        );
    }

    private record DailyGroupKey(
            String itemId,
            String businessDate,
            ProviderType providerType,
            String actualSourceName,
            AccessMethod accessMethod,
            ValidationStatus validationStatus,
            String validationVersion,
            String canonicalSpecCode,
            String currency,
            String unit,
            String calculationVersion,
            int calculationScale,
            int displayScale,
            RoundingMode roundingMode,
            String calendarVersion
    ) {
        static DailyGroupKey of(DailyInput input) {
            return new DailyGroupKey(
                    input.itemId(),
                    input.businessDate(),
                    input.providerType(),
                    input.actualSourceName(),
                    input.accessMethod(),
                    input.validationStatus(),
                    input.validationVersion(),
                    input.canonicalSpecCode(),
                    input.currency(),
                    input.unit(),
                    input.calculationVersion(),
                    input.calculationScale(),
                    input.displayScale(),
                    input.roundingMode(),
                    input.calendarVersion());
        }
    }
}
