package com.supplymind.foundation.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

public enum ValidationStatus {
    PENDING("PENDING"),
    VERIFIED("VERIFIED"),
    VERIFIED_WITH_NOTICE("VERIFIED_WITH_NOTICE"),
    REJECTED("REJECTED"),
    CONFLICT("CONFLICT");

    private final String wireValue;

    ValidationStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    @JsonCreator
    public static ValidationStatus fromWireValue(String value) {
        return Arrays.stream(values())
                .filter(candidate -> candidate.wireValue.equals(value))
                .findFirst()
                .orElseThrow(() -> new SchemaValidationException("Unsupported validationStatus: " + value));
    }

    public boolean isPublishEligible() {
        return this == VERIFIED || this == VERIFIED_WITH_NOTICE;
    }
}
