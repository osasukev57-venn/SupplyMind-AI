package com.supplymind.foundation.acceptance;

import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.CandidateV1;
import com.supplymind.foundation.model.LifecycleSnapshotV1;
import com.supplymind.foundation.model.LifecycleTimelineV1;
import com.supplymind.foundation.model.Mode;
import com.supplymind.foundation.model.MonitorSeriesConfigV1;
import com.supplymind.foundation.model.MonitorSeriesDefaults;
import com.supplymind.foundation.model.ProcessingStage;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.SchemaValidationException;
import com.supplymind.foundation.model.ValidationStatus;
import com.supplymind.foundation.storage.AtomicFileRecovery;
import com.supplymind.foundation.storage.AtomicMoveSupport;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.DirtyMarkerCodec;
import com.supplymind.foundation.storage.DirtyMarkerV1;
import com.supplymind.foundation.storage.DirtyTargetPhase;
import com.supplymind.foundation.storage.DirtyTargetRole;
import com.supplymind.foundation.storage.DirtyTargetV1;
import com.supplymind.foundation.storage.DirtyTransactionPhase;
import com.supplymind.foundation.storage.DirtyTransactionType;
import com.supplymind.foundation.storage.FileDigest;
import com.supplymind.foundation.storage.ManifestFactory;
import com.supplymind.foundation.storage.ManifestVerifier;
import com.supplymind.foundation.storage.StorageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Independent AT-FILE-000 coverage for frozen D1-T03 boundaries. This class
 * deliberately uses only existing public production APIs and test-local temp roots.
 */
class D1T03AtFile000SupplementalAcceptanceTest {
    private static final OffsetDateTime TIME = OffsetDateTime.parse("2026-08-08T10:00:00+08:00");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-08T02:00:00Z"), ZoneOffset.UTC);
    private static final String RUN_ID = "atfile-run-001";
    private static final String RAW_REF = "raw/test/synthetic_demo/FX.ATFILE.CNY/2026/08/atfile-run-001.json";
    private static final CandidateV1 CANDIDATE = new CandidateV1(
            "FX.ATFILE.CNY", "2026-08-08", "7.123456789000", "CNY", "CNY/1 TEST",
            ProviderType.SYNTHETIC_DEMO, "test/contract fixture", AccessMethod.SYNTHETIC_DEMO, "normalization-test-v1"
    );
    private static final List<State> LEGAL_STATES = List.of(
            new State(ProcessingStage.RECEIVED, ValidationStatus.PENDING),
            new State(ProcessingStage.PARSED, ValidationStatus.PENDING),
            new State(ProcessingStage.RECEIVED, ValidationStatus.REJECTED),
            new State(ProcessingStage.VALIDATED, ValidationStatus.VERIFIED),
            new State(ProcessingStage.VALIDATED, ValidationStatus.VERIFIED_WITH_NOTICE),
            new State(ProcessingStage.VALIDATED, ValidationStatus.REJECTED),
            new State(ProcessingStage.VALIDATED, ValidationStatus.CONFLICT),
            new State(ProcessingStage.PUBLISHED, ValidationStatus.VERIFIED),
            new State(ProcessingStage.PUBLISHED, ValidationStatus.VERIFIED_WITH_NOTICE)
    );

    @TempDir
    Path temporaryDirectory;

    @Test
    void lifecycleMatrixAllAdjacentEdgesAndCandidateImmutabilityAreEnforced() {
        Set<State> legalSet = Set.copyOf(LEGAL_STATES);
        int legalCombinations = 0;
        int rejectedCombinations = 0;
        for (ProcessingStage stage : ProcessingStage.values()) {
            for (ValidationStatus status : ValidationStatus.values()) {
                State state = new State(stage, status);
                if (legalSet.contains(state)) {
                    legalCombinations++;
                    assertDoesNotThrow(() -> standaloneSnapshot(state), "must accept " + state);
                } else {
                    rejectedCombinations++;
                    assertThrows(SchemaValidationException.class, () -> standaloneSnapshot(state),
                            "must reject " + state);
                }
            }
        }
        assertEquals(9, legalCombinations, "frozen lifecycle whitelist contains exactly nine combinations");
        assertEquals(11, rejectedCombinations, "all remaining 4x5 combinations are illegal");

        for (Map.Entry<State, LifecycleTimelineV1> origin : validTimelinesByCurrentState().entrySet()) {
            for (State destination : LEGAL_STATES) {
                LifecycleSnapshotV1 next = nextSnapshot(origin.getValue(), destination);
                if (isLegalEdge(origin.getKey(), destination)) {
                    LifecycleTimelineV1 advanced = assertDoesNotThrow(() -> origin.getValue().append(next),
                            () -> "must allow edge " + origin.getKey() + " -> " + destination);
                    assertEquals(origin.getValue().currentRecordVersion() + 1, advanced.currentRecordVersion());
                    assertEquals(destination.processingStage(), advanced.current().processingStage());
                    assertEquals(destination.validationStatus(), advanced.current().validationStatus());
                } else {
                    assertThrows(SchemaValidationException.class, () -> origin.getValue().append(next),
                            () -> "must reject non-edge " + origin.getKey() + " -> " + destination);
                }
            }
        }

        LifecycleTimelineV1 parsed = initialTimeline().append(nextSnapshot(initialTimeline(), LEGAL_STATES.get(1)));
        CandidateV1 altered = new CandidateV1(
                CANDIDATE.itemId(), CANDIDATE.businessDate(), "7.123456789001", CANDIDATE.currency(), CANDIDATE.unit(),
                CANDIDATE.providerType(), CANDIDATE.actualSourceName(), CANDIDATE.accessMethod(), CANDIDATE.normalizationVersion());
        LifecycleSnapshotV1 alteredValidated = new LifecycleSnapshotV1(
                3, ProcessingStage.VALIDATED, ValidationStatus.VERIFIED, altered, null, "validation-test-v1",
                TIME.plusMinutes(3), null, null, TIME.plusMinutes(3));
        assertThrows(SchemaValidationException.class, () -> parsed.append(alteredValidated),
                "a CandidateV1 correction must create a new run rather than rewrite a timeline");
    }

    @Test
    void configActivationRecoveryCompletesHistoryThenActiveTwoTargetFourFileWindow() throws Exception {
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve("config-activation-window"));
        AtomicMoveSupport.probeOrFail(root);
        DirtyMarkerCodec markerCodec = new DirtyMarkerCodec();
        MonitorSeriesConfigV1 configuration = MonitorSeriesDefaults.initialPboc(TIME);
        byte[] configurationBytes = JsonV1Codec.encodeFile(configuration);
        String historyRef = DataPaths.configHistoryRef(configuration.configVersion());
        String activeRef = DataPaths.configActiveRef();
        byte[] historyManifest = JsonV1Codec.encodeFile(ManifestFactory.json(historyRef, configurationBytes, List.of(), TIME));
        byte[] activeManifest = JsonV1Codec.encodeFile(ManifestFactory.json(activeRef, configurationBytes, List.of(), TIME));
        String transactionId = "atfile-config-window-001";

        DirtyMarkerV1 marker = DirtyMarkerV1.open(
                transactionId,
                DirtyTransactionType.CONFIG_ACTIVATION,
                TIME,
                List.of(
                        new DirtyTargetV1(1, DirtyTargetRole.CONFIG_HISTORY, historyRef, DataPaths.manifestRef(historyRef),
                                FileDigest.sha256(configurationBytes), null, DirtyTargetPhase.PREPARED),
                        new DirtyTargetV1(2, DirtyTargetRole.CONFIG_ACTIVE, activeRef, DataPaths.manifestRef(activeRef),
                                FileDigest.sha256(configurationBytes), null, DirtyTargetPhase.PREPARED)
                )
        ).advanceTarget(1, DirtyTargetPhase.DATA_COMMITTED)
                .advanceTarget(1, DirtyTargetPhase.MANIFEST_COMMITTED);

        assertEquals(3, marker.markerRevision());
        assertEquals(DirtyTargetRole.CONFIG_HISTORY, marker.targets().get(0).role());
        assertEquals(DirtyTargetRole.CONFIG_ACTIVE, marker.targets().get(1).role());
        assertEquals(4, marker.targets().stream()
                .flatMap(target -> java.util.stream.Stream.of(target.dataRef(), target.manifestRef())).distinct().count());
        assertEquals(DirtyTargetPhase.MANIFEST_COMMITTED, marker.targets().get(0).targetPhase());
        assertEquals(DirtyTargetPhase.PREPARED, marker.targets().get(1).targetPhase());

        FileDigest.writeCreateNewAndForce(root.resolveDataRef(historyRef), configurationBytes);
        FileDigest.writeCreateNewAndForce(root.resolveDataRef(DataPaths.manifestRef(historyRef)), historyManifest);
        Path activePath = root.resolveDataRef(activeRef);
        Path activeTemporary = activePath.resolveSibling(
                DataPaths.adjacentTemporaryFileName(activePath.getFileName().toString(), transactionId));
        FileDigest.writeCreateNewAndForce(activeTemporary, configurationBytes);
        FileDigest.writeCreateNewAndForce(root.resolveDataRef(DataPaths.dirtyMarkerRef(transactionId)), markerCodec.encode(marker));

        List<DirtyMarkerV1> recovered = new AtomicFileRecovery(root, markerCodec, CLOCK).recoverAll();

        assertEquals(1, recovered.size());
        assertEquals(6, recovered.get(0).markerRevision());
        assertEquals(DirtyTransactionPhase.COMMITTED, recovered.get(0).transactionPhase());
        assertArrayEquals(Files.readAllBytes(root.resolveDataRef(historyRef)), Files.readAllBytes(root.resolveDataRef(activeRef)),
                "config history must become complete before matching active configuration is visible");
        assertTrue(ManifestVerifier.matches(root.resolveDataRef(historyRef),
                root.resolveDataRef(DataPaths.manifestRef(historyRef)), List.of()));
        assertTrue(ManifestVerifier.matches(root.resolveDataRef(activeRef),
                root.resolveDataRef(DataPaths.manifestRef(activeRef)), List.of()));
        assertFalse(Files.exists(root.resolveDataRef(DataPaths.dirtyMarkerRef(transactionId))));
        assertFalse(Files.exists(activeTemporary));
    }

    @Test
    void recoveryNeverAdoptsOrDeletesOrphanBusinessTmpOrBakWithoutAMarker() throws Exception {
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve("orphan-artifacts"));
        root.createIfAbsentAndRequireWritable();
        String transactionId = "atfile-orphan-001";
        Path target = root.resolveDataRef("staging/orphan-run.json");
        Path temporary = target.resolveSibling(
                DataPaths.adjacentTemporaryFileName(target.getFileName().toString(), transactionId));
        Path backup = target.resolveSibling(
                DataPaths.adjacentBackupFileName(target.getFileName().toString(), transactionId));
        byte[] temporaryBytes = "unowned-tmp".getBytes(StandardCharsets.UTF_8);
        byte[] backupBytes = "unowned-bak".getBytes(StandardCharsets.UTF_8);
        FileDigest.writeCreateNewAndForce(temporary, temporaryBytes);
        FileDigest.writeCreateNewAndForce(backup, backupBytes);

        List<DirtyMarkerV1> recovered = new AtomicFileRecovery(root, new DirtyMarkerCodec(), CLOCK).recoverAll();

        assertTrue(recovered.isEmpty());
        assertFalse(Files.exists(target));
        assertEquals("unowned-tmp", Files.readString(temporary));
        assertEquals("unowned-bak", Files.readString(backup));
    }

    @Test
    void unavailableOrNonDirectoryDataRootsFailFastWithoutCreatingAnotherRoot() throws Exception {
        assertThrows(StorageException.class, () -> DataRoot.fromConfiguredPath("relative-data-root"));

        Path existingFile = temporaryDirectory.resolve("not-a-directory");
        Files.writeString(existingFile, "not a directory", StandardCharsets.UTF_8);
        DataRoot rootPointingAtFile = DataRoot.forTest(existingFile);

        assertThrows(StorageException.class, () -> AtomicMoveSupport.probeOrFail(rootPointingAtFile));
        assertTrue(Files.isRegularFile(existingFile));
        assertEquals("not a directory", Files.readString(existingFile));
    }

    private static LifecycleTimelineV1 initialTimeline() {
        return LifecycleTimelineV1.initial("atfile-record-001", RUN_ID, RAW_REF, TIME);
    }

    private static LifecycleSnapshotV1 standaloneSnapshot(State state) {
        int version = state.processingStage() == ProcessingStage.PUBLISHED ? 4
                : state.processingStage() == ProcessingStage.VALIDATED ? 3
                : state.processingStage() == ProcessingStage.PARSED ? 2 : 1;
        return snapshot(state, version, null, RUN_ID);
    }

    private static Map<State, LifecycleTimelineV1> validTimelinesByCurrentState() {
        Map<State, LifecycleTimelineV1> timelines = new LinkedHashMap<>();
        LifecycleTimelineV1 received = initialTimeline();
        State receivedState = LEGAL_STATES.get(0);
        State parsedState = LEGAL_STATES.get(1);
        State receivedRejectedState = LEGAL_STATES.get(2);
        State validatedVerifiedState = LEGAL_STATES.get(3);
        State validatedNoticeState = LEGAL_STATES.get(4);
        State validatedRejectedState = LEGAL_STATES.get(5);
        State validatedConflictState = LEGAL_STATES.get(6);
        State publishedVerifiedState = LEGAL_STATES.get(7);
        State publishedNoticeState = LEGAL_STATES.get(8);
        LifecycleTimelineV1 parsed = received.append(nextSnapshot(received, parsedState));
        LifecycleTimelineV1 receivedRejected = received.append(nextSnapshot(received, receivedRejectedState));
        LifecycleTimelineV1 validatedVerified = parsed.append(nextSnapshot(parsed, validatedVerifiedState));
        LifecycleTimelineV1 validatedNotice = parsed.append(nextSnapshot(parsed, validatedNoticeState));
        LifecycleTimelineV1 validatedRejected = parsed.append(nextSnapshot(parsed, validatedRejectedState));
        LifecycleTimelineV1 validatedConflict = parsed.append(nextSnapshot(parsed, validatedConflictState));
        LifecycleTimelineV1 publishedVerified = validatedVerified.append(nextSnapshot(validatedVerified, publishedVerifiedState));
        LifecycleTimelineV1 publishedNotice = validatedNotice.append(nextSnapshot(validatedNotice, publishedNoticeState));
        timelines.put(receivedState, received);
        timelines.put(parsedState, parsed);
        timelines.put(receivedRejectedState, receivedRejected);
        timelines.put(validatedVerifiedState, validatedVerified);
        timelines.put(validatedNoticeState, validatedNotice);
        timelines.put(validatedRejectedState, validatedRejected);
        timelines.put(validatedConflictState, validatedConflict);
        timelines.put(publishedVerifiedState, publishedVerified);
        timelines.put(publishedNoticeState, publishedNotice);
        return timelines;
    }

    private static LifecycleSnapshotV1 nextSnapshot(LifecycleTimelineV1 timeline, State destination) {
        return snapshot(destination, timeline.currentRecordVersion() + 1, timeline.current(), timeline.runId());
    }

    private static LifecycleSnapshotV1 snapshot(
            State state,
            int recordVersion,
            LifecycleSnapshotV1 previous,
            String runId
    ) {
        boolean received = state.processingStage() == ProcessingStage.RECEIVED;
        boolean validatedOrPublished = state.processingStage() == ProcessingStage.VALIDATED
                || state.processingStage() == ProcessingStage.PUBLISHED;
        boolean notice = state.validationStatus() == ValidationStatus.VERIFIED_WITH_NOTICE;
        boolean rejected = state.validationStatus() == ValidationStatus.REJECTED;
        boolean conflict = state.validationStatus() == ValidationStatus.CONFLICT;
        String reasonCode = (notice ? "NOTICE" : (rejected ? "REJECTED" : (conflict ? "CONFLICT" : null)));
        String validationVersion = validatedOrPublished ? "validation-test-v1" : null;
        OffsetDateTime validatedAt = validatedOrPublished
                ? (state.processingStage() == ProcessingStage.PUBLISHED && previous != null
                && previous.validatedAt() != null ? previous.validatedAt() : TIME.plusMinutes(recordVersion))
                : null;
        OffsetDateTime publishedAt = state.processingStage() == ProcessingStage.PUBLISHED ? TIME.plusMinutes(recordVersion) : null;
        String publishRef = state.processingStage() == ProcessingStage.PUBLISHED
                ? "staging/" + runId + ".json#recordVersion=" + recordVersion : null;
        return new LifecycleSnapshotV1(
                recordVersion,
                state.processingStage(),
                state.validationStatus(),
                received ? null : CANDIDATE,
                reasonCode,
                validationVersion,
                validatedAt,
                publishedAt,
                publishRef,
                TIME.plusMinutes(recordVersion)
        );
    }

    private static boolean isLegalEdge(State from, State to) {
        return (from.equals(new State(ProcessingStage.RECEIVED, ValidationStatus.PENDING))
                && (to.equals(new State(ProcessingStage.PARSED, ValidationStatus.PENDING))
                || to.equals(new State(ProcessingStage.RECEIVED, ValidationStatus.REJECTED))))
                || (from.equals(new State(ProcessingStage.PARSED, ValidationStatus.PENDING))
                && to.processingStage() == ProcessingStage.VALIDATED
                && List.of(ValidationStatus.VERIFIED, ValidationStatus.VERIFIED_WITH_NOTICE,
                ValidationStatus.REJECTED, ValidationStatus.CONFLICT).contains(to.validationStatus()))
                || (from.equals(new State(ProcessingStage.VALIDATED, ValidationStatus.VERIFIED))
                && to.equals(new State(ProcessingStage.PUBLISHED, ValidationStatus.VERIFIED)))
                || (from.equals(new State(ProcessingStage.VALIDATED, ValidationStatus.VERIFIED_WITH_NOTICE))
                && to.equals(new State(ProcessingStage.PUBLISHED, ValidationStatus.VERIFIED_WITH_NOTICE)));
    }

    private record State(ProcessingStage processingStage, ValidationStatus validationStatus) {
    }
}
