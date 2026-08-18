package com.supplymind.warning;

import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.storage.AtomicFileStore;
import com.supplymind.foundation.storage.AtomicMoveSupport;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.DirtyMarkerCodec;
import com.supplymind.foundation.storage.ManifestFactory;
import com.supplymind.foundation.storage.ManifestVerifier;
import com.supplymind.foundation.storage.StorageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D8-T02 DEC-061 acknowledgement sidecar tests. The original WarningRecordV1 stays byte-identical;
 * the ack is a separate CREATE_NEW sidecar with its own manifest; exact retry is idempotent,
 * different content fails closed, dispositionNote is controlled, restarts restore ACKNOWLEDGED,
 * and the query layer never decodes ack/manifest files as WarningRecord evidence.
 */
class WarningAckStoreTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-17T02:00:00Z"), ZoneOffset.UTC);
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-08-17T02:00:00+08:00");

    @TempDir
    Path temporaryDirectory;

    @Test
    void ackWritesSidecarAndKeepsOriginalWarningByteIdentical() {
        Harness harness = harness();
        WarningRecordV1 warning = harness.store().store(warningRecord());
        Path original = harness.root().resolveDataRef(
                DataPaths.warningRef(warning.warningMonth(), warning.warningId()));
        byte[] originalBytes = readBytes(original);

        WarningAcknowledgementV1 ack = harness.ackStore().acknowledge(warning, "已核实，接受", NOW);

        assertEquals(WarningAcknowledgementV1.AckStatus.ACKNOWLEDGED, ack.status());
        assertEquals(warning.warningId(), ack.warningId());
        assertEquals(DataPaths.warningRef(warning.warningMonth(), warning.warningId()), ack.warningRef());
        assertTrue(java.util.Arrays.equals(originalBytes, readBytes(original)),
                "the original warning evidence is byte-identical after acknowledgement");

        String ackRef = DataPaths.warningAckRef(warning.warningMonth(), warning.warningId());
        Path ackPath = harness.root().resolveDataRef(ackRef);
        Path manifest = harness.root().resolveDataRef(DataPaths.manifestRef(ackRef));
        assertTrue(Files.isRegularFile(ackPath), "the ack sidecar is really persisted");
        assertTrue(ManifestVerifier.matches(harness.root(), ackRef, ackPath, manifest, List.of()),
                "the ack sidecar has a valid adjacent manifest");

        WarningAcknowledgementV1 persisted = harness.ackStore().readVerified(warning).orElseThrow();
        assertEquals(ack.dispositionNote(), persisted.dispositionNote());
        assertEquals(ack.warningFileSha256(), persisted.warningFileSha256());
    }

    @Test
    void identicalRetryIsIdempotentAndDifferentContentFailsClosed() {
        Harness harness = harness();
        WarningRecordV1 warning = harness.store().store(warningRecord());
        String warningRef = DataPaths.warningRef(warning.warningMonth(), warning.warningId());

        WarningAcknowledgementV1 first = harness.ackStore().acknowledge(warning, "已核实", NOW);
        WarningAcknowledgementV1 second = harness.ackStore().acknowledge(warning, "已核实", NOW);

        assertEquals(first.warningFileSha256(), second.warningFileSha256(),
                "an exact byte-identical retry is an idempotent no-op");

        assertThrows(StorageException.class,
                () -> harness.ackStore().acknowledge(warning, "不同处置备注", NOW),
                "a different ack under the same warningId fails closed (immutable conflict)");
        assertEquals("已核实",
                harness.ackStore().readVerified(warning).orElseThrow().dispositionNote(),
                "the first ack is never overwritten");
    }

    @Test
    void restartRestoresAcknowledgedStateFromSidecar() {
        Harness harness = harness();
        WarningRecordV1 warning = harness.store().store(warningRecord());
        harness.ackStore().acknowledge(warning, "重启验证", NOW);

        Harness restarted = new Harness(harness.root());
        WarningAcknowledgementV1 restored = restarted.ackStore().readVerified(warning).orElseThrow();
        assertEquals(WarningAcknowledgementV1.AckStatus.ACKNOWLEDGED, restored.status(),
                "restart restores the ACKNOWLEDGED state from the manifest-valid sidecar");
        assertTrue(restarted.query().isAcknowledged(warning),
                "the query layer sees the acknowledged state after restart");
    }

    @Test
    void dispositionNoteValidationRejectsUncontrolledText() {
        Harness harness = harness();
        WarningRecordV1 warning = harness.store().store(warningRecord());

        assertThrows(com.supplymind.foundation.model.SchemaValidationException.class,
                () -> harness.ackStore().acknowledge(warning, "", NOW),
                "blank dispositionNote is rejected");
        assertThrows(com.supplymind.foundation.model.SchemaValidationException.class,
                () -> harness.ackStore().acknowledge(warning, "x".repeat(501), NOW),
                "over-length dispositionNote is rejected");
        assertThrows(com.supplymind.foundation.model.SchemaValidationException.class,
                () -> harness.ackStore().acknowledge(warning, "路径注入 C:\\secret", NOW),
                "path-shaped dispositionNote is rejected");
        assertThrows(com.supplymind.foundation.model.SchemaValidationException.class,
                () -> harness.ackStore().acknowledge(warning, "换行注入\n下一行", NOW),
                "newline injection is rejected");
    }

    @Test
    void queryNeverDecodesAckOrManifestFilesAsWarningRecords() {
        Harness harness = harness();
        WarningRecordV1 warning = harness.store().store(warningRecord());
        harness.ackStore().acknowledge(warning, "扫描隔离验证", NOW);

        List<WarningRecordV1> records = harness.query().queryByRange(
                warning.itemId(), LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-31"));

        assertEquals(1, records.size(),
                "exactly one WarningRecord - the ack/manifest files are never decoded as records");
        assertEquals(warning.warningId(), records.get(0).warningId());
    }

    @Test
    void realFromToRangeSpansExactMonthsNotARoughLookback() {
        Harness harness = harness();
        WarningRecordV1 august = harness.store().store(warningRecord());

        // July warning (stored directly under warning/2026-07) must NOT appear in an August query.
        WarningRecordV1 july = new WarningRecordV1(
                "1.0", "w-july-00000000000000000000000000000001", "r1", "v1", august.itemId(), "month",
                "2026-07-01", "2026-07-31", null, "5", "1.5", "3.0",
                WarningRecordV1.RiskLevel.MEDIUM, List.of("processed/aggregate/x/month/2026.csv"),
                "PUBLISHED_VERIFIED", NOW.minusMonths(1), "f".repeat(64), true, "TEST/DEMO");
        harness.store().store(july);

        List<WarningRecordV1> augustOnly = harness.query().queryByRange(
                august.itemId(), LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-31"));
        assertEquals(1, augustOnly.size(), "exact range: the July record is outside [2026-08-01, 2026-08-31]");
        assertEquals(august.warningId(), augustOnly.get(0).warningId());

        List<WarningRecordV1> julyToAugust = harness.query().queryByRange(
                august.itemId(), LocalDate.parse("2026-07-01"), LocalDate.parse("2026-08-31"));
        assertEquals(2, julyToAugust.size(), "the exact [from,to] months are both scanned");
    }

    @Test
    void brokenWarningFileDoesNotAbortTheScan() throws Exception {
        Harness harness = harness();
        WarningRecordV1 warning = harness.store().store(warningRecord());

        String badRef = DataPaths.warningRef(warning.warningMonth(), "w-broken-00000000000000000000000000000001");
        Path badPath = harness.root().resolveDataRef(badRef);
        Files.createDirectories(badPath.getParent());
        Files.write(badPath, "not json".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        List<WarningRecordV1> records = harness.query().queryByRange(
                warning.itemId(), LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-31"));
        assertEquals(1, records.size(),
                "a broken warning file is skipped - the remaining valid evidence still returns");
    }

    // ---- M2: DEC-061 authoritative binding attacks ----

    @Test
    void unmodifiedAckReadsBackThroughTheAuthoritativeEntry() {
        Harness harness = harness();
        WarningRecordV1 warning = harness.store().store(warningRecord());
        harness.ackStore().acknowledge(warning, "权威入口验证", NOW);

        assertTrue(harness.ackStore().readVerified(warning).isPresent(),
                "the unmodified ack + original warning pass the authoritative verification");
        assertTrue(harness.query().isAcknowledged(warning),
                "the query layer uses the same authoritative entry");
        assertTrue(harness.ackStore().readVerified(warning).orElseThrow()
                        .warningFileSha256().equals(
                                com.supplymind.foundation.storage.FileDigest.sha256(
                                        harness.root().resolveDataRef(DataPaths.warningRef(
                                                warning.warningMonth(), warning.warningId())))),
                "the recomputed original SHA-256 binds to the ack");
    }

    @Test
    void tamperedAckWarningIdWithRewrittenManifestFailsClosed() throws Exception {
        Harness harness = harness();
        WarningRecordV1 warning = harness.store().store(warningRecord());
        harness.ackStore().acknowledge(warning, "篡改测试", NOW);

        String ackRef = DataPaths.warningAckRef(warning.warningMonth(), warning.warningId());
        Path ackPath = harness.root().resolveDataRef(ackRef);
        WarningAcknowledgementV1 ack = JsonV1Codec.decodeFile(
                readBytes(ackPath), WarningAcknowledgementV1.class);
        WarningAcknowledgementV1 forged = new WarningAcknowledgementV1(
                "1.0", "w-forged-00000000000000000000000000000002", ack.warningRef(),
                ack.warningFileSha256(), ack.status(), ack.acknowledgedAt(), ack.dispositionNote());
        rewriteWithManifest(harness.root(), ackRef, forged);

        assertThrows(StorageException.class,
                () -> harness.ackStore().readVerified(warning),
                "a forged warningId in the ack fails closed even with a rewritten manifest");
        assertThrows(StorageException.class,
                () -> harness.query().isAcknowledged(warning),
                "the query layer must fail closed too - never a silent acknowledged=true");
    }

    @Test
    void tamperedAckWarningRefWithRewrittenManifestFailsClosed() throws Exception {
        Harness harness = harness();
        WarningRecordV1 warning = harness.store().store(warningRecord());
        harness.ackStore().acknowledge(warning, "篡改测试", NOW);

        String ackRef = DataPaths.warningAckRef(warning.warningMonth(), warning.warningId());
        Path ackPath = harness.root().resolveDataRef(ackRef);
        WarningAcknowledgementV1 ack = JsonV1Codec.decodeFile(
                readBytes(ackPath), WarningAcknowledgementV1.class);
        WarningAcknowledgementV1 forged = new WarningAcknowledgementV1(
                "1.0", ack.warningId(), "warning/2026-08/other-warning.json",
                ack.warningFileSha256(), ack.status(), ack.acknowledgedAt(), ack.dispositionNote());
        rewriteWithManifest(harness.root(), ackRef, forged);

        assertThrows(StorageException.class,
                () -> harness.ackStore().readVerified(warning),
                "a forged warningRef in the ack fails closed even with a rewritten manifest");
        assertThrows(StorageException.class,
                () -> harness.query().isAcknowledged(warning));
    }

    @Test
    void tamperedAckShaWithRewrittenManifestFailsClosed() throws Exception {
        Harness harness = harness();
        WarningRecordV1 warning = harness.store().store(warningRecord());
        harness.ackStore().acknowledge(warning, "篡改测试", NOW);

        String ackRef = DataPaths.warningAckRef(warning.warningMonth(), warning.warningId());
        Path ackPath = harness.root().resolveDataRef(ackRef);
        WarningAcknowledgementV1 ack = JsonV1Codec.decodeFile(
                readBytes(ackPath), WarningAcknowledgementV1.class);
        WarningAcknowledgementV1 forged = new WarningAcknowledgementV1(
                "1.0", ack.warningId(), ack.warningRef(), "0".repeat(64),
                ack.status(), ack.acknowledgedAt(), ack.dispositionNote());
        rewriteWithManifest(harness.root(), ackRef, forged);

        assertThrows(StorageException.class,
                () -> harness.ackStore().readVerified(warning),
                "a forged warningFileSha256 in the ack fails closed even with a rewritten manifest");
        assertThrows(StorageException.class,
                () -> harness.query().isAcknowledged(warning));
    }

    @Test
    void tamperedOriginalWarningWithRewrittenWarningManifestFailsClosed() throws Exception {
        Harness harness = harness();
        WarningRecordV1 warning = harness.store().store(warningRecord());
        harness.ackStore().acknowledge(warning, "篡改测试", NOW);

        // Tamper the ORIGINAL warning bytes and rewrite ITS manifest too - a synchronized
        // replacement must still fail because the ack's warningFileSha256 no longer binds.
        String warningRef = DataPaths.warningRef(warning.warningMonth(), warning.warningId());
        WarningRecordV1 altered = new WarningRecordV1(
                "1.0", warning.warningId(), "demo-price-change-x", "demo-v1",
                "MAT.ADC12.SMM", "month", "2026-08-01", "2026-08-31", null,
                "0.99", "0.987", "0.052", WarningRecordV1.RiskLevel.HIGH,
                warning.evidenceRefs(), "PUBLISHED_VERIFIED", NOW, "b".repeat(64), true,
                "TEST/DEMO threshold - tampered");
        rewriteWithManifest(harness.root(), warningRef, altered);

        assertThrows(StorageException.class,
                () -> harness.ackStore().readVerified(warning),
                "a tampered original warning fails the SHA binding even with its manifest rewritten");
        assertThrows(StorageException.class,
                () -> harness.query().isAcknowledged(warning));
    }

    @Test
    void missingOriginalWarningManifestFailsClosed() throws Exception {
        Harness harness = harness();
        WarningRecordV1 warning = harness.store().store(warningRecord());
        harness.ackStore().acknowledge(warning, "篡改测试", NOW);

        String warningRef = DataPaths.warningRef(warning.warningMonth(), warning.warningId());
        Files.deleteIfExists(harness.root().resolveDataRef(DataPaths.manifestRef(warningRef)));

        assertThrows(StorageException.class,
                () -> harness.ackStore().readVerified(warning),
                "a missing original warning manifest fails closed");
        assertThrows(StorageException.class,
                () -> harness.query().isAcknowledged(warning));
    }

    @Test
    void sidecarAndManifestSynchronouslyReplacedStillFailsTheBinding() throws Exception {
        Harness harness = harness();
        WarningRecordV1 warning = harness.store().store(warningRecord());
        harness.ackStore().acknowledge(warning, "篡改测试", NOW);

        // Replace BOTH the sidecar and its manifest with a fully self-consistent forged ack that
        // points to a DIFFERENT warningId - the binding check must still reject it.
        String ackRef = DataPaths.warningAckRef(warning.warningMonth(), warning.warningId());
        WarningAcknowledgementV1 forged = new WarningAcknowledgementV1(
                "1.0", "w-forged-sync-00000000000000000000000000000003",
                "warning/2026-08/w-forged-sync-00000000000000000000000000000003.json",
                "1".repeat(64), WarningAcknowledgementV1.AckStatus.ACKNOWLEDGED, NOW, "同步替换");
        rewriteWithManifest(harness.root(), ackRef, forged);

        assertThrows(StorageException.class,
                () -> harness.ackStore().readVerified(warning),
                "a synchronized sidecar+manifest replacement can never produce acknowledged=true");
        assertThrows(StorageException.class,
                () -> harness.query().isAcknowledged(warning));
    }

    private static void rewriteWithManifest(DataRoot root, String dataRef, Object document) throws Exception {
        byte[] bytes = JsonV1Codec.encodeFile(document);
        Path path = root.resolveDataRef(dataRef);
        Files.createDirectories(path.getParent());
        Files.write(path, bytes);
        com.supplymind.foundation.model.ManifestV1 manifest = ManifestFactory.json(
                dataRef, bytes, List.of(), NOW);
        Files.write(root.resolveDataRef(DataPaths.manifestRef(dataRef)),
                JsonV1Codec.encodeFile(manifest));
    }

    // ---- fixture ----

    private Harness harness() {
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve("ack root " + System.nanoTime()));
        AtomicMoveSupport.probeOrFail(root);
        return new Harness(root);
    }

    private WarningRecordV1 warningRecord() {
        return new WarningRecordV1(
                "1.0", "w-20260817-00000000000000000000000000000001", "demo-price-change-x", "demo-v1",
                "MAT.ADC12.SMM", "month", "2026-08-01", "2026-08-31", null,
                "0.05", "0.087", "0.052", WarningRecordV1.RiskLevel.HIGH,
                List.of("processed/aggregate/MAT.ADC12.SMM/month/2026.csv"),
                "PUBLISHED_VERIFIED", NOW, "a".repeat(64), true,
                "TEST/DEMO threshold - not a final business threshold (EXT-07 open)");
    }

    private static byte[] readBytes(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (java.io.IOException exception) {
            throw new RuntimeException(exception);
        }
    }

    private record Harness(
            DataRoot root,
            WarningStore store,
            WarningAckStore ackStore,
            WarningQueryService query
    ) {
        Harness(DataRoot root) {
            this(root, new WarningStore(root, new AtomicFileStore(root, new DirtyMarkerCodec()), CLOCK),
                    new WarningAckStore(root, new AtomicFileStore(root, new DirtyMarkerCodec()), CLOCK),
                    new WarningQueryService(root,
                            new WarningAckStore(root, new AtomicFileStore(root, new DirtyMarkerCodec()), CLOCK)));
        }
    }
}
