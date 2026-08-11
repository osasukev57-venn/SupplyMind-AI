package com.supplymind.localimport;

import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.ManifestV1;
import com.supplymind.foundation.storage.AtomicFileStore;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.DirtyTargetRole;
import com.supplymind.foundation.storage.DirtyTransactionType;
import com.supplymind.foundation.storage.FileDigest;
import com.supplymind.foundation.storage.FileTransactionTarget;
import com.supplymind.foundation.storage.ManifestFactory;
import com.supplymind.foundation.storage.ManifestVerifier;
import com.supplymind.foundation.storage.StorageException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * D3-T05 immutable source-level import file store. The receipt (complete original file bytes)
 * is written before any decode/parse; re-persisting the same importId is an idempotent replay
 * that never rewrites the existing evidence.
 */
public final class LocalImportFileStore {

    private final DataRoot dataRoot;
    private final AtomicFileStore fileStore;
    private final Clock clock;

    public LocalImportFileStore(DataRoot dataRoot, AtomicFileStore fileStore, Clock clock) {
        this.dataRoot = Objects.requireNonNull(dataRoot, "dataRoot");
        this.fileStore = Objects.requireNonNull(fileStore, "fileStore");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public StoredImportFile store(LocalImportReceiptV1 receipt) {
        Objects.requireNonNull(receipt, "receipt");
        String ref = receipt.importRef();
        Path dataPath = dataRoot.resolveDataRef(ref);
        Path manifestPath = dataRoot.resolveDataRef(DataPaths.manifestRef(ref));
        byte[] dataBytes = JsonV1Codec.encodeFile(receipt);
        OffsetDateTime now = OffsetDateTime.now(clock);

        if (Files.isRegularFile(dataPath)) {
            boolean manifestValid = ManifestVerifier.matches(
                    dataRoot, ref, dataPath, manifestPath, List.of(receipt.importId()));
            if (FileDigest.bytesEqual(dataPath, dataBytes)) {
                if (!manifestValid) {
                    byte[] repaired = reusableOrRebuiltManifest(ref, dataBytes, List.of(receipt.importId()), now);
                    fileStore.commit("import-manifest-repair", DirtyTransactionType.SINGLE_FILE, now,
                            List.of(new FileTransactionTarget(
                                    DirtyTargetRole.BUSINESS_FILE, ref, dataBytes, repaired, false)));
                }
                return new StoredImportFile(ref, FileDigest.sha256(dataBytes));
            }
            if (manifestValid) {
                return new StoredImportFile(ref, FileDigest.sha256(dataPath));
            }
            throw new StorageException("Existing import file bytes differ and its manifest is invalid: " + ref);
        }

        byte[] manifestBytes = reusableOrRebuiltManifest(ref, dataBytes, List.of(receipt.importId()), now);
        fileStore.commit("import-file-" + receipt.importId(), DirtyTransactionType.SINGLE_FILE, now,
                List.of(new FileTransactionTarget(
                        DirtyTargetRole.BUSINESS_FILE, ref, dataBytes, manifestBytes, true)));
        return new StoredImportFile(ref, FileDigest.sha256(dataBytes));
    }

    private byte[] reusableOrRebuiltManifest(
            String dataRef, byte[] dataBytes, List<String> sourceRunIds, OffsetDateTime now
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

    public record StoredImportFile(String importRef, String importFileSha256) {
    }
}
