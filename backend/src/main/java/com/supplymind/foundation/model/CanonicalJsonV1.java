package com.supplymind.foundation.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Minimal JSON-v1 escaping for deterministic embedded compact JSON vectors. */
public final class CanonicalJsonV1 {
    private CanonicalJsonV1() {
    }

    public static String sourceIdentity(ProviderType providerType, String actualSourceName, AccessMethod accessMethod) {
        ModelRules.required(providerType, "providerType");
        ModelRules.nonBlank(actualSourceName, "actualSourceName");
        ModelRules.required(accessMethod, "accessMethod");
        return "{\"providerType\":" + quote(providerType.wireValue())
                + ",\"actualSourceName\":" + quote(actualSourceName)
                + ",\"accessMethod\":" + quote(accessMethod.wireValue()) + "}";
    }

    public static String sha256LowerHex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                hex.append(String.format("%02x", item));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available in the Java runtime", exception);
        }
    }

    public static String quote(String value) {
        ModelRules.required(value, "JSON string");
        StringBuilder result = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (character < 0x20) {
                        result.append(String.format("\\u%04x", (int) character));
                    } else {
                        result.append(character);
                    }
                }
            }
        }
        return result.append('"').toString();
    }
}
