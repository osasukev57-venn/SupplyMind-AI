package com.supplymind.foundation.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

public enum RouteDecision {
    PRIMARY("primary"),
    FALLBACK_FREE_PUBLIC("fallback_free_public"),
    FALLBACK_MANUAL("fallback_manual"),
    DIRECT_LOCAL_IMPORT("direct_local_import"),
    SYNTHETIC_DEMO("synthetic_demo");

    private final String wireValue;

    RouteDecision(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    @JsonCreator
    public static RouteDecision fromWireValue(String value) {
        return Arrays.stream(values())
                .filter(candidate -> candidate.wireValue.equals(value))
                .findFirst()
                .orElseThrow(() -> new SchemaValidationException("Unsupported routeDecision: " + value));
    }
}
