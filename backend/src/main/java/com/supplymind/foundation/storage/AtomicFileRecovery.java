package com.supplymind.foundation.storage;

import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.LifecycleTimelineV1;
import com.supplymind.foundation.model.ManifestV1;
import com.supplymind.foundation.model.QuarantineProjectionV1;
import com.supplymind.foundation.model.RawReceiptV1;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Startup completion for canonical dirty transactions. It never adopts an
 * ordinary tmp/bak because this entry point receives only a validated
 * DirtyMarkerV1 restored by {@link DirtyMarkerRecovery}.
 */
public final class AtomicFileRecovery {

    private final DataRoot dataRoot;
    private final DirtyMarkerCodec markerCodec;
    private final Clock clock;
    private final ManifestDerivedFieldsVerifier manifestDerivedFieldsVerifier;

    public AtomicFileRecovery(DataRoot dataRoot, DirtyMarkerCodec markerCodec, Clock clock) {
        this.dataRoot = Objects.requireNonNull(dataRoot, "dataRoot");
        this.markerCodec = Objects.requireNonNull(markerCodec, "markerCodec");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.manifestDerivedFieldsVerifier = new ManifestDerivedFieldsVerifier(this.dataRoot);
    }

    /** Restores marker candidates first, then deterministically finishes each recoverable target. */
    public List<DirtyMarkerV1> recoverAll() {
        DirtyMarkerRecovery markerRecovery = new DirtyMarkerRecovery(markerCodec);
        List<DirtyMarkerV1> markers = markerRecovery.recoverCanonicalMarkers(dataRoot);
        List<DirtyMarkerV1> completed = new ArrayList<>(markers.size());
        for (DirtyMarkerV1 marker : markers) {
            completed.add(recover(marker));
        }
        return List.copyOf(completed);
    }

    public DirtyMarkerV1 recover(DirtyMarkerV1 original) {
        DirtyMarkerV1 marker = Objects.requireNonNull(original, "marker");
        for (DirtyTargetV1 target : marker.targets()) {
            ensureData(target, marker);
            if (target.targetPhase() == DirtyTargetPhase.PREPARED) {
                marker = marker.advanceTarget(target.order(), DirtyTargetPhase.DATA_COMMITTED);
                persistNextMarker(marker);
            }

            ensureManifest(target, marker.transactionId());
            DirtyTargetPhase currentPhase = marker.targets().get(target.order() - 1).targetPhase();
            if (currentPhase == DirtyTargetPhase.DATA_COMMITTED) {
                marker = marker.advanceTarget(target.order(), DirtyTargetPhase.MANIFEST_COMMITTED);
                persistNextMarker(marker);
            }
        }
        if (marker.transactionPhase() == DirtyTransactionPhase.OPEN) {
            marker = marker.commit();
            persistNextMarker(marker);
        }
        verifyComplete(marker);
        cleanup(marker);
        return marker;
    }

    private void ensureData(DirtyTargetV1 target, DirtyMarkerV1 marker) {
        String transactionId = marker.transactionId();
        Path data = dataRoot.resolveDataRef(target.dataRef());
        Path temporary = adjacent(data, transactionId, true);
        Path backup = adjacent(data, transactionId, false);
        if (hasExpectedData(target, data)) {
            return;
        }
        if (canRecoverConfigActiveFromCommittedHistory(target, marker, data, temporary, backup)) {
            return;
        }
        if (Files.exists(data) && Files.isRegularFile(data)
                && hasVerifiedOldBackup(target, backup) && !Files.exists(temporary)) {
            // Keep the corrupt canonical bytes transaction-attributable. The
            // old business file can then be atomically restored, but this
            // transaction stays dirty and fails closed for manual review.
            AtomicMoveSupport.moveToEmptyTarget(data, temporary);
            AtomicMoveSupport.moveToEmptyTarget(backup, data);
            throw new StorageException("Transaction " + transactionId + " restored old data for " + target.dataRef()
                    + " after the committed target could not be proven; manual review is required");
        }
        if (!Files.exists(data) && hasExpectedData(target, temporary)) {
            AtomicMoveSupport.moveToEmptyTarget(temporary, data);
            return;
        }
        if (!Files.exists(data) && hasVerifiedOldBackup(target, backup)) {
            // No safe new-data material remains. Restore the known old target,
            // then retain the marker and fail closed rather than guessing.
            AtomicMoveSupport.moveToEmptyTarget(backup, data);
            throw new StorageException("Transaction " + transactionId + " rolled back old data for " + target.dataRef()
                    + " because expected new bytes are unavailable; manual review is required");
        }
        throw new StorageException("Recovery cannot prove complete data bytes for " + target.dataRef()
                + "; no normal tmp/bak is adopted without marker evidence");
    }

    /**
     * A CONFIG_ACTIVATION marker commits the history target before the active
     * target, and AtomicFileStore requires their exact business bytes to be
     * identical. Therefore a crash after history is fully committed but before
     * active data begins can deterministically finish activation from the
     * marker-proven immutable history snapshot.
     */
    private boolean canRecoverConfigActiveFromCommittedHistory(
            DirtyTargetV1 active,
            DirtyMarkerV1 marker,
            Path activeData,
            Path activeTemporary,
            Path activeBackup
    ) {
        if (marker.transactionType() != DirtyTransactionType.CONFIG_ACTIVATION
                || active.role() != DirtyTargetRole.CONFIG_ACTIVE
                || active.order() != 2
                || active.targetPhase() != DirtyTargetPhase.PREPARED
                || marker.targets().size() != 2
                || Files.exists(activeTemporary)) {
            return false;
        }
        DirtyTargetV1 history = marker.targets().get(0);
        if (history.role() != DirtyTargetRole.CONFIG_HISTORY
                || history.targetPhase() != DirtyTargetPhase.MANIFEST_COMMITTED
                || !history.expectedFileSha256().equals(active.expectedFileSha256())) {
            return false;
        }
        Path historyData = dataRoot.resolveDataRef(history.dataRef());
        Path historyManifest = dataRoot.resolveDataRef(history.manifestRef());
        if (!hasExpectedData(history, historyData)
                || !ManifestVerifier.matches(dataRoot, history.dataRef(), historyData, historyManifest, List.of())) {
            throw new StorageException("CONFIG_ACTIVATION history cannot prove active recovery bytes: "
                    + history.dataRef());
        }
        if (Files.exists(activeData)) {
            if (active.oldFileSha256() == null || !active.oldFileSha256().equals(FileDigest.sha256(activeData))
                    || Files.exists(activeBackup)) {
                throw new StorageException("CONFIG_ACTIVATION active target cannot safely replace its prior bytes: "
                        + active.dataRef());
            }
            AtomicMoveSupport.moveToEmptyTarget(activeData, activeBackup);
        } else if (Files.exists(activeBackup) && !hasVerifiedOldBackup(active, activeBackup)) {
            throw new StorageException("CONFIG_ACTIVATION active backup is not marker-proven: " + activeBackup);
        }
        byte[] historyBytes = readBytes(historyData);
        FileDigest.writeCreateNewAndForce(activeTemporary, historyBytes);
        if (!hasExpectedData(active, activeTemporary)) {
            throw new StorageException("CONFIG_ACTIVATION reconstructed active tmp did not validate: " + activeTemporary);
        }
        AtomicMoveSupport.moveToEmptyTarget(activeTemporary, activeData);
        return true;
    }
    private boolean hasExpectedData(DirtyTargetV1 target, Path path) {
        if (!Files.isRegularFile(path) || !target.expectedFileSha256().equals(FileDigest.sha256(path))) {
            return false;
        }
        StorageSchemaVerifier.verifyData(target.dataRef(), readBytes(path));
        return true;
    }

    private boolean hasVerifiedOldBackup(DirtyTargetV1 target, Path backup) {
        if (target.oldFileSha256() == null || !Files.isRegularFile(backup)
                || !target.oldFileSha256().equals(FileDigest.sha256(backup))) {
            return false;
        }
        StorageSchemaVerifier.verifyData(target.dataRef(), readBytes(backup));
        return true;
    }

    private void ensureManifest(DirtyTargetV1 target, String transactionId) {
        Path data = dataRoot.resolveDataRef(target.dataRef());
        requireHash(data, target.expectedFileSha256(), "manifest recovery data");
        Path manifest = dataRoot.resolveDataRef(target.manifestRef());
        if (ManifestVerifier.matches(dataRoot, target.dataRef(), data, manifest)) {
            return;
        }
        Path temporary = adjacent(manifest, transactionId, true);
        if (Files.exists(temporary) && ManifestVerifier.matches(dataRoot, target.dataRef(), data, temporary)) {
            installRecoveredManifest(manifest, temporary, transactionId);
            return;
        }
        if (Files.exists(temporary)) {
            throw new StorageException("Recovery manifest tmp cannot be proven to match target data: " + temporary);
        }
        Path backup = adjacent(manifest, transactionId, false);
        if (!Files.exists(manifest) && Files.exists(backup) && ManifestVerifier.matches(dataRoot, target.dataRef(), data, backup)) {
            AtomicMoveSupport.moveToEmptyTarget(backup, manifest);
            return;
        }

        byte[] rebuilt = rebuildManifest(target.dataRef(), data);
        FileDigest.writeCreateNewAndForce(temporary, rebuilt);
        if (!ManifestVerifier.matches(dataRoot, target.dataRef(), data, temporary)) {
            throw new StorageException("Deterministically rebuilt manifest did not validate: " + temporary);
        }
        installRecoveredManifest(manifest, temporary, transactionId);
    }

    /**
     * A manifest is only derived integrity metadata, so a marker-proven,
     * schema-valid business file can deterministically repair a missing or
     * stale manifest. Replacement still follows the fixed same-directory
     * tmp/bak protocol; no provider-specific replace-existing move is used.
     */
    private void installRecoveredManifest(Path manifest, Path temporary, String transactionId) {
        if (Files.exists(manifest)) {
            Path backup = adjacent(manifest, transactionId, false);
            if (Files.exists(backup)) {
                throw new StorageException("Manifest recovery cannot replace a stale manifest while its transaction "
                        + "backup is still present: " + backup);
            }
            AtomicMoveSupport.moveToEmptyTarget(manifest, backup);
        }
        AtomicMoveSupport.moveToEmptyTarget(temporary, manifest);
    }

    private byte[] rebuildManifest(String dataRef, Path data) {
        byte[] dataBytes = readBytes(data);
        ManifestV1 rebuilt = manifestDerivedFieldsVerifier.derive(dataRef, dataBytes, OffsetDateTime.now(clock));
        return JsonV1Codec.encodeFile(rebuilt);
    }

    private void persistNextMarker(DirtyMarkerV1 marker) {
        Path canonical = dataRoot.resolveDataRef(DataPaths.dirtyMarkerRef(marker.transactionId()));
        Path temporary = dataRoot.resolveInternalRelative(DataPaths.dirtyMarkerTemporaryRef(marker.transactionId()));
        Path backup = dataRoot.resolveInternalRelative(DataPaths.dirtyMarkerBackupRef(marker.transactionId()));
        if (!Files.exists(canonical) || Files.exists(temporary)) {
            throw new StorageException("DirtyMarker recovery persistence has ambiguous marker candidates for "
                    + marker.transactionId());
        }
        FileDigest.writeCreateNewAndForce(temporary, markerCodec.encode(marker));
        DirtyMarkerV1 decoded = markerCodec.decode(readBytes(temporary));
        if (!decoded.equals(marker)) {
            throw new StorageException("Recovered DirtyMarker revision did not round-trip");
        }
        clearVerifiedPreviousMarkerBackup(canonical, backup, marker);
        AtomicMoveSupport.moveToEmptyTarget(canonical, backup);
        AtomicMoveSupport.moveToEmptyTarget(temporary, canonical);
    }

    /** The next revision is forced first; only then may a proven prior backup be reused. */
    private void clearVerifiedPreviousMarkerBackup(Path canonical, Path backup, DirtyMarkerV1 next) {
        DirtyMarkerV1 current = markerCodec.decode(readBytes(canonical));
        if (!next.isDirectLegalSuccessorOf(current)) {
            throw new StorageException("Recovered DirtyMarker next revision is not a direct successor of canonical state");
        }
        if (!Files.exists(backup)) {
            return;
        }
        DirtyMarkerV1 previous = markerCodec.decode(readBytes(backup));
        if (!current.equals(previous) && !current.isDirectLegalSuccessorOf(previous)) {
            throw new StorageException("Recovered DirtyMarker backup is not a verified prior revision: " + backup);
        }
        deleteIfPresent(backup);
    }

    private void verifyComplete(DirtyMarkerV1 marker) {
        if (marker.transactionPhase() != DirtyTransactionPhase.COMMITTED) {
            throw new StorageException("Recovered transaction remains open: " + marker.transactionId());
        }
        for (DirtyTargetV1 target : marker.targets()) {
            Path data = dataRoot.resolveDataRef(target.dataRef());
            Path manifest = dataRoot.resolveDataRef(target.manifestRef());
            requireHash(data, target.expectedFileSha256(), "completed recovery data");
            if (!ManifestVerifier.matches(dataRoot, target.dataRef(), data, manifest)) {
                throw new StorageException("Completed recovery manifest is invalid: " + manifest);
            }
        }
    }

    private void cleanup(DirtyMarkerV1 marker) {
        for (DirtyTargetV1 target : marker.targets()) {
            Path data = dataRoot.resolveDataRef(target.dataRef());
            Path manifest = dataRoot.resolveDataRef(target.manifestRef());
            deleteIfPresent(adjacent(data, marker.transactionId(), true));
            deleteIfPresent(adjacent(data, marker.transactionId(), false));
            deleteIfPresent(adjacent(manifest, marker.transactionId(), true));
            deleteIfPresent(adjacent(manifest, marker.transactionId(), false));
        }
        deleteIfPresent(dataRoot.resolveInternalRelative(DataPaths.dirtyMarkerTemporaryRef(marker.transactionId())));
        deleteIfPresent(dataRoot.resolveInternalRelative(DataPaths.dirtyMarkerBackupRef(marker.transactionId())));
        deleteIfPresent(dataRoot.resolveDataRef(DataPaths.dirtyMarkerRef(marker.transactionId())));
    }

    private Path adjacent(Path target, String transactionId, boolean temporary) {
        String fileName = target.getFileName().toString();
        return target.resolveSibling(temporary
                ? DataPaths.adjacentTemporaryFileName(fileName, transactionId)
                : DataPaths.adjacentBackupFileName(fileName, transactionId));
    }

    private void requireHash(Path path, String expected, String context) {
        if (!Files.isRegularFile(path) || !expected.equals(FileDigest.sha256(path))) {
            throw new StorageException("SHA-256 check failed for " + context + ": " + path);
        }
    }

    private byte[] readBytes(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new StorageException("Unable to read recovery file " + path, exception);
        }
    }

    private void deleteIfPresent(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            throw new StorageException("Unable to remove completed transaction artifact " + path, exception);
        }
    }
}
