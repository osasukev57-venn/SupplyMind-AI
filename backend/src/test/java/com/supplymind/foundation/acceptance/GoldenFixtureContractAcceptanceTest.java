package com.supplymind.foundation.acceptance;

import com.supplymind.foundation.codec.CsvV1Codec;
import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.AggregateRecordV1;
import com.supplymind.foundation.model.CandidateV1;
import com.supplymind.foundation.model.DailyRecordV1;
import com.supplymind.foundation.model.LifecycleTimelineV1;
import com.supplymind.foundation.model.ManifestV1;
import com.supplymind.foundation.model.Mode;
import com.supplymind.foundation.model.MonitorSeriesConfigV1;
import com.supplymind.foundation.model.ProcessingStage;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.QuarantineProjectionV1;
import com.supplymind.foundation.model.RawReceiptV1;
import com.supplymind.foundation.model.SchemaValidationException;
import com.supplymind.foundation.model.ValidationStatus;
import com.supplymind.foundation.storage.DirtyMarkerCodec;
import com.supplymind.foundation.storage.DirtyMarkerV1;
import com.supplymind.foundation.storage.DirtyTargetPhase;
import com.supplymind.foundation.storage.DirtyTargetRole;
import com.supplymind.foundation.storage.DirtyTransactionType;
import com.supplymind.foundation.storage.StorageException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AT-FILE-000 golden-fixture gate.  Every resource under contracts/v1 is explicitly routed to
 * its frozen v1 model or codec; resources are test/contract fixtures, never source evidence.
 */
class GoldenFixtureContractAcceptanceTest {

    private static final String VALID = "contracts/v1/valid/";
    private static final String INVALID = "contracts/v1/invalid/";

    @Test
    void validRawLifecycleConfigurationQuarantineManifestAndDirtyMarkerFixturesMapToTheirContracts() throws IOException {
        RawReceiptV1 ordinaryRaw = validJson("raw-receipt-v1.json", RawReceiptV1.class);
        RawReceiptV1 rejectedRaw = validJson("raw-receipt-rejected-v1.json", RawReceiptV1.class);
        RawReceiptV1 dualUsdRaw = validJson("raw-receipt-dual-usd-v1.json", RawReceiptV1.class);
        RawReceiptV1 dualEurRaw = validJson("raw-receipt-dual-eur-v1.json", RawReceiptV1.class);

        for (RawReceiptV1 raw : List.of(ordinaryRaw, rejectedRaw, dualUsdRaw, dualEurRaw)) {
            assertEquals(RawReceiptV1.deriveRawRef(raw.mode(), raw.providerType(), raw.itemId(), raw.receivedAt(), raw.runId()),
                    raw.rawRef());
            assertEquals(JsonV1Codec.sha256LowerHex(Base64.getDecoder().decode(raw.payloadBase64())), raw.payloadSha256());
            assertEquals(raw.receivedAt(), raw.updatedAt(), "RawReceiptV1 must stay immutable after receipt");
            assertEquals(Mode.TEST, raw.mode());
            assertEquals(ProviderType.SYNTHETIC_DEMO, raw.providerType());
            assertEquals(AccessMethod.SYNTHETIC_DEMO, raw.accessMethod());
            assertTrue(raw.actualSourceName().contains("test/contract fixture"));
        }
        assertNull(rejectedRaw.rawValue());
        assertNull(rejectedRaw.rawUnit());
        assertNull(rejectedRaw.rawCurrency());
        assertEquals(dualUsdRaw.acquisitionId(), dualEurRaw.acquisitionId(),
                "shared acquisition is allowed but each item remains an independent raw receipt");
        assertNotEquals(dualUsdRaw.runId(), dualEurRaw.runId());
        assertNotEquals(dualUsdRaw.rawRef(), dualEurRaw.rawRef());
        assertEquals("fixture.usd.cny", dualUsdRaw.matchAnchor());
        assertEquals("fixture.eur.cny", dualEurRaw.matchAnchor());
        assertEquals("CNY/1 USD", dualUsdRaw.rawUnit());
        assertEquals("CNY/1 EUR", dualEurRaw.rawUnit());

        LifecycleTimelineV1 published = validJson("lifecycle-published-v1.json", LifecycleTimelineV1.class);
        LifecycleTimelineV1 rejected = validJson("lifecycle-rejected-v1.json", LifecycleTimelineV1.class);
        assertEquals(4, published.currentRecordVersion());
        assertEquals(List.of(ProcessingStage.RECEIVED, ProcessingStage.PARSED, ProcessingStage.VALIDATED,
                        ProcessingStage.PUBLISHED),
                published.records().stream().map(snapshot -> snapshot.processingStage()).toList());
        assertEquals(List.of(ValidationStatus.PENDING, ValidationStatus.PENDING, ValidationStatus.VERIFIED,
                        ValidationStatus.VERIFIED),
                published.records().stream().map(snapshot -> snapshot.validationStatus()).toList());
        CandidateV1 candidate = published.records().get(1).candidate();
        assertNotNull(candidate);
        assertEquals(candidate, published.records().get(2).candidate());
        assertEquals(candidate, published.records().get(3).candidate());
        assertTrue(published.isPublishedForDailyInput());
        assertEquals(ProcessingStage.RECEIVED, rejected.current().processingStage());
        assertEquals(ValidationStatus.REJECTED, rejected.current().validationStatus());
        assertFalse(rejected.isPublishedForDailyInput());

        byte[] productionActiveConfigBytes = fixtureBytes(VALID + "monitor-series-v1.json");
        byte[] productionHistoryConfigBytes = fixtureBytes(VALID + "monitor-series-history-1.json");
        byte[] fixtureActiveConfigBytes = fixtureBytes(VALID + "monitor-series-contract-fixture-v2.json");
        byte[] fixtureHistoryConfigBytes = fixtureBytes(VALID + "monitor-series-contract-fixture-history-v2.json");
        assertArrayEquals(productionActiveConfigBytes, productionHistoryConfigBytes,
                "config/history/<configVersion>.json must be byte-identical to the active config snapshot");
        assertArrayEquals(fixtureActiveConfigBytes, fixtureHistoryConfigBytes,
                "synthetic config history must use the same immutable snapshot bytes");
        MonitorSeriesConfigV1 productionConfig = validJson("monitor-series-v1.json", MonitorSeriesConfigV1.class);
        MonitorSeriesConfigV1 productionHistory = validJson("monitor-series-history-1.json", MonitorSeriesConfigV1.class);
        MonitorSeriesConfigV1 fixtureConfig = validJson("monitor-series-contract-fixture-v2.json", MonitorSeriesConfigV1.class);
        MonitorSeriesConfigV1 fixtureHistory = validJson("monitor-series-contract-fixture-history-v2.json", MonitorSeriesConfigV1.class);
        assertEquals(productionConfig, productionHistory);
        assertEquals(fixtureConfig, fixtureHistory);
        assertEquals(1, productionConfig.configVersion());
        assertEquals(Mode.FORMAL, productionConfig.mode());
        assertEquals("CNY/1 USD", productionConfig.requireItem("FX.USD.CNY.PBOC_MID").unit());
        assertEquals("CNY/1 EUR", productionConfig.requireItem("FX.EUR.CNY.PBOC_MID").unit());
        assertEquals(8, productionConfig.requireItem("FX.USD.CNY.PBOC_MID").calculationScale());
        assertEquals(4, productionConfig.requireItem("FX.EUR.CNY.PBOC_MID").displayScale());
        assertEquals(2, fixtureConfig.configVersion());
        assertEquals(Mode.TEST, fixtureConfig.mode());
        assertEquals(ProviderType.SYNTHETIC_DEMO,
                fixtureConfig.requireItem("FX.USD.CNY.CONTRACT_FIXTURE").providerType());

        QuarantineProjectionV1 quarantine = validJson("quarantine-received-rejected-v1.json", QuarantineProjectionV1.class);
        assertEquals(ProcessingStage.RECEIVED, quarantine.processingStage());
        assertEquals(ValidationStatus.REJECTED, quarantine.validationStatus());
        assertEquals(2, quarantine.terminalRecordVersion());
        assertNull(quarantine.validationVersion());
        assertEquals(QuarantineProjectionV1.deriveQuarantineRef(quarantine.itemId(), quarantine.receivedAt(), quarantine.runId()),
                quarantine.quarantineRef());

        ManifestV1 rawManifest = validJson("manifest-raw-receipt-v1.json", ManifestV1.class);
        ManifestV1 dailyManifest = validJson("manifest-daily-v1.json", ManifestV1.class);
        assertManifestMatchesFixture(rawManifest, "raw-receipt-v1.json", ordinaryRaw.runId() + ".json", ordinaryRaw.runId());
        assertManifestMatchesFixture(dailyManifest, "daily-v1.csv", "2026-08.csv", ordinaryRaw.runId());
        assertNull(rawManifest.rowCount());
        assertEquals(1L, dailyManifest.rowCount());
        assertEquals("COMMITTED", rawManifest.commitState());
        assertEquals("COMMITTED", dailyManifest.commitState());

        DirtyMarkerV1 marker = validDirtyMarker("dirty-marker-config-activation-v1.json");
        assertEquals(DirtyTransactionType.CONFIG_ACTIVATION, marker.transactionType());
        assertEquals(3L, marker.markerRevision());
        assertEquals(2, marker.targets().size());
        assertEquals(DirtyTargetRole.CONFIG_HISTORY, marker.targets().get(0).role());
        assertEquals("config/history/2.json", marker.targets().get(0).dataRef());
        assertEquals(DirtyTargetPhase.MANIFEST_COMMITTED, marker.targets().get(0).targetPhase());
        assertEquals(DirtyTargetRole.CONFIG_ACTIVE, marker.targets().get(1).role());
        assertEquals("config/monitor-series.json", marker.targets().get(1).dataRef());
        assertEquals(DirtyTargetPhase.PREPARED, marker.targets().get(1).targetPhase());
    }

    @Test
    void validDailyAndAggregateGoldenFilesAreExactCanonicalCsvContracts() throws IOException {
        byte[] dailyBytes = fixtureBytes(VALID + "daily-v1.csv");
        byte[] aggregateBytes = fixtureBytes(VALID + "aggregate-v1.csv");
        assertCsvFileConventions(dailyBytes, "daily-v1.csv");
        assertCsvFileConventions(aggregateBytes, "aggregate-v1.csv");

        List<DailyRecordV1> dailyRows = CsvV1Codec.decodeDaily(dailyBytes);
        List<AggregateRecordV1> aggregateRows = CsvV1Codec.decodeAggregate(aggregateBytes);
        assertEquals(1, dailyRows.size());
        assertEquals(1, aggregateRows.size());
        DailyRecordV1 daily = dailyRows.get(0);
        AggregateRecordV1 aggregate = aggregateRows.get(0);
        assertEquals(ProcessingStage.PUBLISHED, daily.processingStage());
        assertEquals(ValidationStatus.VERIFIED, daily.validationStatus());
        assertEquals(4, daily.inputRefs().get(0).recordVersion(),
                "daily inputRefs may only point to PUBLISHED lifecycle recordVersion=4");
        assertEquals("7.123456789", daily.sum());
        assertEquals("7.123456789000", daily.avg());
        assertEquals(12, daily.calculationScale());
        assertEquals(9, daily.displayScale());
        assertEquals("CNY", daily.currency());
        assertEquals("CNY/1 USD", daily.unit());
        assertEquals("7.123456789000", aggregate.sum());
        assertEquals("7.123456789000", aggregate.avg());
        assertEquals("7.123456789000", aggregate.min());
        assertEquals("7.123456789000", aggregate.max());
        assertFalse(aggregate.complete());

        assertArrayEquals(dailyBytes, CsvV1Codec.encodeDaily(dailyRows),
                "daily fixture is the exact canonical CSV golden file");
        assertArrayEquals(aggregateBytes, CsvV1Codec.encodeAggregate(aggregateRows),
                "aggregate fixture is the exact canonical CSV golden file");
    }

    @Test
    void invalidFixturesAreRejectedByTheModelOrCodecTheyTarget() throws IOException {
        assertInvalidJson("raw-receipt-missing-payload-sha.json", RawReceiptV1.class);
        assertInvalidJson("raw-receipt-path-traversal.json", RawReceiptV1.class);
        assertInvalidJson("lifecycle-illegal-published-pending.json", LifecycleTimelineV1.class);
        assertInvalidJson("lifecycle-noncontiguous-version.json", LifecycleTimelineV1.class);
        assertInvalidJson("monitor-series-quote-currency.json", MonitorSeriesConfigV1.class);
        assertInvalidJson("quarantine-published.json", QuarantineProjectionV1.class);
        assertInvalidJson("manifest-nonhex.json", ManifestV1.class);
        assertInvalidDirtyMarker("dirty-marker-revision-zero.json");

        byte[] dailyInvalid = fixtureBytes(INVALID + "daily-inputref-version-3.csv");
        byte[] aggregateInvalid = fixtureBytes(INVALID + "aggregate-quality-mismatch.csv");
        assertCsvFileConventions(dailyInvalid, "daily-inputref-version-3.csv");
        assertCsvFileConventions(aggregateInvalid, "aggregate-quality-mismatch.csv");
        assertThrows(SchemaValidationException.class, () -> CsvV1Codec.decodeDaily(dailyInvalid),
                "daily input refs below PUBLISHED recordVersion=4 must fail closed");
        assertThrows(SchemaValidationException.class, () -> CsvV1Codec.decodeAggregate(aggregateInvalid),
                "aggregate quality/status/input-ref invariants must fail closed");
    }

    @Test
    void syntheticPBOCShapedFixturesCarryNonEvidenceDisclaimers() throws IOException {
        String responseEntity = fixtureText(VALID + "dual-currency-response-entity.txt");
        String html = fixtureText(VALID + "pboc-dual-currency-response-test-fixture.html");
        String metadata = fixtureText(VALID + "README.md");

        assertTrue(responseEntity.contains("test/contract fixture"));
        assertTrue(responseEntity.contains("NOT REAL PBOC"));
        assertTrue(responseEntity.contains("NOT AT-SRC-002 or Day 1/Day 2 PASS"));
        assertTrue(html.contains("TEST/CONTRACT FIXTURE ONLY"));
        assertTrue(html.contains("not a PBOC response"));
        assertTrue(html.contains("cannot be used to claim AT-SRC-002 or Day 1/Day 2 acceptance PASS"));
        assertTrue(metadata.contains("test/contract fixture"));
        assertTrue(metadata.contains("not product data or acceptance evidence"));
    }

    private <T> T validJson(String name, Class<T> type) throws IOException {
        byte[] fixture = fixtureBytes(VALID + name);
        assertJsonFileConventions(fixture, name);
        T decoded = JsonV1Codec.decodeFile(fixture, type);
        byte[] canonical = JsonV1Codec.encodeFile(decoded);
        assertJsonFileConventions(canonical, "canonical " + name);
        assertEquals(decoded, JsonV1Codec.decodeFile(canonical, type),
                "canonical JSON codec must preserve the " + name + " model");
        return decoded;
    }

    private DirtyMarkerV1 validDirtyMarker(String name) throws IOException {
        byte[] fixture = fixtureBytes(VALID + name);
        assertJsonFileConventions(fixture, name);
        DirtyMarkerCodec codec = new DirtyMarkerCodec();
        DirtyMarkerV1 decoded = codec.decode(fixture);
        byte[] canonical = codec.encode(decoded);
        assertJsonFileConventions(canonical, "canonical " + name);
        assertEquals(decoded, codec.decode(canonical));
        return decoded;
    }

    private <T> void assertInvalidJson(String name, Class<T> type) throws IOException {
        byte[] fixture = fixtureBytes(INVALID + name);
        assertJsonFileConventions(fixture, name);
        assertThrows(SchemaValidationException.class, () -> JsonV1Codec.decodeFile(fixture, type),
                "invalid fixture must be rejected by " + type.getSimpleName() + ": " + name);
    }

    private void assertInvalidDirtyMarker(String name) throws IOException {
        byte[] fixture = fixtureBytes(INVALID + name);
        assertJsonFileConventions(fixture, name);
        assertThrows(StorageException.class, () -> new DirtyMarkerCodec().decode(fixture),
                "invalid DirtyMarker fixture must fail the DirtyMarker codec: " + name);
    }

    private void assertManifestMatchesFixture(
            ManifestV1 manifest,
            String dataFixtureName,
            String expectedFileName,
            String expectedRunId
    ) throws IOException {
        byte[] dataFixture = fixtureBytes(VALID + dataFixtureName);
        assertEquals(expectedFileName, manifest.fileName());
        assertEquals(JsonV1Codec.sha256LowerHex(dataFixture), manifest.fileSha256());
        assertEquals((long) dataFixture.length, manifest.byteLength());
        assertEquals(List.of(expectedRunId), manifest.sourceRunIds());
    }

    private void assertJsonFileConventions(byte[] bytes, String fixtureName) {
        assertFalse(hasUtf8Bom(bytes), fixtureName + " must not have a UTF-8 BOM");
        String text = new String(bytes, StandardCharsets.UTF_8);
        assertFalse(text.contains("\r"), fixtureName + " must use LF only");
        assertTrue(text.endsWith("\n"), fixtureName + " must end with one LF");
        assertFalse(text.endsWith("\n\n"), fixtureName + " must end with exactly one LF");
    }

    private void assertCsvFileConventions(byte[] bytes, String fixtureName) {
        assertFalse(hasUtf8Bom(bytes), fixtureName + " must not have a UTF-8 BOM");
        String text = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(text.endsWith("\r\n"), fixtureName + " must end with one CRLF");
        assertFalse(text.endsWith("\r\n\r\n"), fixtureName + " must end with exactly one CRLF");
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current == '\r') {
                assertTrue(index + 1 < text.length() && text.charAt(index + 1) == '\n',
                        fixtureName + " must contain CR only as CRLF");
            }
            if (current == '\n') {
                assertTrue(index > 0 && text.charAt(index - 1) == '\r',
                        fixtureName + " must contain LF only as CRLF");
            }
        }
    }

    private boolean hasUtf8Bom(byte[] bytes) {
        return bytes.length >= 3 && (bytes[0] & 0xff) == 0xef && (bytes[1] & 0xff) == 0xbb
                && (bytes[2] & 0xff) == 0xbf;
    }

    private String fixtureText(String resource) throws IOException {
        return new String(fixtureBytes(resource), StandardCharsets.UTF_8);
    }

    private byte[] fixtureBytes(String resource) throws IOException {
        try (InputStream stream = Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(resource),
                () -> "Missing contract fixture " + resource)) {
            return stream.readAllBytes();
        }
    }
}
