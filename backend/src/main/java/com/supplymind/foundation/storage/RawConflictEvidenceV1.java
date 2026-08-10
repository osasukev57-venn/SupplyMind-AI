package com.supplymind.foundation.storage;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.supplymind.foundation.model.RawReceiptV1;
import com.supplymind.foundation.model.SchemaV1;

import java.time.OffsetDateTime;
import java.util.Objects;

/** Complete forensic evidence created when an immutable raw target has a different incoming file hash. */
@JsonPropertyOrder({
        "schemaVersion", "conflictId", "itemId", "runId", "existingRawRef", "existingFileSha256",
        "incomingFileSha256", "incomingReceipt", "detectedAt"
})
public record RawConflictEvidenceV1(
        String schemaVersion,
        String conflictId,
        String itemId,
        String runId,
        String existingRawRef,
        String existingFileSha256,
        String incomingFileSha256,
        RawReceiptV1 incomingReceipt,
        OffsetDateTime detectedAt
) {
    public RawConflictEvidenceV1 {
        if (!SchemaV1.VERSION.equals(schemaVersion)) {
            throw new StorageException("RawConflictEvidenceV1 schemaVersion must be \"1.0\"");
        }
        DataPaths.requireIdentifier(conflictId, "conflictId");
        DataPaths.requireIdentifier(itemId, "itemId");
        DataPaths.requireIdentifier(runId, "runId");
        DataPaths.requireLegalDataRef(existingRawRef);
        if (!FileDigest.isLowerHexSha256(existingFileSha256) || !FileDigest.isLowerHexSha256(incomingFileSha256)) {
            throw new StorageException("RawConflictEvidenceV1 hashes must be lower-case SHA-256 values");
        }
        Objects.requireNonNull(incomingReceipt, "incomingReceipt");
        Objects.requireNonNull(detectedAt, "detectedAt");
        if (!itemId.equals(incomingReceipt.itemId()) || !runId.equals(incomingReceipt.runId())) {
            throw new StorageException("RawConflictEvidenceV1 must identify its incoming RawReceiptV1 exactly");
        }
    }
}
