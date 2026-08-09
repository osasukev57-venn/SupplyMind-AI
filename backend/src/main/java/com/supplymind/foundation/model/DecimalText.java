package com.supplymind.foundation.model;

import java.math.BigDecimal;

/**
 * Contract boundary for precise values. It deliberately accepts only String input and always
 * writes non-scientific decimal text without stripping meaningful trailing zeroes.
 */
public final class DecimalText {
    private DecimalText() {
    }

    public static String canonical(String value, String fieldName) {
        ModelRules.nonBlank(value, fieldName);
        try {
            return new BigDecimal(value).toPlainString();
        } catch (NumberFormatException exception) {
            throw new SchemaValidationException(fieldName + " must be a precise decimal string");
        }
    }

    public static String canonicalAtScale(String value, int scale, String fieldName) {
        ModelRules.nonNegative(scale, fieldName + " scale");
        String canonical = canonical(value, fieldName);
        if (new BigDecimal(canonical).scale() != scale) {
            throw new SchemaValidationException(fieldName + " must preserve exactly " + scale + " decimal places");
        }
        return canonical;
    }
    public static BigDecimal parse(String value, String fieldName) {
        return new BigDecimal(canonical(value, fieldName));
    }

    public static String toPlainString(BigDecimal value, String fieldName) {
        if (value == null) {
            throw new SchemaValidationException(fieldName + " is required");
        }
        return value.toPlainString();
    }
}
