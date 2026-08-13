package com.supplymind.agent;

import com.supplymind.agent.infrastructure.springai.CostImpactToolAdapter;
import com.supplymind.agent.infrastructure.springai.HistoryQueryToolAdapter;
import com.supplymind.agent.infrastructure.springai.PeriodMetricsToolAdapter;
import com.supplymind.agent.infrastructure.springai.ProvenanceTraceToolAdapter;
import com.supplymind.agent.infrastructure.springai.QualityInspectToolAdapter;
import com.supplymind.agent.infrastructure.springai.SeriesResolveToolAdapter;
import com.supplymind.agent.infrastructure.springai.SupplyMindToolCallbackProvider;
import com.supplymind.agent.infrastructure.springai.WarningExplainToolAdapter;
import com.supplymind.agent.tool.ToolResult;
import com.supplymind.agent.tool.ToolStatus;
import com.supplymind.config.ConfigManagementService;
import com.supplymind.foundation.codec.CsvV1Codec;
import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.DailyInputRefV1;
import com.supplymind.foundation.model.DailyRecordV1;
import com.supplymind.foundation.model.ManifestV1;
import com.supplymind.foundation.model.Mode;
import com.supplymind.foundation.model.MonitorSeriesConfigV1;
import com.supplymind.foundation.model.MonitorSeriesDefaults;
import com.supplymind.foundation.model.MonitorSeriesItemV1;
import com.supplymind.foundation.model.ProcessingStage;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.RouteDecision;
import com.supplymind.foundation.model.ValidationStatus;
import com.supplymind.foundation.storage.AtomicFileStore;
import com.supplymind.foundation.storage.AtomicMoveSupport;
import com.supplymind.foundation.storage.ConfigActivationStore;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.DirtyMarkerCodec;
import com.supplymind.foundation.storage.DirtyTargetRole;
import com.supplymind.foundation.storage.DirtyTransactionType;
import com.supplymind.foundation.storage.FileTransactionTarget;
import com.supplymind.foundation.storage.ManifestFactory;
import com.supplymind.history.HistoryQueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;

import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D6-T01 read-only tool boundary tests: the seven frozen tools call the REAL production
 * services (HistoryQueryService / ConfigManagementService) over a real temporary data root;
 * Spring AI Tool metadata is genuinely exposed through the ToolCallbackProvider; every tool
 * stays read-only (data-root snapshot unchanged); path traversal / unknown series / invalid
 * dates / oversized ranges are REJECTED structurally.
 */
class AgentToolBoundaryTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-10T02:00:00Z"), ZoneOffset.UTC);
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-08-10T10:00:00+08:00");
    private static final String FX_ITEM = MonitorSeriesDefaults.USD_CNY_ITEM_ID;

    @TempDir
    Path temporaryDirectory;

    @Test
    void springAiExposesExactlySevenReadOnlyToolCallbacks() {
        Harness harness = harness("t01 callbacks");
        SupplyMindToolCallbackProvider provider = new SupplyMindToolCallbackProvider(List.of(
                harness.seriesResolve(), harness.historyQuery(), harness.periodMetrics(),
                harness.qualityInspect(), harness.costImpact(), harness.warningExplain(),
                harness.provenanceTrace()));
        ToolCallback[] callbacks = provider.getToolCallbacks();
        List<String> names = java.util.Arrays.stream(callbacks)
                .map(callback -> callback.getToolDefinition().name()).sorted().toList();
        assertEquals(List.of(
                "cost.impact", "history.query", "period.metrics", "provenance.trace",
                "quality.inspect", "series.resolve", "warning.explain"), names);
        for (ToolCallback callback : callbacks) {
            assertFalse(callback.getToolDefinition().description().isBlank());
            assertTrue(callback.getToolDefinition().inputSchema() != null);
        }
    }

    @Test
    void seriesResolveReadsActiveConfigAndRejectsUnknownSeries() {
        Harness harness = harness("t01 series");
        ToolResult resolved = harness.seriesResolve().seriesResolve(FX_ITEM, "r1");
        assertEquals(ToolStatus.SUCCESS, resolved.status());
        assertEquals(FX_ITEM, resolved.result().get("itemId"));
        assertTrue(resolved.evidenceRefs().contains(DataPaths.configActiveRef()));

        ToolResult unknown = harness.seriesResolve().seriesResolve("FX.UNKNOWN.XXX", "r2");
        assertEquals(ToolStatus.NO_DATA, unknown.status());

        ToolResult pathAttack = harness.seriesResolve().seriesResolve("../../etc/passwd", "r3");
        assertEquals(ToolStatus.REJECTED, pathAttack.status(), "path traversal must be REJECTED");
        ToolResult windowsAttack = harness.seriesResolve().seriesResolve("D:\\secret\\file", "r4");
        assertEquals(ToolStatus.REJECTED, windowsAttack.status());
    }

    @Test
    void historyQueryReusesProductionHistoryServiceAndEnforcesBounds() {
        Harness harness = harness("t01 history");
        ToolResult result = harness.historyQuery().historyQuery(
                FX_ITEM, "2026-08-01", "2026-08-10", "r1");
        assertEquals(ToolStatus.SUCCESS, result.status());
        assertEquals(10, result.result().get("rowCount"));
        assertTrue(result.evidenceRefs().stream().anyMatch(ref -> ref.startsWith("raw/")),
                "history rows must carry raw evidence refs");

        ToolResult invalidRange = harness.historyQuery().historyQuery(
                FX_ITEM, "2026-08-10", "2026-08-01", "r2");
        assertEquals(ToolStatus.REJECTED, invalidRange.status(), "inverted range must be REJECTED");

        ToolResult oversized = harness.historyQuery().historyQuery(
                FX_ITEM, "2010-01-01", "2026-08-10", "r3");
        assertEquals(ToolStatus.REJECTED, oversized.status(), "oversized range must be REJECTED");

        ToolResult badDate = harness.historyQuery().historyQuery(
                FX_ITEM, "not-a-date", "2026-08-10", "r4");
        assertEquals(ToolStatus.REJECTED, badDate.status());

        ToolResult pathItem = harness.historyQuery().historyQuery(
                "../daily/../config/monitor-series", "2026-08-01", "2026-08-10", "r5");
        assertEquals(ToolStatus.REJECTED, pathItem.status(), "path-like itemId must be REJECTED");
    }

    @Test
    void periodMetricsAndCostImpactReadPersistedAggregates() {
        Harness harness = harness("t01 metrics");
        ToolResult metrics = harness.periodMetrics().periodMetrics(
                FX_ITEM, "month", "2026", "2026", "r1");
        assertEquals(ToolStatus.SUCCESS, metrics.status());
        assertTrue(metrics.evidenceRefs().contains(DataPaths.aggregateRef(FX_ITEM, "month", 2026)));

        ToolResult impact = harness.costImpact().costImpact(
                FX_ITEM, "month", "2026-08-01", "r2");
        assertEquals(ToolStatus.NO_DATA, impact.status(),
                "no previous-period baseline in fixture: cost.impact must honestly report NO_DATA");
        assertFalse(impact.result().containsKey("changeRatio"),
                "without a baseline no change ratio may be fabricated");

        ToolResult badGrain = harness.periodMetrics().periodMetrics(
                FX_ITEM, "decade", "2026", "2026", "r3");
        assertEquals(ToolStatus.REJECTED, badGrain.status());
    }

    @Test
    void qualityInspectAndWarningExplainAreHonest() {
        Harness harness = harness("t01 quality");
        ToolResult quality = harness.qualityInspect().qualityInspect(
                FX_ITEM, "2026-08-01", "2026-08-10", "r1");
        assertEquals(ToolStatus.SUCCESS, quality.status());
        assertEquals(10, quality.result().get("rowCount"));
        assertEquals(0, quality.result().get("missingCountTotal"), "fixture rows are complete");

        ToolResult warnings = harness.warningExplain().warningExplain(FX_ITEM, "2026-08", "r2");
        assertEquals(ToolStatus.NO_DATA, warnings.status(), "no warnings persisted: honest NO_DATA");
    }

    @Test
    void provenanceTraceMarksMissingRawAsUnavailableNeverVerified() {
        Harness harness = harness("t01 provenance");
        ToolResult trace = harness.provenanceTrace().provenanceTrace(
                FX_ITEM, "2026-08-01", "2026-08-10", "r1");
        assertEquals(ToolStatus.SUCCESS, trace.status());
        assertFalse(((List<?>) trace.result().get("unavailableRawRefs")).isEmpty(),
                "fixture raw files are not persisted, so provenance must report them unavailable");
    }

    @Test
    void everyToolLeavesTheDataRootByteIdentical() throws Exception {
        Harness harness = harness("t01 readonly");
        Map<String, String> before = snapshot(harness.root());
        harness.seriesResolve().seriesResolve(FX_ITEM, "r1");
        harness.historyQuery().historyQuery(FX_ITEM, "2026-08-01", "2026-08-10", "r2");
        harness.periodMetrics().periodMetrics(FX_ITEM, "month", "2026", "2026", "r3");
        harness.qualityInspect().qualityInspect(FX_ITEM, "2026-08-01", "2026-08-10", "r4");
        harness.costImpact().costImpact(FX_ITEM, "month", "2026-08-01", "r5");
        harness.warningExplain().warningExplain(FX_ITEM, "2026-08", "r6");
        harness.provenanceTrace().provenanceTrace(FX_ITEM, "2026-08-01", "2026-08-10", "r7");
        assertEquals(before, snapshot(harness.root()),
                "tools are READ_ONLY: no byte in the data root may change");
    }

    private Harness harness(String leaf) {
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve(leaf));
        AtomicMoveSupport.probeOrFail(root);
        AtomicFileStore files = new AtomicFileStore(root, new DirtyMarkerCodec());
        ConfigActivationStore configs = new ConfigActivationStore(root, files, CLOCK);
        configs.activate(new MonitorSeriesConfigV1("1.0", 1, Mode.FORMAL, AT,
                List.of(fxItem())));
        HistoryQueryService history = new HistoryQueryService(root);
        String dailySha256 = writeDailyFixture(root, files, FX_ITEM);
        writeAggregateFixture(root, files, FX_ITEM, dailySha256);
        return new Harness(root, history, new ConfigManagementService(configs, new com.supplymind.provider.DataProviderRegistry()),
                new SeriesResolveToolAdapter(new ConfigManagementService(configs, new com.supplymind.provider.DataProviderRegistry())),
                new HistoryQueryToolAdapter(history),
                new PeriodMetricsToolAdapter(history),
                new QualityInspectToolAdapter(history),
                new CostImpactToolAdapter(history),
                new WarningExplainToolAdapter(root),
                new ProvenanceTraceToolAdapter(root, history));
    }

    private static MonitorSeriesItemV1 fxItem() {
        return new MonitorSeriesItemV1(
                FX_ITEM, "美元/人民币中间价", true, "PBOC", ProviderType.OFFICIAL_WEB,
                AccessMethod.PUBLIC_OFFICIAL_HTML, "中国人民银行官网（授权中国外汇交易中心公布）",
                RouteDecision.PRIMARY, null, AT, null, "USD", "1美元对人民币", "人民币汇率中间价",
                "arithmetic-mean-v1", 8, 4, RoundingMode.HALF_UP, "weekday-asia-shanghai-v1",
                "CNY", "USD", "CNY/1 USD", null);
    }

    private static String writeDailyFixture(DataRoot root, AtomicFileStore files, String itemId) {
        YearMonth month = YearMonth.of(2026, 8);
        java.util.ArrayList<DailyRecordV1> rows = new java.util.ArrayList<>();
        for (int day = 1; day <= 10; day++) {
            String businessDate = "2026-08-" + String.format("%02d", day);
            String runId = "fx-run-" + businessDate.replace("-", "");
            String rawRef = DataPaths.rawRef("formal", "official_web", itemId,
                    AT, runId);
            rows.add(new DailyRecordV1(
                    "1.0", businessDate, itemId, ProviderType.OFFICIAL_WEB,
                    "中国人民银行官网（授权中国外汇交易中心公布）", AccessMethod.PUBLIC_OFFICIAL_HTML,
                    ProcessingStage.PUBLISHED, ValidationStatus.VERIFIED, "pboc-basic-validation-v1",
                    List.of(1), "arithmetic-mean-v1", 8, 4, RoundingMode.HALF_UP,
                    "weekday-asia-shanghai-v1", String.format("7.%08d", 11000000 + day * 1000000),
                    1, String.format("7.%08d", 11000000 + day * 1000000), 1, 0, true,
                    "CNY", "CNY/1 USD", List.of(new DailyInputRefV1(runId, rawRef, 4)),
                    AT, null));
        }
        String ref = DataPaths.dailyRef(itemId, month);
        byte[] data = CsvV1Codec.encodeDaily(rows);
        ManifestV1 manifest = ManifestFactory.csv(ref, data, rows.size(),
                "2026-08-01", "2026-08-10", rows.stream().flatMap(row -> row.inputRefs().stream())
                        .map(DailyInputRefV1::runId).distinct().sorted().toList(), AT);
        files.commit("daily-fixture", DirtyTransactionType.SINGLE_FILE, AT,
                List.of(new FileTransactionTarget(DirtyTargetRole.BUSINESS_FILE, ref, data,
                        com.supplymind.foundation.codec.JsonV1Codec.encodeFile(manifest), false)));
        return manifest.fileSha256();
    }

    private static void writeAggregateFixture(DataRoot root, AtomicFileStore files, String itemId,
                                              String dailySha256) {
        YearMonth month = YearMonth.of(2026, 8);
        java.util.ArrayList<com.supplymind.foundation.model.AggregateRecordV1> rows = new java.util.ArrayList<>();
        rows.add(aggregateRow(itemId, "2026-08", "7.15000000", dailySha256));
        String ref = DataPaths.aggregateRef(itemId, "month", 2026);
        byte[] data = CsvV1Codec.encodeAggregate(rows);
        ManifestV1 manifest = ManifestFactory.csv(ref, data, rows.size(),
                "2026-08-01", "2026-08-31",
                rows.stream().flatMap(row -> row.inputRefs().stream())
                        .map(com.supplymind.foundation.model.AggregateInputRefV1::dailyFileRef)
                        .map(dailyRef -> {
                            try {
                                return com.supplymind.foundation.codec.JsonV1Codec.decodeFile(
                                        Files.readAllBytes(root.resolveDataRef(DataPaths.manifestRef(dailyRef))),
                                        ManifestV1.class).sourceRunIds();
                            } catch (java.io.IOException exception) {
                                throw new RuntimeException(exception);
                            }
                        })
                        .flatMap(List::stream).distinct().sorted().toList(), AT);
        files.commit("aggregate-fixture", DirtyTransactionType.SINGLE_FILE, AT,
                List.of(new FileTransactionTarget(DirtyTargetRole.BUSINESS_FILE, ref, data,
                        com.supplymind.foundation.codec.JsonV1Codec.encodeFile(manifest), false)));
    }

    private static com.supplymind.foundation.model.AggregateRecordV1 aggregateRow(
            String itemId, String monthKey, String avg, String dailySha256
    ) {
        YearMonth month = YearMonth.parse(monthKey);
        java.util.ArrayList<com.supplymind.foundation.model.AggregateInputRefV1> inputs = new java.util.ArrayList<>();
        for (int day = 1; day <= 10; day++) {
            inputs.add(new com.supplymind.foundation.model.AggregateInputRefV1(
                    DataPaths.dailyRef(itemId, month),
                    month.atDay(day).toString(), "pboc-basic-validation-v1",
                    dailySha256));
        }
        return new com.supplymind.foundation.model.AggregateRecordV1(
                "1.0", com.supplymind.foundation.model.AggregateGrain.MONTH,
                month.atDay(1).toString(), month.atEndOfMonth().toString(), itemId,
                ProviderType.OFFICIAL_WEB, "中国人民银行官网（授权中国外汇交易中心公布）",
                AccessMethod.PUBLIC_OFFICIAL_HTML, ValidationStatus.VERIFIED,
                "pboc-basic-validation-v1", List.of(1), "arithmetic-mean-v1",
                8, 4, RoundingMode.HALF_UP, "weekday-asia-shanghai-v1",
                "70.50000000", 10, avg, "7.00000000", "7.20000000",
                10, 0, true, com.supplymind.foundation.model.QualityStatus.COMPLETE,
                "CNY", "CNY/1 USD",
                com.supplymind.foundation.model.CanonicalJsonV1.sha256LowerHex(
                        com.supplymind.foundation.model.CanonicalJsonV1.sourceIdentity(
                                ProviderType.OFFICIAL_WEB,
                                "中国人民银行官网（授权中国外汇交易中心公布）",
                                AccessMethod.PUBLIC_OFFICIAL_HTML)),
                List.copyOf(inputs), AT, null);
    }

    private static Map<String, String> snapshot(DataRoot root) throws Exception {
        java.util.TreeMap<String, String> snapshot = new java.util.TreeMap<>();
        try (var walk = Files.walk(root.path())) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> !root.path().relativize(path).toString().replace('\\', '/')
                            .equals("runtime/dirty/.supplymind-writer.lock"))
                    .forEach(path -> {
                        try {
                            snapshot.put(root.path().relativize(path).toString().replace('\\', '/'),
                                    com.supplymind.foundation.storage.FileDigest.sha256(path));
                        } catch (Exception ignored) {
                        }
                    });
        }
        return snapshot;
    }

    private record Harness(
            DataRoot root,
            HistoryQueryService history,
            ConfigManagementService config,
            SeriesResolveToolAdapter seriesResolve,
            HistoryQueryToolAdapter historyQuery,
            PeriodMetricsToolAdapter periodMetrics,
            QualityInspectToolAdapter qualityInspect,
            CostImpactToolAdapter costImpact,
            WarningExplainToolAdapter warningExplain,
            ProvenanceTraceToolAdapter provenanceTrace
    ) {
    }
}
