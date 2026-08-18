package com.supplymind.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.supplymind.dashboard.api.DashboardV1;
import com.supplymind.foundation.codec.JsonV1Codec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D7 frozen DTO contract: the JSON field names ARE the API contract. Every nested record must
 * serialize to exactly its frozen keys - adding/removing a field breaks the contract test.
 */
class DashboardDtoContractTest {

    @Test
    void overviewFieldNamesAreFrozen() throws Exception {
        DashboardV1.OverviewResponse dto = new DashboardV1.OverviewResponse(
                "FORMAL", List.of(new DashboardV1.ItemCard(
                "FX.USD.CNY.PBOC_MID", "美元/人民币", true, "6.7904", "2026-08-10",
                "CNY/1 USD", "CNY", "2026-08-10", "1.000000000000",
                new DashboardV1.SourceView("official_web", "public_official_html",
                        "中国人民银行官网", "PRIMARY", null),
                new DashboardV1.QualityView("VERIFIED", "VERIFIED", "pboc-basic-validation-v1",
                        false, "2026-08-10T09:25:38+08:00"),
                null,
                new DashboardV1.AggregateSummary("month", "2026-08-01", "2026-08-31",
                        "6.79040000", "CNY/1 USD"))), List.of());

        JsonNode json = JsonV1Codec.mapper().readTree(JsonV1Codec.encodeCompact(dto));
        assertEquals(Set.of("mode", "items", "warnings"), keys(json));
        JsonNode card = json.get("items").get(0);
        assertEquals(Set.of("itemId", "displayName", "enabled", "latestValue", "businessDate",
                "unit", "currency", "dataThrough", "completeness", "source", "quality",
                "warningSummary", "aggregateSummary"), keys(card));
        assertEquals(Set.of("providerType", "accessMethod", "actualSourceName", "routeDecision",
                "fallbackReason"), keys(card.get("source")));
        assertEquals(Set.of("status", "validationStatus", "validationVersion", "stale",
                "updatedAt"), keys(card.get("quality")));
        assertEquals(Set.of("grain", "periodStart", "periodEnd", "value", "unit"),
                keys(card.get("aggregateSummary")));
        assertEquals("6.7904", json.get("items").get(0).get("latestValue").asText(),
                "the exact decimal string must survive the wire contract");
    }

    @Test
    void historyMetricsQualitySourcesFieldNamesAreFrozen() throws Exception {
        DashboardV1.HistoryResponse history = new DashboardV1.HistoryResponse(
                "FX.USD.CNY.PBOC_MID", "2026-08-01", "2026-08-31",
                List.of(new DashboardV1.HistoryPoint("2026-08-10", "6.79040000", "CNY/1 USD",
                        "中国人民银行官网", "VERIFIED", "pboc-basic-validation-v1")),
                new DashboardV1.Chart(640, 160, List.of(new DashboardV1.ChartPoint(
                        "2026-08-10 6.79040000", "8.0", "76.0"))),
                List.of(new DashboardV1.EvidenceIssue(List.of("2026-01"), "MISSING",
                        "daily file(s) not found")),
                "2026-08-10");
        JsonNode historyJson = JsonV1Codec.mapper().readTree(JsonV1Codec.encodeCompact(history));
        assertEquals(Set.of("itemId", "fromDate", "toDate", "points", "chart", "evidenceIssues",
                "dataThrough"), keys(historyJson));
        assertEquals(Set.of("businessDate", "value", "unit", "actualSourceName",
                "validationStatus", "validationVersion"), keys(historyJson.get("points").get(0)));
        assertEquals(Set.of("width", "height", "points"), keys(historyJson.get("chart")));
        assertEquals(Set.of("label", "x", "y"), keys(historyJson.get("chart").get("points").get(0)));
        assertEquals(Set.of("periods", "status", "reason"), keys(historyJson.get("evidenceIssues").get(0)));
        assertFalse(historyJson.get("evidenceIssues").get(0).get("periods").get(0).asText()
                        .contains("processed/"),
                "internal CSV paths must never appear in the wire contract");

        DashboardV1.MetricsResponse metrics = new DashboardV1.MetricsResponse(
                "FX.USD.CNY.PBOC_MID", "month", 2026, 2026,
                List.of(new DashboardV1.MetricRow("2026-08-01", "2026-08-31", "6.79040000",
                        "CNY/1 USD", "中国人民银行官网", "VERIFIED", "pboc-basic-validation-v1")),
                List.of());
        JsonNode metricsJson = JsonV1Codec.mapper().readTree(JsonV1Codec.encodeCompact(metrics));
        assertEquals(Set.of("itemId", "grain", "fromYear", "toYear", "rows", "evidenceIssues"),
                keys(metricsJson));
        assertEquals(Set.of("periodStart", "periodEnd", "value", "unit", "actualSourceName",
                "validationStatus", "validationVersion"), keys(metricsJson.get("rows").get(0)));

        DashboardV1.QualityResponse quality = new DashboardV1.QualityResponse(
                "FX.USD.CNY.PBOC_MID", "VERIFIED",
                List.of(new DashboardV1.QualityRow("2026-08-10", "6.79040000", "CNY/1 USD",
                        "中国人民银行官网", "official_web", "public_official_html", "VERIFIED",
                        "pboc-basic-validation-v1", false, "1.000000000000")),
                List.of(new DashboardV1.WarningView("w1", "r1", "v1", "2026-08-01", "2026-08-31",
                        "7.00000000", "5.00000000", "HIGH", "PUBLISHED_VERIFIED",
                        "2026-08-10T10:00:00+08:00")),
                List.of(new DashboardV1.EvidenceIssue(List.of("2026-01"), "MISSING", "not found")));
        JsonNode qualityJson = JsonV1Codec.mapper().readTree(JsonV1Codec.encodeCompact(quality));
        assertEquals(Set.of("itemId", "latestStatus", "rows", "warnings", "evidenceIssues"),
                keys(qualityJson));
        assertEquals(Set.of("businessDate", "value", "unit", "actualSourceName", "providerType",
                "accessMethod", "validationStatus", "validationVersion", "stale", "completeness"),
                keys(qualityJson.get("rows").get(0)));
        assertEquals("1.000000000000", qualityJson.get("rows").get(0).get("completeness").asText(),
                "completeness is a backend-computed decimal string");
        assertEquals(Set.of("warningId", "ruleId", "ruleVersion", "periodStart", "periodEnd",
                "value", "threshold", "riskLevel", "status", "createdAt"),
                keys(qualityJson.get("warnings").get(0)));

        DashboardV1.SourcesResponse sources = new DashboardV1.SourcesResponse(
                "FORMAL",
                List.of(new DashboardV1.SourceItem("FX.USD.CNY.PBOC_MID", "美元/人民币", true,
                        "PBOC", "official_web", "public_official_html", "中国人民银行官网",
                        "PRIMARY", null, "2026-08-10T00:00:00+08:00")),
                new DashboardV1.EntryStatus("PENDING", "contract"),
                new DashboardV1.EntryStatus("PENDING", "contract"));
        JsonNode sourcesJson = JsonV1Codec.mapper().readTree(JsonV1Codec.encodeCompact(sources));
        assertEquals(Set.of("mode", "items", "manualEntry", "importEntry"), keys(sourcesJson));
        assertEquals(Set.of("itemId", "displayName", "enabled", "sourceIntent", "providerType",
                "accessMethod", "actualSourceName", "routeDecision", "fallbackReason",
                "routeEffectiveAt"), keys(sourcesJson.get("items").get(0)));
        assertEquals("PENDING", sourcesJson.get("manualEntry").get("status").asText());
    }

    @Test
    void decimalStringsSurviveWireEncodingUnchanged() throws Exception {
        DashboardV1.HistoryResponse history = new DashboardV1.HistoryResponse(
                "FX.USD.CNY.PBOC_MID", "2026-08-01", "2026-08-31",
                List.of(new DashboardV1.HistoryPoint("2026-08-10",
                        "999999999999.123456789", "CNY/1 USD", "s", "VERIFIED", "v1")),
                new DashboardV1.Chart(640, 160, List.of()),
                List.of(), "2026-08-10");
        JsonNode json = JsonV1Codec.mapper().readTree(JsonV1Codec.encodeCompact(history));
        assertEquals("999999999999.123456789", json.get("points").get(0).get("value").asText(),
                "an 18-digit decimal string must not be mangled by the wire contract");
        assertFalse(json.get("points").get(0).get("value").isNumber(),
                "values stay strings - never parsed numbers");
    }

    private static Set<String> keys(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return Set.copyOf(names);
    }
}
