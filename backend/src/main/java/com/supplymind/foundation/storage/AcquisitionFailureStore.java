package com.supplymind.foundation.storage;
import com.supplymind.foundation.model.AcquisitionFailureEvidenceV1;
import com.supplymind.foundation.codec.JsonV1Codec;
import java.nio.file.Files;
import java.util.List;

/** Immutable attempt identity: identical save is a no-op; different bytes fail closed. */
public final class AcquisitionFailureStore {
    private final DataRoot root;
    private final AtomicFileStore files;
    public AcquisitionFailureStore(DataRoot root, AtomicFileStore files) { this.root=root; this.files=files; }
    public synchronized String store(AcquisitionFailureEvidenceV1 evidence) {
        String ref=evidence.evidenceRef(); byte[] bytes=JsonV1Codec.encodeFile(evidence);
        if (Files.exists(root.resolveDataRef(ref))) {
            if (!FileDigest.bytesEqual(root.resolveDataRef(ref), bytes) || !ManifestVerifier.matches(root, ref,
                    root.resolveDataRef(ref), root.resolveDataRef(DataPaths.manifestRef(ref)))) throw new StorageException("Failure evidence identity conflict or damage");
            return ref;
        }
        var manifest=ManifestFactory.json(ref, bytes, List.of(evidence.attemptId()), evidence.failedAt());
        files.commit("failure-"+evidence.attemptId(), DirtyTransactionType.SINGLE_FILE, evidence.failedAt(),
                List.of(new FileTransactionTarget(DirtyTargetRole.BUSINESS_FILE, ref, bytes, JsonV1Codec.encodeFile(manifest), true)));
        return ref;
    }
}
