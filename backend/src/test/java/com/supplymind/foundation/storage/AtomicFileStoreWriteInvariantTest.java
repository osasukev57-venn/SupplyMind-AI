package com.supplymind.foundation.storage;

import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.DomainFixtures;
import com.supplymind.foundation.model.LifecycleSnapshotV1;
import com.supplymind.foundation.model.LifecycleTimelineV1;
import com.supplymind.foundation.model.ManifestV1;
import com.supplymind.foundation.model.MonitorSeriesConfigV1;
import com.supplymind.foundation.model.MonitorSeriesDefaults;
import com.supplymind.foundation.model.ProcessingStage;
import com.supplymind.foundation.model.QuarantineProjectionV1;
import com.supplymind.foundation.model.RawReceiptV1;
import com.supplymind.foundation.model.ValidationStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focused guards for the raw/config write boundaries frozen in plan section 9. */
class AtomicFileStoreWriteInvariantTest {

    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-08-08T10:00:00+08:00");

    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsMutableRawBeforeAWriteCanUseSingleFileReplacementSemantics() {
        RawReceiptV1 receipt = DomainFixtures.rawReceipt();
        byte[] rawBytes = JsonV1Codec.encodeFile(receipt);

        StorageException failure = assertThrows(StorageException.class, () -> new FileTransactionTarget(
                DirtyTargetRole.BUSINESS_FILE,
                receipt.rawRef(),
                rawBytes,
                manifestBytes(receipt.rawRef(), rawBytes, List.of(receipt.runId())),
                false
        ));

        assertTrue(failure.getMessage().contains("immutable CREATE_NEW"));
    }

    @Test
    void rejectsMutableQuarantineAndRawConflictEvidenceTargets() {
        StorageException quarantineFailure = assertThrows(StorageException.class, () -> new FileTransactionTarget(
                DirtyTargetRole.BUSINESS_FILE,
                "quarantine/FX.TEST.CNY/2026-08/quarantine-run-1.json",
                new byte[]{1},
                new byte[]{2},
                false
        ));
        StorageException conflictFailure = assertThrows(StorageException.class, () -> new FileTransactionTarget(
                DirtyTargetRole.BUSINESS_FILE,
                "runtime/conflicts/raw/FX.TEST.CNY/2026-08/raw-run-1/conflict-1.json",
                new byte[]{1},
                new byte[]{2},
                false
        ));

        assertTrue(quarantineFailure.getMessage().contains("immutable CREATE_NEW"));
        assertTrue(conflictFailure.getMessage().contains("immutable CREATE_NEW"));
    }

    @Test
    void rejectsRawReceiptWhenItsEmbeddedRawRefDoesNotMatchTheAtomicTarget() throws Exception {
        DataRoot root = root("raw-target-mismatch");
        AtomicFileStore store = new AtomicFileStore(root, new DirtyMarkerCodec());
        RawReceiptV1 receipt = DomainFixtures.rawReceipt();
        byte[] rawBytes = JsonV1Codec.encodeFile(receipt);
        String differentTarget = RawReceiptV1.deriveRawRef(
                receipt.mode(), receipt.providerType(), receipt.itemId(), receipt.receivedAt(), "test-run-usd-002");
        String transactionId = "raw-target-mismatch-1";

        StorageException failure = assertThrows(StorageException.class, () -> store.commit(
                transactionId,
                DirtyTransactionType.SINGLE_FILE,
                AT,
                List.of(new FileTransactionTarget(
                        DirtyTargetRole.BUSINESS_FILE,
                        differentTarget,
                        rawBytes,
                        manifestBytes(differentTarget, rawBytes, List.of(receipt.runId())),
                        true
                ))
        ));

        assertTrue(failure.getMessage().contains("rawRef must match"));
        assertFalse(Files.exists(root.resolveDataRef(DataPaths.dirtyMarkerRef(transactionId))));
        assertFalse(Files.exists(root.resolveDataRef(differentTarget)));
    }

    @Test
    void rejectsStagingQuarantineAndRawConflictDocumentsAtMismatchedTargets() {
        RawReceiptV1 raw = DomainFixtures.rawReceipt();
        byte[] rawBytes = JsonV1Codec.encodeFile(raw);
        LifecycleTimelineV1 published = DomainFixtures.publishedTimeline();
        StorageException stagingFailure = assertThrows(StorageException.class, () -> StorageSchemaVerifier.verifyData(
                "staging/other-run-001.json", JsonV1Codec.encodeFile(published)));

        LifecycleTimelineV1 rejected = LifecycleTimelineV1.initial(
                "record-rejected-001", raw.runId(), raw.rawRef(), raw.receivedAt()).append(new LifecycleSnapshotV1(
                        2, ProcessingStage.RECEIVED, ValidationStatus.REJECTED, null,
                        "PAYLOAD_SHAPE_INVALID", null, null, null, null, raw.receivedAt().plusSeconds(1)
                ));
        QuarantineProjectionV1 quarantine = QuarantineProjectionV1.fromTerminal(raw, rejected, FileDigest.sha256(rawBytes));
        StorageException quarantineFailure = assertThrows(StorageException.class, () -> StorageSchemaVerifier.verifyData(
                DataPaths.quarantineRef(raw.itemId(), raw.receivedAt(), "other-run-001"),
                JsonV1Codec.encodeFile(quarantine)
        ));

        RawConflictEvidenceV1 conflict = new RawConflictEvidenceV1(
                "1.0", "conflict-actual-001", raw.itemId(), raw.runId(), raw.rawRef(), "a".repeat(64),
                FileDigest.sha256(rawBytes), raw, AT
        );
        StorageException conflictFailure = assertThrows(StorageException.class, () -> StorageSchemaVerifier.verifyData(
                DataPaths.rawConflictRef(raw.itemId(), raw.receivedAt(), raw.runId(), "conflict-other-001"),
                JsonV1Codec.encodeFile(conflict)
        ));

        assertTrue(stagingFailure.getMessage().contains("staging atomic target"));
        assertTrue(quarantineFailure.getMessage().contains("quarantineRef"));
        assertTrue(conflictFailure.getMessage().contains("identity must match"));
    }

    @Test
    void rejectsConfigurationTargetsOutsideConfigActivationBeforeDirtyMarkerCreation() throws Exception {
        DataRoot root = root("config-outside-activation");
        AtomicFileStore store = new AtomicFileStore(root, new DirtyMarkerCodec());
        MonitorSeriesConfigV1 configuration = MonitorSeriesDefaults.initialPboc(AT);
        byte[] bytes = JsonV1Codec.encodeFile(configuration);
        String transactionId = "config-single-file-1";

        StorageException failure = assertThrows(StorageException.class, () -> store.commit(
                transactionId,
                DirtyTransactionType.SINGLE_FILE,
                AT,
                List.of(new FileTransactionTarget(
                        DirtyTargetRole.CONFIG_ACTIVE,
                        DataPaths.configActiveRef(),
                        bytes,
                        manifestBytes(DataPaths.configActiveRef(), bytes, List.of()),
                        false
                ))
        ));

        assertTrue(failure.getMessage().contains("only be written by CONFIG_ACTIVATION"));
        assertFalse(Files.exists(root.resolveDataRef(DataPaths.dirtyMarkerRef(transactionId))));
        assertFalse(Files.exists(root.resolveDataRef(DataPaths.configActiveRef())));
    }

    @Test
    void requiresOneByteIdenticalHistoryAndActivePairWithVersionMatchedHistoryReference() throws Exception {
        DataRoot root = root("config-pair");
        AtomicFileStore store = new AtomicFileStore(root, new DirtyMarkerCodec());
        MonitorSeriesConfigV1 versionOne = MonitorSeriesDefaults.initialPboc(AT);
        byte[] historyBytes = JsonV1Codec.encodeFile(versionOne);
        byte[] changedActiveBytes = JsonV1Codec.encodeFile(MonitorSeriesDefaults.initialPboc(AT.plusSeconds(1)));
        String historyRef = DataPaths.configHistoryRef(versionOne.configVersion());
        String transactionId = "config-byte-mismatch-1";

        StorageException byteMismatch = assertThrows(StorageException.class, () -> store.commit(
                transactionId,
                DirtyTransactionType.CONFIG_ACTIVATION,
                AT,
                List.of(
                        new FileTransactionTarget(DirtyTargetRole.CONFIG_HISTORY, historyRef, historyBytes,
                                manifestBytes(historyRef, historyBytes, List.of()), true),
                        new FileTransactionTarget(DirtyTargetRole.CONFIG_ACTIVE, DataPaths.configActiveRef(), changedActiveBytes,
                                manifestBytes(DataPaths.configActiveRef(), changedActiveBytes, List.of()), false)
                )
        ));
        assertTrue(byteMismatch.getMessage().contains("bytes must be identical"));
        assertFalse(Files.exists(root.resolveDataRef(DataPaths.dirtyMarkerRef(transactionId))));

        String wrongHistoryRef = DataPaths.configHistoryRef(2);
        StorageException versionMismatch = assertThrows(StorageException.class, () -> store.commit(
                "config-history-version-mismatch-1",
                DirtyTransactionType.CONFIG_ACTIVATION,
                AT,
                List.of(
                        new FileTransactionTarget(DirtyTargetRole.CONFIG_HISTORY, wrongHistoryRef, historyBytes,
                                manifestBytes(wrongHistoryRef, historyBytes, List.of()), true),
                        new FileTransactionTarget(DirtyTargetRole.CONFIG_ACTIVE, DataPaths.configActiveRef(), historyBytes,
                                manifestBytes(DataPaths.configActiveRef(), historyBytes, List.of()), false)
                )
        ));
        assertTrue(versionMismatch.getMessage().contains("reference must match"));

        store.commit(
                "config-activation-valid-1",
                DirtyTransactionType.CONFIG_ACTIVATION,
                AT,
                List.of(
                        new FileTransactionTarget(DirtyTargetRole.CONFIG_HISTORY, historyRef, historyBytes,
                                manifestBytes(historyRef, historyBytes, List.of()), true),
                        new FileTransactionTarget(DirtyTargetRole.CONFIG_ACTIVE, DataPaths.configActiveRef(), historyBytes,
                                manifestBytes(DataPaths.configActiveRef(), historyBytes, List.of()), false)
                )
        );
        assertArrayEquals(
                Files.readAllBytes(root.resolveDataRef(historyRef)),
                Files.readAllBytes(root.resolveDataRef(DataPaths.configActiveRef()))
        );
    }

    private DataRoot root(String name) {
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve(name));
        root.createIfAbsentAndRequireWritable();
        return root;
    }

    private static byte[] manifestBytes(String dataRef, byte[] dataBytes, List<String> sourceRunIds) {
        ManifestV1 manifest = ManifestFactory.json(dataRef, dataBytes, sourceRunIds, AT);
        return JsonV1Codec.encodeFile(manifest);
    }
}
