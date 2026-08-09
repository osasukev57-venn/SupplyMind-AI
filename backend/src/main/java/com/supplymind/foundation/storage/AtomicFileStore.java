package com.supplymind.foundation.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Strict two-physical-file (data then manifest) transactional commit engine.
 * It writes no business data until an OPEN DirtyMarkerV1 has been forced.
 */
public final class AtomicFileStore {

    private static final ConcurrentHashMap<String, ReentrantLock> WRITE_LOCKS = new ConcurrentHashMap<>();

    private final DataRoot dataRoot;
    private final DirtyMarkerCodec markerCodec;
    private final ManifestDerivedFieldsVerifier manifestDerivedFieldsVerifier;

    public AtomicFileStore(DataRoot dataRoot, DirtyMarkerCodec markerCodec) {
        this.dataRoot = Objects.requireNonNull(dataRoot, "dataRoot");
        this.markerCodec = Objects.requireNonNull(markerCodec, "markerCodec");
        this.manifestDerivedFieldsVerifier = new ManifestDerivedFieldsVerifier(this.dataRoot);
    }

    /**
     * Commits data and manifests in target order. Any failure intentionally
     * leaves the canonical DirtyMarkerV1 and transaction-attributable files
     * behind for fail-closed startup recovery.
     */
    public DirtyMarkerV1 commit(
            String transactionId,
            DirtyTransactionType transactionType,
            OffsetDateTime createdAt,
            List<FileTransactionTarget> targets
    ) {
        Objects.requireNonNull(transactionType, "transactionType");
        Objects.requireNonNull(createdAt, "createdAt");
        DataPaths.requireIdentifier(transactionId, "transactionId");
        List<FileTransactionTarget> canonicalTargets = canonicalizeTargets(transactionType, targets);
        ReentrantLock lock = WRITE_LOCKS.computeIfAbsent(lockKey(canonicalTargets), ignored -> new ReentrantLock());
        lock.lock();
        try {
            return commitLocked(transactionId, transactionType, createdAt, canonicalTargets);
        } finally {
            lock.unlock();
        }
    }

    private DirtyMarkerV1 commitLocked(
            String transactionId,
            DirtyTransactionType transactionType,
            OffsetDateTime createdAt,
            List<FileTransactionTarget> targets
    ) {
        Path markerPath = dataRoot.resolveDataRef(DataPaths.dirtyMarkerRef(transactionId));
        if (Files.exists(markerPath)) {
            throw new StorageException("A canonical DirtyMarker already exists; startup recovery is required first: " + markerPath);
        }

        List<DirtyTargetV1> markerTargets = new ArrayList<>(targets.size());
        for (int index = 0; index < targets.size(); index++) {
            FileTransactionTarget target = targets.get(index);
            verifySuppliedTarget(target);
            Path dataPath = dataRoot.resolveDataRef(target.dataRef());
            String oldHash = Files.exists(dataPath) ? FileDigest.sha256(dataPath) : null;
            String incomingHash = FileDigest.sha256(target.dataBytes());
            if (target.immutableData() && oldHash != null && !oldHash.equals(incomingHash)) {
                throw new ImmutableFileConflictException(target.dataRef(), oldHash, incomingHash);
            }
            markerTargets.add(new DirtyTargetV1(index + 1, target.role(), target.dataRef(), target.manifestRef(),
                    incomingHash, oldHash, DirtyTargetPhase.PREPARED));
        }

        DirtyMarkerV1 marker = DirtyMarkerV1.open(transactionId, transactionType, createdAt, markerTargets);
        FileDigest.writeCreateNewAndForce(markerPath, markerCodec.encode(marker));

        for (int index = 0; index < targets.size(); index++) {
            FileTransactionTarget target = targets.get(index);
            int order = index + 1;
            commitData(target, transactionId, marker.targets().get(index).expectedFileSha256());
            marker = marker.advanceTarget(order, DirtyTargetPhase.DATA_COMMITTED);
            persistNextMarker(marker);

            commitManifest(target, transactionId);
            marker = marker.advanceTarget(order, DirtyTargetPhase.MANIFEST_COMMITTED);
            persistNextMarker(marker);
        }

        marker = marker.commit();
        persistNextMarker(marker);
        verifyCommitted(marker, targets);
        cleanupCompletedTransaction(marker, targets);
        return marker;
    }

    private void commitData(FileTransactionTarget target, String transactionId, String expectedHash) {
        Path finalPath = dataRoot.resolveDataRef(target.dataRef());
        if (Files.exists(finalPath) && expectedHash.equals(FileDigest.sha256(finalPath))) {
            // Existing immutable raw/history or an idempotent mutable retry: never rewrite equal bytes.
            StorageSchemaVerifier.verifyData(target.dataRef(), readBytes(finalPath));
            return;
        }
        if (target.immutableData() && Files.exists(finalPath)) {
            throw new ImmutableFileConflictException(target.dataRef(), FileDigest.sha256(finalPath), expectedHash);
        }

        Path temporary = adjacent(finalPath, transactionId, true);
        writeOrVerifyTemporary(temporary, target.dataBytes());
        requireHash(temporary, expectedHash, "data tmp");
        StorageSchemaVerifier.verifyData(target.dataRef(), readBytes(temporary));
        if (Files.exists(finalPath)) {
            Path backup = adjacent(finalPath, transactionId, false);
            requireAbsentOrVerifiedBackup(backup, finalPath, transactionId);
            AtomicMoveSupport.moveToEmptyTarget(finalPath, backup);
        }
        AtomicMoveSupport.moveToEmptyTarget(temporary, finalPath);
        requireHash(finalPath, expectedHash, "committed data");
        StorageSchemaVerifier.verifyData(target.dataRef(), readBytes(finalPath));
    }

    private void commitManifest(FileTransactionTarget target, String transactionId) {
        verifySuppliedManifest(target);
        Path finalPath = dataRoot.resolveDataRef(target.manifestRef());
        if (Files.exists(finalPath) && FileDigest.bytesEqual(finalPath, target.manifestBytes())) {
            return;
        }
        Path temporary = adjacent(finalPath, transactionId, true);
        writeOrVerifyTemporary(temporary, target.manifestBytes());
        if (Files.exists(finalPath)) {
            Path backup = adjacent(finalPath, transactionId, false);
            requireAbsentOrVerifiedBackup(backup, finalPath, transactionId);
            AtomicMoveSupport.moveToEmptyTarget(finalPath, backup);
        }
        AtomicMoveSupport.moveToEmptyTarget(temporary, finalPath);
        Path dataPath = dataRoot.resolveDataRef(target.dataRef());
        if (!FileDigest.bytesEqual(finalPath, target.manifestBytes())
                || !ManifestVerifier.matches(dataRoot, target.dataRef(), dataPath, finalPath)) {
            throw new StorageException("Committed manifest bytes failed verification: " + finalPath);
        }
    }

    private void persistNextMarker(DirtyMarkerV1 marker) {
        Path canonical = dataRoot.resolveDataRef(DataPaths.dirtyMarkerRef(marker.transactionId()));
        Path temporary = dataRoot.resolveInternalRelative(DataPaths.dirtyMarkerTemporaryRef(marker.transactionId()));
        Path backup = dataRoot.resolveInternalRelative(DataPaths.dirtyMarkerBackupRef(marker.transactionId()));
        if (!Files.exists(canonical)) {
            throw new StorageException("DirtyMarker canonical file disappeared during transaction: " + canonical);
        }
        if (Files.exists(temporary)) {
            throw new StorageException("Existing marker tmp requires bootstrap recovery before another revision: " + temporary);
        }
        FileDigest.writeCreateNewAndForce(temporary, markerCodec.encode(marker));
        DirtyMarkerV1 decoded = markerCodec.decode(readBytes(temporary));
        if (!decoded.equals(marker)) {
            throw new StorageException("DirtyMarker tmp did not round-trip before atomic move");
        }
        clearVerifiedPreviousMarkerBackup(canonical, backup, marker);
        AtomicMoveSupport.moveToEmptyTarget(canonical, backup);
        AtomicMoveSupport.moveToEmptyTarget(temporary, canonical);
    }

    /** The next revision is forced first; only then may a proven prior backup be reused. */
    private void clearVerifiedPreviousMarkerBackup(Path canonical, Path backup, DirtyMarkerV1 next) {
        DirtyMarkerV1 current = markerCodec.decode(readBytes(canonical));
        if (!next.isDirectLegalSuccessorOf(current)) {
            throw new StorageException("DirtyMarker next revision is not a direct successor of canonical state");
        }
        if (!Files.exists(backup)) {
            return;
        }
        DirtyMarkerV1 previous = markerCodec.decode(readBytes(backup));
        if (!current.equals(previous) && !current.isDirectLegalSuccessorOf(previous)) {
            throw new StorageException("DirtyMarker backup is not a verified prior revision: " + backup);
        }
        deleteKnownTransactionFile(backup);
    }

    private void verifyCommitted(DirtyMarkerV1 marker, List<FileTransactionTarget> targets) {
        if (marker.transactionPhase() != DirtyTransactionPhase.COMMITTED) {
            throw new StorageException("Cannot clean an uncommitted transaction");
        }
        for (int index = 0; index < targets.size(); index++) {
            FileTransactionTarget target = targets.get(index);
            DirtyTargetV1 markerTarget = marker.targets().get(index);
            Path data = dataRoot.resolveDataRef(target.dataRef());
            Path manifest = dataRoot.resolveDataRef(target.manifestRef());
            requireHash(data, markerTarget.expectedFileSha256(), "committed final data");
            StorageSchemaVerifier.verifyData(target.dataRef(), readBytes(data));
            if (!FileDigest.bytesEqual(manifest, target.manifestBytes())
                    || !ManifestVerifier.matches(dataRoot, target.dataRef(), data, manifest)) {
                throw new StorageException("Committed manifest changed before transaction cleanup: " + manifest);
            }
        }
    }

    private void cleanupCompletedTransaction(DirtyMarkerV1 marker, List<FileTransactionTarget> targets) {
        for (FileTransactionTarget target : targets) {
            deleteKnownTransactionFile(adjacent(dataRoot.resolveDataRef(target.dataRef()), marker.transactionId(), false));
            deleteKnownTransactionFile(adjacent(dataRoot.resolveDataRef(target.manifestRef()), marker.transactionId(), false));
        }
        deleteKnownTransactionFile(dataRoot.resolveInternalRelative(DataPaths.dirtyMarkerTemporaryRef(marker.transactionId())));
        deleteKnownTransactionFile(dataRoot.resolveInternalRelative(DataPaths.dirtyMarkerBackupRef(marker.transactionId())));
        deleteKnownTransactionFile(dataRoot.resolveDataRef(DataPaths.dirtyMarkerRef(marker.transactionId())));
    }

    private void writeOrVerifyTemporary(Path temporary, byte[] bytes) {
        if (Files.exists(temporary)) {
            if (!FileDigest.bytesEqual(temporary, bytes)) {
                throw new StorageException("Transaction tmp already exists with different bytes: " + temporary);
            }
            return;
        }
        FileDigest.writeCreateNewAndForce(temporary, bytes);
    }

    private void requireAbsentOrVerifiedBackup(Path backup, Path expectedOldFile, String transactionId) {
        if (!Files.exists(backup)) {
            return;
        }
        // A normal .bak may be adopted only inside the still-open transaction that owns it.
        // This method is only reached while its canonical DirtyMarker is still present.
        if (!backup.getFileName().toString().equals(DataPaths.adjacentBackupFileName(expectedOldFile.getFileName().toString(), transactionId))) {
            throw new StorageException("Unexpected backup filename: " + backup);
        }
        throw new StorageException("Existing target backup requires dirty-marker recovery before commit continues: " + backup);
    }

    private Path adjacent(Path target, String transactionId, boolean temporary) {
        String filename = target.getFileName().toString();
        String adjacent = temporary
                ? DataPaths.adjacentTemporaryFileName(filename, transactionId)
                : DataPaths.adjacentBackupFileName(filename, transactionId);
        return target.resolveSibling(adjacent);
    }

    private List<FileTransactionTarget> canonicalizeTargets(
            DirtyTransactionType type,
            List<FileTransactionTarget> targets
    ) {
        if (targets == null || targets.isEmpty()) {
            throw new StorageException("A file transaction must contain targets");
        }
        List<FileTransactionTarget> canonical = new ArrayList<>(targets);
        if (type == DirtyTransactionType.AGGREGATION_BATCH) {
            canonical.sort(Comparator.comparing(FileTransactionTarget::dataRef));
        }
        Set<String> dataRefs = new HashSet<>();
        for (FileTransactionTarget target : canonical) {
            if (!dataRefs.add(target.dataRef())) {
                throw new StorageException("A file transaction must not repeat a dataRef: " + target.dataRef());
            }
        }
        verifyTransactionWriteInvariants(type, canonical);
        // DirtyMarkerV1 validates exact target role/cardinality/order after this construction.
        return List.copyOf(canonical);
    }

    /**
     * Enforces the frozen write boundaries before a DirtyMarker is created.
     * Generic transactions may never bypass the config activation protocol.
     */
    private void verifyTransactionWriteInvariants(
            DirtyTransactionType transactionType,
            List<FileTransactionTarget> targets
    ) {
        boolean containsConfiguration = targets.stream().anyMatch(this::isConfigurationTarget);
        if (transactionType != DirtyTransactionType.CONFIG_ACTIVATION) {
            if (containsConfiguration) {
                throw new StorageException("config/history and config/monitor-series.json may only be written by CONFIG_ACTIVATION");
            }
            return;
        }

        if (targets.size() != 2
                || targets.get(0).role() != DirtyTargetRole.CONFIG_HISTORY
                || targets.get(1).role() != DirtyTargetRole.CONFIG_ACTIVE) {
            throw new StorageException("CONFIG_ACTIVATION requires CONFIG_HISTORY order 1 and CONFIG_ACTIVE order 2");
        }
        FileTransactionTarget history = targets.get(0);
        FileTransactionTarget active = targets.get(1);
        if (!history.dataRef().startsWith("config/history/")
                || !active.dataRef().equals(DataPaths.configActiveRef())) {
            throw new StorageException("CONFIG_ACTIVATION must target config/history/<configVersion>.json then config/monitor-series.json");
        }
        if (!Arrays.equals(history.dataBytes(), active.dataBytes())) {
            throw new StorageException("CONFIG_ACTIVATION history and active configuration bytes must be identical");
        }
        // This verifies the history filename/version relationship and validates both documents before marker creation.
        StorageSchemaVerifier.verifyData(history.dataRef(), history.dataBytes());
        StorageSchemaVerifier.verifyData(active.dataRef(), active.dataBytes());
    }

    private boolean isConfigurationTarget(FileTransactionTarget target) {
        return target.dataRef().equals(DataPaths.configActiveRef()) || target.dataRef().startsWith("config/history/");
    }

    private String lockKey(List<FileTransactionTarget> targets) {
        return dataRoot.path() + "|" + targets.stream().map(FileTransactionTarget::dataRef).sorted()
                .reduce("", (left, right) -> left + "|" + right);
    }

    private void verifySuppliedTarget(FileTransactionTarget target) {
        StorageSchemaVerifier.verifyData(target.dataRef(), target.dataBytes());
        verifySuppliedManifest(target);
    }

    private void verifySuppliedManifest(FileTransactionTarget target) {
        com.supplymind.foundation.model.ManifestV1 manifest = com.supplymind.foundation.codec.JsonV1Codec.decodeFile(
                target.manifestBytes(), com.supplymind.foundation.model.ManifestV1.class);
        Path targetPath = dataRoot.resolveDataRef(target.dataRef());
        if (!manifest.fileName().equals(targetPath.getFileName().toString())
                || !manifest.fileSha256().equals(FileDigest.sha256(target.dataBytes()))
                || manifest.byteLength() != target.dataBytes().length) {
            throw new StorageException("Supplied manifest is not a derived integrity record for " + target.dataRef());
        }
        manifestDerivedFieldsVerifier.verify(target.dataRef(), target.dataBytes(), manifest);
    }
    private void requireHash(Path path, String expectedHash, String context) {
        if (!Files.exists(path) || !expectedHash.equals(FileDigest.sha256(path))) {
            throw new StorageException("SHA-256 verification failed for " + context + ": " + path);
        }
    }

    private byte[] readBytes(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new StorageException("Unable to read " + path, exception);
        }
    }

    private void deleteKnownTransactionFile(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            throw new StorageException("Unable to clean completed transaction file " + path, exception);
        }
    }
}
