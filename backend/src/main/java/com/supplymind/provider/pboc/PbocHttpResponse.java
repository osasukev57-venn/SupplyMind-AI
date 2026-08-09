package com.supplymind.provider.pboc;

import java.net.URI;
import java.util.Arrays;
import java.util.Objects;

/** Exact external HTTP response entity and metadata; headers are intentionally not retained. */
public record PbocHttpResponse(URI responseUri, int statusCode, String contentType, byte[] entityBytes) {
    public PbocHttpResponse {
        Objects.requireNonNull(responseUri, "responseUri");
        entityBytes = entityBytes == null ? null : Arrays.copyOf(entityBytes, entityBytes.length);
        if (entityBytes == null) { throw new IllegalArgumentException("entityBytes are required"); }
    }

    @Override
    public byte[] entityBytes() { return Arrays.copyOf(entityBytes, entityBytes.length); }
}