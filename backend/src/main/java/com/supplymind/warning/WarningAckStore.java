package com.supplymind.warning;

import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.ManifestV1;
import com.supplymind.foundation.storage.AtomicFileStore;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.DirtyTargetRole;
import com.supplymind.foundation.storage.DirtyTransactionType;
import com.supplymind.foundation.storage.FileDigest;
import com.supplymind.foundation.storage.FileTransactionTarget;
import com.supplymind.foundation.storage.ImmutableFileConflictException;
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
import java.util.UUID;

/**
 * DEC-061 warning acknowledgement sidecar persistence. The original WarningRecordV1 is never
 * rewritten; an acknowledgement is a separate immutable sidecar at warning/YYYY-MM/
 * &lt;warningId&gt;.ack.json with its adjacent manifest. Rules: the original warning + manifest
 * MUST verify first; warningId/warningRef/warningFileSha256 MUST match; CREATE_NEW; an exact
 * byte-identical retry is idempotent; a different ack under the same warningId fails closed as
 * an immutable conflict; restarts restore the ACKNOWLEDGED state from the sidecar.
 */
public final class WarningAckStore {

    private final DataRoot dataRoot;
    private final AtomicFileStore fileStore;
    private final Clock clock;

    public WarningAckStore(DataRoot dataRoot, AtomicFileStore fileStore, Clock clock) {
        this.dataRoot = Objects.requireNonNull(dataRoot, "dataRoot");
        this.fileStore = Objects.requireNonNull(fileStore, "fileStore");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public boolean exists(String warningRef, String warningId) {
        String ackRef = ackRefOf(warningRef, warningId);
        return Files.isRegularFile(dataRoot.resolveDataRef(ackRef));
    }

    /**
     * M2: the SINGLE authoritative acknowledgement read/verification entry. It verifies ALL of:
     * 1) ack file + manifest; 2) decodes WarningAcknowledgementV1; 3) schema/status validity;
     * 4) ack.warningId == requested warningId; 5) ack.warningRef == canonical warning ref;
     * 6) original warning file exists; 7) original warning manifest verifies; 8) decodes the
     * original WarningRecordV1; 9) original warning.warningId matches; 10) recomputes the
     * original warning SHA-256; 11) equals ack.warningFileSha256 EXACTLY.
     *
     * <p>Any failure fails closed (StorageException) - a tampered sidecar or a tampered
     * original warning can NEVER surface as acknowledged=true. A missing ack file returns
     * {@code Optional.empty()} (the warning is genuinely not acknowledged yet).
     * Every caller (WarningQueryService, WarningController) MUST use this entry - no weaker
     * per-caller check exists.
     */
    public java.util.Optional<WarningAcknowledgementV1> readVerified(WarningRecordV1 warning) {
        Objects.requireNonNull(warning, "warning");
        String warningRef = DataPaths.warningRef(warning.warningMonth(), warning.warningId());
        String ackRef = ackRefOf(warningRef, warning.warningId());
        Path ackPath = dataRoot.resolveDataRef(ackRef);
        if (!Files.isRegularFile(ackPath)) {
            return java.util.Optional.empty();
        }
        Path ackManifest = dataRoot.resolveDataRef(DataPaths.manifestRef(ackRef));
        if (!ManifestVerifier.matches(dataRoot, ackRef, ackPath, ackManifest, List.of())) {
            throw new StorageException(
                    "Warning acknowledgement fails its manifest (tampered sidecar): " + warning.warningId());
        }
        WarningAcknowledgementV1 ack;
        try {
            ack = JsonV1Codec.decodeFile(Files.readAllBytes(ackPath), WarningAcknowledgementV1.class);
        } catch (IOException | RuntimeException decodeFailed) {
            throw new StorageException(
                    "Warning acknowledgement cannot be decoded (tampered sidecar): " + warning.warningId(), decodeFailed);
        }
        if (ack.status() != WarningAcknowledgementV1.AckStatus.ACKNOWLEDGED) {
            throw new StorageException(
                    "Warning acknowledgement has an invalid status (v1 only ACKNOWLEDGED): " + warning.warningId());
        }
        if (!warning.warningId().equals(ack.warningId())) {
            throw new StorageException(
                    "Warning acknowledgement warningId does not match the requested warning: " + warning.warningId());
        }
        if (!warningRef.equals(ack.warningRef())) {
            throw new StorageException(
                    "Warning acknowledgement warningRef does not match the canonical warning ref: " + warning.warningId());
        }
        Path warningPath = dataRoot.resolveDataRef(warningRef);
        Path warningManifest = dataRoot.resolveDataRef(DataPaths.manifestRef(warningRef));
        if (!Files.isRegularFile(warningPath)
                || !ManifestVerifier.matches(dataRoot, warningRef, warningPath, warningManifest, List.of())) {
            throw new StorageException(
                    "Original warning fails its manifest (tampered warning): " + warning.warningId());
        }
        WarningRecordV1 persisted;
        try {
            persisted = JsonV1Codec.decodeFile(Files.readAllBytes(warningPath), WarningRecordV1.class);
        } catch (IOException | RuntimeException decodeFailed) {
            throw new StorageException(
                    "Original warning cannot be decoded (tampered warning): " + warning.warningId(), decodeFailed);
        }
        if (!warning.warningId().equals(persisted.warningId())) {
            throw new StorageException(
                    "Original warning warningId does not match the requested warning: " + warning.warningId());
        }
        String currentSha = FileDigest.sha256(warningPath);
        if (!ack.warningFileSha256().equals(currentSha)) {
            throw new StorageException(
                    "Warning acknowledgement SHA-256 does not bind to the current original warning bytes: "
                            + warning.warningId());
        }
        return java.util.Optional.of(ack);
    }

    /** Convenience: true only when readVerified returns a value; any violation fails closed. */
    public boolean isAcknowledgedVerified(WarningRecordV1 warning) {
        return readVerified(warning).isPresent();
    }

    /**
     * Acknowledge one warning. The original warning file + manifest MUST verify; the sidecar is
     * then committed CREATE_NEW with its own manifest. An exact byte-identical retry is a no-op;
     * a DIFFERENT ack for the same warningId fails closed (immutable conflict), so a warning can
     * be acknowledged exactly once with exactly one disposition.
     */
    public WarningAcknowledgementV1 acknowledge(
            WarningRecordV1 warning, String dispositionNote, OffsetDateTime acknowledgedAt
    ) {
        Objects.requireNonNull(warning, "warning");
        Objects.requireNonNull(dispositionNote, "dispositionNote");
        Objects.requireNonNull(acknowledgedAt, "acknowledgedAt");
        String warningRef = DataPaths.warningRef(warning.warningMonth(), warning.warningId());
        verifyOriginalWarning(warning, warningRef);

        WarningAcknowledgementV1 acknowledgement = new WarningAcknowledgementV1(
                "1.0", warning.warningId(), warningRef,
                FileDigest.sha256(dataRoot.resolveDataRef(warningRef)),
                WarningAcknowledgementV1.AckStatus.ACKNOWLEDGED, acknowledgedAt, dispositionNote);

        String ackRef = ackRefOf(warningRef, warning.warningId());
        byte[] ackBytes = JsonV1Codec.encodeFile(acknowledgement);
        Path ackPath = dataRoot.resolveDataRef(ackRef);
        Path manifestPath = dataRoot.resolveDataRef(DataPaths.manifestRef(ackRef));
        if (Files.isRegularFile(ackPath)
                && ManifestVerifier.matches(dataRoot, ackRef, ackPath, manifestPath, List.of())
                && FileDigest.bytesEqual(ackPath, ackBytes)) {
            return acknowledgement; // exact byte-identical retry: idempotent no-op
        }
        if (Files.isRegularFile(ackPath)) {
            throw new StorageException(
                    "Warning acknowledgement already exists with different content (immutable): " + warning.warningId());
        }

        OffsetDateTime at = OffsetDateTime.now(clock);
        ManifestV1 manifest = ManifestFactory.json(ackRef, ackBytes, List.of(), at);
        byte[] manifestBytes = JsonV1Codec.encodeFile(manifest);
        try {
            fileStore.commit("warning-ack-" + warning.warningId(),
                    DirtyTransactionType.SINGLE_FILE, at,
                    List.of(new FileTransactionTarget(
                            DirtyTargetRole.BUSINESS_FILE, ackRef, ackBytes, manifestBytes, true)));
        } catch (ImmutableFileConflictException conflict) {
            throw new StorageException(
                    "Warning acknowledgement immutable conflict for " + warning.warningId(), conflict);
        }
        return acknowledgement;
    }

    private void verifyOriginalWarning(WarningRecordV1 warning, String warningRef) {
        Path warningPath = dataRoot.resolveDataRef(warningRef);
        Path warningManifest = dataRoot.resolveDataRef(DataPaths.manifestRef(warningRef));
        if (!Files.isRegularFile(warningPath)
                || !ManifestVerifier.matches(dataRoot, warningRef, warningPath, warningManifest, List.of())) {
            throw new StorageException("Original warning fails its manifest: " + warning.warningId());
        }
        try {
            WarningRecordV1 persisted = JsonV1Codec.decodeFile(
                    Files.readAllBytes(warningPath), WarningRecordV1.class);
            if (!persisted.warningId().equals(warning.warningId())) {
                throw new StorageException("warningId mismatch on acknowledgement: " + warning.warningId());
            }
        } catch (IOException exception) {
            throw new StorageException("Unable to read original warning " + warning.warningId(), exception);
        }
    }

    private static String ackRefOf(String warningRef, String warningId) {
        if (warningRef == null || !warningRef.startsWith("warning/")) {
            throw new StorageException("warningRef must be a warning evidence ref");
        }
        String month = warningRef.substring("warning/".length(), "warning/".length() + 7);
        return DataPaths.warningAckRef(java.time.YearMonth.parse(month), warningId);
    }
}
