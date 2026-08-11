package com.supplymind.foundation.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Base64;

/** Immutable, per-item original receipt. Lifecycle state intentionally has no place here. */
@JsonPropertyOrder({
        "schemaVersion", "rawRef", "acquisitionId", "runId", "mode", "providerType", "accessMethod",
        "configVersion", "actualSourceName", "sourceUrl", "sourceReference", "itemId",
        "sourceBusinessDateRaw", "sourceBusinessDate", "sourcePublishedAtRaw", "sourcePublishedAt",
        "receivedAt", "inputAt", "rawValue", "rawUnit", "rawCurrency", "operatorRef", "httpStatus",
        "contentType", "payloadEncoding", "payloadBase64", "payloadSha256", "matchAnchor", "updatedAt",
        "acquisitionRef", "declaredSourceName"
})
public record RawReceiptV1(
        String schemaVersion,
        String rawRef,
        String acquisitionId,
        String runId,
        Mode mode,
        ProviderType providerType,
        AccessMethod accessMethod,
        int configVersion,
        String actualSourceName,
        String sourceUrl,
        String sourceReference,
        String itemId,
        String sourceBusinessDateRaw,
        String sourceBusinessDate,
        String sourcePublishedAtRaw,
        OffsetDateTime sourcePublishedAt,
        OffsetDateTime receivedAt,
        OffsetDateTime inputAt,
        String rawValue,
        String rawUnit,
        String rawCurrency,
        String operatorRef,
        Integer httpStatus,
        String contentType,
        String payloadEncoding,
        String payloadBase64,
        String payloadSha256,
        String matchAnchor,
        OffsetDateTime updatedAt,
        String acquisitionRef,
        String declaredSourceName
) {
    private static final ZoneId ASIA_SHANGHAI = ZoneId.of("Asia/Shanghai");

    public RawReceiptV1 {
        ModelRules.schemaVersion(schemaVersion);
        ModelRules.id(acquisitionId, "acquisitionId");
        ModelRules.id(runId, "runId");
        ModelRules.required(mode, "mode");
        ModelRules.required(providerType, "providerType");
        ModelRules.required(accessMethod, "accessMethod");
        ModelRules.providerPair(providerType, accessMethod);
        ModelRules.positive(configVersion, "configVersion");
        ModelRules.nonBlank(actualSourceName, "actualSourceName");
        ModelRules.id(itemId, "itemId");
        ModelRules.dateTime(receivedAt, "receivedAt");
        ModelRules.dateTime(updatedAt, "updatedAt");
        if (!receivedAt.equals(updatedAt)) {
            throw new SchemaValidationException("RawReceipt.updatedAt must equal receivedAt forever");
        }

        String expectedRawRef = deriveRawRef(mode, providerType, itemId, receivedAt, runId);
        if (!expectedRawRef.equals(rawRef)) {
            throw new SchemaValidationException("rawRef must be derived from mode/providerType/itemId/receivedAt/runId");
        }
        ModelRules.relativeDataRef(rawRef, "rawRef");

        if (sourceBusinessDate != null) {
            ModelRules.isoDateText(sourceBusinessDate, "sourceBusinessDate");
        }
        if (sourcePublishedAt != null) {
            ModelRules.dateTime(sourcePublishedAt, "sourcePublishedAt");
        }

        validateProviderSpecificFields(providerType, sourceUrl, sourceReference, inputAt, operatorRef, httpStatus);
        ModelRules.nonBlank(contentType, "contentType");
        if (!"base64".equals(payloadEncoding)) {
            throw new SchemaValidationException("payloadEncoding must be base64");
        }
        ModelRules.nonBlank(payloadBase64, "payloadBase64");
        byte[] payload = decodePayload(payloadBase64);
        ModelRules.sha256(payloadSha256, "payloadSha256");
        String calculatedHash = sha256Bytes(payload);
        if (!payloadSha256.equals(calculatedHash)) {
            throw new SchemaValidationException("payloadSha256 must hash decoded payload bytes");
        }
        if (acquisitionRef != null) {
            ModelRules.relativeDataRef(acquisitionRef, "acquisitionRef");
        }
        if (declaredSourceName != null) {
            ModelRules.nonBlank(declaredSourceName, "declaredSourceName");
        }
    }

    public static String deriveRawRef(
            Mode mode,
            ProviderType providerType,
            String itemId,
            OffsetDateTime receivedAt,
            String runId
    ) {
        ModelRules.required(mode, "mode");
        ModelRules.required(providerType, "providerType");
        ModelRules.id(itemId, "itemId");
        ModelRules.dateTime(receivedAt, "receivedAt");
        ModelRules.id(runId, "runId");
        var inShanghai = receivedAt.atZoneSameInstant(ASIA_SHANGHAI);
        return "raw/" + mode.wireValue() + "/" + providerType.wireValue() + "/" + itemId + "/"
                + String.format("%04d", inShanghai.getYear()) + "/" + String.format("%02d", inShanghai.getMonthValue())
                + "/" + runId + ".json";
    }

    private static void validateProviderSpecificFields(
            ProviderType providerType,
            String sourceUrl,
            String sourceReference,
            OffsetDateTime inputAt,
            String operatorRef,
            Integer httpStatus
    ) {
        if (providerType.isExternalHttpProvider()) {
            ModelRules.httpUrl(sourceUrl, "sourceUrl");
            if (httpStatus == null) {
                throw new SchemaValidationException("httpStatus is required for external HTTP providers");
            }
            if (inputAt != null) {
                throw new SchemaValidationException("inputAt must be null for external HTTP providers");
            }
            return;
        }
        if (providerType == ProviderType.SYNTHETIC_DEMO) {
            ModelRules.nonBlank(sourceReference, "sourceReference fixture ID");
            if (sourceUrl != null || httpStatus != null || inputAt != null) {
                throw new SchemaValidationException("SyntheticDemo requires fixture sourceReference and null sourceUrl/httpStatus/inputAt");
            }
            return;
        }
        ModelRules.nonBlank(sourceReference, "sourceReference");
        if (sourceUrl != null) {
            ModelRules.httpUrl(sourceUrl, "sourceUrl");
        }
        if (httpStatus != null) {
            throw new SchemaValidationException("httpStatus must be null for Manual and LocalImport");
        }
        if (inputAt == null) {
            throw new SchemaValidationException("inputAt is required for Manual and LocalImport");
        }
        if (providerType == ProviderType.MANUAL) {
            ModelRules.nonBlank(operatorRef, "operatorRef");
        }
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
