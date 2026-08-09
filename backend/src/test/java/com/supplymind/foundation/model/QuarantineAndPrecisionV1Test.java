package com.supplymind.foundation.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QuarantineAndPrecisionV1Test {
    @Test
    void onlyTheThreeFailureTerminalsCanProjectToQuarantineAndProjectionUsesReceivedPartition() {
        RawReceiptV1 raw = DomainFixtures.rawReceipt();
        LifecycleTimelineV1 rejected = LifecycleTimelineV1.initial(
                "test-record-rejected", raw.runId(), raw.rawRef(), raw.receivedAt()).append(new LifecycleSnapshotV1(
                2, ProcessingStage.RECEIVED, ValidationStatus.REJECTED, null, "PARSE_FAILED", null, null,
                null, null, raw.receivedAt().plusMinutes(1)));

        QuarantineProjectionV1 projection = QuarantineProjectionV1.fromTerminal(raw, rejected, "c".repeat(64));

        assertEquals("quarantine/FX.USD.CNY.PBOC_MID/2026-08/test-run-usd-001.json", projection.quarantineRef());
        assertEquals(2, projection.terminalRecordVersion());
        assertEquals(raw.receivedAt().plusMinutes(1), projection.quarantinedAt());
        assertThrows(SchemaValidationException.class, () -> QuarantineProjectionV1.fromTerminal(
                raw, DomainFixtures.publishedTimeline(), "c".repeat(64)));
    }

    @Test
    void preciseValuesUseStringConstructionPlainTextAndPreserveTrailingZeros() {
        assertEquals("7.123456789000", DecimalText.canonical("7.123456789000", "fixture"));
        assertEquals("0.000000001", DecimalText.canonical("0.000000001", "fixture"));
        assertEquals("1000", DecimalText.canonical("1E+3", "fixture"));
        assertEquals("7.123456789000", DecimalText.canonicalAtScale("7.123456789000", 12, "fixture"));
        assertEquals("0.333333333333", DecimalText.toPlainString(
                DecimalText.parse("1", "numerator").divide(new BigDecimal("3"), 12, RoundingMode.HALF_UP),
                "non-terminating quotient"));
        assertThrows(SchemaValidationException.class, () -> DecimalText.canonicalAtScale("7.123456789", 12, "fixture"));
    }
}
