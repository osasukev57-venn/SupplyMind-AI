package com.supplymind.foundation.storage;

import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.LifecycleTimelineV1;
import com.supplymind.foundation.model.ManifestV1;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtomicFileRecoveryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void canonicalMarkerCanFinishDataThenRebuildItsMissingJsonManifestAfterInterruption() throws Exception {
        DataRoot root = DataRoot.forTest(temporaryDirectory);
        root.createIfAbsentAndRequireWritable();
        byte[] timelineBytes = resource("contracts/v1/valid/lifecycle-published-v1.json");
        LifecycleTimelineV1 timeline = JsonV1Codec.decodeFile(timelineBytes, LifecycleTimelineV1.class);
        String dataRef = "staging/" + timeline.runId() + ".json";
        String transactionId = "timeline-recovery-1";
        DirtyTargetV1 target = new DirtyTargetV1(
                1,
                DirtyTargetRole.BUSINESS_FILE,
                dataRef,
                DataPaths.manifestRef(dataRef),
                FileDigest.sha256(timelineBytes),
                null,
                DirtyTargetPhase.PREPARED
        );
        DirtyMarkerV1 marker = DirtyMarkerV1.open(
                transactionId,
                DirtyTransactionType.SINGLE_FILE,
                OffsetDateTime.parse("2026-08-08T10:00:00+08:00"),
                List.of(target)
        );
        Path data = root.resolveDataRef(dataRef);
        Path manifest = root.resolveDataRef(DataPaths.manifestRef(dataRef));
        assertFalse(Files.exists(manifest));
        Path dataTmp = data.resolveSibling(DataPaths.adjacentTemporaryFileName(data.getFileName().toString(), transactionId));
        FileDigest.writeCreateNewAndForce(dataTmp, timelineBytes);
        DirtyMarkerCodec markerCodec = new DirtyMarkerCodec();
        FileDigest.writeCreateNewAndForce(root.resolveDataRef(DataPaths.dirtyMarkerRef(transactionId)), markerCodec.encode(marker));

        new AtomicFileRecovery(root, markerCodec,
                Clock.fixed(Instant.parse("2026-08-08T02:10:00Z"), ZoneOffset.UTC)).recoverAll();

        assertArrayEquals(timelineBytes, Files.readAllBytes(data));
        assertTrue(ManifestVerifier.matches(data, manifest, List.of(timeline.runId())));
        assertFalse(Files.exists(root.resolveDataRef(DataPaths.dirtyMarkerRef(transactionId))));
        assertFalse(Files.exists(dataTmp));
    }

    @Test
    void canonicalMarkerReplacesAnOldManifestHashAfterBusinessDataWasCommitted() throws Exception {
        DataRoot root = DataRoot.forTest(temporaryDirectory);
        root.createIfAbsentAndRequireWritable();
        byte[] timelineBytes = resource("contracts/v1/valid/lifecycle-published-v1.json");
        LifecycleTimelineV1 timeline = JsonV1Codec.decodeFile(timelineBytes, LifecycleTimelineV1.class);
        String dataRef = "staging/" + timeline.runId() + ".json";
        String transactionId = "timeline-old-manifest-1";
        DirtyTargetV1 target = new DirtyTargetV1(
                1,
                DirtyTargetRole.BUSINESS_FILE,
                dataRef,
                DataPaths.manifestRef(dataRef),
                FileDigest.sha256(timelineBytes),
                null,
                DirtyTargetPhase.PREPARED
        );
        DirtyMarkerV1 marker = DirtyMarkerV1.open(
                transactionId,
                DirtyTransactionType.SINGLE_FILE,
                OffsetDateTime.parse("2026-08-08T10:00:00+08:00"),
                List.of(target)
        );
        Path data = root.resolveDataRef(dataRef);
        Path manifest = root.resolveDataRef(DataPaths.manifestRef(dataRef));
        FileDigest.writeCreateNewAndForce(data, timelineBytes);
        byte[] staleManifest = JsonV1Codec.encodeFile(ManifestFactory.json(
                dataRef,
                FileDigest.utf8("old committed bytes\\n"),
                List.of(timeline.runId()),
                OffsetDateTime.parse("2026-08-08T09:59:00+08:00")
        ));
        FileDigest.writeCreateNewAndForce(manifest, staleManifest);
        assertFalse(ManifestVerifier.matches(data, manifest));

        DirtyMarkerCodec markerCodec = new DirtyMarkerCodec();
        FileDigest.writeCreateNewAndForce(root.resolveDataRef(DataPaths.dirtyMarkerRef(transactionId)), markerCodec.encode(marker));

        new AtomicFileRecovery(root, markerCodec,
                Clock.fixed(Instant.parse("2026-08-08T02:10:00Z"), ZoneOffset.UTC)).recoverAll();

        assertArrayEquals(timelineBytes, Files.readAllBytes(data));
        assertTrue(ManifestVerifier.matches(data, manifest, List.of(timeline.runId())));
        assertFalse(java.util.Arrays.equals(staleManifest, Files.readAllBytes(manifest)));
        assertFalse(Files.exists(root.resolveDataRef(DataPaths.dirtyMarkerRef(transactionId))));
        assertFalse(Files.exists(manifest.resolveSibling(
                DataPaths.adjacentBackupFileName(manifest.getFileName().toString(), transactionId))));
    }

    @Test
    void canonicalMarkerRestoresAProvenBackupWhenCommittedBusinessDataIsCorrupt() throws Exception {
        DataRoot root = DataRoot.forTest(temporaryDirectory);
        root.createIfAbsentAndRequireWritable();
        byte[] newTimelineBytes = resource("contracts/v1/valid/lifecycle-published-v1.json");
        LifecycleTimelineV1 timeline = JsonV1Codec.decodeFile(newTimelineBytes, LifecycleTimelineV1.class);
        byte[] oldTimelineBytes = FileDigest.utf8(new String(newTimelineBytes, java.nio.charset.StandardCharsets.UTF_8)
                .replace("7.123456789", "7.000000000"));
        JsonV1Codec.decodeFile(oldTimelineBytes, LifecycleTimelineV1.class);
        String dataRef = "staging/" + timeline.runId() + ".json";
        String transactionId = "timeline-corrupt-data-1";
        DirtyTargetV1 target = new DirtyTargetV1(
                1,
                DirtyTargetRole.BUSINESS_FILE,
                dataRef,
                DataPaths.manifestRef(dataRef),
                FileDigest.sha256(newTimelineBytes),
                FileDigest.sha256(oldTimelineBytes),
                DirtyTargetPhase.PREPARED
        );
        DirtyMarkerV1 marker = DirtyMarkerV1.open(
                transactionId,
                DirtyTransactionType.SINGLE_FILE,
                OffsetDateTime.parse("2026-08-08T10:00:00+08:00"),
                List.of(target)
        );
        Path data = root.resolveDataRef(dataRef);
        Path dataTmp = data.resolveSibling(DataPaths.adjacentTemporaryFileName(data.getFileName().toString(), transactionId));
        Path dataBackup = data.resolveSibling(DataPaths.adjacentBackupFileName(data.getFileName().toString(), transactionId));
        byte[] corruptBytes = FileDigest.utf8("{not a complete lifecycle document}\n");
        FileDigest.writeCreateNewAndForce(data, corruptBytes);
        FileDigest.writeCreateNewAndForce(dataBackup, oldTimelineBytes);
        DirtyMarkerCodec markerCodec = new DirtyMarkerCodec();
        Path markerPath = root.resolveDataRef(DataPaths.dirtyMarkerRef(transactionId));
        FileDigest.writeCreateNewAndForce(markerPath, markerCodec.encode(marker));

        assertThrows(StorageException.class, () -> new AtomicFileRecovery(root, markerCodec,
                Clock.fixed(Instant.parse("2026-08-08T02:10:00Z"), ZoneOffset.UTC)).recoverAll());

        assertArrayEquals(oldTimelineBytes, Files.readAllBytes(data));
        assertArrayEquals(corruptBytes, Files.readAllBytes(dataTmp));
        assertFalse(Files.exists(dataBackup));
        assertTrue(Files.exists(markerPath), "rollback remains dirty and fail-closed for review");
    }

    @Test
    void retainsVerifiedMarkerBackupWhenBusinessRecoveryFailsBeforeFinalCleanup() throws Exception {
        DataRoot root = DataRoot.forTest(temporaryDirectory);
        root.createIfAbsentAndRequireWritable();
        String transactionId = "marker-candidate-failure-1";
        byte[] expectedData = resource("contracts/v1/valid/lifecycle-published-v1.json");
        LifecycleTimelineV1 timeline = JsonV1Codec.decodeFile(expectedData, LifecycleTimelineV1.class);
        String dataRef = "staging/" + timeline.runId() + ".json";
        DirtyTargetV1 target = new DirtyTargetV1(
                1,
                DirtyTargetRole.BUSINESS_FILE,
                dataRef,
                DataPaths.manifestRef(dataRef),
                FileDigest.sha256(expectedData),
                null,
                DirtyTargetPhase.PREPARED
        );
        DirtyMarkerV1 revisionOne = DirtyMarkerV1.open(
                transactionId,
                DirtyTransactionType.SINGLE_FILE,
                OffsetDateTime.parse("2026-08-08T10:00:00+08:00"),
                List.of(target)
        );
        DirtyMarkerV1 revisionTwo = revisionOne.advanceTarget(1, DirtyTargetPhase.DATA_COMMITTED);
        DirtyMarkerCodec markerCodec = new DirtyMarkerCodec();
        Path canonical = root.resolveDataRef(DataPaths.dirtyMarkerRef(transactionId));
        Path markerTemporary = root.resolveInternalRelative(DataPaths.dirtyMarkerTemporaryRef(transactionId));
        Path markerBackup = root.resolveInternalRelative(DataPaths.dirtyMarkerBackupRef(transactionId));
        FileDigest.writeCreateNewAndForce(canonical, markerCodec.encode(revisionOne));
        FileDigest.writeCreateNewAndForce(markerTemporary, markerCodec.encode(revisionTwo));

        assertThrows(StorageException.class, () -> new AtomicFileRecovery(root, markerCodec,
                Clock.fixed(Instant.parse("2026-08-08T02:10:00Z"), ZoneOffset.UTC)).recoverAll());

        assertEquals(revisionTwo, markerCodec.decode(Files.readAllBytes(canonical)));
        assertTrue(Files.exists(markerBackup), "bootstrap candidate remains until final business recovery succeeds");
        assertFalse(Files.exists(markerTemporary), "highest tmp was atomically installed as canonical");
    }

    @Test
    void writesTheNextMarkerTmpBeforeRejectingAnUnverifiedBackupDuringRecovery() throws Exception {
        DataRoot root = DataRoot.forTest(temporaryDirectory);
        root.createIfAbsentAndRequireWritable();
        String transactionId = "marker-write-order-recovery-1";
        byte[] timelineBytes = resource("contracts/v1/valid/lifecycle-published-v1.json");
        LifecycleTimelineV1 timeline = JsonV1Codec.decodeFile(timelineBytes, LifecycleTimelineV1.class);
        String dataRef = "staging/" + timeline.runId() + ".json";
        DirtyTargetV1 target = new DirtyTargetV1(1, DirtyTargetRole.BUSINESS_FILE, dataRef,
                DataPaths.manifestRef(dataRef), FileDigest.sha256(timelineBytes), null, DirtyTargetPhase.PREPARED);
        DirtyMarkerV1 revisionOne = DirtyMarkerV1.open(transactionId, DirtyTransactionType.SINGLE_FILE,
                OffsetDateTime.parse("2026-08-08T10:00:00+08:00"), List.of(target));
        Path data = root.resolveDataRef(dataRef);
        Path manifest = root.resolveDataRef(DataPaths.manifestRef(dataRef));
        FileDigest.writeCreateNewAndForce(data, timelineBytes);
        FileDigest.writeCreateNewAndForce(manifest, JsonV1Codec.encodeFile(
                ManifestFactory.json(dataRef, timelineBytes, List.of(timeline.runId()), revisionOne.createdAt())));
        DirtyMarkerCodec markerCodec = new DirtyMarkerCodec();
        Path canonical = root.resolveDataRef(DataPaths.dirtyMarkerRef(transactionId));
        Path markerTemporary = root.resolveInternalRelative(DataPaths.dirtyMarkerTemporaryRef(transactionId));
        Path markerBackup = root.resolveInternalRelative(DataPaths.dirtyMarkerBackupRef(transactionId));
        FileDigest.writeCreateNewAndForce(canonical, markerCodec.encode(revisionOne));
        FileDigest.writeCreateNewAndForce(markerBackup, FileDigest.utf8("{invalid marker backup}\\n"));

        assertThrows(StorageException.class, () -> new AtomicFileRecovery(root, markerCodec,
                Clock.fixed(Instant.parse("2026-08-08T02:10:00Z"), ZoneOffset.UTC)).recover(revisionOne));

        assertEquals(revisionOne.advanceTarget(1, DirtyTargetPhase.DATA_COMMITTED),
                markerCodec.decode(Files.readAllBytes(markerTemporary)));
        assertTrue(Files.exists(markerBackup), "unverified backup is retained after next tmp is forced");
        assertEquals(revisionOne, markerCodec.decode(Files.readAllBytes(canonical)));
    }

    @Test
    void rebuildsDailyAndAggregateCsvManifestsFromProvenCsvAndPersistedDailyManifest() throws Exception {
        DataRoot root = DataRoot.forTest(temporaryDirectory);
        root.createIfAbsentAndRequireWritable();
        DirtyMarkerCodec markerCodec = new DirtyMarkerCodec();
        Clock recoveryClock = Clock.fixed(Instant.parse("2026-08-08T02:10:00Z"), ZoneOffset.UTC);
        String itemId = "FX.USD.CNY.CONTRACT_FIXTURE";
        String dailyRef = DataPaths.dailyRef(itemId, java.time.YearMonth.of(2026, 8));
        byte[] dailyBytes = resource("contracts/v1/valid/daily-v1.csv");
        Path daily = root.resolveDataRef(dailyRef);
        FileDigest.writeCreateNewAndForce(daily, dailyBytes);
        String dailyTransactionId = "csv-daily-manifest-recovery-1";
        DirtyTargetV1 dailyTarget = new DirtyTargetV1(1, DirtyTargetRole.BUSINESS_FILE, dailyRef,
                DataPaths.manifestRef(dailyRef), FileDigest.sha256(dailyBytes), null, DirtyTargetPhase.PREPARED);
        DirtyMarkerV1 dailyMarker = DirtyMarkerV1.open(dailyTransactionId, DirtyTransactionType.SINGLE_FILE,
                OffsetDateTime.parse("2026-08-08T10:00:00+08:00"), List.of(dailyTarget));
        FileDigest.writeCreateNewAndForce(root.resolveDataRef(DataPaths.dirtyMarkerRef(dailyTransactionId)),
                markerCodec.encode(dailyMarker));

        new AtomicFileRecovery(root, markerCodec, recoveryClock).recoverAll();

        Path dailyManifestPath = root.resolveDataRef(DataPaths.manifestRef(dailyRef));
        ManifestV1 dailyManifest = JsonV1Codec.decodeFile(Files.readAllBytes(dailyManifestPath), ManifestV1.class);
        assertEquals(1L, dailyManifest.rowCount());
        assertEquals("2026-08-10", dailyManifest.minBusinessDate());
        assertEquals("2026-08-10", dailyManifest.maxBusinessDate());
        assertEquals(List.of("run-fixture-usd-0001"), dailyManifest.sourceRunIds());
        assertTrue(ManifestVerifier.matches(root, dailyRef, daily, dailyManifestPath));

        String aggregateRef = DataPaths.aggregateRef(itemId, "month", 2026);
        String aggregateText = new String(resource("contracts/v1/valid/aggregate-v1.csv"), java.nio.charset.StandardCharsets.UTF_8)
                .replace("d20d2e43675e939a5e950e427dfe5c784b5d6e412362150105ec9b5f78c5ca98", FileDigest.sha256(dailyBytes));
        byte[] aggregateBytes = FileDigest.utf8(aggregateText);
        Path aggregate = root.resolveDataRef(aggregateRef);
        Path aggregateManifestPath = root.resolveDataRef(DataPaths.manifestRef(aggregateRef));
        FileDigest.writeCreateNewAndForce(aggregate, aggregateBytes);
        FileDigest.writeCreateNewAndForce(aggregateManifestPath, JsonV1Codec.encodeFile(ManifestFactory.csv(
                aggregateRef, FileDigest.utf8("stale aggregate bytes\\n"), 1,
                "2026-08-01", "2026-08-31", List.of("stale-run-001"), dailyMarker.createdAt())));
        String aggregateTransactionId = "csv-aggregate-manifest-recovery-1";
        DirtyTargetV1 aggregateTarget = new DirtyTargetV1(1, DirtyTargetRole.BUSINESS_FILE, aggregateRef,
                DataPaths.manifestRef(aggregateRef), FileDigest.sha256(aggregateBytes), null, DirtyTargetPhase.PREPARED);
        DirtyMarkerV1 aggregateMarker = DirtyMarkerV1.open(aggregateTransactionId, DirtyTransactionType.SINGLE_FILE,
                OffsetDateTime.parse("2026-08-08T10:01:00+08:00"), List.of(aggregateTarget));
        FileDigest.writeCreateNewAndForce(root.resolveDataRef(DataPaths.dirtyMarkerRef(aggregateTransactionId)),
                markerCodec.encode(aggregateMarker));

        new AtomicFileRecovery(root, markerCodec, recoveryClock).recoverAll();

        ManifestV1 aggregateManifest = JsonV1Codec.decodeFile(Files.readAllBytes(aggregateManifestPath), ManifestV1.class);
        assertEquals(1L, aggregateManifest.rowCount());
        assertEquals("2026-08-01", aggregateManifest.minBusinessDate());
        assertEquals("2026-08-31", aggregateManifest.maxBusinessDate());
        assertEquals(List.of("run-fixture-usd-0001"), aggregateManifest.sourceRunIds());
        assertTrue(ManifestVerifier.matches(root, aggregateRef, aggregate, aggregateManifestPath));
        assertFalse(Files.exists(aggregateManifestPath.resolveSibling(
                DataPaths.adjacentBackupFileName(aggregateManifestPath.getFileName().toString(), aggregateTransactionId))));
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream stream = AtomicFileRecoveryTest.class.getClassLoader().getResourceAsStream(name)) {
            if (stream == null) {
                throw new IOException("Missing test resource " + name);
            }
            return stream.readAllBytes();
        }
    }
}