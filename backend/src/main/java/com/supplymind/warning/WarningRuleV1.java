package com.supplymind.warning;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.supplymind.foundation.model.ModelRules;
import com.supplymind.foundation.model.SchemaValidationException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * D5-T05 one versioned deterministic warning rule. EXT-07/EXT-08 thresholds and cost weights
 * are not yet confirmed by the business, so rules are explicit TEST/DEMO configurations and
 * always carry demoRule=true and a description stating they are not final business
 * thresholds. Rules never decide risk levels through an LLM - the service maps deterministic
 * outcomes to frozen risk levels.
 */
@JsonPropertyOrder({
        "ruleId", "ruleVersion", "ruleKind", "itemId", "grain", "threshold", "direction",
        "baselinePeriods", "demoRule", "description"
})
public record WarningRuleV1(
        String ruleId,
        String ruleVersion,
        RuleKind ruleKind,
        String itemId,
        String grain,
        String threshold,
        Direction direction,
        int baselinePeriods,
        boolean demoRule,
        String description
) {
    public enum RuleKind {
        PRICE_CHANGE,
        RATE_CHANGE,
        COST_IMPACT,
        DATA_QUALITY
    }

    public enum Direction {
        ABOVE,
        BELOW
    }

    public WarningRuleV1 {
        ModelRules.id(ruleId, "ruleId");
        ModelRules.nonBlank(ruleVersion, "ruleVersion");
        if (ruleKind == null) {
            throw new SchemaValidationException("ruleKind is required");
        }
        ModelRules.id(itemId, "itemId");
        ModelRules.nonBlank(grain, "grain");
        if (threshold == null || threshold.isBlank()) {
            throw new SchemaValidationException("threshold must be a decimal string");
        }
        try {
            new BigDecimal(threshold);
        } catch (NumberFormatException exception) {
            throw new SchemaValidationException("threshold must be a decimal string: " + threshold);
        }
        if (direction == null) {
            throw new SchemaValidationException("direction is required");
        }
        ModelRules.positive(baselinePeriods, "baselinePeriods");
        ModelRules.nonBlank(description, "description");
    }

    public BigDecimal thresholdValue() {
        return new BigDecimal(threshold);
    }

    @Override
    public String toString() {
        return ruleId + "@" + ruleVersion;
    }
}
