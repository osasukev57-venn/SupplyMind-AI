package com.supplymind.foundation.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * D5-T01 recoverable time/rotation state. A single monotonic runtime record: the last observed
 * wall-clock instant (Asia/Shanghai), the last observed business date, the last completed
 * monthly period and a monotonic stateVersion. Business results are never derived from this
 * file (daily/aggregate stay deterministic from inputs); it only drives rotation detection
 * and recovery so that period rollover, forward jumps, rollbacks and restarts are observable
 * and idempotent.
 */
@JsonPropertyOrder({
        "schemaVersion", "stateVersion", "lastObservedTime", "lastObservedBusinessDate",
        "lastCompletedPeriod", "updatedAt"
})
public record TimeStateV1(
        String schemaVersion,
        int stateVersion,
        OffsetDateTime lastObservedTime,
        LocalDate lastObservedBusinessDate,
        String lastCompletedPeriod,
        OffsetDateTime updatedAt
) {
    public TimeStateV1 {
        ModelRules.schemaVersion(schemaVersion);
        ModelRules.positive(stateVersion, "stateVersion");
        ModelRules.dateTime(lastObservedTime, "lastObservedTime");
        ModelRules.dateTime(updatedAt, "updatedAt");
        if (lastObservedBusinessDate == null) {
            throw new SchemaValidationException("lastObservedBusinessDate is required");
        }
        if (lastCompletedPeriod != null && !lastCompletedPeriod.matches("\\d{4}-\\d{2}")) {
            throw new SchemaValidationException("lastCompletedPeriod must be YYYY-MM: " + lastCompletedPeriod);
        }
        if (!updatedAt.isAfter(lastObservedTime) && !updatedAt.equals(lastObservedTime)) {
            throw new SchemaValidationException("updatedAt must not be before lastObservedTime");
        }
    }

    public static TimeStateV1 initial(OffsetDateTime observedAt, LocalDate businessDate) {
        return new TimeStateV1(SchemaV1.VERSION, 1, observedAt, businessDate,
                java.time.YearMonth.from(businessDate).toString(), observedAt);
    }
}
