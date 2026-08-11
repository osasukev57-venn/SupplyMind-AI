package com.supplymind.localimport;

import com.supplymind.foundation.model.SchemaValidationException;
import com.supplymind.provider.ProviderModelChecks;

import java.time.OffsetDateTime;
import java.util.Base64;

/**
 * D3-T05 source-level immutable import receipt: the complete original import file bytes
 * persisted BEFORE any decode/parse (raw-first). Its payload is always the exact bytes the
 * import entry received - never re-encoded, trimmed or re-serialized.
 */
public record LocalImportReceiptV1(
        String schemaVersion,
        String importRef,
        String importId,
        OffsetDateTime receivedAt,
        long byteLength,
        String payloadEncoding,
        String payloadBase64,
        String payloadSha256
) {
    public LocalImportReceiptV1 {
        ProviderModelChecks.schemaVersion(schemaVersion);
        ProviderModelChecks.nonBlank(importRef, "importRef");
        ProviderModelChecks.identifier(importId, "importId");
        if (receivedAt == null) {
            throw new SchemaValidationException("receivedAt is required");
        }
        ProviderModelChecks.nonBlank(payloadEncoding, "payloadEncoding");
        if (!"base64".equals(payloadEncoding)) {
            throw new SchemaValidationException("payloadEncoding must be base64");
        }
        ProviderModelChecks.nonBlank(payloadBase64, "payloadBase64");
        if (!com.supplymind.foundation.storage.FileDigest.isLowerHexSha256(payloadSha256)) {
            throw new SchemaValidationException("payloadSha256 must be a lower-case SHA-256 value");
        }
        byte[] payload = Base64.getDecoder().decode(payloadBase64);
        if (payload.length != byteLength) {
            throw new SchemaValidationException("byteLength must equal the decoded payload length");
        }
        String expectedRef = com.supplymind.foundation.storage.DataPaths.importRef(importId);
        if (!expectedRef.equals(importRef)) {
            throw new SchemaValidationException("importRef must be derived from importId");
        }
        if (!payloadSha256.equals(com.supplymind.foundation.storage.FileDigest.sha256(payload))) {
            throw new SchemaValidationException("payloadSha256 must hash the decoded payload bytes");
        }
    }
}
