package com.supplymind.foundation.codec;

import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.AggregateGrain;
import com.supplymind.foundation.model.AggregateInputRefV1;
import com.supplymind.foundation.model.AggregateRecordV1;
import com.supplymind.foundation.model.CanonicalJsonV1;
import com.supplymind.foundation.model.DailyInputRefV1;
import com.supplymind.foundation.model.DailyRecordV1;
import com.supplymind.foundation.model.ProcessingStage;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.QualityStatus;
import com.supplymind.foundation.model.SchemaV1;
import com.supplymind.foundation.model.ValidationStatus;
import org.junit.jupiter.api.Test;

import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvV1CodecTest {
    private static final String ITEM_ID = "FX.USD.CNY.PBOC_MID";
    private static final String SOURCE = "test/contract fixture";

    @Test
    void dailyCsvIsSortedRfc4180AndKeepsTwelveDigitPrecisionAndPublishedVersionFourRefs() {
        DailyRecordV1 later = daily("2026-08-20", "test-run-002");
        DailyRecordV1 earlier = daily("2026-08-10", "test-run-001");

        byte[] csv = CsvV1Codec.encodeDaily(List.of(later, earlier));
        String text = new String(csv, StandardCharsets.UTF_8);
        List<DailyRecordV1> decoded = CsvV1Codec.decodeDaily(csv);

        assertTrue(text.startsWith(String.join(",", CsvV1Codec.DAILY_HEADER) + "\r\n"));
        assertTrue(text.endsWith("\r\n"));
        assertFalse(text.contains("\n\n"));
        assertTrue(text.contains("7.123456789000"));
        assertTrue(text.contains("\"\"recordVersion\"\":4"));
        assertEquals("2026-08-10", decoded.get(0).businessDate());
        assertEquals(List.of(1, 2), decoded.get(0).configVersions());
    }

    @Test
    void aggregateFingerprintAndInputReferenceHaveFrozenCompactVectors() {
        String fingerprint = CanonicalJsonV1.sha256LowerHex(
                CanonicalJsonV1.sourceIdentity(ProviderType.SYNTHETIC_DEMO, SOURCE, AccessMethod.SYNTHETIC_DEMO));
        AggregateRecordV1 aggregate = new AggregateRecordV1(
                SchemaV1.VERSION, AggregateGrain.MONTH, "2026-08-01", "2026-08-31", ITEM_ID,
                ProviderType.SYNTHETIC_DEMO, SOURCE, AccessMethod.SYNTHETIC_DEMO, ValidationStatus.VERIFIED,
                "validation-test-v1", List.of(2, 1), "arithmetic-mean-v1", 12, 9, RoundingMode.HALF_UP,
                "golden-calendar-v1", "7.123456789000", 1, "7.123456789000", "7.123456789000",
                "7.123456789000", 1, 0, true, QualityStatus.COMPLETE, "CNY", "CNY/1 USD", fingerprint,
                List.of(new AggregateInputRefV1(
                        "processed/daily/FX.USD.CNY.PBOC_MID/2026-08.csv", "2026-08-10", "validation-test-v1",
                        "b".repeat(64))), OffsetDateTime.parse("2026-08-20T10:00:00+08:00"), null);

        byte[] csv = CsvV1Codec.encodeAggregate(List.of(aggregate));
        String text = new String(csv, StandardCharsets.UTF_8);

        assertTrue(text.startsWith(String.join(",", CsvV1Codec.AGGREGATE_HEADER) + "\r\n"));
        assertTrue(text.contains(fingerprint));
        assertTrue(text.contains("\"\"dailyFileRef\"\":\"\"processed/daily/FX.USD.CNY.PBOC_MID/2026-08.csv\"\""));
        assertEquals(List.of(aggregate), CsvV1Codec.decodeAggregate(csv));
    }

    private static DailyRecordV1 daily(String businessDate, String runId) {
        return new DailyRecordV1(
                SchemaV1.VERSION, businessDate, ITEM_ID, ProviderType.SYNTHETIC_DEMO, SOURCE,
                AccessMethod.SYNTHETIC_DEMO, ProcessingStage.PUBLISHED, ValidationStatus.VERIFIED,
                "validation-test-v1", List.of(2, 1), "arithmetic-mean-v1", 12, 9, RoundingMode.HALF_UP,
                "golden-calendar-v1", "7.123456789000", 1, "7.123456789000", 1, 0, true, "CNY", "CNY/1 USD",
                List.of(new DailyInputRefV1(runId,
                        "raw/test/synthetic_demo/FX.USD.CNY.PBOC_MID/2026/08/" + runId + ".json", 4)),
                OffsetDateTime.parse("2026-08-20T10:00:00+08:00"),
                null
        );
    }
}
