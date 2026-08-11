package com.supplymind.foundation.acceptance;

import com.supplymind.foundation.codec.CsvV1Codec;
import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.AggregateGrain;
import com.supplymind.foundation.model.AggregateInputRefV1;
import com.supplymind.foundation.model.AggregateRecordV1;
import com.supplymind.foundation.model.DailyInputRefV1;
import com.supplymind.foundation.model.DailyRecordV1;
import com.supplymind.foundation.model.Mode;
import com.supplymind.foundation.model.ProcessingStage;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.QualityStatus;
import com.supplymind.foundation.model.RawReceiptV1;
import com.supplymind.foundation.model.SchemaValidationException;
import com.supplymind.foundation.model.ValidationStatus;
import com.supplymind.foundation.storage.DirtyMarkerCodec;
import com.supplymind.foundation.storage.DirtyMarkerV1;
import com.supplymind.foundation.storage.DirtyTargetPhase;
import com.supplymind.foundation.storage.DirtyTargetRole;
import com.supplymind.foundation.storage.DirtyTargetV1;
import com.supplymind.foundation.storage.DirtyTransactionPhase;
import com.supplymind.foundation.storage.DirtyTransactionType;
import com.supplymind.foundation.storage.StorageException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Independent AT-FILE-000 format evidence. Expected output is hand-authored in frozen
 * test resources; this class deliberately does not use production codec/header/factory helpers
 * to construct its oracle values. Every value is a local TEST/CONTRACT FIXTURE, never PBOC evidence.
 */
class IndependentFormatContractAcceptanceTest {

    private static final String FIXTURE_ROOT = "contracts/v1/review-fix/";
    private static final String SOURCE_NAME = "D1-T03 independent test/contract fixture; NOT REAL PBOC";
    private static final String SOURCE_IDENTITY_JSON = "{\"providerType\":\"synthetic_demo\",\"actualSourceName\":\""
            + SOURCE_NAME + "\",\"accessMethod\":\"synthetic_demo\"}";
    private static final String SOURCE_FINGERPRINT = "a8fdb8ba84aeec216d4453436ae4a29d95fc29245e1892f038dd9773b7391deb";
    private static final String PAYLOAD_BASE64 = "RDEtVDAzIFRFU1QvQ09OVFJBQ1QgRklYVFVSRSBPTkxZOyBOT1QgUkVBTCBQQk9D";
    private static final String PAYLOAD_SHA256 = "553d58a4dd628811a45eb341a6b7ffe85346934415e6a5dec82145abc8ec79d9";
    private static final String RAW_JSON_SHA256 = "8795276a367bf0f8834d10c9f171bb2837b18f3a0ddb14f9b17b8208b6725838";

    // These fields are intentionally literal, not CsvV1Codec.DAILY_HEADER / AGGREGATE_HEADER.
    private static final String DAILY_HEADER = "schemaVersion,businessDate,itemId,providerType,actualSourceName,accessMethod,"
            + "processingStage,validationStatus,validationVersion,configVersions,calculationVersion,calculationScale,"
            + "displayScale,roundingMode,calendarVersion,sum,validCount,avg,expectedCount,missingCount,complete,"
            + "currency,unit,inputRefs,updatedAt";
    private static final String AGGREGATE_HEADER = "schemaVersion,grain,periodStart,periodEnd,itemId,providerType,"
            + "actualSourceName,accessMethod,validationStatus,validationVersion,configVersions,calculationVersion,"
            + "calculationScale,displayScale,roundingMode,calendarVersion,sum,validCount,avg,min,max,expectedCount,"
            + "missingCount,complete,qualityStatus,currency,unit,sourceFingerprint,inputRefs,calculatedAt";
    private static final Pattern SCIENTIFIC_DECIMAL = Pattern.compile(".*\\d(?:\\.\\d+)?[eE][+-]?\\d+.*", Pattern.DOTALL);

    @Test
    void unorderedDailyAndAggregateInputsMatchHandFrozenCsvBytesAndHashes() throws IOException {
        byte[] expectedDaily = fixture("daily-fixed-bytes-v1.csv");
        byte[] expectedAggregate = fixture("aggregate-fixed-bytes-v1.csv");

        byte[] actualDaily = CsvV1Codec.encodeDaily(List.of(dailyUsdLater(), dailyEurEarlier()));
        byte[] actualAggregate = CsvV1Codec.encodeAggregate(List.of(aggregateUsdQuarter(), aggregateEurMonth()));

        assertFixedCsv(expectedDaily, DAILY_HEADER, "daily-fixed-bytes-v1.csv");
        assertFixedCsv(expectedAggregate, AGGREGATE_HEADER, "aggregate-fixed-bytes-v1.csv");
        assertArrayEquals(expectedDaily, actualDaily,
                "Daily CSV must use the independently hand-frozen UTF-8/no-BOM/CRLF contract bytes");
        assertArrayEquals(expectedAggregate, actualAggregate,
                "Aggregate CSV must use the independently hand-frozen UTF-8/no-BOM/CRLF contract bytes");
        assertEquals("4f2281f461d06fcc2f2d93b2b613f755e1d0a29d25fbf38ad30d90bd0d52285e", sha256(actualDaily));
        assertEquals("8d71d924ca18c18c0cbdaf91002898399735b4b04e427eb6640c432a691cc87d", sha256(actualAggregate));

        String dailyText = new String(actualDaily, StandardCharsets.UTF_8);
        String aggregateText = new String(actualAggregate, StandardCharsets.UTF_8);
        assertTrue(dailyText.indexOf("2026-08-01,FX.EUR.CNY.INDEPENDENT")
                        < dailyText.indexOf("2026-08-02,FX.USD.CNY.INDEPENDENT"),
                "The unordered daily input must be persisted in frozen canonical row order");
        assertTrue(aggregateText.indexOf("1.0,month,2026-08-01,2026-08-31,FX.EUR.CNY.INDEPENDENT")
                        < aggregateText.indexOf("1.0,quarter,2026-07-01,2026-09-30,FX.USD.CNY.INDEPENDENT"),
                "The unordered aggregate input must be persisted in frozen canonical row order");
        assertTrue(dailyText.contains("\"[{\"\"runId\"\":\"\"run-usd-a\"\""),
                "Daily inputRefs must be fixed compact JSON with PUBLISHED recordVersion=4");
        assertTrue(dailyText.contains("\"\"recordVersion\"\":4"));
        assertTrue(aggregateText.contains(SOURCE_FINGERPRINT),
                "Aggregate sourceFingerprint must occupy its fixed CSV field");
        assertTrue(aggregateText.contains("\"[{\"\"dailyFileRef\"\":\"\"processed/daily/FX.USD.CNY.INDEPENDENT/2026/08.csv\"\""),
                "Aggregate inputRefs must remain a fixed compact JSON field");

        List<DailyRecordV1> dailyRoundTrip = CsvV1Codec.decodeDaily(actualDaily);
        List<AggregateRecordV1> aggregateRoundTrip = CsvV1Codec.decodeAggregate(actualAggregate);
        assertEquals("100.0", dailyRoundTrip.get(0).sum(), "100.0 must survive CSV string round-trip unchanged");
        assertEquals("100.000000000000", dailyRoundTrip.get(0).avg(), "Trailing decimal zeroes must survive CSV round-trip");
        assertEquals("99999999999.123456790", dailyRoundTrip.get(1).sum(), "Large decimal text must not pass through float/double");
        assertEquals("0.000000001000", aggregateRoundTrip.get(0).min(), "Tiny decimal text must survive CSV round-trip");
        assertEquals("99999999999.123456790000", aggregateRoundTrip.get(1).max(),
                "Large trailing-zero decimal text must survive CSV round-trip");

        assertTrue(dailyText.contains(",100.0,1,100.000000000000,"));
        assertTrue(dailyText.contains(",99999999999.123456790,2,49999999999.561728395000,"));
        assertTrue(aggregateText.contains(",0.000000001000,100.000000000000,"));
        assertNoScientificNotation("100.0", "100.000000000000", "99999999999.123456790",
                "49999999999.561728395000", "0.000000001000", "99999999999.123456790000");
    }

    @Test
    void manualRawAndDirtyMarkerObjectsMatchHandFrozenJsonBytesAndHashes() throws IOException {
        byte[] expectedRaw = fixture("raw-receipt-fixed-bytes-v1.json");
        byte[] expectedMarker = fixture("dirty-marker-fixed-bytes-v1.json");

        byte[] actualRaw = JsonV1Codec.encodeFile(rawReceipt());
        byte[] actualMarker = new DirtyMarkerCodec().encode(dirtyMarker());

        assertFixedJson(expectedRaw, "raw-receipt-fixed-bytes-v1.json");
        assertFixedJson(expectedMarker, "dirty-marker-fixed-bytes-v1.json");
        assertArrayEquals(expectedRaw, actualRaw,
                "Raw JSON must use the independently hand-frozen UTF-8/no-BOM/LF contract bytes");
        assertArrayEquals(expectedMarker, actualMarker,
                "DirtyMarker JSON must use the independently hand-frozen UTF-8/no-BOM/LF contract bytes");
        assertEquals(RAW_JSON_SHA256, sha256(actualRaw));
        assertEquals("10d88510ac2ab92586bcbbf795577fb6d42e0743bd8500d55c8ecb358861f8dc", sha256(actualMarker));
        assertEquals(SOURCE_FINGERPRINT, sha256(SOURCE_IDENTITY_JSON.getBytes(StandardCharsets.UTF_8)),
                "sourceFingerprint is an independently frozen JDK SHA-256 vector, not a production CanonicalJson oracle");
        assertTrue(new String(actualRaw, StandardCharsets.UTF_8).contains("NOT REAL PBOC"));
        assertTrue(new String(actualMarker, StandardCharsets.UTF_8).contains("run-format-json-eur"));
    }

    @Test
    void handFrozenJsonCounterexamplesRejectTamperedContractBytes() throws IOException {
        byte[] rawTamperedPayloadHash = new String(fixture("raw-receipt-fixed-bytes-v1.json"), StandardCharsets.UTF_8)
                .replace(PAYLOAD_SHA256, "0000000000000000000000000000000000000000000000000000000000000000")
                .getBytes(StandardCharsets.UTF_8);
        byte[] markerTamperedRevision = new String(fixture("dirty-marker-fixed-bytes-v1.json"), StandardCharsets.UTF_8)
                .replace("\"markerRevision\":4", "\"markerRevision\":5")
                .getBytes(StandardCharsets.UTF_8);

        assertThrows(SchemaValidationException.class,
                () -> JsonV1Codec.decodeFile(rawTamperedPayloadHash, RawReceiptV1.class),
                "A fixed raw JSON payload hash tamper must fail model validation");
        assertThrows(StorageException.class,
                () -> new DirtyMarkerCodec().decode(markerTamperedRevision),
                "A fixed DirtyMarker revision tamper must fail its exact monotonic state contract");
    }

    private DailyRecordV1 dailyEurEarlier() {
        return new DailyRecordV1(
                "1.0", "2026-08-01", "FX.EUR.CNY.INDEPENDENT", ProviderType.SYNTHETIC_DEMO, SOURCE_NAME,
                AccessMethod.SYNTHETIC_DEMO, ProcessingStage.PUBLISHED, ValidationStatus.VERIFIED, "contract-v1",
                List.of(2, 1), "arithmetic-mean-v1", 12, 9, RoundingMode.HALF_UP, "fixture-calendar-v1",
                "100.0", 1, "100.000000000000", 1, 0, true, "CNY", "CNY/1 EUR",
                List.of(dailyInput("run-eur-a", "FX.EUR.CNY.INDEPENDENT")), at("2026-08-03T04:05:06+08:00")
        );
    }

    private DailyRecordV1 dailyUsdLater() {
        return new DailyRecordV1(
                "1.0", "2026-08-02", "FX.USD.CNY.INDEPENDENT", ProviderType.SYNTHETIC_DEMO, SOURCE_NAME,
                AccessMethod.SYNTHETIC_DEMO, ProcessingStage.PUBLISHED, ValidationStatus.VERIFIED, "contract-v1",
                List.of(2, 1), "arithmetic-mean-v1", 12, 9, RoundingMode.HALF_UP, "fixture-calendar-v1",
                "99999999999.123456790", 2, "49999999999.561728395000", 2, 0, true, "CNY", "CNY/1 USD",
                List.of(dailyInput("run-usd-b", "FX.USD.CNY.INDEPENDENT"), dailyInput("run-usd-a", "FX.USD.CNY.INDEPENDENT")),
                at("2026-08-03T04:05:07+08:00")
        );
    }

    private AggregateRecordV1 aggregateEurMonth() {
        return new AggregateRecordV1(
                "1.0", AggregateGrain.MONTH, "2026-08-01", "2026-08-31", "FX.EUR.CNY.INDEPENDENT",
                ProviderType.SYNTHETIC_DEMO, SOURCE_NAME, AccessMethod.SYNTHETIC_DEMO, ValidationStatus.VERIFIED,
                "contract-v1", List.of(2, 1), "arithmetic-mean-v1", 12, 9, RoundingMode.HALF_UP,
                "fixture-calendar-v1", "100.0", 1, "100.000000000000", "0.000000001000", "100.000000000000",
                1, 0, true, QualityStatus.COMPLETE, "CNY", "CNY/1 EUR", SOURCE_FINGERPRINT,
                List.of(aggregateInput("FX.EUR.CNY.INDEPENDENT", "2026-08-01",
                        "f28ce85ac5d329aaf0d73b7c894dbf603e8aee22490cf256278c3c5b1268655c")),
                at("2026-08-03T04:05:08+08:00")
        );
    }

    private AggregateRecordV1 aggregateUsdQuarter() {
        return new AggregateRecordV1(
                "1.0", AggregateGrain.QUARTER, "2026-07-01", "2026-09-30", "FX.USD.CNY.INDEPENDENT",
                ProviderType.SYNTHETIC_DEMO, SOURCE_NAME, AccessMethod.SYNTHETIC_DEMO, ValidationStatus.VERIFIED,
                "contract-v1", List.of(2, 1), "arithmetic-mean-v1", 12, 9, RoundingMode.HALF_UP,
                "fixture-calendar-v1", "99999999999.123456790", 2, "49999999999.561728395000",
                "0.000000001000", "99999999999.123456790000", 2, 0, true, QualityStatus.COMPLETE,
                "CNY", "CNY/1 USD", SOURCE_FINGERPRINT,
                List.of(
                        aggregateInput("FX.USD.CNY.INDEPENDENT", "2026-08-03",
                                "05a0e2fcfb8040b67911c282b45c3faf961132ae070bb680a02f8df61ebc5842"),
                        aggregateInput("FX.USD.CNY.INDEPENDENT", "2026-08-02",
                                "f57765ba847221e4bec249a11d932dbd11f453289df279f9c313b500e7015dd2")
                ), at("2026-08-03T04:05:09+08:00")
        );
    }

    private DailyInputRefV1 dailyInput(String runId, String itemId) {
        return new DailyInputRefV1(runId,
                "raw/test/synthetic_demo/" + itemId + "/2026/08/" + runId + ".json", 4);
    }

    private AggregateInputRefV1 aggregateInput(String itemId, String businessDate, String fileSha256) {
        return new AggregateInputRefV1("processed/daily/" + itemId + "/2026/08.csv", businessDate, "contract-v1", fileSha256);
    }

    private RawReceiptV1 rawReceipt() {
        OffsetDateTime receivedAt = at("2026-08-03T04:05:06+08:00");
        return new RawReceiptV1(
                "1.0", "raw/test/synthetic_demo/FX.EUR.CNY.INDEPENDENT/2026/08/run-format-json-eur.json",
                "acq-format-json-dual", "run-format-json-eur", Mode.TEST, ProviderType.SYNTHETIC_DEMO,
                AccessMethod.SYNTHETIC_DEMO, 2, SOURCE_NAME, null, "D1-T03-review-fix-raw-v1",
                "FX.EUR.CNY.INDEPENDENT", "2026-08-03", "2026-08-03", null, null, receivedAt, null,
                "100.0", "CNY/1 EUR", "CNY", null, null, "text/plain; charset=utf-8", "base64",
  PAYLOAD_BASE64, PAYLOAD_SHA256, "D1-T03-review-fix-anchor", receivedAt, null, null
  );
    }

    private DirtyMarkerV1 dirtyMarker() {
        String rawRef = "raw/test/synthetic_demo/FX.EUR.CNY.INDEPENDENT/2026/08/run-format-json-eur.json";
        return new DirtyMarkerV1(
                "1.0", "tx-format-marker", DirtyTransactionType.SINGLE_FILE, at("2026-08-03T04:05:06+08:00"), 4,
                DirtyTransactionPhase.COMMITTED,
                List.of(new DirtyTargetV1(
                        1, DirtyTargetRole.BUSINESS_FILE, rawRef, rawRef + ".manifest.json", RAW_JSON_SHA256,
                        null, DirtyTargetPhase.MANIFEST_COMMITTED
                ))
        );
    }

    private void assertFixedCsv(byte[] bytes, String literalHeader, String fixtureName) {
        assertFalse(hasUtf8Bom(bytes), fixtureName + " must not have a UTF-8 BOM");
        String text = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(text.startsWith(literalHeader + "\r\n"), fixtureName + " must have the literal frozen header");
        assertTrue(text.endsWith("\r\n"), fixtureName + " must end with exactly one CRLF");
        assertFalse(text.endsWith("\r\n\r\n"), fixtureName + " must end with exactly one CRLF");
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == '\r') {
                assertTrue(index + 1 < text.length() && text.charAt(index + 1) == '\n',
                        fixtureName + " must use CR only in CRLF");
            }
            if (text.charAt(index) == '\n') {
                assertTrue(index > 0 && text.charAt(index - 1) == '\r',
                        fixtureName + " must use LF only in CRLF");
            }
        }
    }

    private void assertFixedJson(byte[] bytes, String fixtureName) {
        assertFalse(hasUtf8Bom(bytes), fixtureName + " must not have a UTF-8 BOM");
        String text = new String(bytes, StandardCharsets.UTF_8);
        assertFalse(text.contains("\r"), fixtureName + " must use LF only");
        assertTrue(text.endsWith("\n"), fixtureName + " must end with exactly one LF");
        assertFalse(text.endsWith("\n\n"), fixtureName + " must end with exactly one LF");
    }

    private boolean hasUtf8Bom(byte[] bytes) {
        return bytes.length >= 3 && (bytes[0] & 0xff) == 0xef && (bytes[1] & 0xff) == 0xbb && (bytes[2] & 0xff) == 0xbf;
    }

    private String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK SHA-256 must be available", exception);
        }
    }

    private void assertNoScientificNotation(String... decimals) {
        for (String decimal : decimals) {
            assertFalse(SCIENTIFIC_DECIMAL.matcher(decimal).matches(),
                    "Fixed decimal contract values must not use scientific notation: " + decimal);
        }
    }

    private OffsetDateTime at(String value) {
        return OffsetDateTime.parse(value);
    }

    private byte[] fixture(String name) throws IOException {
        try (InputStream input = Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(FIXTURE_ROOT + name),
                () -> "Missing independent contract fixture " + name)) {
            return input.readAllBytes();
        }
    }
}
