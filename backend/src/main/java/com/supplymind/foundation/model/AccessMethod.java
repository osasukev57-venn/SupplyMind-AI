package com.supplymind.foundation.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

public enum AccessMethod {
    PUBLIC_OFFICIAL_HTML("public_official_html"),
    AUTHORIZED_API("authorized_api"),
    FREE_PUBLIC_WEB("free_public_web"),
    MANUAL("manual"),
    LOCAL_IMPORT("local_import"),
    SYNTHETIC_DEMO("synthetic_demo");

    private final String wireValue;

    AccessMethod(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    @JsonCreator
    public static AccessMethod fromWireValue(String value) {
        return Arrays.stream(values())
                .filter(candidate -> candidate.wireValue.equals(value))
                .findFirst()
                .orElseThrow(() -> new SchemaValidationException("Unsupported accessMethod: " + value));
    }
}
