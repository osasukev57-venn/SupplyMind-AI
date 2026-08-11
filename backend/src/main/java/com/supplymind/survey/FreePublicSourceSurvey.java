package com.supplymind.survey;

import com.supplymind.foundation.model.SchemaValidationException;
import com.supplymind.provider.ProviderModelChecks;

import java.time.OffsetDateTime;

/**
 * D3-T03 one real free-public source investigation fact record: what was accessed, when, what
 * was observable through normal public access, and whether it matches the ADC12/AZ91D data
 * semantics (grade, unit, currency, market, business date, source identity). Only observed
 * facts are recorded; a NOT_APPROVED verdict with a reason is a valid investigation outcome.
 */
public record FreePublicSourceSurvey(
        String schemaVersion,
        String sourceKey,
        String actualSourceName,
        String sourceUrl,
        OffsetDateTime investigatedAt,
        Integer httpStatus,
        String accessFailure,
        Integer publicPageBytes,
        boolean requiresLoginOrMembership,
        boolean publicStructuredPriceInterface,
        boolean adc12Match,
        boolean az91dMatch,
        SourceVerdict verdict,
        String reason
) {
    public FreePublicSourceSurvey {
        ProviderModelChecks.schemaVersion(schemaVersion);
        ProviderModelChecks.identifier(sourceKey, "sourceKey");
        ProviderModelChecks.nonBlank(actualSourceName, "actualSourceName");
        ProviderModelChecks.httpUrl(sourceUrl, "sourceUrl");
        if (investigatedAt == null) {
            throw new SchemaValidationException("investigatedAt is required");
        }
        ProviderModelChecks.required(verdict, "verdict");
        ProviderModelChecks.nonBlank(reason, "reason");
    }

    public static FreePublicSourceSurvey of(
            String sourceKey,
            String actualSourceName,
            String sourceUrl,
            OffsetDateTime investigatedAt,
            Integer httpStatus,
            String accessFailure,
            Integer publicPageBytes,
            boolean requiresLoginOrMembership,
            boolean publicStructuredPriceInterface,
            boolean adc12Match,
            boolean az91dMatch,
            SourceVerdict verdict,
            String reason
    ) {
        return new FreePublicSourceSurvey(
                "1.0", sourceKey, actualSourceName, sourceUrl, investigatedAt, httpStatus,
                accessFailure, publicPageBytes, requiresLoginOrMembership,
                publicStructuredPriceInterface, adc12Match, az91dMatch, verdict, reason);
    }
}
