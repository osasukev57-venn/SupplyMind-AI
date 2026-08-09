package com.supplymind.foundation.storage;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Objects;

/** One logical target with data and adjacent manifest physical files. */
@JsonPropertyOrder({
        "order", "role", "dataRef", "manifestRef", "expectedFileSha256", "oldFileSha256", "targetPhase"
})
public record DirtyTargetV1(
        int order,
        DirtyTargetRole role,
        String dataRef,
        String manifestRef,
        String expectedFileSha256,
        String oldFileSha256,
        DirtyTargetPhase targetPhase
) {

    public DirtyTargetV1 {
        if (order < 1) {
            throw new StorageException("DirtyMarker target order must be positive");
        }
        Objects.requireNonNull(role, "role");
        DataPaths.requireLegalDataRef(dataRef);
        if (!DataPaths.manifestRef(dataRef).equals(manifestRef)) {
            throw new StorageException("DirtyMarker manifestRef must be the adjacent manifest of dataRef");
        }
        if (!FileDigest.isLowerHexSha256(expectedFileSha256)) {
            throw new StorageException("DirtyMarker expectedFileSha256 must be lower-case SHA-256");
        }
        if (oldFileSha256 != null && !FileDigest.isLowerHexSha256(oldFileSha256)) {
            throw new StorageException("DirtyMarker oldFileSha256 must be null or lower-case SHA-256");
        }
        Objects.requireNonNull(targetPhase, "targetPhase");
    }

    public DirtyTargetV1 advanceTo(DirtyTargetPhase nextPhase) {
        if (!targetPhase.canAdvanceTo(nextPhase)) {
            throw new StorageException("DirtyMarker target phase cannot move backwards from " + targetPhase + " to " + nextPhase);
        }
        if (targetPhase == nextPhase) {
            throw new StorageException("DirtyMarker target phase must make a real forward transition");
        }
        return new DirtyTargetV1(order, role, dataRef, manifestRef, expectedFileSha256, oldFileSha256, nextPhase);
    }

    boolean hasSameImmutableFields(DirtyTargetV1 other) {
        return other != null
                && order == other.order
                && role == other.role
                && dataRef.equals(other.dataRef)
                && manifestRef.equals(other.manifestRef)
                && expectedFileSha256.equals(other.expectedFileSha256)
                && Objects.equals(oldFileSha256, other.oldFileSha256);
    }
}
