package com.supplymind.foundation.acceptance;

import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.CandidateV1;
import com.supplymind.foundation.model.LifecycleSnapshotV1;
import com.supplymind.foundation.model.LifecycleTimelineV1;
import com.supplymind.foundation.model.MonitorSeriesConfigV1;
import com.supplymind.foundation.model.ProcessingStage;
import com.supplymind.foundation.model.RawReceiptV1;
import com.supplymind.foundation.model.SchemaValidationException;
import com.supplymind.foundation.model.ValidationStatus;
import com.supplymind.foundation.storage.AtomicFileStore;
import com.supplymind.foundation.storage.ConfigActivationStore;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.DirtyMarkerCodec;
import com.supplymind.foundation.storage.RawReceiptConflictException;
import com.supplymind.foundation.storage.RawReceiptStore;
import com.supplymind.foundation.storage.StorageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

/** AT-FILE-000 dual-currency acquisition, raw immutability, and v1 lifecycle acceptance evidence. */
class DualCurrencyRawLifecycleAcceptanceTest {

    private static final String FIXTURE_ROOT = "contracts/v1/valid/";
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-08-10T01:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path temporaryDirectory;

    @Test
    void keepsTheExplicitlySyntheticDualCurrencyEntitySeparateFromLivePbocEvidence() throws IOException {
        byte[] responseEntity = fixtureBytes("dual-currency-response-entity.txt");
        String responseText = new String(responseEntity, StandardCharsets.UTF_8);
        RawReceiptV1 usd = fixtureJson("raw-receipt-dual-usd-v1.json", RawReceiptV1.class);
        RawReceiptV1 eur = fixtureJson("raw-receipt-dual-eur-v1.json", RawReceiptV1.class);

        assertTrue(responseText.contains("test/contract fixture"));
        assertTrue(responseText.contains("NOT REAL PBOC"));
        assertTrue(responseText.contains("NOT AT-SRC-002 or Day 1/Day 2 PASS"));
        assertTrue(responseText.contains("fixture.usd.cny"));
        assertTrue(responseText.contains("fixture.eur.cny"));
        assertArrayEquals(responseEntity, Base64.getDecoder().decode(usd.payloadBase64()));
        assertArrayEquals(responseEntity, Base64.getDecoder().decode(eur.payloadBase64()));
        assertEquals("3f42e7ed771bce1fbbbcc35fc66bd3a1014ae3f0b709cb6c89e49c448fc4f34c", usd.payloadSha256());
        assertEquals(usd.payloadSha256(), eur.payloadSha256());
        assertEquals(usd.acquisitionId(), eur.acquisitionId());
        assertNotEquals(usd.runId(), eur.runId());
        assertNotEquals(usd.rawRef(), eur.rawRef());
        assertNotEquals(usd.itemId(), eur.itemId());
    }

    @Test
    void storesTwoItemReceiptsForOneAcquisitionWithoutCompetingForRawOrTimelineIdentity() throws IOException {
        RawReceiptV1 usd = fixtureJson("raw-receipt-dual-usd-v1.json", RawReceiptV1.class);
        RawReceiptV1 eur = fixtureJson("raw-receipt-dual-eur-v1.json", RawReceiptV1.class);
        DataRoot root = initializedDualFixtureRoot();
        RawReceiptStore rawStore = rawStore(root);

        RawReceiptStore.StoredRawReceipt storedUsd = rawStore.store(usd);
        RawReceiptStore.StoredRawReceipt replayUsd = rawStore.store(usd);
        RawReceiptStore.StoredRawReceipt storedEur = rawStore.store(eur);

        assertEquals(storedUsd, replayUsd, "same raw bytes must be an immutable idempotent replay");
        assertNotEquals(storedUsd.rawRef(), storedEur.rawRef());
        assertTrue(Files.isRegularFile(root.resolveDataRef(usd.rawRef())));
        assertTrue(Files.isRegularFile(root.resolveDataRef(eur.rawRef())));
        assertTrue(Files.isRegularFile(root.resolveDataRef(DataPaths.manifestRef(usd.rawRef()))));
        assertTrue(Files.isRegularFile(root.resolveDataRef(DataPaths.manifestRef(eur.rawRef()))));

        LifecycleTimelineV1 usdTimeline = publishedTimeline(usd);
        LifecycleTimelineV1 eurTimeline = publishedTimeline(eur);
        assertEquals(4, usdTimeline.currentRecordVersion());
        assertEquals(4, eurTimeline.currentRecordVersion());
        assertTrue(usdTimeline.isPublishedForDailyInput());
        assertTrue(eurTimeline.isPublishedForDailyInput());
        assertNotEquals(usdTimeline.runId(), eurTimeline.runId());
        assertNotEquals(usdTimeline.rawRef(), eurTimeline.rawRef());
        assertEquals(ProcessingStage.PUBLISHED, usdTimeline.current().processingStage());
        assertEquals(ValidationStatus.VERIFIED, usdTimeline.current().validationStatus(),
                "ProcessingStage and ValidationStatus remain separate fields");

        byte[] persistedTimeline = JsonV1Codec.encodeFile(usdTimeline);
        assertEquals(usdTimeline, JsonV1Codec.decodeFile(persistedTimeline, LifecycleTimelineV1.class));
        assertThrows(SchemaValidationException.class,
                () -> new com.supplymind.foundation.model.DailyInputRefV1(usd.runId(), usd.rawRef(), 3));
        assertEquals(4, new com.supplymind.foundation.model.DailyInputRefV1(usd.runId(), usd.rawRef(), 4).recordVersion());
    }

    @Test
    void rejectsForgedPathsAndPreservesIncomingCollisionEvidenceWithoutReplacingRaw() throws IOException {
        RawReceiptV1 original = fixtureJson("raw-receipt-dual-usd-v1.json", RawReceiptV1.class);
        DataRoot root = initializedDualFixtureRoot();
        RawReceiptStore rawStore = rawStore(root);
        rawStore.store(original);
        Path rawPath = root.resolveDataRef(original.rawRef());
        byte[] originalBytes = Files.readAllBytes(rawPath);

        assertThrows(StorageException.class, () -> root.resolveDataRef("../outside.json"));
        assertThrows(SchemaValidationException.class, () -> forgedRawRef(original));

        RawReceiptV1 differentIncoming = copyWithPayload(original,
                "D1-T03 changed synthetic payload; still not a live PBOC response\n".getBytes(StandardCharsets.UTF_8));
        RawReceiptConflictException conflict = assertThrows(RawReceiptConflictException.class,
                () -> rawStore.store(differentIncoming));

        assertTrue(conflict.conflictRef().startsWith("runtime/conflicts/raw/"));
        assertTrue(Files.isRegularFile(root.resolveDataRef(conflict.conflictRef())));
        assertTrue(Files.isRegularFile(root.resolveDataRef(DataPaths.manifestRef(conflict.conflictRef()))));
        assertArrayEquals(originalBytes, Files.readAllBytes(rawPath), "immutable raw must never be replaced on collision");
    }

    private DataRoot initializedDualFixtureRoot() throws IOException {
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve("双币 raw root"));
        DirtyMarkerCodec markerCodec = new DirtyMarkerCodec();
        AtomicFileStore fileStore = new AtomicFileStore(root, markerCodec);
        ConfigActivationStore configurationStore = new ConfigActivationStore(root, fileStore, FIXED_CLOCK);
        configurationStore.ensureInitialDefault();
        configurationStore.activate(fixtureJson("monitor-series-contract-fixture-v2.json", MonitorSeriesConfigV1.class));
        return root;
    }

    private RawReceiptStore rawStore(DataRoot root) {
        return new RawReceiptStore(root, new AtomicFileStore(root, new DirtyMarkerCodec()), FIXED_CLOCK);
    }

    private LifecycleTimelineV1 publishedTimeline(RawReceiptV1 raw) {
        OffsetDateTime received = raw.receivedAt();
        CandidateV1 candidate = new CandidateV1(
                raw.itemId(), raw.sourceBusinessDate(), raw.rawValue(), raw.rawCurrency(), raw.rawUnit(),
                raw.providerType(), raw.actualSourceName(), raw.accessMethod(), "normalization-contract-v1"
        );
        LifecycleTimelineV1 initial = LifecycleTimelineV1.initial("record-" + raw.runId(), raw.runId(), raw.rawRef(), received);
        LifecycleSnapshotV1 parsed = new LifecycleSnapshotV1(
                2, ProcessingStage.PARSED, ValidationStatus.PENDING, candidate,
                null, null, null, null, null, received.plusSeconds(1));
        OffsetDateTime validatedAt = received.plusSeconds(2);
        LifecycleSnapshotV1 validated = new LifecycleSnapshotV1(
                3, ProcessingStage.VALIDATED, ValidationStatus.VERIFIED, candidate,
                null, "validation-contract-v1", validatedAt, null, null, validatedAt);
        OffsetDateTime publishedAt = received.plusSeconds(3);
        LifecycleSnapshotV1 published = new LifecycleSnapshotV1(
                4, ProcessingStage.PUBLISHED, ValidationStatus.VERIFIED, candidate,
                null, "validation-contract-v1", validatedAt, publishedAt,
                "staging/" + raw.runId() + ".json#recordVersion=4", publishedAt);
        return initial.append(parsed).append(validated).append(published);
    }

    private RawReceiptV1 forgedRawRef(RawReceiptV1 original) {
        return new RawReceiptV1(
                original.schemaVersion(), "raw/test/synthetic_demo/forged/2026/08/not-derived.json",
                original.acquisitionId(), original.runId(), original.mode(), original.providerType(), original.accessMethod(),
                original.configVersion(), original.actualSourceName(), original.sourceUrl(), original.sourceReference(),
                original.itemId(), original.sourceBusinessDateRaw(), original.sourceBusinessDate(),
                original.sourcePublishedAtRaw(), original.sourcePublishedAt(), original.receivedAt(), original.inputAt(),
                original.rawValue(), original.rawUnit(), original.rawCurrency(), original.operatorRef(), original.httpStatus(),
                original.contentType(), original.payloadEncoding(), original.payloadBase64(), original.payloadSha256(),
                original.matchAnchor(), original.updatedAt(), null);
    }

    private RawReceiptV1 copyWithPayload(RawReceiptV1 original, byte[] payload) {
        String encodedPayload = Base64.getEncoder().encodeToString(payload);
        return new RawReceiptV1(
                original.schemaVersion(), original.rawRef(), original.acquisitionId(), original.runId(), original.mode(),
                original.providerType(), original.accessMethod(), original.configVersion(), original.actualSourceName(),
                original.sourceUrl(), original.sourceReference(), original.itemId(), original.sourceBusinessDateRaw(),
                original.sourceBusinessDate(), original.sourcePublishedAtRaw(), original.sourcePublishedAt(), original.receivedAt(),
                original.inputAt(), original.rawValue(), original.rawUnit(), original.rawCurrency(), original.operatorRef(),
                original.httpStatus(), original.contentType(), original.payloadEncoding(), encodedPayload,
                JsonV1Codec.sha256LowerHex(payload), original.matchAnchor(), original.updatedAt(), null);
    }

    private byte[] fixtureBytes(String name) throws IOException {
        try (InputStream stream = Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(FIXTURE_ROOT + name),
                () -> "Missing contract fixture " + name)) {
            return stream.readAllBytes();
        }
    }

    private <T> T fixtureJson(String name, Class<T> type) throws IOException {
        return JsonV1Codec.decodeFile(fixtureBytes(name), type);
    }
}
