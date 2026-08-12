package com.supplymind.foundation.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * D5-T01/F1 recoverable time/rotation state with separated semantics: `lastObservedTime` is a
 * diagnostic fact (the raw wall clock as seen, which MAY go backward on rollback) while
 * `effectiveHighWaterTime` / `effectiveBusinessDate` / `lastCompletedPeriod` are the monotonic
 * business high-water marks used for rotation decisions. A rollback never lowers the effective
 * marks, so a recovered clock cannot re-trigger an already completed boundary and can never
 * cause duplicate publish/daily/aggregate work.
 */
@JsonPropertyOrder({
        "schemaVersion", "stateVersion", "lastObservedTime", "effectiveHighWaterTime",
        "effectiveBusinessDate", "lastCompletedPeriod", "updatedAt"
})
public record TimeStateV1(
        String schemaVersion,
        int stateVersion,
        OffsetDateTime lastObservedTime,
        OffsetDateTime effectiveHighWaterTime,
        LocalDate effectiveBusinessDate,
        String lastCompletedPeriod,
        OffsetDateTime updatedAt
) {
    public TimeStateV1 {
        ModelRules.schemaVersion(schemaVersion);
        ModelRules.positive(stateVersion, "stateVersion");
        ModelRules.dateTime(lastObservedTime, "lastObservedTime");
        ModelRules.dateTime(effectiveHighWaterTime, "effectiveHighWaterTime");
        ModelRules.dateTime(updatedAt, "updatedAt");
        if (effectiveBusinessDate == null) {
            throw new SchemaValidationException("effectiveBusinessDate is required");
        }
        if (effectiveHighWaterTime.isBefore(lastObservedTime)) {
            throw new SchemaValidationException(
                    "effectiveHighWaterTime must never be below the last observed time");
        }
        if (lastCompletedPeriod != null && !lastCompletedPeriod.matches("\\d{4}-\\d{2}")) {
            throw new SchemaValidationException("lastCompletedPeriod must be YYYY-MM: " + lastCompletedPeriod);
        }
        if (!updatedAt.isAfter(lastObservedTime) && !updatedAt.equals(lastObservedTime)) {
            throw new SchemaValidationException("updatedAt must not be before lastObservedTime");
        }
    }

    public static TimeStateV1 initial(OffsetDateTime observedAt, LocalDate businessDate) {
        return new TimeStateV1(SchemaV1.VERSION, 1, observedAt, observedAt, businessDate,
                java.time.YearMonth.from(businessDate).toString(), observedAt);
    }
}
