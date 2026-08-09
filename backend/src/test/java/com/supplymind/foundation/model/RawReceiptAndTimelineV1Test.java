package com.supplymind.foundation.model;

import com.supplymind.foundation.codec.JsonV1Codec;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RawReceiptAndTimelineV1Test {
    @Test
    void rawReceiptPreservesRawLexicalValueButContainsNoLifecycleState() {
        RawReceiptV1 receipt = DomainFixtures.rawReceipt();

        String json = new String(JsonV1Codec.encodeFile(receipt), java.nio.charset.StandardCharsets.UTF_8);

        assertTrue(json.contains("\"rawValue\":\"7.123456789000\""));
        assertTrue(json.contains("\"sourceUrl\":null"));
        assertFalse(json.contains("processingStage"));
        assertFalse(json.contains("validationStatus"));
        assertEquals(receipt, JsonV1Codec.decodeFile(JsonV1Codec.encodeFile(receipt), RawReceiptV1.class));
    }

    @Test
    void lifecycleAllowsOnlyTheFrozenFourRecordPublishedChain() {
        LifecycleTimelineV1 timeline = DomainFixtures.publishedTimeline();

        assertEquals(4, timeline.currentRecordVersion());
        assertTrue(timeline.isPublishedForDailyInput());
        assertEquals("staging/test-run-usd-001.json#recordVersion=4", timeline.current().publishRef());

        LifecycleSnapshotV1 invalidSkip = new LifecycleSnapshotV1(
                2, ProcessingStage.VALIDATED, ValidationStatus.VERIFIED, DomainFixtures.candidate(), null,
                "validation-test-v1", OffsetDateTime.parse("2026-08-08T10:01:00+08:00"), null, null,
                OffsetDateTime.parse("2026-08-08T10:01:00+08:00"));
        assertThrows(SchemaValidationException.class, () -> LifecycleTimelineV1.initial(
                "test-record-002", "test-run-002", DomainFixtures.rawReceipt().rawRef(), DomainFixtures.RECEIVED_AT
        ).append(invalidSkip));
    }

    @Test
    void candidateIsImmutableWithinRunAndDailyReferenceCannotUseAnotherVersion() {
        LifecycleTimelineV1 initial = LifecycleTimelineV1.initial(
                "test-record-003", DomainFixtures.RUN_ID, DomainFixtures.rawReceipt().rawRef(), DomainFixtures.RECEIVED_AT);
        LifecycleTimelineV1 parsed = initial.append(new LifecycleSnapshotV1(
                2, ProcessingStage.PARSED, ValidationStatus.PENDING, DomainFixtures.candidate(), null, null, null, null,
                null, DomainFixtures.RECEIVED_AT.plusMinutes(1)));
        CandidateV1 altered = new CandidateV1(
                DomainFixtures.ITEM_ID, "2026-08-08", "7.123456789001", "CNY", "CNY/1 USD",
                ProviderType.SYNTHETIC_DEMO, "test/contract fixture", AccessMethod.SYNTHETIC_DEMO,
                "normalization-test-v1");
        LifecycleSnapshotV1 alteredValidation = new LifecycleSnapshotV1(
                3, ProcessingStage.VALIDATED, ValidationStatus.VERIFIED, altered, null, "validation-test-v1",
                DomainFixtures.RECEIVED_AT.plusMinutes(2), null, null, DomainFixtures.RECEIVED_AT.plusMinutes(2));

        assertThrows(SchemaValidationException.class, () -> parsed.append(alteredValidation));
        assertThrows(SchemaValidationException.class, () -> new DailyInputRefV1(
                DomainFixtures.RUN_ID, DomainFixtures.rawReceipt().rawRef(), 3));
    }
}
