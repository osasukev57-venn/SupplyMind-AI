package com.supplymind.provider;


import com.supplymind.foundation.model.SchemaValidationException;

import java.util.List;

/** D3-T01: the monitored targets a single collection request asks a DataProvider to cover. */
public record ProviderCollectRequest(List<String> itemIds) {

    public ProviderCollectRequest {
        if (itemIds == null || itemIds.isEmpty()) {
            throw new SchemaValidationException("A provider collect request must name at least one itemId");
        }
        List<String> distinct = itemIds.stream().distinct().toList();
        for (String itemId : distinct) {
            ProviderModelChecks.identifier(itemId, "itemId");
        }
        itemIds = List.copyOf(distinct);
    }
}
