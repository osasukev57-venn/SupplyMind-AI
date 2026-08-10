package com.supplymind.foundation.storage;

import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.ManifestV1;
import com.supplymind.foundation.model.RawAcquisitionV1;
import com.supplymind.foundation.model.RawReceiptV1;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * DEC-056 enforced item Raw -&gt; source Raw traceability for external HTTP item receipts.
 * Before a formal RawReceiptV1 may be stored, the canonical source acquisition (derived from
 * the receipt acquisitionId via DataPaths) must exist with a COMMITTED, verifiable manifest,
 * and its acquisitionId/payload identity/source identity must match the item receipt exactly.
 * Any failure is fail-closed: the item raw is never stored. Non-HTTP providers are untouched.
 */
public final class RawAcquisitionLinkVerifier {

    private final DataRoot dataRoot;

    public RawAcquisitionLinkVerifier(DataRoot dataRoot) {
        this.dataRoot = Objects.requireNonNull(dataRoot, "dataRoot");
    }

    public void verify(RawReceiptV1 receipt) {
        Objects.requireNonNull(receipt, "receipt");
        if (!receipt.providerType().isExternalHttpProvider()) {
            return;
        }
        String canonicalRef = DataPaths.acquisitionRef(receipt.acquisitionId());
        if (receipt.acquisitionRef() == null || !receipt.acquisitionRef().equals(canonicalRef)) {
            throw new StorageException("RawReceiptV1.acquisitionRef must equal the canonical source acquisition path "
                    + canonicalRef);
        }
        Path acquisitionPath = dataRoot.resolveDataRef(canonicalRef);
        Path manifestPath = dataRoot.resolveDataRef(DataPaths.manifestRef(canonicalRef));
        if (!Files.isRegularFile(acquisitionPath)) {
            throw new StorageException("Source RawAcquisitionV1 is missing for " + canonicalRef);
        }
        if (!Files.isRegularFile(manifestPath)) {
            throw new StorageException("Source RawAcquisitionV1 manifest is missing for " + canonicalRef);
        }
        ManifestV1 manifest = JsonV1Codec.decodeFile(readAllBytes(manifestPath), ManifestV1.class);
        if (!ManifestV1.COMMITTED.equals(manifest.commitState())) {
            throw new StorageException("Source RawAcquisitionV1 manifest is not COMMITTED: " + canonicalRef);
        }
        if (!ManifestVerifier.matches(dataRoot, canonicalRef, acquisitionPath, manifestPath,
                List.of(receipt.acquisitionId()))) {
            throw new StorageException("Source RawAcquisitionV1 manifest does not verify: " + canonicalRef);
        }
        RawAcquisitionV1 acquisition = JsonV1Codec.decodeFile(readAllBytes(acquisitionPath), RawAcquisitionV1.class);
        if (!acquisition.acquisitionId().equals(receipt.acquisitionId())) {
            throw new StorageException("Source RawAcquisitionV1.acquisitionId must match the item RawReceiptV1.acquisitionId");
        }
        if (!acquisition.payloadSha256().equals(receipt.payloadSha256())) {
            throw new StorageException("Source RawAcquisitionV1 payload identity must match the item RawReceiptV1.payloadSha256");
        }
        if (acquisition.mode() != receipt.mode()
                || acquisition.providerType() != receipt.providerType()
                || acquisition.accessMethod() != receipt.accessMethod()
                || !acquisition.actualSourceName().equals(receipt.actualSourceName())
                || acquisition.configVersion() != receipt.configVersion()) {
            throw new StorageException("Source RawAcquisitionV1 source identity must match the item RawReceiptV1");
        }
        if (receipt.sourceUrl() != null && !receipt.sourceUrl().equals(acquisition.detailUrl())) {
            throw new StorageException("Source RawAcquisitionV1.detailUrl must match the item RawReceiptV1.sourceUrl");
        }
    }

    private static byte[] readAllBytes(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new StorageException("Unable to read " + path, exception);
        }
    }
}
