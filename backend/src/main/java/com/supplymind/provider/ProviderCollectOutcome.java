package com.supplymind.provider;

import com.supplymind.foundation.model.LifecycleTimelineV1;

import com.supplymind.foundation.model.RawReceiptV1;
import com.supplymind.foundation.model.SchemaValidationException;

import java.util.List;
import java.util.Map;

/**
 * D3-T01 unified RawRecord: the standardized result of one DataProvider collection, expressed
 * in the frozen per-item RawReceiptV1 / LifecycleTimelineV1 formats so upper layers never see
 * vendor DTOs or URLs. Unsupported requested targets are rejected explicitly with a reason;
 * they are never silently skipped or replaced by another source.
 */
public record ProviderCollectOutcome(
        String schemaVersion,
        String providerId,
        String acquisitionId,
        String businessDate,
        String payloadSha256,
        List<RawReceiptV1> raws,
        List<LifecycleTimelineV1> timelines,
        Map<String, String> rejectedItemIds
) {
    public ProviderCollectOutcome {
        ProviderModelChecks.schemaVersion(schemaVersion);
        ProviderModelChecks.identifier(providerId, "providerId");
        raws = List.copyOf(raws == null ? List.of() : raws);
        timelines = List.copyOf(timelines == null ? List.of() : timelines);
        rejectedItemIds = Map.copyOf(rejectedItemIds == null ? Map.of() : rejectedItemIds);
        for (RawReceiptV1 raw : raws) {
            if (raw == null) {
                throw new SchemaValidationException("ProviderCollectOutcome.raws must not contain null");
            }
            if (rejectedItemIds.containsKey(raw.itemId())) {
                throw new SchemaValidationException(
                        "An item cannot be both collected and rejected: " + raw.itemId());
            }
        }
        for (Map.Entry<String, String> entry : rejectedItemIds.entrySet()) {
            ProviderModelChecks.identifier(entry.getKey(), "rejected itemId");
            ProviderModelChecks.nonBlank(entry.getValue(), "rejection reason");
        }
    }

    public static ProviderCollectOutcome rejectedOnly(String providerId, Map<String, String> rejectedItemIds) {
        return new ProviderCollectOutcome("1.0", providerId, null, null, null, List.of(), List.of(), rejectedItemIds);
    }
}
