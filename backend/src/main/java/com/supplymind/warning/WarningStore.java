package com.supplymind.warning;

import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.ManifestV1;
import com.supplymind.foundation.storage.AtomicFileStore;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.DirtyTargetRole;
import com.supplymind.foundation.storage.DirtyTransactionType;
import com.supplymind.foundation.storage.FileTransactionTarget;
import com.supplymind.foundation.storage.ManifestFactory;
import com.supplymind.foundation.storage.ManifestVerifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * D5-T05 immutable warning evidence persistence under the frozen warning/YYYY-MM pattern.
 * Identical re-writes (same warningId = same logical inputs) are no-ops; a different document
 * under the same warningId is impossible by construction (fingerprint-derived identity) and
 * would fail closed as an immutable-file conflict.
 */
public final class WarningStore {

    private final DataRoot dataRoot;
    private final AtomicFileStore fileStore;
    private final Clock clock;

    public WarningStore(DataRoot dataRoot, AtomicFileStore fileStore, Clock clock) {
        this.dataRoot = Objects.requireNonNull(dataRoot, "dataRoot");
        this.fileStore = Objects.requireNonNull(fileStore, "fileStore");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public boolean exists(String warningRef) {
        return Files.isRegularFile(dataRoot.resolveDataRef(warningRef));
    }

    public WarningRecordV1 store(WarningRecordV1 warning) {
        Objects.requireNonNull(warning, "warning");
        String ref = DataPaths.warningRef(warning.warningMonth(), warning.warningId());
        byte[] warningBytes = JsonV1Codec.encodeFile(warning);
        Path path = dataRoot.resolveDataRef(ref);
        Path manifestPath = dataRoot.resolveDataRef(DataPaths.manifestRef(ref));
        if (Files.isRegularFile(path)
                && ManifestVerifier.matches(dataRoot, ref, path, manifestPath, List.of())
                && java.util.Arrays.equals(readBytes(path), warningBytes)) {
            return warning;
        }
        OffsetDateTime at = OffsetDateTime.now(clock);
        ManifestV1 manifest = ManifestFactory.json(ref, warningBytes, List.of(), at);
        byte[] manifestBytes = JsonV1Codec.encodeFile(manifest);
        fileStore.commit("warning-" + warning.warningId(),
                DirtyTransactionType.SINGLE_FILE, at,
                List.of(new FileTransactionTarget(
                        DirtyTargetRole.BUSINESS_FILE, ref, warningBytes, manifestBytes, true)));
        return warning;
    }

    private static byte[] readBytes(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new com.supplymind.foundation.storage.StorageException(
                    "Unable to read warning evidence " + path, exception);
        }
    }
}
