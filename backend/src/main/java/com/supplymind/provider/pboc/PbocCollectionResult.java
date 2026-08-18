package com.supplymind.provider.pboc;

import com.supplymind.foundation.model.LifecycleTimelineV1;
import com.supplymind.foundation.model.RawReceiptV1;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Successful D1-T04/D8-M1 outcome; no Candidate, validation, publishing, daily, or aggregate
 * state is present. usdRaw/eurRaw are convenience accessors preserved for the frozen
 * dual-currency contract; raws/timelines carry EVERY configured PBOC target resolved in this
 * run (USD/EUR and any dynamically configured target such as GBP).
 */
public record PbocCollectionResult(String acquisitionId, URI listUrl, URI detailUrl, String businessDate,
                                   String payloadSha256, RawReceiptV1 usdRaw, RawReceiptV1 eurRaw,
                                   LifecycleTimelineV1 usdTimeline, LifecycleTimelineV1 eurTimeline,
                                   List<RawReceiptV1> raws, List<LifecycleTimelineV1> timelines,
                                   Map<String, String> rejectedTargets) {
    public PbocCollectionResult {
        Objects.requireNonNull(acquisitionId, "acquisitionId");
        Objects.requireNonNull(listUrl, "listUrl");
        Objects.requireNonNull(detailUrl, "detailUrl");
        Objects.requireNonNull(businessDate, "businessDate");
        Objects.requireNonNull(payloadSha256, "payloadSha256");
        // usdRaw/eurRaw may be null for a request-driven collect() of a single dynamic target
        // (e.g. only GBP); the legacy dual-currency entry enforces their presence in the caller.
        raws = raws == null ? List.of() : List.copyOf(raws);
        timelines = timelines == null ? List.of() : List.copyOf(timelines);
        rejectedTargets = rejectedTargets == null ? Map.of() : Map.copyOf(rejectedTargets);
    }

    /** D1-T04 compatibility constructor (dual-currency only). */
    public PbocCollectionResult(String acquisitionId, URI listUrl, URI detailUrl, String businessDate,
                                String payloadSha256, RawReceiptV1 usdRaw, RawReceiptV1 eurRaw,
                                LifecycleTimelineV1 usdTimeline, LifecycleTimelineV1 eurTimeline) {
        this(acquisitionId, listUrl, detailUrl, businessDate, payloadSha256, usdRaw, eurRaw,
                usdTimeline, eurTimeline, List.of(usdRaw, eurRaw), List.of(usdTimeline, eurTimeline), Map.of());
    }
}
