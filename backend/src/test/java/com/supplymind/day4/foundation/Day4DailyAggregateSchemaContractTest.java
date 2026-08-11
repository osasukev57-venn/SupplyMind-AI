package com.supplymind.day4.foundation;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hand-frozen Day 4 CSV schema/reference harness. Header and fingerprint expectations are local
 * literals from FILE-SCHEMA-V1, not production codec constants or a production canonicalizer.
 */
class Day4DailyAggregateSchemaContractTest {

    static final List<String> DAILY_HEADER = List.of(
            "schemaVersion", "businessDate", "itemId", "providerType", "actualSourceName", "accessMethod",
            "processingStage", "validationStatus", "validationVersion", "configVersions", "calculationVersion",
            "calculationScale", "displayScale", "roundingMode", "calendarVersion", "sum", "validCount", "avg",
            "expectedCount", "missingCount", "complete", "currency", "unit", "inputRefs", "updatedAt");
    static final List<String> AGGREGATE_HEADER = List.of(
            "schemaVersion", "grain", "periodStart", "periodEnd", "itemId", "providerType", "actualSourceName",
            "accessMethod", "validationStatus", "validationVersion", "configVersions", "calculationVersion",
            "calculationScale", "displayScale", "roundingMode", "calendarVersion", "sum", "validCount", "avg",
            "min", "max", "expectedCount", "missingCount", "complete", "qualityStatus", "currency", "unit",
            "sourceFingerprint", "inputRefs", "calculatedAt");
    private static final String SOURCE_IDENTITY = "{\"providerType\":\"official_web\",\"actualSourceName\":\""
            + "Day4 test contract source\",\"accessMethod\":\"public_official_html\"}";

    @Test
    void dailyHeaderAndPublishedInputReferenceContractAreFrozenForFutureD4T03() {
        assertEquals(25, DAILY_HEADER.size());
        assertEquals("schemaVersion,businessDate,itemId,providerType,actualSourceName,accessMethod,"
                        + "processingStage,validationStatus,validationVersion,configVersions,calculationVersion,"
                        + "calculationScale,displayScale,roundingMode,calendarVersion,sum,validCount,avg,"
                        + "expectedCount,missingCount,complete,currency,unit,inputRefs,updatedAt",
                String.join(",", DAILY_HEADER));

        DailyInputRefContract legal = new DailyInputRefContract("run-0001", "raw/formal/official_web/"
                + "FX.EUR.CNY.GOLDEN/2025/01/run-0001.json", 4, "PUBLISHED", "VERIFIED");
        DailyInputRefContract pending = new DailyInputRefContract("run-0002", "raw/formal/official_web/"
                + "FX.EUR.CNY.GOLDEN/2025/01/run-0002.json", 4, "PARSED", "PENDING");
        DailyInputRefContract oldVersion = new DailyInputRefContract("run-0003", "raw/formal/official_web/"
                + "FX.EUR.CNY.GOLDEN/2025/01/run-0003.json", 3, "PUBLISHED", "VERIFIED");

        assertTrue(legal.eligibleForDaily());
        assertFalse(pending.eligibleForDaily());
        assertFalse(oldVersion.eligibleForDaily());
    }

    @Test
    void aggregateHeaderFingerprintAndInputReferenceOrderAreFrozenForFutureD4T04() throws Exception {
        assertEquals(30, AGGREGATE_HEADER.size());
        assertEquals("schemaVersion,grain,periodStart,periodEnd,itemId,providerType,actualSourceName,"
                        + "accessMethod,validationStatus,validationVersion,configVersions,calculationVersion,"
                        + "calculationScale,displayScale,roundingMode,calendarVersion,sum,validCount,avg,min,max,"
                        + "expectedCount,missingCount,complete,qualityStatus,currency,unit,sourceFingerprint,inputRefs,calculatedAt",
                String.join(",", AGGREGATE_HEADER));
        assertFalse(AGGREGATE_HEADER.contains("period"), "frozen schema uses grain/periodStart/periodEnd only");
        assertEquals("db7a7eb317b91f5ca5802cc44554a87f6b4acedabc25aad3c24d27378f393166",
                sha256(SOURCE_IDENTITY));

        List<AggregateInputRefContract> unordered = List.of(
                new AggregateInputRefContract("processed/daily/FX.EUR.CNY.GOLDEN/2025-02.csv", "2025-02-20", "v1", "bb"),
                new AggregateInputRefContract("processed/daily/FX.EUR.CNY.GOLDEN/2025-01.csv", "2025-01-20", "v1", "cc"),
                new AggregateInputRefContract("processed/daily/FX.EUR.CNY.GOLDEN/2025-01.csv", "2025-01-10", "v1", "aa"));
        List<AggregateInputRefContract> ordered = unordered.stream().sorted(AggregateInputRefContract.ORDER).toList();

        assertEquals(List.of("2025-01-10", "2025-01-20", "2025-02-20"),
                ordered.stream().map(AggregateInputRefContract::businessDate).toList());
        assertEquals(List.of("month", "quarter", "halfyear", "year"),
                List.of("month", "quarter", "halfyear", "year"));
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    record DailyInputRefContract(String runId, String rawRef, int recordVersion, String stage, String status) {
        boolean eligibleForDaily() {
            return recordVersion == 4 && "PUBLISHED".equals(stage)
                    && ("VERIFIED".equals(status) || "VERIFIED_WITH_NOTICE".equals(status));
        }
    }

    record AggregateInputRefContract(String dailyFileRef, String businessDate, String validationVersion, String fileSha256) {
        static final Comparator<AggregateInputRefContract> ORDER = Comparator
                .comparing(AggregateInputRefContract::businessDate)
                .thenComparing(AggregateInputRefContract::dailyFileRef)
                .thenComparing(AggregateInputRefContract::validationVersion)
                .thenComparing(AggregateInputRefContract::fileSha256);
    }
}