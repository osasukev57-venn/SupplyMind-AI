package com.supplymind.processing;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * M6 shared deterministic cost-impact calculation, extracted from the D5 warning chain so the
 * Agent cost.impact tool and the Day5 business path compute through the SAME production
 * component. No new formula: the change ratio is (current - previous) / previous with 12
 * decimal HALF_UP, and the EXT-08 cost weight stays the demo weight of exactly 1 (explicitly
 * marked demo; EXT-07/EXT-08 unconfirmed). BigDecimal only, never double/float.
 */
public final class CostImpactCalculator {

    /** EXT-08 demo cost weight (unconfirmed); kept in one place. */
    public static final BigDecimal DEMO_COST_WEIGHT = BigDecimal.ONE;

    private CostImpactCalculator() {
    }

    public static BigDecimal changeRatio(BigDecimal current, BigDecimal previous) {
        if (current == null || previous == null) {
            throw new IllegalArgumentException("current and previous averages are required");
        }
        if (previous.signum() == 0) {
            throw new IllegalArgumentException("previous-period baseline must not be zero");
        }
        return current.subtract(previous)
                .divide(previous, 12, RoundingMode.HALF_UP);
    }

    public static BigDecimal costImpact(BigDecimal changeRatio) {
        return changeRatio.multiply(DEMO_COST_WEIGHT, MathContext.DECIMAL64)
                .setScale(12, RoundingMode.HALF_UP);
    }
}
