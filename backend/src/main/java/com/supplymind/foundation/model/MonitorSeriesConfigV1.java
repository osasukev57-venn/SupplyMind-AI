package com.supplymind.foundation.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The single active monitor-series configuration and the byte-identical history snapshot shape. */
@JsonPropertyOrder({"schemaVersion", "configVersion", "mode", "updatedAt", "items"})
public record MonitorSeriesConfigV1(
        String schemaVersion,
        int configVersion,
        Mode mode,
        OffsetDateTime updatedAt,
        List<MonitorSeriesItemV1> items
) {
    public MonitorSeriesConfigV1 {
        ModelRules.schemaVersion(schemaVersion);
        ModelRules.positive(configVersion, "configVersion");
        ModelRules.required(mode, "mode");
        ModelRules.dateTime(updatedAt, "updatedAt");
        items = ModelRules.immutableList(items, "items");
        if (items.isEmpty()) {
            throw new SchemaValidationException("monitor-series items must not be empty");
        }
        Set<String> itemIds = new HashSet<>();
        for (MonitorSeriesItemV1 item : items) {
            if (!itemIds.add(item.itemId())) {
                throw new SchemaValidationException("monitor-series itemId must be unique: " + item.itemId());
            }
        }
        List<MonitorSeriesItemV1> canonical = new ArrayList<>(items);
        canonical.sort(Comparator.comparing(MonitorSeriesItemV1::itemId));
        items = List.copyOf(canonical);
    }

    public MonitorSeriesItemV1 requireItem(String itemId) {
        return items.stream()
                .filter(item -> item.itemId().equals(itemId))
                .findFirst()
                .orElseThrow(() -> new SchemaValidationException("No item in configVersion " + configVersion + ": " + itemId));
    }
}
