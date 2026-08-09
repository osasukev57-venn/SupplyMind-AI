package com.supplymind.foundation.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirtyMarkerRecoveryTest {

    @TempDir
    Path temporaryDirectory;

    private final DirtyMarkerCodec codec = new DirtyMarkerCodec();

    @Test
    void recoversHighestForcedMarkerTmpToCanonicalWithoutAdoptingNormalTmpFiles() throws Exception {
        DataRoot root = DataRoot.forTest(temporaryDirectory);
        root.createIfAbsentAndRequireWritable();
        String transactionId = "marker-tmp-window";
        DirtyMarkerV1 revisionOne = openMarker(transactionId, "a".repeat(64));
        DirtyMarkerV1 revisionTwo = revisionOne.advanceTarget(1, DirtyTargetPhase.DATA_COMMITTED);
        FileDigest.writeCreateNewAndForce(root.resolveDataRef(DataPaths.dirtyMarkerRef(transactionId)), codec.encode(revisionOne));
        FileDigest.writeCreateNewAndForce(root.resolveInternalRelative(DataPaths.dirtyMarkerTemporaryRef(transactionId)),
                codec.encode(revisionTwo));

        Path normalTmp = root.resolveDataRef("staging/run-1.json").resolveSibling(".run-1.json.unowned.tmp");
        FileDigest.writeCreateNewAndForce(normalTmp, FileDigest.utf8("unowned"));

        List<DirtyMarkerV1> recovered = new DirtyMarkerRecovery(codec).recoverCanonicalMarkers(root);

        assertEquals(1, recovered.size());
        assertEquals(2, recovered.get(0).markerRevision());
        assertEquals(revisionTwo, codec.decode(Files.readAllBytes(root.resolveDataRef(DataPaths.dirtyMarkerRef(transactionId)))));
        assertFalse(Files.exists(root.resolveInternalRelative(DataPaths.dirtyMarkerTemporaryRef(transactionId))));
        assertTrue(Files.exists(root.resolveInternalRelative(DataPaths.dirtyMarkerBackupRef(transactionId))),
                "verified marker backup is retained until business recovery succeeds");
        assertTrue(Files.exists(normalTmp), "ordinary tmp must never be adopted or silently deleted");
    }

    @Test
    void sameRevisionDifferentBytesFailClosedAndPreservesEvidence() throws Exception {
        DataRoot root = DataRoot.forTest(temporaryDirectory);
        root.createIfAbsentAndRequireWritable();
        String transactionId = "marker-conflict";
        DirtyMarkerV1 revisionOne = openMarker(transactionId, "a".repeat(64));
        DirtyMarkerV1 divergentSameRevision = openMarker(transactionId, "b".repeat(64));
        FileDigest.writeCreateNewAndForce(root.resolveDataRef(DataPaths.dirtyMarkerRef(transactionId)), codec.encode(revisionOne));
        FileDigest.writeCreateNewAndForce(root.resolveInternalRelative(DataPaths.dirtyMarkerTemporaryRef(transactionId)),
                codec.encode(divergentSameRevision));

        assertThrows(StorageException.class, () -> new DirtyMarkerRecovery(codec).recoverCanonicalMarkers(root));
        assertTrue(Files.exists(root.resolveDataRef(DataPaths.dirtyMarkerRef(transactionId))));
        assertTrue(Files.exists(root.resolveInternalRelative(DataPaths.dirtyMarkerTemporaryRef(transactionId))));
    }

    @Test
    void isolatedCanonicalRevisionJumpFailsClosedAndPreservesTheMarker() throws Exception {
        DataRoot root = DataRoot.forTest(temporaryDirectory);
        root.createIfAbsentAndRequireWritable();
        String transactionId = "marker-revision-jump";
        DirtyMarkerV1 revisionOne = openMarker(transactionId, "a".repeat(64));
        byte[] jumpedBytes = FileDigest.utf8(new String(codec.encode(revisionOne), java.nio.charset.StandardCharsets.UTF_8)
                .replace("\"markerRevision\":1", "\"markerRevision\":2"));
        Path canonical = root.resolveDataRef(DataPaths.dirtyMarkerRef(transactionId));
        FileDigest.writeCreateNewAndForce(canonical, jumpedBytes);

        assertThrows(StorageException.class, () -> new DirtyMarkerRecovery(codec).recoverCanonicalMarkers(root));
        assertTrue(Files.exists(canonical), "invalid isolated canonical marker remains evidence");
    }

    @Test
    void restoresHighestBackupAcrossCanonicalAndLowerTmpCandidates() throws Exception {
        DataRoot root = DataRoot.forTest(temporaryDirectory);
        root.createIfAbsentAndRequireWritable();
        String transactionId = "marker-backup-highest-window";
        DirtyMarkerV1 revisionOne = openMarker(transactionId, "a".repeat(64));
        DirtyMarkerV1 revisionTwo = revisionOne.advanceTarget(1, DirtyTargetPhase.DATA_COMMITTED);
        DirtyMarkerV1 revisionThree = revisionTwo.advanceTarget(1, DirtyTargetPhase.MANIFEST_COMMITTED);
        Path canonical = root.resolveDataRef(DataPaths.dirtyMarkerRef(transactionId));
        Path temporary = root.resolveInternalRelative(DataPaths.dirtyMarkerTemporaryRef(transactionId));
        Path backup = root.resolveInternalRelative(DataPaths.dirtyMarkerBackupRef(transactionId));
        FileDigest.writeCreateNewAndForce(canonical, codec.encode(revisionOne));
        FileDigest.writeCreateNewAndForce(temporary, codec.encode(revisionTwo));
        FileDigest.writeCreateNewAndForce(backup, codec.encode(revisionThree));

        assertEquals(List.of(revisionThree), new DirtyMarkerRecovery(codec).recoverCanonicalMarkers(root));
        assertEquals(revisionThree, codec.decode(Files.readAllBytes(canonical)));
        assertEquals(revisionOne, codec.decode(Files.readAllBytes(backup)));
        assertFalse(Files.exists(temporary));
    }

    @Test
    void configActivationHasTwoOrderedLogicalTargetsWhichCoverFourPhysicalFiles() {
        String historyRef = DataPaths.configHistoryRef(2);
        String activeRef = DataPaths.configActiveRef();
        DirtyMarkerV1 marker = DirtyMarkerV1.open(
                "config-activation-1",
                DirtyTransactionType.CONFIG_ACTIVATION,
                OffsetDateTime.parse("2026-08-08T10:00:00+08:00"),
                List.of(
                        new DirtyTargetV1(1, DirtyTargetRole.CONFIG_HISTORY, historyRef, DataPaths.manifestRef(historyRef),
                                "a".repeat(64), null, DirtyTargetPhase.PREPARED),
                        new DirtyTargetV1(2, DirtyTargetRole.CONFIG_ACTIVE, activeRef, DataPaths.manifestRef(activeRef),
                                "b".repeat(64), "c".repeat(64), DirtyTargetPhase.PREPARED)
                )
        );

        assertEquals(1, marker.markerRevision());
        assertEquals(DirtyTargetRole.CONFIG_HISTORY, marker.targets().get(0).role());
        assertEquals(DirtyTargetRole.CONFIG_ACTIVE, marker.targets().get(1).role());
        assertEquals(4, marker.targets().stream().flatMap(target -> java.util.stream.Stream.of(target.dataRef(), target.manifestRef())).count());
        assertThrows(StorageException.class, () -> DirtyMarkerV1.open(
                "bad-config-activation", DirtyTransactionType.CONFIG_ACTIVATION, marker.createdAt(),
                List.of(marker.targets().get(1), marker.targets().get(0))));
        assertThrows(StorageException.class, () -> marker.advanceTarget(2, DirtyTargetPhase.DATA_COMMITTED),
                "CONFIG_ACTIVE cannot advance before CONFIG_HISTORY manifest");
    }

    private static DirtyMarkerV1 openMarker(String transactionId, String expectedHash) {
        String dataRef = "staging/run-1.json";
        return DirtyMarkerV1.open(
                transactionId,
                DirtyTransactionType.SINGLE_FILE,
                OffsetDateTime.parse("2026-08-08T10:00:00+08:00"),
                List.of(new DirtyTargetV1(1, DirtyTargetRole.BUSINESS_FILE, dataRef, DataPaths.manifestRef(dataRef),
                        expectedHash, null, DirtyTargetPhase.PREPARED))
        );
    }
}