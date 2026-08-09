package com.supplymind.provider.pboc;

import com.supplymind.foundation.model.LifecycleTimelineV1;
import com.supplymind.foundation.model.RawReceiptV1;

import java.net.URI;
import java.util.Objects;

/** Successful D1-T04 outcome; no Candidate, validation, publishing, daily, or aggregate state is present. */
public record PbocCollectionResult(String acquisitionId, URI listUrl, URI detailUrl, String businessDate,
                                   String payloadSha256, RawReceiptV1 usdRaw, RawReceiptV1 eurRaw,
                                   LifecycleTimelineV1 usdTimeline, LifecycleTimelineV1 eurTimeline) {
    public PbocCollectionResult {
        Objects.requireNonNull(acquisitionId, "acquisitionId");
        Objects.requireNonNull(listUrl, "listUrl");
        Objects.requireNonNull(detailUrl, "detailUrl");
        Objects.requireNonNull(businessDate, "businessDate");
        Objects.requireNonNull(payloadSha256, "payloadSha256");
        Objects.requireNonNull(usdRaw, "usdRaw");
        Objects.requireNonNull(eurRaw, "eurRaw");
        Objects.requireNonNull(usdTimeline, "usdTimeline");
        Objects.requireNonNull(eurTimeline, "eurTimeline");
    }
}