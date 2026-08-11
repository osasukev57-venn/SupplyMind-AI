package com.supplymind.foundation.model;

import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Frozen daily CSV row contract. D1-T03 only encodes it; D2 computes its values. */
public record DailyRecordV1(
        String schemaVersion,
        String businessDate,
        String itemId,
        ProviderType providerType,
        String actualSourceName,
        AccessMethod accessMethod,
        ProcessingStage processingStage,
        ValidationStatus validationStatus,
        String validationVersion,
        List<Integer> configVersions,
        String calculationVersion,
        int calculationScale,
        int displayScale,
        RoundingMode roundingMode,
        String calendarVersion,
        String sum,
        int validCount,
        String avg,
        int expectedCount,
        int missingCount,
        boolean complete,
        String currency,
        String unit,
        List<DailyInputRefV1> inputRefs,
        OffsetDateTime updatedAt,
        String canonicalSpecCode
) {
    public static final Comparator<DailyRecordV1> ORDER = Comparator
            .comparing(DailyRecordV1::businessDate)
            .thenComparing(DailyRecordV1::itemId)
            .thenComparing(record -> record.providerType().wireValue())
            .thenComparing(DailyRecordV1::actualSourceName)
            .thenComparing(record -> record.accessMethod().wireValue())
            .thenComparing(record -> record.validationStatus().wireValue())
            .thenComparing(DailyRecordV1::validationVersion)
            .thenComparing(record -> specOrEmpty(record.canonicalSpecCode()))
            .thenComparing(DailyRecordV1::calculationVersion)
            .thenComparingInt(DailyRecordV1::calculationScale)
            .thenComparingInt(DailyRecordV1::displayScale)
            .thenComparing(record -> record.roundingMode().name())
            .thenComparing(DailyRecordV1::calendarVersion)
            .thenComparing(DailyRecordV1::currency)
            .thenComparing(DailyRecordV1::unit);

    public DailyRecordV1 {
        ModelRules.schemaVersion(schemaVersion);
        ModelRules.isoDateText(businessDate, "businessDate");
        ModelRules.id(itemId, "itemId");
        ModelRules.required(providerType, "providerType");
        ModelRules.nonBlank(actualSourceName, "actualSourceName");
        ModelRules.required(accessMethod, "accessMethod");
        ModelRules.providerPair(providerType, accessMethod);
        if (processingStage != ProcessingStage.PUBLISHED) {
            throw new SchemaValidationException("daily processingStage must be PUBLISHED");
        }
        if (validationStatus == null || !validationStatus.isPublishEligible()) {
            throw new SchemaValidationException("daily validationStatus must be VERIFIED or VERIFIED_WITH_NOTICE");
        }
        ModelRules.nonBlank(validationVersion, "validationVersion");
        configVersions = canonicalConfigVersions(configVersions);
        ModelRules.nonBlank(calculationVersion, "calculationVersion");
        ModelRules.nonNegative(calculationScale, "calculationScale");
        ModelRules.nonNegative(displayScale, "displayScale");
        ModelRules.required(roundingMode, "roundingMode");
        ModelRules.nonBlank(calendarVersion, "calendarVersion");
        sum = DecimalText.canonical(sum, "sum");
        ModelRules.positive(validCount, "validCount");
        avg = DecimalText.canonicalAtScale(avg, calculationScale, "avg");
        ModelRules.nonNegative(expectedCount, "expectedCount");
        ModelRules.nonNegative(missingCount, "missingCount");
        if (missingCount != Math.max(expectedCount - validCount, 0)) {
            throw new SchemaValidationException("daily missingCount must equal max(expectedCount-validCount,0)");
        }
        if (complete != (validCount >= expectedCount)) {
            throw new SchemaValidationException("daily complete must equal validCount>=expectedCount");
        }
        ModelRules.nonBlank(currency, "currency");
        ModelRules.nonBlank(unit, "unit");
        inputRefs = canonicalInputRefs(inputRefs);
        if (inputRefs.size() != validCount) {
            throw new SchemaValidationException("daily inputRefs must cover exactly validCount PUBLISHED inputs");
        }
        ModelRules.dateTime(updatedAt, "updatedAt");
        if (canonicalSpecCode != null) {
            ModelRules.nonBlank(canonicalSpecCode, "canonicalSpecCode");
        }
    }

    private static String specOrEmpty(String canonicalSpecCode) {
        return canonicalSpecCode == null ? "" : canonicalSpecCode;
    }

    private static List<Integer> canonicalConfigVersions(List<Integer> configVersions) {
        ModelRules.required(configVersions, "configVersions");
        Set<Integer> unique = new HashSet<>();
        for (Integer version : configVersions) {
            if (version == null || version <= 0) {
                throw new SchemaValidationException("configVersions must contain only positive integers");
            }
            unique.add(version);
        }
        if (unique.isEmpty()) {
            throw new SchemaValidationException("configVersions must not be empty for a daily row");
        }
        List<Integer> canonical = new ArrayList<>(unique);
        canonical.sort(Integer::compareTo);
        return List.copyOf(canonical);
    }

    private static List<DailyInputRefV1> canonicalInputRefs(List<DailyInputRefV1> inputRefs) {
        List<DailyInputRefV1> canonical = new ArrayList<>(ModelRules.immutableList(inputRefs, "inputRefs"));
        canonical.sort(DailyInputRefV1.ORDER);
        for (int index = 1; index < canonical.size(); index++) {
            if (canonical.get(index - 1).equals(canonical.get(index))) {
                throw new SchemaValidationException("daily inputRefs must not contain duplicates");
            }
        }
        return List.copyOf(canonical);
    }
}
