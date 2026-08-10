package com.supplymind.foundation.storage;

import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.ManifestV1;
import com.supplymind.foundation.model.RawAcquisitionV1;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * DEC-056 source-level raw acquisition persistence. The acquisition is the immutable
 * pre-parse evidence of one HTTP detail response and is written before any HTML parsing.
 * Re-persistence of the same acquisitionId is an idempotent replay: an existing valid
 * acquisition is reused and never rewritten (receivedAt is observation metadata and must
 * not change the formal evidence bytes).
 */
public final class RawAcquisitionStore {

    private final DataRoot dataRoot;
    private final AtomicFileStore fileStore;
    private final Clock clock;

    public RawAcquisitionStore(DataRoot dataRoot, AtomicFileStore fileStore, Clock clock) {
        this.dataRoot = Objects.requireNonNull(dataRoot, "dataRoot");
        this.fileStore = Objects.requireNonNull(fileStore, "fileStore");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public StoredAcquisition store(RawAcquisitionV1 acquisition) {
        Objects.requireNonNull(acquisition, "acquisition");
        String ref = acquisition.acquisitionRef();
        Path acquisitionPath = dataRoot.resolveDataRef(ref);
        Path manifestPath = dataRoot.resolveDataRef(DataPaths.manifestRef(ref));
        byte[] acquisitionBytes = JsonV1Codec.encodeFile(acquisition);
        OffsetDateTime now = OffsetDateTime.now(clock);

        if (Files.isRegularFile(acquisitionPath)) {
            boolean manifestValid = ManifestVerifier.matches(
                    dataRoot, ref, acquisitionPath, manifestPath, List.of(acquisition.acquisitionId()));
            if (FileDigest.bytesEqual(acquisitionPath, acquisitionBytes)) {
                if (!manifestValid) {
                    byte[] repairedManifest = reusableOrRebuiltManifest(
                            ref, acquisitionBytes, List.of(acquisition.acquisitionId()), now);
                    fileStore.commit("acquisition-manifest-repair", DirtyTransactionType.SINGLE_FILE, now,
                            List.of(new FileTransactionTarget(
                                    DirtyTargetRole.BUSINESS_FILE, ref, acquisitionBytes, repairedManifest, false)));
                }
                return new StoredAcquisition(ref, FileDigest.sha256(acquisitionBytes));
            }
            if (manifestValid) {
                return new StoredAcquisition(ref, FileDigest.sha256(acquisitionPath));
            }
            throw new StorageException("Existing acquisition bytes differ and its manifest is invalid: " + ref);
        }

        byte[] manifestBytes = reusableOrRebuiltManifest(ref, acquisitionBytes,
                List.of(acquisition.acquisitionId()), now);
        fileStore.commit("acquisition-" + acquisition.acquisitionId(), DirtyTransactionType.SINGLE_FILE, now,
                List.of(new FileTransactionTarget(
                        DirtyTargetRole.BUSINESS_FILE, ref, acquisitionBytes, manifestBytes, true)));
        return new StoredAcquisition(ref, FileDigest.sha256(acquisitionBytes));
    }

    private byte[] reusableOrRebuiltManifest(
            String dataRef,
            byte[] dataBytes,
            List<String> sourceRunIds,
            OffsetDateTime now
    ) {
        Path dataPath = dataRoot.resolveDataRef(dataRef);
        Path manifestPath = dataRoot.resolveDataRef(DataPaths.manifestRef(dataRef));
        if (Files.isRegularFile(dataPath) && FileDigest.bytesEqual(dataPath, dataBytes)
                && ManifestVerifier.matches(dataRoot, dataRef, dataPath, manifestPath, sourceRunIds)) {
            try {
                return Files.readAllBytes(manifestPath);
            } catch (IOException exception) {
                throw new StorageException("Unable to reuse valid manifest " + manifestPath, exception);
            }
        }
        ManifestV1 manifest = ManifestFactory.json(dataRef, dataBytes, sourceRunIds, now);
        return JsonV1Codec.encodeFile(manifest);
    }

    public record StoredAcquisition(String acquisitionRef, String acquisitionFileSha256) {
    }
}
