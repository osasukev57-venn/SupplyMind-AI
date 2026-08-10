package com.supplymind.foundation.storage;

import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.ManifestV1;
import com.supplymind.foundation.model.Mode;
import com.supplymind.foundation.model.MonitorSeriesDefaults;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.RawAcquisitionV1;
import com.supplymind.foundation.model.RawReceiptV1;
import com.supplymind.foundation.model.SchemaV1;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DEC-056 enforced item Raw -&gt; source Raw traceability: the production RawReceiptStore
 * must fail closed whenever an external HTTP item receipt cannot be proven to link to its
 * canonical source acquisition (existence, manifest, acquisitionId, payload identity).
 */
class RawAcquisitionLinkVerifierTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-10T02:00:00Z"), ZoneOffset.UTC);
    private static final String ACQ_ID = "pboc-acq-20260810-d7d03779bc630b8df6c2dffc02d3937f4ef4f7051022c41764724f828e30544a";
    private static final byte[] PAYLOAD = "PBOC official page bytes (deterministic fixture)".getBytes(StandardCharsets.UTF_8);
    private static final String PAYLOAD_SHA = JsonV1Codec.sha256LowerHex(PAYLOAD);

    @TempDir
    Path temporaryDirectory;

    @Test
    void validExternalHttpReceiptLinksToCanonicalAcquisitionAndStores() throws Exception {
        TestRoot root = initializedRoot();
        root.acquisitionStore().store(validAcquisition());
        RawReceiptStore.StoredRawReceipt stored = root.rawStore().store(validReceipt());
        assertTrue(stored.rawRef().startsWith("raw/formal/official_web/FX.USD.CNY.PBOC_MID/2026/08/"));
    }

    @Test
    void rejectsMissingAcquisitionRef() throws Exception {
        TestRoot root = initializedRoot();
        root.acquisitionStore().store(validAcquisition());
        RawReceiptV1 receipt = withRef(validReceipt(), null);
        StorageException failure = assertThrows(StorageException.class, () -> root.rawStore().store(receipt));
        assertTrue(failure.getMessage().contains("acquisitionRef"),
                "missing acquisitionRef must fail closed: " + failure.getMessage());
    }

    @Test
    void rejectsAcquisitionRefThatIsNotTheCanonicalPathForItsAcquisitionId() throws Exception {
        TestRoot root = initializedRoot();
        root.acquisitionStore().store(validAcquisition());
        RawReceiptV1 receipt = withRef(validReceipt(), DataPaths.acquisitionRef("pboc-acq-other-id-00000000"));
        StorageException failure = assertThrows(StorageException.class, () -> root.rawStore().store(receipt));
        assertTrue(failure.getMessage().contains("canonical"),
                "a correct acquisitionId with a wrong path must fail closed: " + failure.getMessage());
    }

    @Test
    void rejectsSourceAcquisitionWhoseAcquisitionIdDiffersFromItemReceipt() throws Exception {
        TestRoot root = initializedRoot();
        RawAcquisitionV1 other = new RawAcquisitionV1(
                SchemaV1.VERSION,
                DataPaths.acquisitionRef(ACQ_ID),
                ACQ_ID,
                Mode.FORMAL,
                ProviderType.OFFICIAL_WEB,
                AccessMethod.PUBLIC_OFFICIAL_HTML,
                1,
                MonitorSeriesDefaults.PBOC_SOURCE_NAME,
                "https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/index.html",
                "https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/2026081009005136814/index.html",
                200,
                "text/html; charset=utf-8",
                OffsetDateTime.parse("2026-08-10T02:00:00+08:00"),
                "base64",
                Base64.getEncoder().encodeToString(PAYLOAD),
                PAYLOAD_SHA);
        root.acquisitionStore().store(other);
        // Replace the canonical file content with an acquisition whose internal id differs.
        RawAcquisitionV1 mismatched = new RawAcquisitionV1(
                SchemaV1.VERSION,
                DataPaths.acquisitionRef("pboc-acq-internal-mismatch-0000000000000000000000000000000000000000000000000000"),
                "pboc-acq-internal-mismatch-0000000000000000000000000000000000000000000000000000",
                Mode.FORMAL,
                ProviderType.OFFICIAL_WEB,
                AccessMethod.PUBLIC_OFFICIAL_HTML,
                1,
                MonitorSeriesDefaults.PBOC_SOURCE_NAME,
                "https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/index.html",
                "https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/2026081009005136814/index.html",
                200,
                "text/html; charset=utf-8",
                OffsetDateTime.parse("2026-08-10T02:00:00+08:00"),
                "base64",
                Base64.getEncoder().encodeToString(PAYLOAD),
                PAYLOAD_SHA);
        byte[] mismatchedBytes = JsonV1Codec.encodeFile(mismatched);
        Path canonical = root.dataRoot().resolveDataRef(DataPaths.acquisitionRef(ACQ_ID));
        Files.write(canonical, mismatchedBytes);
        Files.write(root.dataRoot().resolveDataRef(DataPaths.manifestRef(DataPaths.acquisitionRef(ACQ_ID))),
                JsonV1Codec.encodeFile(ManifestFactory.json(DataPaths.acquisitionRef(ACQ_ID), mismatchedBytes,
                        List.of(ACQ_ID), OffsetDateTime.parse("2026-08-10T02:00:00+08:00"))));

        StorageException failure = assertThrows(StorageException.class, () -> root.rawStore().store(validReceipt()));
        assertTrue(failure.getMessage().contains("manifest") || failure.getMessage().contains("acquisitionId"),
                "a source acquisition with a different internal acquisitionId must fail closed: "
                        + failure.getMessage());
    }

    @Test
    void rejectsPayloadIdentityMismatchBetweenSourceAndItem() throws Exception {
        TestRoot root = initializedRoot();
        root.acquisitionStore().store(validAcquisition());
        RawReceiptV1 receipt = validReceipt();
        byte[] differentPayload = "different official payload bytes".getBytes(StandardCharsets.UTF_8);
        RawReceiptV1 mismatched = new RawReceiptV1(
                receipt.schemaVersion(), receipt.rawRef(), receipt.acquisitionId(), receipt.runId(), receipt.mode(),
                receipt.providerType(), receipt.accessMethod(), receipt.configVersion(), receipt.actualSourceName(),
                receipt.sourceUrl(), receipt.sourceReference(), receipt.itemId(), receipt.sourceBusinessDateRaw(),
                receipt.sourceBusinessDate(), receipt.sourcePublishedAtRaw(), receipt.sourcePublishedAt(),
                receipt.receivedAt(), receipt.inputAt(), receipt.rawValue(), receipt.rawUnit(), receipt.rawCurrency(),
                receipt.operatorRef(), receipt.httpStatus(), receipt.contentType(), "base64",
                Base64.getEncoder().encodeToString(differentPayload),
                JsonV1Codec.sha256LowerHex(differentPayload), receipt.matchAnchor(), receipt.updatedAt(),
                receipt.acquisitionRef());
        StorageException failure = assertThrows(StorageException.class, () -> root.rawStore().store(mismatched));
        assertTrue(failure.getMessage().contains("payload identity"),
                "a payload identity mismatch must fail closed: " + failure.getMessage());
    }

    @Test
    void rejectsMissingSourceAcquisitionManifest() throws Exception {
        TestRoot root = initializedRoot();
        root.acquisitionStore().store(validAcquisition());
        Files.delete(root.dataRoot().resolveDataRef(DataPaths.manifestRef(DataPaths.acquisitionRef(ACQ_ID))));
        StorageException failure = assertThrows(StorageException.class, () -> root.rawStore().store(validReceipt()));
        assertTrue(failure.getMessage().contains("manifest"),
                "a missing source manifest must fail closed: " + failure.getMessage());
    }

    @Test
    void rejectsSourceAcquisitionManifestThatDoesNotVerify() throws Exception {
        TestRoot root = initializedRoot();
        root.acquisitionStore().store(validAcquisition());
        Path manifestPath = root.dataRoot().resolveDataRef(DataPaths.manifestRef(DataPaths.acquisitionRef(ACQ_ID)));
        ManifestV1 manifest = JsonV1Codec.decodeFile(Files.readAllBytes(manifestPath), ManifestV1.class);
        ManifestV1 tampered = new ManifestV1(manifest.schemaVersion(), manifest.fileName(), "00".repeat(32),
                manifest.byteLength(), manifest.rowCount(), manifest.minBusinessDate(), manifest.maxBusinessDate(),
                manifest.sourceRunIds(), manifest.generatedAt(), manifest.commitState());
        Files.write(manifestPath, JsonV1Codec.encodeFile(tampered));
        StorageException failure = assertThrows(StorageException.class, () -> root.rawStore().store(validReceipt()));
        assertTrue(failure.getMessage().contains("verify") || failure.getMessage().contains("manifest"),
                "a non-verifying source manifest must fail closed: " + failure.getMessage());
    }

    private RawAcquisitionV1 validAcquisition() {
        return new RawAcquisitionV1(
                SchemaV1.VERSION,
                DataPaths.acquisitionRef(ACQ_ID),
                ACQ_ID,
                Mode.FORMAL,
                ProviderType.OFFICIAL_WEB,
                AccessMethod.PUBLIC_OFFICIAL_HTML,
                1,
                MonitorSeriesDefaults.PBOC_SOURCE_NAME,
                "https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/index.html",
                "https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/2026081009005136814/index.html",
                200,
                "text/html; charset=utf-8",
                OffsetDateTime.parse("2026-08-10T02:00:00+08:00"),
                "base64",
                Base64.getEncoder().encodeToString(PAYLOAD),
                PAYLOAD_SHA);
    }

    private RawReceiptV1 validReceipt() {
        String runId = "pboc-usd-20260810-" + PAYLOAD_SHA;
        String rawRef = RawReceiptV1.deriveRawRef(Mode.FORMAL, ProviderType.OFFICIAL_WEB,
                MonitorSeriesDefaults.USD_CNY_ITEM_ID, OffsetDateTime.parse("2026-08-10T02:00:00+08:00"), runId);
        return new RawReceiptV1(
                SchemaV1.VERSION, rawRef, ACQ_ID, runId, Mode.FORMAL, ProviderType.OFFICIAL_WEB,
                AccessMethod.PUBLIC_OFFICIAL_HTML, 1, MonitorSeriesDefaults.PBOC_SOURCE_NAME,
                "https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/2026081009005136814/index.html",
                "PBOC公告列表=https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/index.html;公告标题=fixture",
                MonitorSeriesDefaults.USD_CNY_ITEM_ID, "2026年8月10日", "2026-08-10", "2026-08-10 09:25:38",
                OffsetDateTime.parse("2026-08-10T09:25:38+08:00"), OffsetDateTime.parse("2026-08-10T02:00:00+08:00"),
                null, "6.7884", "CNY/1 USD", "CNY", null, 200, "text/html; charset=utf-8", "base64",
                Base64.getEncoder().encodeToString(PAYLOAD), PAYLOAD_SHA, "1美元对人民币",
                OffsetDateTime.parse("2026-08-10T02:00:00+08:00"), DataPaths.acquisitionRef(ACQ_ID));
    }

    private static RawReceiptV1 withRef(RawReceiptV1 receipt, String acquisitionRef) {
        return new RawReceiptV1(
                receipt.schemaVersion(), receipt.rawRef(), receipt.acquisitionId(), receipt.runId(), receipt.mode(),
                receipt.providerType(), receipt.accessMethod(), receipt.configVersion(), receipt.actualSourceName(),
                receipt.sourceUrl(), receipt.sourceReference(), receipt.itemId(), receipt.sourceBusinessDateRaw(),
                receipt.sourceBusinessDate(), receipt.sourcePublishedAtRaw(), receipt.sourcePublishedAt(),
                receipt.receivedAt(), receipt.inputAt(), receipt.rawValue(), receipt.rawUnit(), receipt.rawCurrency(),
                receipt.operatorRef(), receipt.httpStatus(), receipt.contentType(), receipt.payloadEncoding(),
                receipt.payloadBase64(), receipt.payloadSha256(), receipt.matchAnchor(), receipt.updatedAt(),
                acquisitionRef);
    }

    private TestRoot initializedRoot() throws IOException {
        DataRoot dataRoot = DataRoot.forTest(temporaryDirectory.resolve("link verifier " + System.nanoTime()));
        AtomicMoveSupport.probeOrFail(dataRoot);
        AtomicFileStore fileStore = new AtomicFileStore(dataRoot, new DirtyMarkerCodec());
        new ConfigActivationStore(dataRoot, fileStore, CLOCK).ensureInitialDefault();
        return new TestRoot(dataRoot, fileStore,
                new RawAcquisitionStore(dataRoot, fileStore, CLOCK),
                new RawReceiptStore(dataRoot, fileStore, CLOCK));
    }

    private record TestRoot(
            DataRoot dataRoot,
            AtomicFileStore fileStore,
            RawAcquisitionStore acquisitionStore,
            RawReceiptStore rawStore
    ) {
    }
}
