package com.supplymind.foundation.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

public enum AggregateGrain {
    MONTH("month"),
    QUARTER("quarter"),
    HALFYEAR("halfyear"),
    YEAR("year");

    private final String wireValue;

    AggregateGrain(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    @JsonCreator
    public static AggregateGrain fromWireValue(String value) {
        return Arrays.stream(values())
                .filter(candidate -> candidate.wireValue.equals(value))
                .findFirst()
                .orElseThrow(() -> new SchemaValidationException("Unsupported aggregate grain: " + value));
    }
}
