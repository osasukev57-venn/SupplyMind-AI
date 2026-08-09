package com.supplymind.foundation.storage;

import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.ManifestV1;
import com.supplymind.foundation.model.QuarantineProjectionV1;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Immutable quarantine projection persistence. CREATE_NEW semantics: the identical complete
 * business file hash is an idempotent replay (manifest may be repaired), a different hash
 * fails closed and never overwrites the existing projection.
 */
public final class QuarantineStore {

    private final DataRoot dataRoot;
    private final AtomicFileStore fileStore;
    private final Clock clock;

    public QuarantineStore(DataRoot dataRoot, AtomicFileStore fileStore, Clock clock) {
        this.dataRoot = Objects.requireNonNull(dataRoot, "dataRoot");
        this.fileStore = Objects.requireNonNull(fileStore, "fileStore");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public StoredQuarantine store(QuarantineProjectionV1 projection) {
        Objects.requireNonNull(projection, "projection");
        String quarantineRef = projection.quarantineRef();
        byte[] projectionBytes = JsonV1Codec.encodeFile(projection);
        Path projectionPath = dataRoot.resolveDataRef(quarantineRef);
        Path manifestPath = dataRoot.resolveDataRef(DataPaths.manifestRef(quarantineRef));
        if (Files.isRegularFile(projectionPath) && FileDigest.bytesEqual(projectionPath, projectionBytes)
                && ManifestVerifier.matches(dataRoot, quarantineRef, projectionPath, manifestPath,
                List.of(projection.runId()))) {
            return new StoredQuarantine(quarantineRef, FileDigest.sha256(projectionBytes));
        }
        byte[] manifestBytes = reusableOrRebuiltManifest(quarantineRef, projectionBytes,
                List.of(projection.runId()), OffsetDateTime.now(clock));
        fileStore.commit("quarantine-" + projection.runId(), DirtyTransactionType.SINGLE_FILE,
                OffsetDateTime.now(clock),
                List.of(new FileTransactionTarget(
                        DirtyTargetRole.BUSINESS_FILE, quarantineRef, projectionBytes, manifestBytes, true)));
        return new StoredQuarantine(quarantineRef, FileDigest.sha256(projectionBytes));
    }

    private byte[] reusableOrRebuiltManifest(String dataRef, byte[] dataBytes, List<String> sourceRunIds,
                                             OffsetDateTime now) {
        Path dataPath = dataRoot.resolveDataRef(dataRef);
        Path manifestPath = dataRoot.resolveDataRef(DataPaths.manifestRef(dataRef));
        if (Files.isRegularFile(dataPath) && FileDigest.bytesEqual(dataPath, dataBytes)
                && ManifestVerifier.matches(dataRoot, dataRef, dataPath, manifestPath, sourceRunIds)) {
            try {
                return Files.readAllBytes(manifestPath);
            } catch (IOException exception) {
                throw new StorageException("Unable to reuse quarantine manifest " + manifestPath, exception);
            }
        }
        ManifestV1 manifest = ManifestFactory.json(dataRef, dataBytes, sourceRunIds, now);
        return JsonV1Codec.encodeFile(manifest);
    }

    public record StoredQuarantine(String quarantineRef, String quarantineFileSha256) {
    }
}
