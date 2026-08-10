package com.supplymind.foundation.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Objects;

/**
 * DEC-056 source-level raw acquisition envelope: the immutable, pre-parse evidence of one
 * external HTTP detail response (full entity bytes). It is persisted before any HTML decoding
 * or announcement parsing, so a parse failure still leaves verifiable source evidence on disk.
 * It deliberately carries no parsed item fields (businessDate/rawValue/matchAnchor live in
 * item-level RawReceiptV1 records, which reference this acquisition via acquisitionRef).
 */
@JsonPropertyOrder({
        "schemaVersion", "acquisitionRef", "acquisitionId", "mode", "providerType", "accessMethod",
        "configVersion", "actualSourceName", "listUrl", "detailUrl", "httpStatus", "contentType",
        "receivedAt", "payloadEncoding", "payloadBase64", "payloadSha256"
})
public record RawAcquisitionV1(
        String schemaVersion,
        String acquisitionRef,
        String acquisitionId,
        Mode mode,
        ProviderType providerType,
        AccessMethod accessMethod,
        int configVersion,
        String actualSourceName,
        String listUrl,
        String detailUrl,
        Integer httpStatus,
        String contentType,
        OffsetDateTime receivedAt,
        String payloadEncoding,
        String payloadBase64,
        String payloadSha256
) {
    public RawAcquisitionV1 {
        ModelRules.schemaVersion(schemaVersion);
        ModelRules.id(acquisitionId, "acquisitionId");
        ModelRules.required(mode, "mode");
        ModelRules.required(providerType, "providerType");
        ModelRules.required(accessMethod, "accessMethod");
        ModelRules.providerPair(providerType, accessMethod);
        ModelRules.positive(configVersion, "configVersion");
        ModelRules.nonBlank(actualSourceName, "actualSourceName");
        ModelRules.httpUrl(listUrl, "listUrl");
        ModelRules.httpUrl(detailUrl, "detailUrl");
        ModelRules.dateTime(receivedAt, "receivedAt");
        if (providerType.isExternalHttpProvider()) {
            if (httpStatus == null) {
                throw new SchemaValidationException("httpStatus is required for external HTTP providers");
            }
        }
        ModelRules.nonBlank(contentType, "contentType");
        if (!"base64".equals(payloadEncoding)) {
            throw new SchemaValidationException("payloadEncoding must be base64");
        }
        ModelRules.nonBlank(payloadBase64, "payloadBase64");
        ModelRules.sha256(payloadSha256, "payloadSha256");
        String expectedRef = deriveAcquisitionRef(acquisitionId);
        if (!expectedRef.equals(acquisitionRef)) {
            throw new SchemaValidationException("acquisitionRef must be derived from acquisitionId");
        }
        ModelRules.relativeDataRef(acquisitionRef, "acquisitionRef");
        byte[] payload = decodePayload(payloadBase64);
        if (!payloadSha256.equals(sha256Bytes(payload))) {
            throw new SchemaValidationException("payloadSha256 must hash decoded payload bytes");
        }
    }

    public static String deriveAcquisitionRef(String acquisitionId) {
        ModelRules.id(acquisitionId, "acquisitionId");
        return "raw/source/" + acquisitionId + ".json";
    }

    private static byte[] decodePayload(String payloadBase64) {
        try {
            return Base64.getDecoder().decode(payloadBase64);
        } catch (IllegalArgumentException exception) {
            throw new SchemaValidationException("payloadBase64 must be valid base64");
        }
    }

    private static String sha256Bytes(byte[] bytes) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available in the Java runtime", exception);
        }
    }
}
