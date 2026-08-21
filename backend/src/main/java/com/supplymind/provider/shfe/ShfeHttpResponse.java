package com.supplymind.provider.shfe;

import java.net.URI;
import java.util.Arrays;
import java.util.Objects;

/** Exact SHFE public HTTP response entity bytes; response headers are never persisted. */
public record ShfeHttpResponse(URI responseUri, int statusCode, String contentType, byte[] entityBytes) {
    public ShfeHttpResponse {
        Objects.requireNonNull(responseUri, "responseUri");
        entityBytes = entityBytes == null ? null : Arrays.copyOf(entityBytes, entityBytes.length);
        if (entityBytes == null) {
            throw new IllegalArgumentException("entityBytes are required");
        }
    }

    @Override
    public byte[] entityBytes() {
        return Arrays.copyOf(entityBytes, entityBytes.length);
    }
}
