package com.supplymind.warning;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.supplymind.foundation.model.ModelRules;
import com.supplymind.foundation.model.SchemaValidationException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Versioned user overrides. System defaults are derived from active monitor metadata. */
@JsonPropertyOrder({"schemaVersion", "configVersion", "updatedAt", "overrides"})
public record WarningRuleSetV1(
        String schemaVersion,
        int configVersion,
        OffsetDateTime updatedAt,
        List<WarningRuleConfigV1> overrides
) {
    public WarningRuleSetV1 {
        ModelRules.schemaVersion(schemaVersion);
        ModelRules.positive(configVersion, "configVersion");
        ModelRules.dateTime(updatedAt, "updatedAt");
        overrides = overrides == null ? List.of() : List.copyOf(overrides);
        Set<String> itemIds = new HashSet<>();
        for (WarningRuleConfigV1 rule : overrides) {
            if (rule.origin() != WarningRuleConfigV1.Origin.USER_CUSTOM) {
                throw new SchemaValidationException("persisted warning overrides must be USER_CUSTOM");
            }
            if (!itemIds.add(rule.itemId())) {
                throw new SchemaValidationException("only one warning override is allowed per itemId");
            }
        }
        List<WarningRuleConfigV1> canonical = new ArrayList<>(overrides);
        canonical.sort(Comparator.comparing(WarningRuleConfigV1::itemId));
        overrides = List.copyOf(canonical);
    }
}
