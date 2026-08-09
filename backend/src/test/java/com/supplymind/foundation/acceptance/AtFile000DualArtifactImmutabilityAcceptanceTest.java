package com.supplymind.foundation.acceptance;

import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.CandidateV1;
import com.supplymind.foundation.model.LifecycleSnapshotV1;
import com.supplymind.foundation.model.LifecycleTimelineV1;
import com.supplymind.foundation.model.ManifestV1;
import com.supplymind.foundation.model.MonitorSeriesConfigV1;
import com.supplymind.foundation.model.ProcessingStage;
import com.supplymind.foundation.model.QuarantineProjectionV1;
import com.supplymind.foundation.model.RawReceiptV1;
import com.supplymind.foundation.model.SchemaValidationException;
import com.supplymind.foundation.model.ValidationStatus;
import com.supplymind.foundation.storage.AtomicFileRecovery;
import com.supplymind.foundation.storage.AtomicFileStore;
import com.supplymind.foundation.storage.ConfigActivationStore;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.DirtyMarkerCodec;
import com.supplymind.foundation.storage.DirtyTargetRole;
import com.supplymind.foundation.storage.DirtyTransactionType;
import com.supplymind.foundation.storage.FileTransactionTarget;
import com.supplymind.foundation.storage.ImmutableFileConflictException;
import com.supplymind.foundation.storage.ManifestVerifier;
import com.supplymind.foundation.storage.RawConflictEvidenceV1;
import com.supplymind.foundation.storage.RawReceiptConflictException;
import com.supplymind.foundation.storage.RawReceiptStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Independent AT-FILE-000 step 2--5 evidence.
 *
 * <p>All source artifacts here are explicitly local {@code test/contract fixture}
 * inputs. The test deliberately does not call ManifestFactory or a production
 * codec to generate its expected paths, expected data bytes, or manifest bytes.</p>
 */
class AtFile000DualArtifactImmutabilityAcceptanceTest {

    private static final String FIXTURE_ROOT = "contracts/v1/valid/";
    private static final String SHARED_ACQUISITION_ID = "acq-fixture-dual-currency-0001";
    private static final String SHARED_PAYLOAD_SHA256 = "3f42e7ed771bce1fbbbcc35fc66bd3a1014ae3f0b709cb6c89e49c448fc4f34c";
    private static final String USD_RUN_ID = "run-fixture-dual-usd-0001";
    private static final String EUR_RUN_ID = "run-fixture-dual-eur-0001";
    private static final OffsetDateTime MANIFEST_TIME = OffsetDateTime.parse("2026-08-10T01:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-08-10T01:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path temporaryDirectory;

    @Test
    void storesOneExplicitSyntheticResponseAsTwoIndependentRawAndTimelineArtifactsAcrossRestart() throws Exception {
        byte[] responseBytes = fixtureBytes("dual-currency-response-entity.txt");
        RawReceiptV1 usd = fixtureJson("raw-receipt-dual-usd-v1.json", RawReceiptV1.class);
        RawReceiptV1 eur = fixtureJson("raw-receipt-dual-eur-v1.json", RawReceiptV1.class);
        byte[] usdTimelineBytes = fixtureBytes("lifecycle-published-dual-usd-v1.json");
        byte[] eurTimelineBytes = fixtureBytes("lifecycle-published-dual-eur-v1.json");
        LifecycleTimelineV1 usdTimeline = JsonV1Codec.decodeFile(usdTimelineBytes, LifecycleTimelineV1.class);
        LifecycleTimelineV1 eurTimeline = JsonV1Codec.decodeFile(eurTimelineBytes, LifecycleTimelineV1.class);

        assertExplicitFixtureResponse(responseBytes, usd, eur);
        assertEquals(USD_RUN_ID, usd.runId());
        assertEquals(EUR_RUN_ID, eur.runId());
        assertEquals(USD_RUN_ID, usdTimeline.runId());
        assertEquals(EUR_RUN_ID, eurTimeline.runId());
        assertEquals("staging/run-fixture-dual-usd-0001.json#recordVersion=4", usdTimeline.current().publishRef());
        assertEquals("staging/run-fixture-dual-eur-0001.json#recordVersion=4", eurTimeline.current().publishRef());

        DataRoot dataRoot = initializedFixtureRoot("two-currency-artifacts");
        AtomicFileStore fileStore = fileStore(dataRoot);
        RawReceiptStore rawStore = rawStore(dataRoot);
        rawStore.store(usd);
        rawStore.store(eur);
        persistTimeline(dataRoot, fileStore, "timeline-usd-fixture-001", usdTimeline, usdTimelineBytes);
        persistTimeline(dataRoot, fileStore, "timeline-eur-fixture-001", eurTimeline, eurTimelineBytes);

        assertRawArtifact(dataRoot, usd);
        assertRawArtifact(dataRoot, eur);
        assertTimelineArtifact(dataRoot, usdTimeline, usdTimelineBytes);
        assertTimelineArtifact(dataRoot, eurTimeline, eurTimelineBytes);
        assertNotEquals(usd.rawRef(), eur.rawRef());
        assertNotEquals(DataPaths.stagingRef(usd.runId()), DataPaths.stagingRef(eur.runId()));

        DataRoot restartedRoot = DataRoot.forTest(dataRoot.path());
        assertTrue(new AtomicFileRecovery(restartedRoot, new DirtyMarkerCodec(), FIXED_CLOCK).recoverAll().isEmpty(),
                "clean committed artifacts need no recovery work after restart");
        RawReceiptV1 rereadUsd = JsonV1Codec.decodeFile(
                Files.readAllBytes(restartedRoot.resolveDataRef(usd.rawRef())), RawReceiptV1.class);
        RawReceiptV1 rereadEur = JsonV1Codec.decodeFile(
                Files.readAllBytes(restartedRoot.resolveDataRef(eur.rawRef())), RawReceiptV1.class);
        LifecycleTimelineV1 rereadUsdTimeline = JsonV1Codec.decodeFile(
                Files.readAllBytes(restartedRoot.resolveDataRef(DataPaths.stagingRef(usd.runId()))), LifecycleTimelineV1.class);
        LifecycleTimelineV1 rereadEurTimeline = JsonV1Codec.decodeFile(
                Files.readAllBytes(restartedRoot.resolveDataRef(DataPaths.stagingRef(eur.runId()))), LifecycleTimelineV1.class);

        assertEquals(SHARED_ACQUISITION_ID, rereadUsd.acquisitionId());
        assertEquals(SHARED_ACQUISITION_ID, rereadEur.acquisitionId());
        assertEquals("FX.USD.CNY.CONTRACT_FIXTURE", rereadUsd.itemId());
        assertEquals("FX.EUR.CNY.CONTRACT_FIXTURE", rereadEur.itemId());
        assertEquals(4, rereadUsdTimeline.currentRecordVersion());
        assertEquals(4, rereadEurTimeline.currentRecordVersion());
        assertEquals(ProcessingStage.PUBLISHED, rereadUsdTimeline.current().processingStage());
        assertEquals(ValidationStatus.VERIFIED, rereadEurTimeline.current().validationStatus());
    }

    @Test
    void persistsAllThreeTerminalQuarantinesWithoutProjectingPendingOrPublishEligibleTimelines() throws Exception {
        DataRoot dataRoot = initializedFixtureRoot("quarantine-terminal-artifacts");
        AtomicFileStore fileStore = fileStore(dataRoot);
        RawReceiptStore rawStore = rawStore(dataRoot);
        RawReceiptV1 usd = fixtureJson("raw-receipt-dual-usd-v1.json", RawReceiptV1.class);
        RawReceiptV1 eur = fixtureJson("raw-receipt-dual-eur-v1.json", RawReceiptV1.class);
        RawReceiptV1 conflictRaw = copyWithRunId(usd, "run-fixture-dual-usd-conflict-0001");

        RawReceiptStore.StoredRawReceipt storedUsd = rawStore.store(usd);
        RawReceiptStore.StoredRawReceipt storedEur = rawStore.store(eur);
        RawReceiptStore.StoredRawReceipt storedConflict = rawStore.store(conflictRaw);

        LifecycleTimelineV1 receivedRejected = receivedRejectedTimeline(usd);
        LifecycleTimelineV1 validatedRejected = validatedTerminalTimeline(eur, ValidationStatus.REJECTED, "VALIDATION_REJECTED");
        LifecycleTimelineV1 validatedConflict = validatedTerminalTimeline(conflictRaw, ValidationStatus.CONFLICT, "DUPLICATE_CONFLICT");

        QuarantineProjectionV1 receivedProjection = QuarantineProjectionV1.fromTerminal(
                usd, receivedRejected, storedUsd.rawFileSha256());
        QuarantineProjectionV1 rejectedProjection = QuarantineProjectionV1.fromTerminal(
                eur, validatedRejected, storedEur.rawFileSha256());
        QuarantineProjectionV1 conflictProjection = QuarantineProjectionV1.fromTerminal(
                conflictRaw, validatedConflict, storedConflict.rawFileSha256());

        assertEquals("quarantine/FX.USD.CNY.CONTRACT_FIXTURE/2026-08/run-fixture-dual-usd-0001.json",
                receivedProjection.quarantineRef());
        assertEquals(ProcessingStage.RECEIVED, receivedProjection.processingStage());
        assertEquals(ValidationStatus.REJECTED, receivedProjection.validationStatus());
        assertEquals(2, receivedProjection.terminalRecordVersion());
        assertEquals(ProcessingStage.VALIDATED, rejectedProjection.processingStage());
        assertEquals(ValidationStatus.REJECTED, rejectedProjection.validationStatus());
        assertEquals(3, rejectedProjection.terminalRecordVersion());
        assertEquals(ProcessingStage.VALIDATED, conflictProjection.processingStage());
        assertEquals(ValidationStatus.CONFLICT, conflictProjection.validationStatus());
        assertEquals(3, conflictProjection.terminalRecordVersion());

        persistAndProveImmutableQuarantine(dataRoot, fileStore, "quarantine-received-001", receivedProjection,
                "RECEIVED_REASON_REWRITE");
        persistAndProveImmutableQuarantine(dataRoot, fileStore, "quarantine-rejected-001", rejectedProjection,
                "REJECTED_REASON_REWRITE");
        persistAndProveImmutableQuarantine(dataRoot, fileStore, "quarantine-conflict-001", conflictProjection,
                "CONFLICT_REASON_REWRITE");

        LifecycleTimelineV1 pending = LifecycleTimelineV1.initial(
                "record-fixture-pending-0001", usd.runId(), usd.rawRef(), usd.receivedAt());
        LifecycleTimelineV1 verified = validatedTerminalTimeline(usd, ValidationStatus.VERIFIED, null);
        LifecycleTimelineV1 published = publishedTimeline(usd);
        assertThrows(SchemaValidationException.class,
                () -> QuarantineProjectionV1.fromTerminal(usd, pending, storedUsd.rawFileSha256()));
        assertThrows(SchemaValidationException.class,
                () -> QuarantineProjectionV1.fromTerminal(usd, verified, storedUsd.rawFileSha256()));
        assertThrows(SchemaValidationException.class,
                () -> QuarantineProjectionV1.fromTerminal(usd, published, storedUsd.rawFileSha256()));
    }

    @Test
    void keepsRawAndActualRawConflictEvidenceCreateNewWithBothDataAndManifestBytesUntouched() throws Exception {
        DataRoot dataRoot = initializedFixtureRoot("raw-and-conflict-create-new");
        AtomicFileStore fileStore = fileStore(dataRoot);
        RawReceiptStore rawStore = rawStore(dataRoot);
        RawReceiptV1 original = fixtureJson("raw-receipt-dual-usd-v1.json", RawReceiptV1.class);
        RawReceiptV1 alteredIncoming = copyWithPayload(original,
                "D1-T03 test/contract fixture changed payload; NOT REAL PBOC\n".getBytes(StandardCharsets.UTF_8));

        rawStore.store(original);
        Path rawPath = dataRoot.resolveDataRef(original.rawRef());
        Path rawManifestPath = dataRoot.resolveDataRef(DataPaths.manifestRef(original.rawRef()));
        byte[] originalRawBytes = Files.readAllBytes(rawPath);
        byte[] originalRawManifestBytes = Files.readAllBytes(rawManifestPath);
        assertTrue(ManifestVerifier.matches(dataRoot, original.rawRef(), rawPath, rawManifestPath, List.of(original.runId())));

        assertThrows(ImmutableFileConflictException.class, () -> fileStore.commit(
                "raw-overwrite-attempt-001",
                DirtyTransactionType.SINGLE_FILE,
                MANIFEST_TIME,
                List.of(immutableTarget(original.rawRef(), JsonV1Codec.encodeFile(alteredIncoming), alteredIncoming.runId()))));
        assertArrayEquals(originalRawBytes, Files.readAllBytes(rawPath));
        assertArrayEquals(originalRawManifestBytes, Files.readAllBytes(rawManifestPath));

        RawReceiptConflictException collision = assertThrows(RawReceiptConflictException.class,
                () -> rawStore.store(alteredIncoming));
        assertArrayEquals(originalRawBytes, Files.readAllBytes(rawPath), "raw collision never replaces original bytes");
        assertArrayEquals(originalRawManifestBytes, Files.readAllBytes(rawManifestPath),
                "raw collision never replaces original manifest bytes");

        Path evidencePath = dataRoot.resolveDataRef(collision.conflictRef());
        Path evidenceManifestPath = dataRoot.resolveDataRef(DataPaths.manifestRef(collision.conflictRef()));
        byte[] evidenceBytes = Files.readAllBytes(evidencePath);
        byte[] evidenceManifestBytes = Files.readAllBytes(evidenceManifestPath);
        RawConflictEvidenceV1 evidence = JsonV1Codec.decodeFile(evidenceBytes, RawConflictEvidenceV1.class);
        assertEquals(original.itemId(), evidence.itemId());
        assertEquals(original.runId(), evidence.runId());
        assertEquals(original.rawRef(), evidence.existingRawRef());
        assertEquals(alteredIncoming.payloadSha256(), evidence.incomingReceipt().payloadSha256());
        assertTrue(ManifestVerifier.matches(dataRoot, collision.conflictRef(), evidencePath, evidenceManifestPath,
                List.of(original.runId())));

        RawConflictEvidenceV1 alteredEvidence = new RawConflictEvidenceV1(
                evidence.schemaVersion(), evidence.conflictId(), evidence.itemId(), evidence.runId(),
                evidence.existingRawRef(), evidence.existingFileSha256(), evidence.incomingFileSha256(),
                evidence.incomingReceipt(), evidence.detectedAt().plusSeconds(1));
        assertThrows(ImmutableFileConflictException.class, () -> fileStore.commit(
                "raw-conflict-overwrite-attempt-001",
                DirtyTransactionType.SINGLE_FILE,
                MANIFEST_TIME,
                List.of(immutableTarget(collision.conflictRef(), JsonV1Codec.encodeFile(alteredEvidence), evidence.runId()))));
        assertArrayEquals(evidenceBytes, Files.readAllBytes(evidencePath));
        assertArrayEquals(evidenceManifestBytes, Files.readAllBytes(evidenceManifestPath));
        assertFalse(Files.exists(dataRoot.resolveDataRef(DataPaths.dirtyMarkerRef("raw-overwrite-attempt-001"))));
        assertFalse(Files.exists(dataRoot.resolveDataRef(DataPaths.dirtyMarkerRef("raw-conflict-overwrite-attempt-001"))));
    }

    private void assertExplicitFixtureResponse(byte[] responseBytes, RawReceiptV1 usd, RawReceiptV1 eur) {
        String response = new String(responseBytes, StandardCharsets.UTF_8);
        assertTrue(response.contains("test/contract fixture"));
        assertTrue(response.contains("NOT REAL PBOC"));
        assertTrue(response.contains("NOT AT-SRC-002 or Day 1/Day 2 PASS"));
        assertTrue(response.contains("fixture.usd.cny=7.123456789"));
        assertTrue(response.contains("fixture.eur.cny=7.987654321"));
        assertEquals(SHARED_PAYLOAD_SHA256, sha256(responseBytes));
        assertEquals(SHARED_PAYLOAD_SHA256, usd.payloadSha256());
        assertEquals(SHARED_PAYLOAD_SHA256, eur.payloadSha256());
        assertArrayEquals(responseBytes, Base64.getDecoder().decode(usd.payloadBase64()));
        assertArrayEquals(responseBytes, Base64.getDecoder().decode(eur.payloadBase64()));
        assertEquals(SHARED_ACQUISITION_ID, usd.acquisitionId());
        assertEquals(SHARED_ACQUISITION_ID, eur.acquisitionId());
        assertNotEquals(usd.runId(), eur.runId());
        assertNotEquals(usd.rawRef(), eur.rawRef());
    }

    private DataRoot initializedFixtureRoot(String directory) throws IOException {
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve(directory));
        root.createIfAbsentAndRequireWritable();
        ConfigActivationStore configs = new ConfigActivationStore(root, fileStore(root), FIXED_CLOCK);
        configs.ensureInitialDefault();
        configs.activate(fixtureJson("monitor-series-contract-fixture-v2.json", MonitorSeriesConfigV1.class));
        return root;
    }

    private AtomicFileStore fileStore(DataRoot root) {
        return new AtomicFileStore(root, new DirtyMarkerCodec());
    }

    private RawReceiptStore rawStore(DataRoot root) {
        return new RawReceiptStore(root, fileStore(root), FIXED_CLOCK);
    }

    private void persistTimeline(
            DataRoot root,
            AtomicFileStore fileStore,
            String transactionId,
            LifecycleTimelineV1 timeline,
            byte[] frozenFixtureBytes
    ) {
        String dataRef = DataPaths.stagingRef(timeline.runId());
        fileStore.commit(transactionId, DirtyTransactionType.SINGLE_FILE, MANIFEST_TIME,
                List.of(new FileTransactionTarget(
                        DirtyTargetRole.BUSINESS_FILE,
                        dataRef,
                        frozenFixtureBytes,
                        manuallyDerivedJsonManifest(dataRef, frozenFixtureBytes, timeline.runId()),
                        false)));
        assertTrue(ManifestVerifier.matches(root, dataRef, root.resolveDataRef(dataRef),
                root.resolveDataRef(DataPaths.manifestRef(dataRef)), List.of(timeline.runId())));
    }

    private void assertRawArtifact(DataRoot root, RawReceiptV1 expected) throws IOException {
        Path data = root.resolveDataRef(expected.rawRef());
        Path manifest = root.resolveDataRef(DataPaths.manifestRef(expected.rawRef()));
        RawReceiptV1 actual = JsonV1Codec.decodeFile(Files.readAllBytes(data), RawReceiptV1.class);
        assertTrue(Files.isRegularFile(data));
        assertTrue(Files.isRegularFile(manifest));
        assertEquals(expected.rawRef(), actual.rawRef());
        assertEquals(expected.runId(), actual.runId());
        assertEquals(expected.payloadSha256(), actual.payloadSha256());
        assertFalse(new String(Files.readAllBytes(data), StandardCharsets.UTF_8).contains("processingStage"));
        assertFalse(new String(Files.readAllBytes(data), StandardCharsets.UTF_8).contains("validationStatus"));
        assertTrue(ManifestVerifier.matches(root, expected.rawRef(), data, manifest, List.of(expected.runId())));
    }

    private void assertTimelineArtifact(DataRoot root, LifecycleTimelineV1 expected, byte[] frozenBytes) throws IOException {
        String dataRef = DataPaths.stagingRef(expected.runId());
        Path data = root.resolveDataRef(dataRef);
        Path manifest = root.resolveDataRef(DataPaths.manifestRef(dataRef));
        assertArrayEquals(frozenBytes, Files.readAllBytes(data), "the transaction must preserve frozen fixture bytes");
        assertEquals(expected, JsonV1Codec.decodeFile(Files.readAllBytes(data), LifecycleTimelineV1.class));
        assertTrue(Files.isRegularFile(manifest));
        assertTrue(ManifestVerifier.matches(root, dataRef, data, manifest, List.of(expected.runId())));
    }

    private void persistAndProveImmutableQuarantine(
            DataRoot root,
            AtomicFileStore fileStore,
            String transactionId,
            QuarantineProjectionV1 projection,
            String rewrittenReason
    ) throws IOException {
        byte[] originalBytes = JsonV1Codec.encodeFile(projection);
        FileTransactionTarget target = immutableTarget(projection.quarantineRef(), originalBytes, projection.runId());
        fileStore.commit(transactionId, DirtyTransactionType.SINGLE_FILE, MANIFEST_TIME, List.of(target));
        Path data = root.resolveDataRef(projection.quarantineRef());
        Path manifest = root.resolveDataRef(DataPaths.manifestRef(projection.quarantineRef()));
        byte[] storedData = Files.readAllBytes(data);
        byte[] storedManifest = Files.readAllBytes(manifest);
        assertArrayEquals(originalBytes, storedData);
        assertTrue(ManifestVerifier.matches(root, projection.quarantineRef(), data, manifest, List.of(projection.runId())));

        QuarantineProjectionV1 rewritten = new QuarantineProjectionV1(
                projection.schemaVersion(), projection.quarantineRef(), projection.itemId(), projection.runId(),
                projection.rawRef(), projection.stagingRef(), projection.terminalRecordVersion(), projection.processingStage(),
                projection.validationStatus(), rewrittenReason, projection.validationVersion(), projection.rawPayloadSha256(),
                projection.rawFileSha256(), projection.receivedAt(), projection.quarantinedAt());
        assertThrows(ImmutableFileConflictException.class, () -> fileStore.commit(
                transactionId + "-overwrite",
                DirtyTransactionType.SINGLE_FILE,
                MANIFEST_TIME,
                List.of(immutableTarget(projection.quarantineRef(), JsonV1Codec.encodeFile(rewritten), projection.runId()))));
        assertArrayEquals(storedData, Files.readAllBytes(data));
        assertArrayEquals(storedManifest, Files.readAllBytes(manifest));
        assertFalse(Files.exists(root.resolveDataRef(DataPaths.dirtyMarkerRef(transactionId + "-overwrite"))));
    }

    private FileTransactionTarget immutableTarget(String dataRef, byte[] dataBytes, String runId) {
        return new FileTransactionTarget(
                DirtyTargetRole.BUSINESS_FILE,
                dataRef,
                dataBytes,
                manuallyDerivedJsonManifest(dataRef, dataBytes, runId),
                true);
    }

    private static byte[] manuallyDerivedJsonManifest(String dataRef, byte[] dataBytes, String runId) {
        String fileName = dataRef.substring(dataRef.lastIndexOf('/') + 1);
        String text = "{\n"
                + "  \"schemaVersion\": \"1.0\",\n"
                + "  \"fileName\": \"" + fileName + "\",\n"
                + "  \"fileSha256\": \"" + sha256(dataBytes) + "\",\n"
                + "  \"byteLength\": " + dataBytes.length + ",\n"
                + "  \"rowCount\": null,\n"
                + "  \"minBusinessDate\": null,\n"
                + "  \"maxBusinessDate\": null,\n"
                + "  \"sourceRunIds\": [\"" + runId + "\"],\n"
                + "  \"generatedAt\": \"2026-08-10T01:00:00Z\",\n"
                + "  \"commitState\": \"COMMITTED\"\n"
                + "}\n";
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private LifecycleTimelineV1 receivedRejectedTimeline(RawReceiptV1 raw) {
        LifecycleTimelineV1 initial = LifecycleTimelineV1.initial(
                "record-received-rejected-" + raw.runId(), raw.runId(), raw.rawRef(), raw.receivedAt());
        return initial.append(new LifecycleSnapshotV1(
                2, ProcessingStage.RECEIVED, ValidationStatus.REJECTED, null,
                "PAYLOAD_SHAPE_INVALID", null, null, null, null, raw.receivedAt().plusSeconds(1)));
    }

    private LifecycleTimelineV1 validatedTerminalTimeline(
            RawReceiptV1 raw,
            ValidationStatus terminalStatus,
            String reasonCode
    ) {
        CandidateV1 candidate = candidate(raw);
        LifecycleTimelineV1 parsed = LifecycleTimelineV1.initial(
                        "record-terminal-" + raw.runId(), raw.runId(), raw.rawRef(), raw.receivedAt())
                .append(new LifecycleSnapshotV1(
                        2, ProcessingStage.PARSED, ValidationStatus.PENDING, candidate,
                        null, null, null, null, null, raw.receivedAt().plusSeconds(1)));
        return parsed.append(new LifecycleSnapshotV1(
                3, ProcessingStage.VALIDATED, terminalStatus, candidate,
                reasonCode, "validation-contract-v1", raw.receivedAt().plusSeconds(2), null, null,
                raw.receivedAt().plusSeconds(2)));
    }

    private LifecycleTimelineV1 publishedTimeline(RawReceiptV1 raw) {
        CandidateV1 candidate = candidate(raw);
        LifecycleTimelineV1 validated = validatedTerminalTimeline(raw, ValidationStatus.VERIFIED, null);
        return validated.append(new LifecycleSnapshotV1(
                4, ProcessingStage.PUBLISHED, ValidationStatus.VERIFIED, candidate,
                null, "validation-contract-v1", raw.receivedAt().plusSeconds(2), raw.receivedAt().plusSeconds(3),
                DataPaths.stagingRef(raw.runId()) + "#recordVersion=4", raw.receivedAt().plusSeconds(3)));
    }

    private CandidateV1 candidate(RawReceiptV1 raw) {
        return new CandidateV1(
                raw.itemId(), raw.sourceBusinessDate(), raw.rawValue(), raw.rawCurrency(), raw.rawUnit(),
                raw.providerType(), raw.actualSourceName(), raw.accessMethod(), "normalization-contract-v1");
    }

    private RawReceiptV1 copyWithRunId(RawReceiptV1 source, String runId) {
        return new RawReceiptV1(
                source.schemaVersion(), RawReceiptV1.deriveRawRef(source.mode(), source.providerType(), source.itemId(),
                        source.receivedAt(), runId), source.acquisitionId(), runId, source.mode(), source.providerType(),
                source.accessMethod(), source.configVersion(), source.actualSourceName(), source.sourceUrl(),
                source.sourceReference(), source.itemId(), source.sourceBusinessDateRaw(), source.sourceBusinessDate(),
                source.sourcePublishedAtRaw(), source.sourcePublishedAt(), source.receivedAt(), source.inputAt(),
                source.rawValue(), source.rawUnit(), source.rawCurrency(), source.operatorRef(), source.httpStatus(),
                source.contentType(), source.payloadEncoding(), source.payloadBase64(), source.payloadSha256(),
                source.matchAnchor(), source.updatedAt());
    }

    private RawReceiptV1 copyWithPayload(RawReceiptV1 source, byte[] payload) {
        String encodedPayload = Base64.getEncoder().encodeToString(payload);
        return new RawReceiptV1(
                source.schemaVersion(), source.rawRef(), source.acquisitionId(), source.runId(), source.mode(),
                source.providerType(), source.accessMethod(), source.configVersion(), source.actualSourceName(),
                source.sourceUrl(), source.sourceReference(), source.itemId(), source.sourceBusinessDateRaw(),
                source.sourceBusinessDate(), source.sourcePublishedAtRaw(), source.sourcePublishedAt(), source.receivedAt(),
                source.inputAt(), source.rawValue(), source.rawUnit(), source.rawCurrency(), source.operatorRef(),
                source.httpStatus(), source.contentType(), source.payloadEncoding(), encodedPayload, sha256(payload),
                source.matchAnchor(), source.updatedAt());
    }

    private byte[] fixtureBytes(String name) throws IOException {
        try (InputStream stream = Objects.requireNonNull(
                getClass().getClassLoader().getResourceAsStream(FIXTURE_ROOT + name),
                () -> "Missing frozen test/contract fixture " + name)) {
            return stream.readAllBytes();
        }
    }

    private <T> T fixtureJson(String name, Class<T> type) throws IOException {
        return JsonV1Codec.decodeFile(fixtureBytes(name), type);
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder text = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                text.append(String.format("%02x", value));
            }
            return text.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available in a Java 17 runtime", exception);
        }
    }
}
