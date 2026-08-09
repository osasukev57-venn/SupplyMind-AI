package com.supplymind.foundation.storage;

import java.util.Arrays;
import java.util.Objects;

/**
 * Supplied, fully encoded bytes for one logical target and its adjacent
 * manifest. The storage layer never reserializes business objects.
 */
public record FileTransactionTarget(
        DirtyTargetRole role,
        String dataRef,
        byte[] dataBytes,
        byte[] manifestBytes,
        boolean immutableData
) {
    public FileTransactionTarget {
        Objects.requireNonNull(role, "role");
        DataPaths.requireLegalDataRef(dataRef);
        if (dataRef.startsWith("runtime/dirty/")) {
            throw new StorageException("Dirty markers cannot be transaction targets or receive manifests");
        }
        dataBytes = requireCopy(dataBytes, "dataBytes");
        manifestBytes = requireCopy(manifestBytes, "manifestBytes");
        if (requiresImmutableCreateNew(dataRef) && !immutableData) {
            throw new StorageException("RawReceipt, QuarantineProjection, and RawConflictEvidence targets must use immutable CREATE_NEW data semantics");
        }
        if (dataRef.startsWith("raw/") && role != DirtyTargetRole.BUSINESS_FILE) {
            throw new StorageException("RawReceipt targets must use the BUSINESS_FILE role");
        }
        if (dataRef.startsWith("config/history/") && role != DirtyTargetRole.CONFIG_HISTORY) {
            throw new StorageException("config/history targets must use the CONFIG_HISTORY role");
        }
        if (dataRef.equals(DataPaths.configActiveRef()) && role != DirtyTargetRole.CONFIG_ACTIVE) {
            throw new StorageException("config/monitor-series.json must use the CONFIG_ACTIVE role");
        }
        if (role == DirtyTargetRole.CONFIG_HISTORY && !dataRef.startsWith("config/history/")) {
            throw new StorageException("CONFIG_HISTORY must target config/history/<configVersion>.json");
        }
        if (role == DirtyTargetRole.CONFIG_ACTIVE && !dataRef.equals(DataPaths.configActiveRef())) {
            throw new StorageException("CONFIG_ACTIVE must target config/monitor-series.json");
        }
        if (role == DirtyTargetRole.CONFIG_HISTORY && !immutableData) {
            throw new StorageException("CONFIG_HISTORY must use immutable CREATE_NEW data semantics");
        }
        if (role == DirtyTargetRole.CONFIG_ACTIVE && immutableData) {
            throw new StorageException("CONFIG_ACTIVE must use replace-by-empty-target semantics");
        }
    }

    @Override
    public byte[] dataBytes() {
        return Arrays.copyOf(dataBytes, dataBytes.length);
    }

    @Override
    public byte[] manifestBytes() {
        return Arrays.copyOf(manifestBytes, manifestBytes.length);
    }

    String manifestRef() {
        return DataPaths.manifestRef(dataRef);
    }

    private static byte[] requireCopy(byte[] bytes, String field) {
        if (bytes == null || bytes.length == 0) {
            throw new StorageException(field + " must contain a complete encoded document");
        }
        return Arrays.copyOf(bytes, bytes.length);
    }

    private static boolean requiresImmutableCreateNew(String dataRef) {
        return dataRef.startsWith("raw/")
                || dataRef.startsWith("quarantine/")
                || dataRef.startsWith("runtime/conflicts/raw/");
    }
}
