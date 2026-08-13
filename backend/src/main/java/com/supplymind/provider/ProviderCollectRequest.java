package com.supplymind.provider;


import com.supplymind.foundation.model.SchemaValidationException;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * D3-T01/M3: the monitored targets a single collection request asks a DataProvider to cover.
 * M3 versioned extension: the request now also states whether it asks for CURRENT data or an
 * explicit HISTORY range. A HISTORY request must carry historyStartDate/historyEndDate (the
 * exact range the provider is asked to collect) - the provider decides what to return from the
 * request, never from implicit internal "next pending day" state. A CURRENT request carries no
 * range.
 */
public record ProviderCollectRequest(
        List<String> itemIds,
        CollectionMode collectionMode,
        LocalDate historyStartDate,
        LocalDate historyEndDate
) {

    public ProviderCollectRequest {
        if (itemIds == null || itemIds.isEmpty()) {
            throw new SchemaValidationException("A provider collect request must name at least one itemId");
        }
        List<String> distinct = itemIds.stream().distinct().toList();
        for (String itemId : distinct) {
            ProviderModelChecks.identifier(itemId, "itemId");
        }
        itemIds = List.copyOf(distinct);
        Objects.requireNonNull(collectionMode, "collectionMode");
        if (collectionMode == CollectionMode.HISTORY) {
            if (historyStartDate == null || historyEndDate == null) {
                throw new SchemaValidationException(
                        "A HISTORY provider collect request must carry an explicit historyStartDate/historyEndDate range");
            }
            if (historyStartDate.isAfter(historyEndDate)) {
                throw new SchemaValidationException(
                        "A HISTORY provider collect request must have historyStartDate <= historyEndDate");
            }
        } else {
            if (historyStartDate != null || historyEndDate != null) {
                throw new SchemaValidationException(
                        "A CURRENT provider collect request must not carry history dates");
            }
        }
    }

    /** Backward-compatible CURRENT request (no history range). */
    public ProviderCollectRequest(List<String> itemIds) {
        this(itemIds, CollectionMode.CURRENT, null, null);
    }

    /** CURRENT request with explicit current mode. */
    public static ProviderCollectRequest current(List<String> itemIds) {
        return new ProviderCollectRequest(itemIds, CollectionMode.CURRENT, null, null);
    }

    /** HISTORY request with the exact date range to collect. */
    public static ProviderCollectRequest history(List<String> itemIds, LocalDate from, LocalDate to) {
        return new ProviderCollectRequest(itemIds, CollectionMode.HISTORY, from, to);
    }
}
