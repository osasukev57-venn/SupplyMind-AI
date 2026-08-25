package com.supplymind.warning;

/** Result of one formal default/custom warning evaluation. */
public record WarningEvaluationV1(
        WarningRuleConfigV1 rule,
        String status,
        String message,
        String currentBusinessDate,
        String currentValue,
        String baselineBusinessDate,
        String baselineValue,
        String changeRatio,
        String unit,
        WarningRecordV1 warning
) {
    public static final String TRIGGERED = "TRIGGERED";
    public static final String NOT_TRIGGERED = "NOT_TRIGGERED";
    public static final String INSUFFICIENT_DATA = "INSUFFICIENT_DATA";
    public static final String INVALID_DATA = "INVALID_DATA";
    public static final String DISABLED = "DISABLED";
}
