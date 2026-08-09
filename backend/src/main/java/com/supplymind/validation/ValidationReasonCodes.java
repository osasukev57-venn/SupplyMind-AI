package com.supplymind.validation;

/** Frozen-in-implementation D2-T01 reason codes; upper-snake wire values. */
public final class ValidationReasonCodes {

    public static final String STANDARDIZATION_FAILED = "STANDARDIZATION_FAILED";
    public static final String SOURCE_MISMATCH = "SOURCE_MISMATCH";
    public static final String FIELD_INVALID = "FIELD_INVALID";
    public static final String UNIT_MISMATCH = "UNIT_MISMATCH";
    public static final String CURRENCY_MISMATCH = "CURRENCY_MISMATCH";
    public static final String FUTURE_BUSINESS_DATE = "FUTURE_BUSINESS_DATE";
    public static final String STALE_BUSINESS_DATE = "STALE_BUSINESS_DATE";
    public static final String OUT_OF_RANGE = "OUT_OF_RANGE";
    public static final String DUPLICATE_OBSERVATION = "DUPLICATE_OBSERVATION";
    public static final String VALUE_CONFLICT = "VALUE_CONFLICT";

    private ValidationReasonCodes() {
    }
}
