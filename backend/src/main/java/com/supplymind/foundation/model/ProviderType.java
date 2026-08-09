package com.supplymind.foundation.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

public enum ProviderType {
    OFFICIAL_WEB("official_web"),
    AUTHORIZED_API("authorized_api"),
    FREE_PUBLIC("free_public"),
    MANUAL("manual"),
    LOCAL_IMPORT("local_import"),
    SYNTHETIC_DEMO("synthetic_demo");

    private final String wireValue;

    ProviderType(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    @JsonCreator
    public static ProviderType fromWireValue(String value) {
        return Arrays.stream(values())
                .filter(candidate -> candidate.wireValue.equals(value))
                .findFirst()
                .orElseThrow(() -> new SchemaValidationException("Unsupported providerType: " + value));
    }

    public boolean isExternalHttpProvider() {
        return this == OFFICIAL_WEB || this == AUTHORIZED_API || this == FREE_PUBLIC;
    }
}
