package com.supplymind.processing;

import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.ValidationStatus;

import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Objects;

/** One gate-eligible PUBLISHED+VERIFIED-class input for the daily calculation. */
public record DailyInput(
        String itemId,
        String businessDate,
        String value,
        String currency,
        String unit,
        ProviderType providerType,
        String actualSourceName,
        AccessMethod accessMethod,
        ValidationStatus validationStatus,
        String validationVersion,
        int configVersion,
        String runId,
        String rawRef,
        int recordVersion,
        String calculationVersion,
        int calculationScale,
        int displayScale,
        RoundingMode roundingMode,
        String calendarVersion,
        OffsetDateTime publishedAt
) {
    public DailyInput {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(businessDate, "businessDate");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(providerType, "providerType");
        Objects.requireNonNull(actualSourceName, "actualSourceName");
        Objects.requireNonNull(accessMethod, "accessMethod");
        Objects.requireNonNull(validationStatus, "validationStatus");
        Objects.requireNonNull(validationVersion, "validationVersion");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(rawRef, "rawRef");
        Objects.requireNonNull(calculationVersion, "calculationVersion");
        Objects.requireNonNull(roundingMode, "roundingMode");
        Objects.requireNonNull(calendarVersion, "calendarVersion");
        Objects.requireNonNull(publishedAt, "publishedAt");
    }
}
