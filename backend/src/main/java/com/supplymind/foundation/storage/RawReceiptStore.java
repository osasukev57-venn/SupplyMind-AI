package com.supplymind.foundation.storage;

import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.ManifestV1;
import com.supplymind.foundation.model.Mode;
import com.supplymind.foundation.model.MonitorSeriesConfigV1;
import com.supplymind.foundation.model.MonitorSeriesItemV1;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.RawReceiptV1;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/** Immutable per-item raw persistence with same-hash idempotency and conflict evidence. */
public final class RawReceiptStore {

    private final DataRoot dataRoot;
    private final AtomicFileStore fileStore;
    private final Clock clock;

    public RawReceiptStore(DataRoot dataRoot, AtomicFileStore fileStore, Clock clock) {
        this.dataRoot = Objects.requireNonNull(dataRoot, "dataRoot");
        this.fileStore = Objects.requireNonNull(fileStore, "fileStore");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public StoredRawReceipt store(RawReceiptV1 receipt) {
        Objects.requireNonNull(receipt, "receipt");
        requireResolvableConfigVersion(receipt);
        OffsetDateTime now = OffsetDateTime.now(clock);
        byte[] rawBytes = JsonV1Codec.encodeFile(receipt);
        Path rawPath = dataRoot.resolveDataRef(receipt.rawRef());
        Path manifestPath = dataRoot.resolveDataRef(DataPaths.manifestRef(receipt.rawRef()));
        if (Files.isRegularFile(rawPath) && FileDigest.bytesEqual(rawPath, rawBytes)
                && ManifestVerifier.matches(dataRoot, receipt.rawRef(), rawPath, manifestPath, List.of(receipt.runId()))) {
            // An exact immutable raw plus a valid derived manifest is a true no-op.
            return new StoredRawReceipt(receipt.rawRef(), FileDigest.sha256(rawBytes));
        }

        byte[] manifestBytes = reusableOrRebuiltManifest(receipt.rawRef(), rawBytes, List.of(receipt.runId()), now);
        FileTransactionTarget target = new FileTransactionTarget(
                DirtyTargetRole.BUSINESS_FILE,
                receipt.rawRef(),
                rawBytes,
                manifestBytes,
                true
        );
        try {
            fileStore.commit(transactionId("raw"), DirtyTransactionType.SINGLE_FILE, now, List.of(target));
            return new StoredRawReceipt(receipt.rawRef(), FileDigest.sha256(rawBytes));
        } catch (ImmutableFileConflictException conflict) {
            String conflictId = transactionId("raw-conflict");
            String conflictRef = DataPaths.rawConflictRef(receipt.itemId(), receipt.receivedAt(), receipt.runId(), conflictId);
            RawConflictEvidenceV1 evidence = new RawConflictEvidenceV1(
                    "1.0",
                    conflictId,
                    receipt.itemId(),
                    receipt.runId(),
                    receipt.rawRef(),
                    conflict.existingFileSha256(),
                    conflict.incomingFileSha256(),
                    receipt,
                    now
            );
            byte[] evidenceBytes = JsonV1Codec.encodeFile(evidence);
            byte[] evidenceManifestBytes = reusableOrRebuiltManifest(
                    conflictRef, evidenceBytes, List.of(receipt.runId()), now);
            fileStore.commit(transactionId("raw-conflict-write"), DirtyTransactionType.SINGLE_FILE,
                    now, List.of(new FileTransactionTarget(
                            DirtyTargetRole.BUSINESS_FILE,
                            conflictRef,
                            evidenceBytes,
                            evidenceManifestBytes,
                            true
                    )));
            throw new RawReceiptConflictException(conflictRef, conflict);
        }
    }

    /**
     * DEC-056 business-key lookup: the formal per-item raw record for one provider+item+
     * businessDate, used to decide IDEMPOTENT REPLAY (same payloadSha256) versus CONFLICT
     * (different payloadSha256) before any incoming write.
     */
    public Optional<RawReceiptV1> findByBusinessKey(
            Mode mode, ProviderType providerType, String itemId, String businessDate
    ) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(providerType, "providerType");
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(businessDate, "businessDate");
        Path itemDir = dataRoot.resolveInternalRelative(
                "raw/" + mode.wireValue() + "/" + providerType.wireValue() + "/" + itemId);
        if (!Files.isDirectory(itemDir)) {
            return Optional.empty();
        }
        try (Stream<Path> walk = Files.walk(itemDir)) {
            List<Path> candidates = walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> !path.getFileName().toString().endsWith(".manifest.json"))
                    .toList();
            for (Path candidate : candidates) {
                try {
                    RawReceiptV1 receipt = JsonV1Codec.decodeFile(
                            Files.readAllBytes(candidate), RawReceiptV1.class);
                    if (businessDate.equals(receipt.sourceBusinessDate())) {
                        return Optional.of(receipt);
                    }
                } catch (IOException | RuntimeException ignored) {
                    // A corrupt candidate is not a business-key match; the store never rewrites it here.
                }
            }
        } catch (IOException exception) {
            throw new StorageException("Unable to scan raw business keys under " + itemDir, exception);
        }
        return Optional.empty();
    }

    /**
     * DEC-056 conflict evidence for the same business key with a different official payload:
     * the original formal raw is preserved untouched and a frozen RawConflictEvidenceV1 with
     * its adjacent manifest is committed before the caller fails closed. Returns the evidence ref.
     */
    public String writeBusinessKeyConflictEvidence(
            RawReceiptV1 incoming, RawReceiptV1 existing, OffsetDateTime now
    ) {
        Objects.requireNonNull(incoming, "incoming");
        Objects.requireNonNull(existing, "existing");
        Objects.requireNonNull(now, "now");
        String conflictId = transactionId("raw-conflict");
        String conflictRef = DataPaths.rawConflictRef(
                incoming.itemId(), incoming.receivedAt(), incoming.runId(), conflictId);
        Path existingRawPath = dataRoot.resolveDataRef(existing.rawRef());
        if (!Files.isRegularFile(existingRawPath)) {
            throw new StorageException("Business-key conflict references a missing formal raw: " + existing.rawRef());
        }
        RawConflictEvidenceV1 evidence = new RawConflictEvidenceV1(
                "1.0",
                conflictId,
                incoming.itemId(),
                incoming.runId(),
                existing.rawRef(),
                FileDigest.sha256(existingRawPath),
                FileDigest.sha256(JsonV1Codec.encodeFile(incoming)),
                incoming,
                now
        );
        byte[] evidenceBytes = JsonV1Codec.encodeFile(evidence);
        byte[] evidenceManifestBytes = reusableOrRebuiltManifest(
                conflictRef, evidenceBytes, List.of(incoming.runId()), now);
        fileStore.commit(transactionId("raw-conflict-write"), DirtyTransactionType.SINGLE_FILE,
                now, List.of(new FileTransactionTarget(
                        DirtyTargetRole.BUSINESS_FILE,
                        conflictRef,
                        evidenceBytes,
                        evidenceManifestBytes,
                        true
                )));
        return conflictRef;
    }

    private void requireResolvableConfigVersion(RawReceiptV1 receipt) {
        String historyRef = DataPaths.configHistoryRef(receipt.configVersion());
        Path historyPath = dataRoot.resolveDataRef(historyRef);
        Path historyManifest = dataRoot.resolveDataRef(DataPaths.manifestRef(historyRef));
        if (!Files.isRegularFile(historyPath) || !ManifestVerifier.matches(dataRoot, historyRef, historyPath, historyManifest, List.of())) {
            throw new StorageException("RawReceipt.configVersion has no valid immutable history snapshot: "
                    + receipt.configVersion());
        }
        try {
            MonitorSeriesConfigV1 history = JsonV1Codec.decodeFile(Files.readAllBytes(historyPath), MonitorSeriesConfigV1.class);
            if (history.configVersion() != receipt.configVersion()) {
                throw new StorageException("Config history filename/version mismatch for RawReceipt.configVersion "
                        + receipt.configVersion());
            }
            MonitorSeriesItemV1 item = history.requireItem(receipt.itemId());
            if (history.mode() != receipt.mode()
                    || item.providerType() != receipt.providerType()
                    || item.accessMethod() != receipt.accessMethod()
                    || !item.actualSourceName().equals(receipt.actualSourceName())) {
                throw new StorageException("RawReceipt source identity does not match its immutable configVersion snapshot");
            }
        } catch (IOException exception) {
            throw new StorageException("Unable to read config history for RawReceipt.configVersion " + receipt.configVersion(),
                    exception);
        }
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

    private static String transactionId(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    public record StoredRawReceipt(String rawRef, String rawFileSha256) {
    }
}