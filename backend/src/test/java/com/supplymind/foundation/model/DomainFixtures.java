package com.supplymind.foundation.model;

import com.supplymind.foundation.codec.JsonV1Codec;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;

/** Test/contract fixture only; it is deliberately SyntheticDemo and never a real PBOC receipt. */
public final class DomainFixtures {
    public static final OffsetDateTime RECEIVED_AT = OffsetDateTime.parse("2026-08-08T10:00:00+08:00");
    public static final String ITEM_ID = "FX.USD.CNY.PBOC_MID";
    public static final String RUN_ID = "test-run-usd-001";

    private DomainFixtures() {
    }

    public static RawReceiptV1 rawReceipt() {
        byte[] payload = "{\"fixture\":\"test/contract fixture\",\"value\":\"7.123456789000\"}"
                .getBytes(StandardCharsets.UTF_8);
        return new RawReceiptV1(
                SchemaV1.VERSION,
                RawReceiptV1.deriveRawRef(Mode.TEST, ProviderType.SYNTHETIC_DEMO, ITEM_ID, RECEIVED_AT, RUN_ID),
                "test-acquisition-001",
                RUN_ID,
                Mode.TEST,
                ProviderType.SYNTHETIC_DEMO,
                AccessMethod.SYNTHETIC_DEMO,
                1,
                "test/contract fixture",
                null,
                "test/contract fixture",
                ITEM_ID,
                "2026-08-08",
                "2026-08-08",
                null,
                null,
                RECEIVED_AT,
                null,
                "7.123456789000",
                "CNY/1 USD",
                "CNY",
                null,
                null,
                "application/json",
                "base64",
                Base64.getEncoder().encodeToString(payload),
                JsonV1Codec.sha256LowerHex(payload),
                "test/contract fixture USD field",
                RECEIVED_AT
        );
    }

    public static CandidateV1 candidate() {
        return new CandidateV1(
                ITEM_ID,
                "2026-08-08",
                "7.123456789000",
                "CNY",
                "CNY/1 USD",
                ProviderType.SYNTHETIC_DEMO,
                "test/contract fixture",
                AccessMethod.SYNTHETIC_DEMO,
                "normalization-test-v1"
        );
    }

    public static LifecycleTimelineV1 publishedTimeline() {
        LifecycleTimelineV1 initial = LifecycleTimelineV1.initial(
                "test-record-001", RUN_ID, rawReceipt().rawRef(), RECEIVED_AT);
        LifecycleSnapshotV1 parsed = new LifecycleSnapshotV1(
                2, ProcessingStage.PARSED, ValidationStatus.PENDING, candidate(), null, null, null, null, null,
                RECEIVED_AT.plusMinutes(1));
        LifecycleSnapshotV1 validated = new LifecycleSnapshotV1(
                3, ProcessingStage.VALIDATED, ValidationStatus.VERIFIED, candidate(), null, "validation-test-v1",
                RECEIVED_AT.plusMinutes(2), null, null, RECEIVED_AT.plusMinutes(2));
        LifecycleSnapshotV1 published = new LifecycleSnapshotV1(
                4, ProcessingStage.PUBLISHED, ValidationStatus.VERIFIED, candidate(), null, "validation-test-v1",
                RECEIVED_AT.plusMinutes(2), RECEIVED_AT.plusMinutes(3), "staging/" + RUN_ID + ".json#recordVersion=4",
                RECEIVED_AT.plusMinutes(3));
        return initial.append(parsed).append(validated).append(published);
    }
}
