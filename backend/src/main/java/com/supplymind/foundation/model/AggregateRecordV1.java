package com.supplymind.foundation.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Frozen aggregate CSV row contract. D1-T03 provides codec/schema only, not aggregation logic. */
public record AggregateRecordV1(
        String schemaVersion,
        AggregateGrain grain,
        String periodStart,
        String periodEnd,
        String itemId,
        ProviderType providerType,
        String actualSourceName,
        AccessMethod accessMethod,
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
        String min,
        String max,
        int expectedCount,
        int missingCount,
        boolean complete,
        QualityStatus qualityStatus,
        String currency,
        String unit,
        String sourceFingerprint,
        List<AggregateInputRefV1> inputRefs,
        OffsetDateTime calculatedAt
) {
    public static final Comparator<AggregateRecordV1> ORDER = Comparator
            .comparing((AggregateRecordV1 record) -> record.grain().wireValue())
            .thenComparing(AggregateRecordV1::periodStart)
            .thenComparing(AggregateRecordV1::periodEnd)
            .thenComparing(AggregateRecordV1::itemId)
            .thenComparing(record -> record.providerType().wireValue())
            .thenComparing(AggregateRecordV1::actualSourceName)
            .thenComparing(record -> record.accessMethod().wireValue())
            .thenComparing(record -> record.validationStatus().wireValue())
            .thenComparing(AggregateRecordV1::validationVersion)
            .thenComparing(AggregateRecordV1::calculationVersion)
            .thenComparingInt(AggregateRecordV1::calculationScale)
            .thenComparingInt(AggregateRecordV1::displayScale)
            .thenComparing(record -> record.roundingMode().name())
            .thenComparing(AggregateRecordV1::calendarVersion)
            .thenComparing(AggregateRecordV1::currency)
            .thenComparing(AggregateRecordV1::unit);

    public AggregateRecordV1 {
        ModelRules.schemaVersion(schemaVersion);
        ModelRules.required(grain, "grain");
        ModelRules.isoDateText(periodStart, "periodStart");
        ModelRules.isoDateText(periodEnd, "periodEnd");
        if (periodStart.compareTo(periodEnd) > 0) {
            throw new SchemaValidationException("periodStart must not be after periodEnd");
        }
        ModelRules.id(itemId, "itemId");
        ModelRules.required(providerType, "providerType");
        ModelRules.nonBlank(actualSourceName, "actualSourceName");
        ModelRules.required(accessMethod, "accessMethod");
        ModelRules.providerPair(providerType, accessMethod);
        if (validationStatus == null || !validationStatus.isPublishEligible()) {
            throw new SchemaValidationException("aggregate validationStatus must be VERIFIED or VERIFIED_WITH_NOTICE");
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
        min = DecimalText.canonicalAtScale(min, calculationScale, "min");
        max = DecimalText.canonicalAtScale(max, calculationScale, "max");
        if (new BigDecimal(min).compareTo(new BigDecimal(max)) > 0) {
            throw new SchemaValidationException("aggregate min must not exceed max");
        }
        ModelRules.nonNegative(expectedCount, "expectedCount");
        ModelRules.nonNegative(missingCount, "missingCount");
        if (missingCount != Math.max(expectedCount - validCount, 0)) {
            throw new SchemaValidationException("aggregate missingCount must equal max(expectedCount-validCount,0)");
        }
        if (complete != (validCount >= expectedCount)) {
            throw new SchemaValidationException("aggregate complete must equal validCount>=expectedCount");
        }
        ModelRules.required(qualityStatus, "qualityStatus");
        if (qualityStatus != (complete ? QualityStatus.COMPLETE : QualityStatus.INCOMPLETE)) {
            throw new SchemaValidationException("aggregate qualityStatus must be derived from complete");
        }
        ModelRules.nonBlank(currency, "currency");
        ModelRules.nonBlank(unit, "unit");
        ModelRules.sha256(sourceFingerprint, "sourceFingerprint");
        String expectedFingerprint = CanonicalJsonV1.sha256LowerHex(
                CanonicalJsonV1.sourceIdentity(providerType, actualSourceName, accessMethod));
        if (!expectedFingerprint.equals(sourceFingerprint)) {
            throw new SchemaValidationException("sourceFingerprint must hash the frozen source identity JSON vector");
        }
        inputRefs = canonicalInputRefs(inputRefs);
        if (inputRefs.size() != validCount) {
            throw new SchemaValidationException("aggregate inputRefs must cover exactly validCount daily inputs");
        }
        ModelRules.dateTime(calculatedAt, "calculatedAt");
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
            throw new SchemaValidationException("configVersions must not be empty for an aggregate row");
        }
        List<Integer> canonical = new ArrayList<>(unique);
        canonical.sort(Integer::compareTo);
        return List.copyOf(canonical);
    }

    private static List<AggregateInputRefV1> canonicalInputRefs(List<AggregateInputRefV1> inputRefs) {
        List<AggregateInputRefV1> canonical = new ArrayList<>(ModelRules.immutableList(inputRefs, "inputRefs"));
        canonical.sort(AggregateInputRefV1.ORDER);
        for (int index = 1; index < canonical.size(); index++) {
            if (canonical.get(index - 1).equals(canonical.get(index))) {
                throw new SchemaValidationException("aggregate inputRefs must not contain duplicates");
            }
        }
        return List.copyOf(canonical);
    }
}
