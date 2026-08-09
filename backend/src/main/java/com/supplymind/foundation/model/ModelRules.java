package com.supplymind.foundation.model;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Small, dependency-free guards used by the immutable v1 schema records. */
final class ModelRules {
    static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]+");
    static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    private ModelRules() {
    }

    static <T> T required(T value, String name) {
        if (value == null) {
            throw new SchemaValidationException(name + " is required");
        }
        return value;
    }

    static String nonBlank(String value, String name) {
        required(value, name);
        if (value.isBlank()) {
            throw new SchemaValidationException(name + " must not be blank");
        }
        return value;
    }

    static String schemaVersion(String value) {
        if (!SchemaV1.VERSION.equals(value)) {
            throw new SchemaValidationException("schemaVersion must be the string \"1.0\"");
        }
        return value;
    }

    static String id(String value, String name) {
        nonBlank(value, name);
        if (!SAFE_ID.matcher(value).matches()) {
            throw new SchemaValidationException(name + " must match " + SAFE_ID.pattern());
        }
        return value;
    }

    static int positive(int value, String name) {
        if (value <= 0) {
            throw new SchemaValidationException(name + " must be positive");
        }
        return value;
    }

    static long nonNegative(long value, String name) {
        if (value < 0) {
            throw new SchemaValidationException(name + " must not be negative");
        }
        return value;
    }

    static int nonNegative(int value, String name) {
        if (value < 0) {
            throw new SchemaValidationException(name + " must not be negative");
        }
        return value;
    }

    static LocalDate isoDate(LocalDate value, String name) {
        return required(value, name);
    }

    static String isoDateText(String value, String name) {
        nonBlank(value, name);
        try {
            LocalDate parsed = LocalDate.parse(value);
            if (!parsed.toString().equals(value)) {
                throw new SchemaValidationException(name + " must be YYYY-MM-DD");
            }
        } catch (DateTimeParseException exception) {
            throw new SchemaValidationException(name + " must be YYYY-MM-DD");
        }
        return value;
    }

    static OffsetDateTime dateTime(OffsetDateTime value, String name) {
        return required(value, name);
    }

    static String sha256(String value, String name) {
        nonBlank(value, name);
        if (!SHA_256.matcher(value).matches()) {
            throw new SchemaValidationException(name + " must be 64 lowercase hexadecimal characters");
        }
        return value;
    }

    static String relativeDataRef(String value, String name) {
        nonBlank(value, name);
        if (value.indexOf('\\') >= 0 || value.startsWith("/") || value.startsWith("//")
                || value.matches("^[A-Za-z]:.*") || value.contains("//")) {
            throw new SchemaValidationException(name + " must be a normalized relative dataRoot reference");
        }
        String[] segments = value.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new SchemaValidationException(name + " must not contain empty, . or .. segments");
            }
        }
        return value;
    }

    static String httpUrl(String value, String name) {
        nonBlank(value, name);
        try {
            URI uri = new URI(value);
            if (!uri.isAbsolute() || !("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null) {
                throw new SchemaValidationException(name + " must be an absolute http(s) URL");
            }
            return value;
        } catch (URISyntaxException exception) {
            throw new SchemaValidationException(name + " must be an absolute http(s) URL");
        }
    }

    static void providerPair(ProviderType providerType, AccessMethod accessMethod) {
        required(providerType, "providerType");
        required(accessMethod, "accessMethod");
        boolean valid = switch (providerType) {
            case OFFICIAL_WEB -> accessMethod == AccessMethod.PUBLIC_OFFICIAL_HTML;
            case AUTHORIZED_API -> accessMethod == AccessMethod.AUTHORIZED_API;
            case FREE_PUBLIC -> accessMethod == AccessMethod.FREE_PUBLIC_WEB;
            case MANUAL -> accessMethod == AccessMethod.MANUAL;
            case LOCAL_IMPORT -> accessMethod == AccessMethod.LOCAL_IMPORT;
            case SYNTHETIC_DEMO -> accessMethod == AccessMethod.SYNTHETIC_DEMO;
        };
        if (!valid) {
            throw new SchemaValidationException("providerType and accessMethod are not a frozen legal pair");
        }
    }

    static <T> List<T> immutableList(List<T> value, String name) {
        required(value, name);
        if (value.stream().anyMatch(Objects::isNull)) {
            throw new SchemaValidationException(name + " must not contain null values");
        }
        return List.copyOf(value);
    }
}
