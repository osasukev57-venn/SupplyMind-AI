package com.supplymind.foundation.storage;

import com.supplymind.foundation.codec.CsvV1Codec;
import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.AggregateGrain;
import com.supplymind.foundation.model.AggregateInputRefV1;
import com.supplymind.foundation.model.AggregateRecordV1;
import com.supplymind.foundation.model.CanonicalJsonV1;
import com.supplymind.foundation.model.DailyInputRefV1;
import com.supplymind.foundation.model.DailyRecordV1;
import com.supplymind.foundation.model.DomainFixtures;
import com.supplymind.foundation.model.LifecycleSnapshotV1;
import com.supplymind.foundation.model.LifecycleTimelineV1;
import com.supplymind.foundation.model.ManifestV1;
import com.supplymind.foundation.model.MonitorSeriesDefaults;
import com.supplymind.foundation.model.ProcessingStage;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.QualityStatus;
import com.supplymind.foundation.model.QuarantineProjectionV1;
import com.supplymind.foundation.model.RawReceiptV1;
import com.supplymind.foundation.model.SchemaV1;
import com.supplymind.foundation.model.ValidationStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focused derivation tests for the frozen ManifestV1 contract in plan 8.4.5. */
class ManifestDerivedFieldsVerifierTest {

    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-08-08T10:00:00+08:00");
    private static final String ITEM_ID = "FX.TEST.CNY";
    private static final String SOURCE = "D1-T03 test/contract fixture — NOT REAL PBOC";

    @TempDir
    Path temporaryDirectory;

    @Test
    void derivesEveryD1T03JsonManifestSourceRunSetAndRejectsCsvMetadataOnJson() {
        DataRoot root = root("json-derived");
        ManifestDerivedFieldsVerifier verifier = new ManifestDerivedFieldsVerifier(root);
        RawReceiptV1 raw = DomainFixtures.rawReceipt();
        byte[] rawBytes = JsonV1Codec.encodeFile(raw);
        LifecycleTimelineV1 timeline = DomainFixtures.publishedTimeline();
        byte[] timelineBytes = JsonV1Codec.encodeFile(timeline);
        LifecycleTimelineV1 rejected = rejectedTimeline(raw);
        QuarantineProjectionV1 quarantine = QuarantineProjectionV1.fromTerminal(raw, rejected, FileDigest.sha256(rawBytes));
        byte[] quarantineBytes = JsonV1Codec.encodeFile(quarantine);
        RawConflictEvidenceV1 conflict = new RawConflictEvidenceV1(
                "1.0", "manifest-conflict-001", raw.itemId(), raw.runId(), raw.rawRef(), "a".repeat(64),
                FileDigest.sha256(rawBytes), raw, AT);
        byte[] conflictBytes = JsonV1Codec.encodeFile(conflict);
        byte[] configBytes = JsonV1Codec.encodeFile(MonitorSeriesDefaults.initialPboc(AT));

        assertDoesNotThrow(() -> verifier.verify(
                DataPaths.configActiveRef(), configBytes, jsonManifest(DataPaths.configActiveRef(), configBytes, List.of())));
        assertDoesNotThrow(() -> verifier.verify(raw.rawRef(), rawBytes, jsonManifest(raw.rawRef(), rawBytes, List.of(raw.runId()))));
        assertDoesNotThrow(() -> verifier.verify(DataPaths.stagingRef(timeline.runId()), timelineBytes,
                jsonManifest(DataPaths.stagingRef(timeline.runId()), timelineBytes, List.of(timeline.runId()))));
        assertDoesNotThrow(() -> verifier.verify(quarantine.quarantineRef(), quarantineBytes,
                jsonManifest(quarantine.quarantineRef(), quarantineBytes, List.of(quarantine.runId()))));
        String conflictRef = DataPaths.rawConflictRef(
                conflict.itemId(), conflict.incomingReceipt().receivedAt(), conflict.runId(), conflict.conflictId());
        assertDoesNotThrow(() -> verifier.verify(conflictRef, conflictBytes,
                jsonManifest(conflictRef, conflictBytes, List.of(conflict.runId()))));

        ManifestV1 wrongJsonMetrics = new ManifestV1(
                SchemaV1.VERSION,
                Path.of(raw.rawRef()).getFileName().toString(),
                FileDigest.sha256(rawBytes),
                rawBytes.length,
                1L,
                null,
                null,
                List.of(raw.runId()),
                AT,
                ManifestV1.COMMITTED
        );
        StorageException failure = assertThrows(StorageException.class,
                () -> verifier.verify(raw.rawRef(), rawBytes, wrongJsonMetrics));
        assertTrue(failure.getMessage().contains("JSON manifest rowCount"));
    }

    @Test
    void derivesDailyRowsDatesAndRunsBeforeAtomicCommit() {
        DataRoot root = root("daily-derived");
        AtomicFileStore store = new AtomicFileStore(root, new DirtyMarkerCodec());
        String dailyRef = DataPaths.dailyRef(ITEM_ID, YearMonth.of(2026, 8));
        byte[] dailyBytes = CsvV1Codec.encodeDaily(List.of(daily("2026-08-11", "daily-run-002"),
                daily("2026-08-10", "daily-run-001")));
        List<String> sourceRunIds = List.of("daily-run-001", "daily-run-002");
        byte[] validManifest = csvManifest(dailyRef, dailyBytes, 2, "2026-08-10", "2026-08-11", sourceRunIds);

        store.commit("daily-derived-valid-001", DirtyTransactionType.SINGLE_FILE, AT, List.of(
                new FileTransactionTarget(DirtyTargetRole.BUSINESS_FILE, dailyRef, dailyBytes, validManifest, false)
        ));
        assertTrue(ManifestVerifier.matches(
                root,
                dailyRef,
                root.resolveDataRef(dailyRef),
                root.resolveDataRef(DataPaths.manifestRef(dailyRef))
        ));

        byte[] badManifest = csvManifest(dailyRef, dailyBytes, 1, "2026-08-10", "2026-08-11", sourceRunIds);
        StorageException failure = assertThrows(StorageException.class, () -> new AtomicFileStore(
                root("daily-derived-invalid"), new DirtyMarkerCodec()).commit(
                "daily-derived-invalid-001",
                DirtyTransactionType.SINGLE_FILE,
                AT,
                List.of(new FileTransactionTarget(
                        DirtyTargetRole.BUSINESS_FILE, dailyRef, dailyBytes, badManifest, false
                ))
        ));
        assertTrue(failure.getMessage().contains("Manifest CSV rowCount/date range"));
    }

    @Test
    void derivesAggregateRunsFromStrictPersistedDailyManifestsAndFailsClosedWithoutThem() {
        DataRoot root = root("aggregate-derived");
        AtomicFileStore store = new AtomicFileStore(root, new DirtyMarkerCodec());
        String dailyRef = DataPaths.dailyRef(ITEM_ID, YearMonth.of(2026, 8));
        byte[] dailyBytes = CsvV1Codec.encodeDaily(List.of(daily("2026-08-10", "aggregate-run-001"),
                daily("2026-08-11", "aggregate-run-002")));
        List<String> dailyRuns = List.of("aggregate-run-001", "aggregate-run-002");
        store.commit("aggregate-source-daily-001", DirtyTransactionType.SINGLE_FILE, AT, List.of(
                new FileTransactionTarget(
                        DirtyTargetRole.BUSINESS_FILE,
                        dailyRef,
                        dailyBytes,
                        csvManifest(dailyRef, dailyBytes, 2, "2026-08-10", "2026-08-11", dailyRuns),
                        false
                )
        ));

        AggregateRecordV1 aggregate = aggregate(AggregateGrain.MONTH, dailyRef, FileDigest.sha256(dailyBytes));
        byte[] aggregateBytes = CsvV1Codec.encodeAggregate(List.of(aggregate));
        String aggregateRef = DataPaths.aggregateRef(ITEM_ID, "month", 2026);
        byte[] validManifest = csvManifest(
                aggregateRef, aggregateBytes, 1, "2026-08-01", "2026-08-31", dailyRuns);
        store.commit("aggregate-derived-valid-001", DirtyTransactionType.SINGLE_FILE, AT, List.of(
                new FileTransactionTarget(
                        DirtyTargetRole.BUSINESS_FILE, aggregateRef, aggregateBytes, validManifest, false
                )
        ));
        assertTrue(ManifestVerifier.matches(
                root,
                aggregateRef,
                root.resolveDataRef(aggregateRef),
                root.resolveDataRef(DataPaths.manifestRef(aggregateRef))
        ));

        String quarterRef = DataPaths.aggregateRef(ITEM_ID, "quarter", 2026);
        AggregateRecordV1 quarter = aggregate(AggregateGrain.QUARTER, dailyRef, FileDigest.sha256(dailyBytes));
        byte[] quarterBytes = CsvV1Codec.encodeAggregate(List.of(quarter));
        StorageException sourceFailure = assertThrows(StorageException.class, () -> store.commit(
                "aggregate-derived-invalid-001",
                DirtyTransactionType.SINGLE_FILE,
                AT,
                List.of(new FileTransactionTarget(
                        DirtyTargetRole.BUSINESS_FILE,
                        quarterRef,
                        quarterBytes,
                        csvManifest(quarterRef, quarterBytes, 1, "2026-08-01", "2026-08-31", List.of("wrong-run-001")),
                        false
                ))
        ));
        assertTrue(sourceFailure.getMessage().contains("sourceRunIds"));

        DataRoot missingDailyRoot = root("aggregate-missing-daily");
        StorageException missingDaily = assertThrows(StorageException.class, () -> new AtomicFileStore(
                missingDailyRoot, new DirtyMarkerCodec()).commit(
                "aggregate-missing-daily-001",
                DirtyTransactionType.SINGLE_FILE,
                AT,
                List.of(new FileTransactionTarget(
                        DirtyTargetRole.BUSINESS_FILE,
                        aggregateRef,
                        aggregateBytes,
                        validManifest,
                        false
                ))
        ));
        assertTrue(missingDaily.getMessage().contains("cannot derive sourceRunIds"));
        assertFalse(Files.exists(missingDailyRoot.resolveDataRef(DataPaths.dirtyMarkerRef("aggregate-missing-daily-001"))));
    }

    private static LifecycleTimelineV1 rejectedTimeline(RawReceiptV1 raw) {
        return LifecycleTimelineV1.initial("manifest-rejected-record-001", raw.runId(), raw.rawRef(), raw.receivedAt())
                .append(new LifecycleSnapshotV1(
                        2,
                        ProcessingStage.RECEIVED,
                        ValidationStatus.REJECTED,
                        null,
                        "PAYLOAD_SHAPE_INVALID",
                        null,
                        null,
                        null,
                        null,
                        raw.receivedAt().plusSeconds(1)
                ));
    }

    private static DailyRecordV1 daily(String businessDate, String runId) {
        return new DailyRecordV1(
                SchemaV1.VERSION,
                businessDate,
                ITEM_ID,
                ProviderType.SYNTHETIC_DEMO,
                SOURCE,
                AccessMethod.SYNTHETIC_DEMO,
                ProcessingStage.PUBLISHED,
                ValidationStatus.VERIFIED,
                "validation-contract-v1",
                List.of(1),
                "arithmetic-mean-v1",
                8,
                4,
                RoundingMode.HALF_UP,
                "golden-calendar-v1",
                "7.12345678",
                1,
                "7.12345678",
                1,
                0,
                true,
                "CNY",
                "CNY/1 TEST",
                List.of(new DailyInputRefV1(
                        runId,
                        "raw/test/synthetic_demo/" + ITEM_ID + "/2026/08/" + runId + ".json",
                        4
                )),
                AT
        );
    }

    private static AggregateRecordV1 aggregate(AggregateGrain grain, String dailyRef, String dailyFileSha256) {
        String sourceFingerprint = CanonicalJsonV1.sha256LowerHex(
                CanonicalJsonV1.sourceIdentity(ProviderType.SYNTHETIC_DEMO, SOURCE, AccessMethod.SYNTHETIC_DEMO));
        return new AggregateRecordV1(
                SchemaV1.VERSION,
                grain,
                "2026-08-01",
                "2026-08-31",
                ITEM_ID,
                ProviderType.SYNTHETIC_DEMO,
                SOURCE,
                AccessMethod.SYNTHETIC_DEMO,
                ValidationStatus.VERIFIED,
                "validation-contract-v1",
                List.of(1),
                "arithmetic-mean-v1",
                8,
                4,
                RoundingMode.HALF_UP,
                "golden-calendar-v1",
                "7.12345678",
                1,
                "7.12345678",
                "7.12345678",
                "7.12345678",
                1,
                0,
                true,
                QualityStatus.COMPLETE,
                "CNY",
                "CNY/1 TEST",
                sourceFingerprint,
                List.of(new AggregateInputRefV1(dailyRef, "2026-08-10", "validation-contract-v1", dailyFileSha256)),
                AT
        );
    }

    private DataRoot root(String name) {
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve(name));
        root.createIfAbsentAndRequireWritable();
        return root;
    }

    private static ManifestV1 jsonManifest(String dataRef, byte[] dataBytes, List<String> sourceRunIds) {
        return ManifestFactory.json(dataRef, dataBytes, sourceRunIds, AT);
    }

    private static byte[] csvManifest(
            String dataRef,
            byte[] dataBytes,
            long rowCount,
            String minBusinessDate,
            String maxBusinessDate,
            List<String> sourceRunIds
    ) {
        return JsonV1Codec.encodeFile(ManifestFactory.csv(
                dataRef, dataBytes, rowCount, minBusinessDate, maxBusinessDate, sourceRunIds, AT));
    }
}
