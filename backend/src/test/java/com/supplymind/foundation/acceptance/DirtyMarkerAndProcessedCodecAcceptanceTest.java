package com.supplymind.foundation.acceptance;

import com.supplymind.foundation.codec.CsvV1Codec;
import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.AggregateGrain;
import com.supplymind.foundation.model.AggregateInputRefV1;
import com.supplymind.foundation.model.AggregateRecordV1;
import com.supplymind.foundation.model.CanonicalJsonV1;
import com.supplymind.foundation.model.DailyInputRefV1;
import com.supplymind.foundation.model.DailyRecordV1;
import com.supplymind.foundation.model.DecimalText;
import com.supplymind.foundation.model.ProcessingStage;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.QualityStatus;
import com.supplymind.foundation.model.SchemaValidationException;
import com.supplymind.foundation.model.ValidationStatus;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.DirtyMarkerCodec;
import com.supplymind.foundation.storage.DirtyMarkerRecovery;
import com.supplymind.foundation.storage.DirtyMarkerV1;
import com.supplymind.foundation.storage.DirtyTargetPhase;
import com.supplymind.foundation.storage.DirtyTargetRole;
import com.supplymind.foundation.storage.DirtyTargetV1;
import com.supplymind.foundation.storage.DirtyTransactionType;
import com.supplymind.foundation.storage.FileDigest;
import com.supplymind.foundation.storage.StorageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** AT-FILE-000 dirty-marker crash windows, exact processed codec, and precision evidence. */
class DirtyMarkerAndProcessedCodecAcceptanceTest {

    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-08-10T09:00:00+08:00");

    @TempDir
    Path temporaryDirectory;

    @Test
    void restoresTheHighestForcedMarkerTmpRevisionToCanonicalAndFailsClosedOnAmbiguity() throws IOException {
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve("marker tmp recovery"));
        root.createIfAbsentAndRequireWritable();
        DirtyMarkerCodec codec = new DirtyMarkerCodec();
        String transactionId = "marker-tmp-window-0001";
        DirtyMarkerV1 revisionOne = businessMarker(transactionId, "staging/marker-tmp-window-0001.json");
        DirtyMarkerV1 revisionTwo = revisionOne.advanceTarget(1, DirtyTargetPhase.DATA_COMMITTED);
        Path canonical = root.resolveDataRef(DataPaths.dirtyMarkerRef(transactionId));
        Path temporary = root.resolveInternalRelative(DataPaths.dirtyMarkerTemporaryRef(transactionId));
        FileDigest.writeCreateNewAndForce(canonical, codec.encode(revisionOne));
        FileDigest.writeCreateNewAndForce(temporary, codec.encode(revisionTwo));

        List<DirtyMarkerV1> recovered = new DirtyMarkerRecovery(codec).recoverCanonicalMarkers(root);

        assertEquals(List.of(revisionTwo), recovered);
        assertEquals(revisionTwo, codec.decode(Files.readAllBytes(canonical)));
        assertFalse(Files.exists(temporary));
        assertTrue(Files.exists(root.resolveInternalRelative(DataPaths.dirtyMarkerBackupRef(transactionId))));
        assertEquals(2, recovered.get(0).markerRevision());

        DataRoot backupRoot = DataRoot.forTest(temporaryDirectory.resolve("marker backup recovery"));
        backupRoot.createIfAbsentAndRequireWritable();
        String backupId = "marker-backup-window-0001";
        DirtyMarkerV1 backupRevision = businessMarker(backupId, "staging/marker-backup-window-0001.json")
                .advanceTarget(1, DirtyTargetPhase.DATA_COMMITTED);
        Path backup = backupRoot.resolveInternalRelative(DataPaths.dirtyMarkerBackupRef(backupId));
        Path restoredCanonical = backupRoot.resolveDataRef(DataPaths.dirtyMarkerRef(backupId));
        FileDigest.writeCreateNewAndForce(backup, codec.encode(backupRevision));

        assertEquals(List.of(backupRevision), new DirtyMarkerRecovery(codec).recoverCanonicalMarkers(backupRoot));
        assertEquals(backupRevision, codec.decode(Files.readAllBytes(restoredCanonical)));
        assertTrue(Files.exists(backup));

        DataRoot ambiguousRoot = DataRoot.forTest(temporaryDirectory.resolve("marker ambiguous recovery"));
        ambiguousRoot.createIfAbsentAndRequireWritable();
        String ambiguousId = "marker-ambiguous-0001";
        DirtyMarkerV1 primary = businessMarker(ambiguousId, "staging/marker-ambiguous-0001.json");
        DirtyMarkerV1 divergentSameRevision = businessMarker(ambiguousId, "staging/marker-ambiguous-alternate.json");
        Path ambiguousCanonical = ambiguousRoot.resolveDataRef(DataPaths.dirtyMarkerRef(ambiguousId));
        Path ambiguousTmp = ambiguousRoot.resolveInternalRelative(DataPaths.dirtyMarkerTemporaryRef(ambiguousId));
        FileDigest.writeCreateNewAndForce(ambiguousCanonical, codec.encode(primary));
        FileDigest.writeCreateNewAndForce(ambiguousTmp, codec.encode(divergentSameRevision));

        assertThrows(StorageException.class, () -> new DirtyMarkerRecovery(codec).recoverCanonicalMarkers(ambiguousRoot));
        assertTrue(Files.exists(ambiguousCanonical), "fail-closed recovery preserves candidate evidence");
        assertTrue(Files.exists(ambiguousTmp), "fail-closed recovery preserves candidate evidence");
    }

    @Test
    void encodesDailyAndAggregateRowsInCanonicalOrderWithPublishedV4ReferencesAndPlainDecimals() {
        DailyRecordV1 later = daily("2026-08-11", "FX.USD.CNY.CONTRACT_FIXTURE", "run-csv-usd-0002", "100.0");
        DailyRecordV1 earlier = daily("2026-08-10", "FX.EUR.CNY.CONTRACT_FIXTURE", "run-csv-eur-0001", "7.987654321");
        byte[] shuffled = CsvV1Codec.encodeDaily(List.of(later, earlier));
        byte[] ordered = CsvV1Codec.encodeDaily(List.of(earlier, later));

        assertArrayEquals(ordered, shuffled, "random input order must not alter daily CSV bytes or file SHA-256");
        assertEquals(FileDigest.sha256(ordered), FileDigest.sha256(shuffled));
        String dailyText = new String(ordered, StandardCharsets.UTF_8);
        assertTrue(dailyText.startsWith(String.join(",", CsvV1Codec.DAILY_HEADER) + "\r\n"));
        assertTrue(dailyText.endsWith("\r\n"));
        assertEquals(List.of(earlier, later), CsvV1Codec.decodeDaily(ordered));
        assertThrows(SchemaValidationException.class,
                () -> new DailyInputRefV1("run-csv-invalid", "raw/test/synthetic_demo/FX.BAD/2026/08/run-csv-invalid.json", 3));

        AggregateRecordV1 aggregate = aggregate(earlier);
        byte[] aggregateBytes = CsvV1Codec.encodeAggregate(List.of(aggregate));
        String aggregateText = new String(aggregateBytes, StandardCharsets.UTF_8);
        assertTrue(aggregateText.startsWith(String.join(",", CsvV1Codec.AGGREGATE_HEADER) + "\r\n"));
        assertEquals(List.of(aggregate), CsvV1Codec.decodeAggregate(aggregateBytes));

        assertEquals("100.0", DecimalText.canonical("100.0", "fixture value"));
        assertEquals("999999999999.123456790",
                DecimalText.canonical("999999999999.123456790", "large precise fixture value"));
        assertFalse(dailyText.contains("E+"));
        assertFalse(aggregateText.contains("E+"));
    }

    private DirtyMarkerV1 businessMarker(String transactionId, String dataRef) {
        String expectedHash = FileDigest.sha256((transactionId + "-bytes").getBytes(StandardCharsets.UTF_8));
        return DirtyMarkerV1.open(transactionId, DirtyTransactionType.SINGLE_FILE, AT, List.of(
                new DirtyTargetV1(1, DirtyTargetRole.BUSINESS_FILE, dataRef, DataPaths.manifestRef(dataRef),
                        expectedHash, null, DirtyTargetPhase.PREPARED)
        ));
    }

    private DailyRecordV1 daily(String businessDate, String itemId, String runId, String value) {
        String rawRef = "raw/test/synthetic_demo/" + itemId + "/2026/08/" + runId + ".json";
        return new DailyRecordV1(
                "1.0", businessDate, itemId, ProviderType.SYNTHETIC_DEMO,
                "D1-T03 test/contract fixture — NOT REAL PBOC", AccessMethod.SYNTHETIC_DEMO,
                ProcessingStage.PUBLISHED, ValidationStatus.VERIFIED, "validation-contract-v1", List.of(1, 2),
                "arithmetic-mean-v1", 12, 9, RoundingMode.HALF_UP, "golden-calendar-v1",
                value, 1, scaleTwelve(value), 1, 0, true, "CNY",
                itemId.contains("EUR") ? "CNY/1 EUR" : "CNY/1 USD",
                List.of(new DailyInputRefV1(runId, rawRef, 4)), AT
        );
    }

    private AggregateRecordV1 aggregate(DailyRecordV1 daily) {
        String fingerprint = CanonicalJsonV1.sha256LowerHex(CanonicalJsonV1.sourceIdentity(
                daily.providerType(), daily.actualSourceName(), daily.accessMethod()));
        String dailyRef = DataPaths.dailyRef(daily.itemId(), java.time.YearMonth.of(2026, 8));
        return new AggregateRecordV1(
                "1.0", AggregateGrain.MONTH, daily.businessDate(), daily.businessDate(), daily.itemId(),
                daily.providerType(), daily.actualSourceName(), daily.accessMethod(), daily.validationStatus(),
                daily.validationVersion(), daily.configVersions(), daily.calculationVersion(), daily.calculationScale(),
                daily.displayScale(), daily.roundingMode(), daily.calendarVersion(), daily.sum(), daily.validCount(),
                daily.avg(), daily.avg(), daily.avg(), daily.expectedCount(), daily.missingCount(), daily.complete(),
                QualityStatus.COMPLETE, daily.currency(), daily.unit(), fingerprint,
                List.of(new AggregateInputRefV1(dailyRef, daily.businessDate(), daily.validationVersion(),
                        FileDigest.sha256("synthetic-daily-file".getBytes(StandardCharsets.UTF_8)))), AT
        );
    }

    private String scaleTwelve(String value) {
        java.math.BigDecimal decimal = new java.math.BigDecimal(value);
        return decimal.setScale(12).toPlainString();
    }
}
