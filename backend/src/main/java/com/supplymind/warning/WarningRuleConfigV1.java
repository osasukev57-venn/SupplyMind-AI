package com.supplymind.warning;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.supplymind.foundation.model.ModelRules;
import com.supplymind.foundation.model.SchemaValidationException;

import java.math.BigDecimal;

/**
 * DEC-064 formal per-series relative-change warning rule. Threshold is a ratio decimal
 * (0.01 = 1%). Rules are applied only to published daily data by Java; an LLM never evaluates
 * or changes them.
 */
@JsonPropertyOrder({
        "schemaVersion", "ruleId", "ruleVersion", "itemId", "rateKind", "lookbackDays",
        "threshold", "direction", "enabled", "origin", "description"
})
public record WarningRuleConfigV1(
        String schemaVersion,
        String ruleId,
        String ruleVersion,
        String itemId,
        String rateKind,
        int lookbackDays,
        String threshold,
        Direction direction,
        boolean enabled,
        Origin origin,
        String description
) {
    public static final String VERSION = "warning-relative-change-v1";

    public enum Direction {
        UP,
        DOWN,
        BOTH
    }

    public enum Origin {
        SYSTEM_DEFAULT,
        USER_CUSTOM
    }

    public WarningRuleConfigV1 {
        ModelRules.schemaVersion(schemaVersion);
        ModelRules.id(ruleId, "ruleId");
        ModelRules.nonBlank(ruleVersion, "ruleVersion");
        ModelRules.id(itemId, "itemId");
        ModelRules.nonBlank(rateKind, "rateKind");
        if (lookbackDays < 1 || lookbackDays > 365) {
            throw new SchemaValidationException("lookbackDays must be between 1 and 365");
        }
        BigDecimal parsed;
        try {
            parsed = new BigDecimal(threshold);
        } catch (RuntimeException exception) {
            throw new SchemaValidationException("threshold must be a decimal ratio string");
        }
        if (parsed.signum() <= 0 || parsed.compareTo(BigDecimal.TEN) > 0) {
            throw new SchemaValidationException("threshold must be in (0,10]");
        }
        if (direction == null) {
            throw new SchemaValidationException("direction is required");
        }
        if (origin == null) {
            throw new SchemaValidationException("origin is required");
        }
        ModelRules.nonBlank(description, "description");
        threshold = parsed.stripTrailingZeros().toPlainString();
    }

    public BigDecimal thresholdValue() {
        return new BigDecimal(threshold);
    }

    @Override
    public String toString() {
        return ruleId + "@" + ruleVersion;
    }
}
