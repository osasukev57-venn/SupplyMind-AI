package com.supplymind.foundation.model;

import com.supplymind.foundation.storage.FileDigest;
import java.time.OffsetDateTime;
import java.net.URI;
import java.util.Base64;
import java.util.Objects;

/** Diagnostic integrity is not business validation. Captured bytes may be incomplete. */
public record AcquisitionFailureEvidenceV1(String schemaVersion, String attemptId, String requestUrl,
        OffsetDateTime startedAt, OffsetDateTime failedAt, String responseState, Integer httpStatus,
        String contentType, String payloadBase64, Long capturedLength, String capturedSha256,
        String failureCode, String sourceEvidenceRef) {
    public AcquisitionFailureEvidenceV1 {
        if (!"ACQUISITION-FAILURE-V1".equals(schemaVersion)) throw new IllegalArgumentException("failure schema");
        ModelRules.id(attemptId, "attemptId");
        URI uri = URI.create(requestUrl);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) throw new IllegalArgumentException("unsafe diagnostic URL");
        Objects.requireNonNull(startedAt); Objects.requireNonNull(failedAt);
        if (!java.util.Set.of("COMPLETE", "PARTIAL", "NONE").contains(responseState)) throw new IllegalArgumentException("response state");
        if (!java.util.Set.of("EMPTY_ENTITY", "MISSING_CONTENT_TYPE", "EMPTY_CONTENT_TYPE", "HTTP_STATUS", "PARTIAL_RESPONSE", "TRANSPORT_FAILURE").contains(failureCode)) throw new IllegalArgumentException("failure code");
        if (responseState.equals("NONE")) {
            if (httpStatus != null || contentType != null || payloadBase64 != null || capturedLength != null
                    || capturedSha256 != null || sourceEvidenceRef != null || !failureCode.equals("TRANSPORT_FAILURE"))
                throw new IllegalArgumentException("no response must not invent response metadata");
        } else {
            if (httpStatus == null || httpStatus < 100 || httpStatus > 599) throw new IllegalArgumentException("status");
            byte[] bytes = Base64.getDecoder().decode(Objects.requireNonNull(payloadBase64));
            if (capturedLength == null || capturedLength != bytes.length || !FileDigest.sha256(bytes).equals(capturedSha256)) throw new IllegalArgumentException("captured payload mismatch");
            if (responseState.equals("PARTIAL") && (!failureCode.equals("PARTIAL_RESPONSE") || sourceEvidenceRef != null)) throw new IllegalArgumentException("partial entity is not source raw");
            if (responseState.equals("COMPLETE") && failureCode.equals("EMPTY_ENTITY") && bytes.length != 0) throw new IllegalArgumentException("not empty");
            if (failureCode.equals("MISSING_CONTENT_TYPE") && contentType != null) throw new IllegalArgumentException("type present");
            if (failureCode.equals("EMPTY_CONTENT_TYPE") && (contentType == null || !contentType.isBlank())) throw new IllegalArgumentException("type not empty");
            if (sourceEvidenceRef != null && !sourceEvidenceRef.matches("raw/source/[A-Za-z0-9._-]+\\.json")) throw new IllegalArgumentException("source ref");
        }
    }
    public String evidenceRef() { return "diagnostic/acquisition/" + attemptId + ".json"; }
}
