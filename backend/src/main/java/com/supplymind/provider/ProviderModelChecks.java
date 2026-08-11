package com.supplymind.provider;

import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.SchemaValidationException;

import java.net.URI;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Minimal D3-T01/D3-T02 model checks shared by the provider and routing packages. Mirrors the
 * frozen source/access pairing and identifier/schema rules without widening the
 * package-private foundation rules.
 */
public final class ProviderModelChecks {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Map<ProviderType, AccessMethod> FROZEN_PAIRS = Map.of(
            ProviderType.OFFICIAL_WEB, AccessMethod.PUBLIC_OFFICIAL_HTML,
            ProviderType.AUTHORIZED_API, AccessMethod.AUTHORIZED_API,
            ProviderType.FREE_PUBLIC, AccessMethod.FREE_PUBLIC_WEB,
            ProviderType.MANUAL, AccessMethod.MANUAL,
            ProviderType.LOCAL_IMPORT, AccessMethod.LOCAL_IMPORT,
            ProviderType.SYNTHETIC_DEMO, AccessMethod.SYNTHETIC_DEMO);

    private ProviderModelChecks() {
    }

    public static void schemaVersion(String schemaVersion) {
        if (!"1.0".equals(schemaVersion)) {
            throw new SchemaValidationException("schemaVersion must be \"1.0\"");
        }
    }

    public static void identifier(String value, String fieldName) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new SchemaValidationException(fieldName + " must match " + IDENTIFIER.pattern());
        }
    }

    public static void nonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new SchemaValidationException(fieldName + " must not be blank");
        }
    }

    public static void required(Object value, String fieldName) {
        if (value == null) {
            throw new SchemaValidationException(fieldName + " is required");
        }
    }

    public static void providerPair(ProviderType providerType, AccessMethod accessMethod) {
        if (providerType == null || accessMethod == null) {
            throw new SchemaValidationException("providerType and accessMethod are required");
        }
        AccessMethod expected = FROZEN_PAIRS.get(providerType);
        if (expected == null || expected != accessMethod) {
            throw new SchemaValidationException(
                    "providerType/accessMethod must use the frozen pair: " + providerType.wireValue()
                            + "/" + accessMethod.wireValue());
        }
    }

    public static void httpUrl(String value, String fieldName) {
        nonBlank(value, fieldName);
        try {
            URI uri = URI.create(value);
            if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) {
                throw new SchemaValidationException(fieldName + " must be an absolute http(s) URL");
            }
        } catch (IllegalArgumentException exception) {
            throw new SchemaValidationException(fieldName + " must be an absolute http(s) URL");
        }
    }
}
