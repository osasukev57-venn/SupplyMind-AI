package com.supplymind.validation;

import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.CandidateV1;
import com.supplymind.foundation.model.LifecycleSnapshotV1;
import com.supplymind.foundation.model.LifecycleTimelineV1;
import com.supplymind.foundation.model.Mode;
import com.supplymind.foundation.model.MonitorSeriesConfigV1;
import com.supplymind.foundation.model.MonitorSeriesDefaults;
import com.supplymind.foundation.model.MonitorSeriesItemV1;
import com.supplymind.foundation.model.ProcessingStage;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.RawReceiptV1;
import com.supplymind.foundation.model.SchemaValidationException;
import com.supplymind.foundation.model.SchemaV1;
import com.supplymind.foundation.model.ValidationStatus;
import com.supplymind.foundation.storage.AtomicFileStore;
import com.supplymind.foundation.storage.AtomicMoveSupport;
import com.supplymind.foundation.storage.ConfigActivationStore;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.DirtyMarkerCodec;
import com.supplymind.foundation.storage.FileDigest;
import com.supplymind.foundation.storage.RawReceiptStore;
import com.supplymind.foundation.storage.TimelineStore;
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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PbocValidationPipelineTest {

    private static final ZoneId SHANGHAI = DataPaths.SHANGHAI;
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-08-10T01:01:00Z"), SHANGHAI);
    private static final OffsetDateTime RECEIVED_AT = OffsetDateTime.ofInstant(
            Instant.parse("2026-08-10T01:00:00Z"), SHANGHAI);
    private static final OffsetDateTime PUBLISHED_AT = OffsetDateTime.parse("2026-08-10T09:25:38+08:00");
    private static final String SOURCE_NAME = MonitorSeriesDefaults.PBOC_SOURCE_NAME;
    private static final String FIXTURE_ROOT = "contracts/v1/";

    @TempDir
    Path temporaryDirectory;

    @Test
    void standardizesAndValidatesNormalDualCurrencyToVerifiedWithGoldenBytes() throws IOException {
        Harness harness = harness();
        RawReceiptV1 usd = pbocRaw("run-usd-validated-001", MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                "6.7904", "2026-08-10", "CNY/1 USD", "CNY", SOURCE_NAME);
        RawReceiptV1 eur = pbocRaw("run-eur-normal-001", MonitorSeriesDefaults.EUR_CNY_ITEM_ID,
                "7.8067", "2026-08-10", "CNY/1 EUR", "CNY", SOURCE_NAME);
        ingest(harness, usd);
        ingest(harness, eur);

        ValidationOutcome usdOutcome = harness.service().process(usd.runId());
        assertValidated(usdOutcome, ValidationStatus.VERIFIED, null, "6.7904", "2026-08-10");
        ValidationOutcome eurOutcome = harness.service().process(eur.runId());
        assertValidated(eurOutcome, ValidationStatus.VERIFIED, null, "7.8067", "2026-08-10");

        byte[] golden = fixtureBytes("valid/lifecycle-validated-verified-pboc-v1.json");
        assertArrayEquals(golden, Files.readAllBytes(
                        harness.root().resolveDataRef(DataPaths.stagingRef(usd.runId()))),
                "the persisted USD timeline must match the hand-authored golden bytes");

        TimelineStore freshStore = new TimelineStore(harness.root(), harness.fileStore(), FIXED_CLOCK);
        LifecycleTimelineV1 usdTimeline = freshStore.read(usd.runId());
        LifecycleTimelineV1 eurTimeline = freshStore.read(eur.runId());
        assertEquals(3, usdTimeline.currentRecordVersion());
        assertEquals(3, eurTimeline.currentRecordVersion());
        assertEquals(ProcessingStage.VALIDATED, eurTimeline.current().processingStage());
        assertEquals(ValidationStatus.VERIFIED, eurTimeline.current().validationStatus());
        assertEquals(usdTimeline.records().get(1).candidate(), usdTimeline.records().get(2).candidate(),
                "CandidateV1 must stay byte-identical across snapshots of the same run");
    }

    @Test
    void missingBusinessDateFailsStandardizationToReceivedRejectedWithGoldenBytes() throws IOException {
        Harness harness = harness();
        RawReceiptV1 raw = pbocRaw("run-usd-standardization-fail-001", MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                "6.7904", null, "CNY/1 USD", "CNY", SOURCE_NAME);
        ingest(harness, raw);

        ValidationOutcome outcome = harness.service().process(raw.runId());

        assertEquals(ProcessingStage.RECEIVED, outcome.processingStage());
        assertEquals(ValidationStatus.REJECTED, outcome.validationStatus());
        assertEquals(ValidationReasonCodes.STANDARDIZATION_FAILED, outcome.reasonCode());
        assertNull(outcome.candidate());
        assertNull(outcome.validationVersion());
        byte[] golden = fixtureBytes("valid/lifecycle-received-rejected-standardization-pboc-v1.json");
        assertArrayEquals(golden, Files.readAllBytes(
                        harness.root().resolveDataRef(DataPaths.stagingRef(raw.runId()))),
                "the persisted rejected timeline must match the hand-authored golden bytes");
    }

    @Test
    void wrongUnitRejectsAtValidatedStageWithGoldenBytes() throws IOException {
        Harness harness = harness();
        RawReceiptV1 raw = pbocRaw("run-eur-unit-mismatch-001", MonitorSeriesDefaults.EUR_CNY_ITEM_ID,
                "7.8067", "2026-08-10", "CNY/100 EUR", "CNY", SOURCE_NAME);
        ingest(harness, raw);

        ValidationOutcome outcome = harness.service().process(raw.runId());

        assertValidated(outcome, ValidationStatus.REJECTED, ValidationReasonCodes.UNIT_MISMATCH,
                "7.8067", "2026-08-10");
        byte[] golden = fixtureBytes("valid/lifecycle-validated-rejected-unit-mismatch-pboc-v1.json");
        assertArrayEquals(golden, Files.readAllBytes(
                        harness.root().resolveDataRef(DataPaths.stagingRef(raw.runId()))),
                "the persisted rejected timeline must match the hand-authored golden bytes");
    }

    @Test
    void futureBusinessDateRejects() throws IOException {
        assertRejectedWithReason("run-usd-future-001", "6.7904", "2099-01-01",
                ValidationReasonCodes.FUTURE_BUSINESS_DATE);
    }

    @Test
    void staleBusinessDateRejects() throws IOException {
        assertRejectedWithReason("run-usd-stale-001", "6.7904", "2026-06-01",
                ValidationReasonCodes.STALE_BUSINESS_DATE);
    }

    @Test
    void exactlyThirtyDayOldBusinessDateIsNotStale() throws IOException {
        Harness harness = harness();
        RawReceiptV1 raw = pbocRaw("run-usd-thirty-days-001", MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                "6.7904", "2026-07-11", "CNY/1 USD", "CNY", SOURCE_NAME);
        ingest(harness, raw);
        ValidationOutcome outcome = harness.service().process(raw.runId());
        assertValidated(outcome, ValidationStatus.VERIFIED, null, "6.7904", "2026-07-11");
    }

    @Test
    void thirtyOneDayOldBusinessDateIsStale() throws IOException {
        assertRejectedWithReason("run-usd-thirty-one-days-001", "6.7904", "2026-07-10",
                ValidationReasonCodes.STALE_BUSINESS_DATE);
    }

    @Test
    void valueExactlyOneHundredIsValid() throws IOException {
        Harness harness = harness();
        RawReceiptV1 raw = pbocRaw("run-usd-one-hundred-001", MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                "100", "2026-08-10", "CNY/1 USD", "CNY", SOURCE_NAME);
        ingest(harness, raw);
        ValidationOutcome outcome = harness.service().process(raw.runId());
        assertValidated(outcome, ValidationStatus.VERIFIED, null, "100", "2026-08-10");
    }

    @Test
    void valueJustAboveOneHundredIsOutOfRange() throws IOException {
        assertRejectedWithReason("run-usd-one-hundred-one-001", "101", "2026-08-10",
                ValidationReasonCodes.OUT_OF_RANGE);
    }

    @Test
    void outOfRangeValueRejects() throws IOException {
        assertRejectedWithReason("run-usd-range-001", "500.0", "2026-08-10",
                ValidationReasonCodes.OUT_OF_RANGE);
    }

    @Test
    void sourceMismatchRejects() throws IOException {
        Harness harness = harness();
        RawReceiptV1 raw = pbocRaw("run-usd-source-001", MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                "6.7904", "2026-08-10", "CNY/1 USD", "CNY", "并非中国人民银行官网（伪造测试源）");
        writeRawDirectly(harness, raw);
        harness.timelineStore().createInitial(raw.runId(), raw.rawRef(), raw.receivedAt());

        ValidationOutcome outcome = harness.service().process(raw.runId());

        assertValidated(outcome, ValidationStatus.REJECTED, ValidationReasonCodes.SOURCE_MISMATCH,
                "6.7904", "2026-08-10");
    }

    @Test
    void currencyMismatchRejects() throws IOException {
        assertRejectedWithReason("run-usd-currency-001", "6.7904", "2026-08-10",
                ValidationReasonCodes.CURRENCY_MISMATCH, SOURCE_NAME, "CNY/1 USD", "USD");
    }

    @Test
    void fieldIntegrityRejects() throws IOException {
        Harness harness = harness();
        RawReceiptV1 raw = pbocRaw("run-usd-field-001", MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                "6.7904", "2026-08-10", "CNY/1 USD", "CNY", SOURCE_NAME, "application/json", 200);
        ingest(harness, raw);
        ValidationOutcome outcome = harness.service().process(raw.runId());
        assertValidated(outcome, ValidationStatus.REJECTED, ValidationReasonCodes.FIELD_INVALID,
                "6.7904", "2026-08-10");
    }

    @Test
    void duplicateObservationBecomesVerifiedWithNoticeAndNeverTouchesTheFirstRun() throws IOException {
        Harness harness = harness();
        RawReceiptV1 first = pbocRaw("run-usd-duplicate-a-001", MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                "6.7904", "2026-08-10", "CNY/1 USD", "CNY", SOURCE_NAME);
        RawReceiptV1 second = pbocRaw("run-usd-duplicate-b-001", MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                "6.7904", "2026-08-10", "CNY/1 USD", "CNY", SOURCE_NAME);
        ingest(harness, first);
        ingest(harness, second);
        ValidationOutcome firstOutcome = harness.service().process(first.runId());
        assertValidated(firstOutcome, ValidationStatus.VERIFIED, null, "6.7904", "2026-08-10");
        Path firstTimeline = harness.root().resolveDataRef(DataPaths.stagingRef(first.runId()));
        String firstBytesBefore = FileDigest.sha256(firstTimeline);

        ValidationOutcome secondOutcome = harness.service().process(second.runId());

        assertValidated(secondOutcome, ValidationStatus.VERIFIED_WITH_NOTICE,
                ValidationReasonCodes.DUPLICATE_OBSERVATION, "6.7904", "2026-08-10");
        assertEquals(firstBytesBefore, FileDigest.sha256(firstTimeline),
                "the first run's timeline must stay byte-identical");
    }

    @Test
    void conflictingValueBecomesConflictAndNeverOverwritesTheValidValue() throws IOException {
        Harness harness = harness();
        RawReceiptV1 valid = pbocRaw("run-usd-conflict-a-001", MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                "6.7904", "2026-08-10", "CNY/1 USD", "CNY", SOURCE_NAME);
        RawReceiptV1 conflicting = pbocRaw("run-usd-conflict-b-001", MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                "6.8000", "2026-08-10", "CNY/1 USD", "CNY", SOURCE_NAME);
        ingest(harness, valid);
        ingest(harness, conflicting);
        ValidationOutcome validOutcome = harness.service().process(valid.runId());
        assertValidated(validOutcome, ValidationStatus.VERIFIED, null, "6.7904", "2026-08-10");
        Path validTimeline = harness.root().resolveDataRef(DataPaths.stagingRef(valid.runId()));
        String validBytesBefore = FileDigest.sha256(validTimeline);

        ValidationOutcome conflictOutcome = harness.service().process(conflicting.runId());

        assertValidated(conflictOutcome, ValidationStatus.CONFLICT, ValidationReasonCodes.VALUE_CONFLICT,
                "6.8000", "2026-08-10");
        assertEquals(validBytesBefore, FileDigest.sha256(validTimeline),
                "a conflicting record must never overwrite the valid value");
    }

    @Test
    void reprocessingIsIdempotentAndNeverAppendsDuplicateSnapshots() throws IOException {
        Harness harness = harness();
        RawReceiptV1 raw = pbocRaw("run-usd-idempotent-001", MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                "6.7904", "2026-08-10", "CNY/1 USD", "CNY", SOURCE_NAME);
        ingest(harness, raw);
        Path staging = harness.root().resolveDataRef(DataPaths.stagingRef(raw.runId()));

        ValidationOutcome first = harness.service().process(raw.runId());
        String bytesAfterFirst = FileDigest.sha256(staging);
        ValidationOutcome second = harness.service().process(raw.runId());
        ValidationOutcome third = harness.service().process(raw.runId());

        assertEquals(first, second);
        assertEquals(first, third);
        assertEquals(ValidationStatus.VERIFIED, second.validationStatus());
        assertEquals(bytesAfterFirst, FileDigest.sha256(staging),
                "idempotent reprocessing must not change or duplicate persisted snapshots");
        assertEquals(3, JsonV1Codec.decodeFile(Files.readAllBytes(staging), LifecycleTimelineV1.class)
                .currentRecordVersion());
    }

    @Test
    void resumesFromAnExistingParsedSnapshotToValidated() throws IOException {
        Harness harness = harness();
        RawReceiptV1 raw = pbocRaw("run-usd-resume-001", MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                "6.7904", "2026-08-10", "CNY/1 USD", "CNY", SOURCE_NAME);
        ingest(harness, raw);
        CandidateV1 candidate = new PbocCandidateStandardizer().standardize(raw).candidate();
        harness.timelineStore().append(raw.runId(), new LifecycleSnapshotV1(
                2, ProcessingStage.PARSED, ValidationStatus.PENDING, candidate, null, null, null, null, null,
                RECEIVED_AT.plusMinutes(1)));

        ValidationOutcome outcome = harness.service().process(raw.runId());

        assertValidated(outcome, ValidationStatus.VERIFIED, null, "6.7904", "2026-08-10");
    }

    @Test
    void cannotJumpFromReceivedToValidatedOrParsedToPublished() throws IOException {
        Harness harness = harness();
        RawReceiptV1 raw = pbocRaw("run-usd-jump-001", MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                "6.7904", "2026-08-10", "CNY/1 USD", "CNY", SOURCE_NAME);
        ingest(harness, raw);
        LifecycleTimelineV1 initial = harness.timelineStore().read(raw.runId());
        CandidateV1 candidate = new PbocCandidateStandardizer().standardize(raw).candidate();

        assertThrows(SchemaValidationException.class, () -> initial.append(new LifecycleSnapshotV1(
                2, ProcessingStage.VALIDATED, ValidationStatus.VERIFIED, candidate, null,
                PbocBasicValidator.VALIDATION_VERSION, RECEIVED_AT.plusMinutes(1), null, null,
                RECEIVED_AT.plusMinutes(1))));
        LifecycleTimelineV1 parsed = initial.append(new LifecycleSnapshotV1(
                2, ProcessingStage.PARSED, ValidationStatus.PENDING, candidate, null, null, null, null, null,
                RECEIVED_AT.plusMinutes(1)));
        assertThrows(SchemaValidationException.class, () -> parsed.append(new LifecycleSnapshotV1(
                3, ProcessingStage.PUBLISHED, ValidationStatus.VERIFIED, candidate, null,
                PbocBasicValidator.VALIDATION_VERSION, RECEIVED_AT.plusMinutes(2), RECEIVED_AT.plusMinutes(2),
                "staging/run-usd-jump-001.json#recordVersion=3", RECEIVED_AT.plusMinutes(2))));

        assertThrows(SchemaValidationException.class,
                () -> JsonV1Codec.decodeFile(fixtureBytes("invalid/lifecycle-received-validated-skip.json"),
                        LifecycleTimelineV1.class));
        assertThrows(SchemaValidationException.class,
                () -> JsonV1Codec.decodeFile(fixtureBytes("invalid/lifecycle-parsed-published-skip.json"),
                        LifecycleTimelineV1.class));
    }

    @Test
    void rejectedHistoryDoesNotParticipateInConflictJudgment() throws IOException {
        Harness harness = harness();
        RawReceiptV1 rejected = pbocRaw("run-usd-rejected-history-001", MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                "6.0000", "2026-08-10", "CNY/100 USD", "CNY", SOURCE_NAME);
        ingest(harness, rejected);
        ValidationOutcome rejectedOutcome = harness.service().process(rejected.runId());
        assertValidated(rejectedOutcome, ValidationStatus.REJECTED, ValidationReasonCodes.UNIT_MISMATCH,
                "6.0000", "2026-08-10");

        RawReceiptV1 valid = pbocRaw("run-usd-rejected-history-002", MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                "6.7904", "2026-08-10", "CNY/1 USD", "CNY", SOURCE_NAME);
        ingest(harness, valid);
        ValidationOutcome validOutcome = harness.service().process(valid.runId());

        assertValidated(validOutcome, ValidationStatus.VERIFIED, null, "6.7904", "2026-08-10");
    }

    @Test
    void conflictHistoryDoesNotPolluteNewValidRecord() throws IOException {
        Harness harness = harness();
        RawReceiptV1 baseline = pbocRaw("run-usd-conflict-history-001", MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                "6.7904", "2026-08-10", "CNY/1 USD", "CNY", SOURCE_NAME);
        RawReceiptV1 conflicting = pbocRaw("run-usd-conflict-history-002", MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                "6.8000", "2026-08-10", "CNY/1 USD", "CNY", SOURCE_NAME);
        ingest(harness, baseline);
        ingest(harness, conflicting);
        assertValidated(harness.service().process(baseline.runId()), ValidationStatus.VERIFIED, null,
                "6.7904", "2026-08-10");
        assertValidated(harness.service().process(conflicting.runId()), ValidationStatus.CONFLICT,
                ValidationReasonCodes.VALUE_CONFLICT, "6.8000", "2026-08-10");

        RawReceiptV1 newValid = pbocRaw("run-usd-conflict-history-003", MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                "6.7904", "2026-08-10", "CNY/1 USD", "CNY", SOURCE_NAME);
        ingest(harness, newValid);
        ValidationOutcome newOutcome = harness.service().process(newValid.runId());

        assertValidated(newOutcome, ValidationStatus.VERIFIED_WITH_NOTICE,
                ValidationReasonCodes.DUPLICATE_OBSERVATION, "6.7904", "2026-08-10");
    }

    @Test
    void parsedPendingHistoryDoesNotParticipateInConflictJudgment() throws IOException {
        Harness harness = harness();
        RawReceiptV1 pending = pbocRaw("run-usd-pending-history-001", MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                "6.0000", "2026-08-10", "CNY/1 USD", "CNY", SOURCE_NAME);
        ingest(harness, pending);
        CandidateV1 pendingCandidate = new PbocCandidateStandardizer().standardize(pending).candidate();
        harness.timelineStore().append(pending.runId(), new LifecycleSnapshotV1(
                2, ProcessingStage.PARSED, ValidationStatus.PENDING, pendingCandidate, null, null, null, null, null,
                RECEIVED_AT.plusMinutes(1)));

        RawReceiptV1 valid = pbocRaw("run-usd-pending-history-002", MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                "6.7904", "2026-08-10", "CNY/1 USD", "CNY", SOURCE_NAME);
        ingest(harness, valid);
        ValidationOutcome validOutcome = harness.service().process(valid.runId());

        assertValidated(validOutcome, ValidationStatus.VERIFIED, null, "6.7904", "2026-08-10");
    }

    @Test
    void usesImmutableConfigVersionHistoryNotCurrentActiveConfig() throws IOException {
        Harness harness = harness();
        RawReceiptV1 v1Raw = pbocRaw("run-usd-config-v1-001", MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                "6.7904", "2026-08-10", "CNY/1 USD", "CNY", SOURCE_NAME);
        ingest(harness, v1Raw);
        ValidationOutcome beforeSwitch = harness.service().process(v1Raw.runId());
        assertValidated(beforeSwitch, ValidationStatus.VERIFIED, null, "6.7904", "2026-08-10");
        Path v1Staging = harness.root().resolveDataRef(DataPaths.stagingRef(v1Raw.runId()));
        String v1BytesBefore = FileDigest.sha256(v1Staging);

        harness.configStore().activate(configV2WithUsdUnit("CNY/100 USD"));

        ValidationOutcome afterSwitch = harness.service().process(v1Raw.runId());
        assertEquals(beforeSwitch, afterSwitch);
        assertEquals(v1BytesBefore, FileDigest.sha256(v1Staging));

        RawReceiptV1 newV1Raw = pbocRaw("run-usd-config-v1-002", MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                "6.7904", "2026-08-09", "CNY/1 USD", "CNY", SOURCE_NAME);
        ingest(harness, newV1Raw);
        assertValidated(harness.service().process(newV1Raw.runId()), ValidationStatus.VERIFIED, null,
                "6.7904", "2026-08-09");

        RawReceiptV1 v2Raw = pbocRaw("run-usd-config-v2-001", MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                "6.7904", "2026-08-08", "CNY/100 USD", "CNY", SOURCE_NAME, 2);
        ingest(harness, v2Raw);
        assertValidated(harness.service().process(v2Raw.runId()), ValidationStatus.VERIFIED, null,
                "6.7904", "2026-08-08");
    }

    @Test
    void zeroValueGoesThroughParsedToRangeRejected() throws IOException {
        Harness harness = harness();
        RawReceiptV1 raw = pbocRaw("run-usd-zero-001", MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                "0", "2026-08-10", "CNY/1 USD", "CNY", SOURCE_NAME);
        ingest(harness, raw);
        ValidationOutcome outcome = harness.service().process(raw.runId());
        assertValidated(outcome, ValidationStatus.REJECTED, ValidationReasonCodes.OUT_OF_RANGE, "0", "2026-08-10");
    }

    @Test
    void negativeValueGoesThroughParsedToRangeRejected() throws IOException {
        Harness harness = harness();
        RawReceiptV1 raw = pbocRaw("run-usd-negative-001", MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                "-1.5", "2026-08-10", "CNY/1 USD", "CNY", SOURCE_NAME);
        ingest(harness, raw);
        ValidationOutcome outcome = harness.service().process(raw.runId());
        assertValidated(outcome, ValidationStatus.REJECTED, ValidationReasonCodes.OUT_OF_RANGE,
                "-1.5", "2026-08-10");
    }

    @Test
    void unparseableValueFailsStandardizationWithNullCandidate() throws IOException {
        Harness harness = harness();
        RawReceiptV1 raw = pbocRaw("run-usd-unparseable-001", MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                "abc", "2026-08-10", "CNY/1 USD", "CNY", SOURCE_NAME);
        ingest(harness, raw);
        ValidationOutcome outcome = harness.service().process(raw.runId());
        assertEquals(ProcessingStage.RECEIVED, outcome.processingStage());
        assertEquals(ValidationStatus.REJECTED, outcome.validationStatus());
        assertEquals(ValidationReasonCodes.STANDARDIZATION_FAILED, outcome.reasonCode());
        assertNull(outcome.candidate());
        assertEquals(2, outcome.recordVersion());
    }

    private MonitorSeriesConfigV1 configV2WithUsdUnit(String unit) {
        MonitorSeriesConfigV1 v1 = MonitorSeriesDefaults.initialPboc(RECEIVED_AT);
        java.util.List<com.supplymind.foundation.model.MonitorSeriesItemV1> items = new java.util.ArrayList<>();
        for (com.supplymind.foundation.model.MonitorSeriesItemV1 item : v1.items()) {
            if (item.itemId().equals(MonitorSeriesDefaults.USD_CNY_ITEM_ID)) {
                items.add(new com.supplymind.foundation.model.MonitorSeriesItemV1(
                        item.itemId(), item.displayName(), item.enabled(), item.sourceIntent(), item.providerType(),
                        item.accessMethod(), item.actualSourceName(), item.routeDecision(), item.fallbackReason(),
                        item.routeEffectiveAt(), item.supersedesItemId(), item.externalCode(), item.sourceFieldKey(),
                        item.rateKind(), item.calculationVersion(), item.calculationScale(), item.displayScale(),
                        item.roundingMode(), item.calendarVersion(), item.currency(), item.baseCurrency(), unit));
            } else {
                items.add(item);
            }
        }
        return new MonitorSeriesConfigV1(SchemaV1.VERSION, 2, Mode.FORMAL, RECEIVED_AT.plusHours(1), items);
    }

    private void assertRejectedWithReason(String runId, String value, String businessDate, String reason)
            throws IOException {
        assertRejectedWithReason(runId, value, businessDate, reason, SOURCE_NAME);
    }

    private void assertRejectedWithReason(
            String runId, String value, String businessDate, String reason, String sourceName) throws IOException {
        assertRejectedWithReason(runId, value, businessDate, reason, sourceName, "CNY/1 USD", "CNY");
    }

    private void assertRejectedWithReason(
            String runId, String value, String businessDate, String reason, String sourceName,
            String unit, String currency
    ) throws IOException {
        Harness harness = harness();
        RawReceiptV1 raw = pbocRaw(runId, MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                value, businessDate, unit, currency, sourceName);
        ingest(harness, raw);
        ValidationOutcome outcome = harness.service().process(raw.runId());
        assertValidated(outcome, ValidationStatus.REJECTED, reason, value, businessDate);
    }

    private static void assertValidated(
            ValidationOutcome outcome,
            ValidationStatus status,
            String reasonCode,
            String value,
            String businessDate
    ) {
        assertEquals(ProcessingStage.VALIDATED, outcome.processingStage());
        assertEquals(status, outcome.validationStatus());
        assertEquals(reasonCode, outcome.reasonCode());
        assertEquals(3, outcome.recordVersion());
        assertEquals(PbocBasicValidator.VALIDATION_VERSION, outcome.validationVersion());
        assertNotNull(outcome.validatedAt());
        assertNotNull(outcome.candidate());
        assertEquals(value, outcome.candidate().value());
        assertEquals(businessDate, outcome.candidate().businessDate());
        if (status == ValidationStatus.VERIFIED) {
            assertNull(reasonCode);
        }
    }

    private Harness harness() {
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve("d2-t01 pipeline root"));
        AtomicMoveSupport.probeOrFail(root);
        AtomicFileStore fileStore = new AtomicFileStore(root, new DirtyMarkerCodec());
        ConfigActivationStore configStore = new ConfigActivationStore(root, fileStore, FIXED_CLOCK);
        configStore.ensureInitialDefault();
        RawReceiptStore rawStore = new RawReceiptStore(root, fileStore, FIXED_CLOCK);
        TimelineStore timelineStore = new TimelineStore(root, fileStore, FIXED_CLOCK);
        LifecycleValidationService service = new LifecycleValidationService(root, timelineStore, FIXED_CLOCK);
        return new Harness(root, fileStore, configStore, rawStore, timelineStore, service);
    }

    private static void ingest(Harness harness, RawReceiptV1 raw) {
        harness.rawStore().store(raw);
        harness.timelineStore().createInitial(raw.runId(), raw.rawRef(), raw.receivedAt());
    }

    private static void writeRawDirectly(Harness harness, RawReceiptV1 raw) throws IOException {
        byte[] rawBytes = JsonV1Codec.encodeFile(raw);
        com.supplymind.foundation.model.ManifestV1 manifest = com.supplymind.foundation.storage.ManifestFactory.json(
                raw.rawRef(), rawBytes, java.util.List.of(raw.runId()), RECEIVED_AT);
        byte[] manifestBytes = JsonV1Codec.encodeFile(manifest);
        harness.fileStore().commit("raw-direct-" + raw.runId(),
                com.supplymind.foundation.storage.DirtyTransactionType.SINGLE_FILE, RECEIVED_AT,
                java.util.List.of(new com.supplymind.foundation.storage.FileTransactionTarget(
                        com.supplymind.foundation.storage.DirtyTargetRole.BUSINESS_FILE,
                        raw.rawRef(), rawBytes, manifestBytes, true)));
    }

    private static RawReceiptV1 pbocRaw(
            String runId,
            String itemId,
            String value,
            String businessDate,
            String unit,
            String currency,
            String sourceName
    ) {
        return pbocRaw(runId, itemId, value, businessDate, unit, currency, sourceName, 1);
    }

    private static RawReceiptV1 pbocRaw(
            String runId,
            String itemId,
            String value,
            String businessDate,
            String unit,
            String currency,
            String sourceName,
            int configVersion
    ) {
        return pbocRaw(runId, itemId, value, businessDate, unit, currency, sourceName, "text/html", 200, configVersion);
    }

    private static RawReceiptV1 pbocRaw(
            String runId,
            String itemId,
            String value,
            String businessDate,
            String unit,
            String currency,
            String sourceName,
            String contentType,
            Integer httpStatus
    ) {
        return pbocRaw(runId, itemId, value, businessDate, unit, currency, sourceName, contentType, httpStatus, 1);
    }

    private static RawReceiptV1 pbocRaw(
            String runId,
            String itemId,
            String value,
            String businessDate,
            String unit,
            String currency,
            String sourceName,
            String contentType,
            Integer httpStatus,
            int configVersion
    ) {
        byte[] payload = ("test/contract fixture PBOC-shaped page — NOT REAL PBOC — " + runId)
                .getBytes(StandardCharsets.UTF_8);
        return new RawReceiptV1(
                SchemaV1.VERSION,
                RawReceiptV1.deriveRawRef(Mode.FORMAL, ProviderType.OFFICIAL_WEB, itemId, RECEIVED_AT, runId),
                "acq-" + runId,
                runId,
                Mode.FORMAL,
                ProviderType.OFFICIAL_WEB,
                AccessMethod.PUBLIC_OFFICIAL_HTML,
                configVersion,
                sourceName,
                "https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/2026081009013821880/index.html",
                "PBOC公告列表=https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/index.html;公告标题=test fixture",
                itemId,
                businessDate,
                businessDate,
                businessDate == null ? null : "2026-08-10 09:25:38",
                PUBLISHED_AT,
                RECEIVED_AT,
                null,
                value,
                unit,
                currency,
                null,
                httpStatus,
                contentType,
                "base64",
                Base64.getEncoder().encodeToString(payload),
                JsonV1Codec.sha256LowerHex(payload),
                  "1美元对人民币",
                  RECEIVED_AT,
                  null
          );
    }

    private static byte[] fixtureBytes(String name) throws IOException {
        try (InputStream stream = Objects.requireNonNull(
                PbocValidationPipelineTest.class.getClassLoader().getResourceAsStream(FIXTURE_ROOT + name),
                () -> "Missing D2-T01 contract fixture " + name)) {
            return stream.readAllBytes();
        }
    }

    private record Harness(
            DataRoot root,
            AtomicFileStore fileStore,
            ConfigActivationStore configStore,
            RawReceiptStore rawStore,
            TimelineStore timelineStore,
            LifecycleValidationService service
    ) {
    }
}
