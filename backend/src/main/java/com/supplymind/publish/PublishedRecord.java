package com.supplymind.publish;

import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.LifecycleTimelineV1;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.RawReceiptV1;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Business read-model projection of one PUBLISHED+VERIFIED-class record.
 * It stays traceable to the PBOC raw (runId/rawRef/payloadSha256/fileSha256) and to the
 * lifecycle record (recordVersion/validationVersion/validatedAt/publishedAt/publishRef).
 * stale is a query-time derived field (DEC-051): the candidate business date is more than
 * 30 calendar days before the reference date in Asia/Shanghai (reusing the DEC-050
 * threshold with the reference date as the comparison base).
 */
public record PublishedRecord(
        String itemId,
        String businessDate,
        String value,
        String currency,
        String unit,
        ProviderType providerType,
        String actualSourceName,
        AccessMethod accessMethod,
        String validationVersion,
        OffsetDateTime validatedAt,
        OffsetDateTime publishedAt,
        String publishRef,
        String runId,
        String rawRef,
        int recordVersion,
        String rawPayloadSha256,
        String rawFileSha256,
        boolean stale
) {
    public PublishedRecord {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(businessDate, "businessDate");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(actualSourceName, "actualSourceName");
        Objects.requireNonNull(validationVersion, "validationVersion");
        Objects.requireNonNull(publishedAt, "publishedAt");
        Objects.requireNonNull(publishRef, "publishRef");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(rawRef, "rawRef");
        Objects.requireNonNull(rawPayloadSha256, "rawPayloadSha256");
        Objects.requireNonNull(rawFileSha256, "rawFileSha256");
    }

    public static PublishedRecord of(
            LifecycleTimelineV1 timeline,
            RawReceiptV1 raw,
            String rawFileSha256,
            java.time.LocalDate referenceDate
    ) {
        var snapshot = timeline.current();
        var candidate = snapshot.candidate();
        Objects.requireNonNull(candidate, "PUBLISHED snapshots must carry CandidateV1");
        Objects.requireNonNull(snapshot.publishRef(),
                "PUBLISHED snapshots must carry their frozen publishRef");
        return new PublishedRecord(
                candidate.itemId(),
                candidate.businessDate(),
                candidate.value(),
                candidate.currency(),
                candidate.unit(),
                candidate.providerType(),
                candidate.actualSourceName(),
                candidate.accessMethod(),
                snapshot.validationVersion(),
                snapshot.validatedAt(),
                snapshot.publishedAt(),
                snapshot.publishRef(),
                timeline.runId(),
                timeline.rawRef(),
                timeline.currentRecordVersion(),
                raw.payloadSha256(),
                rawFileSha256,
                java.time.LocalDate.parse(candidate.businessDate())
                        .isBefore(referenceDate.minusDays(30)));
    }
}
