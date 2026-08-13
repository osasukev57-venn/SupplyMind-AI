package com.supplymind.day5.r2;

import com.supplymind.foundation.codec.CsvV1Codec;
import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.AggregateGrain;
import com.supplymind.foundation.model.AggregateInputRefV1;
import com.supplymind.foundation.model.AggregateRecordV1;
import com.supplymind.foundation.model.DailyInputRefV1;
import com.supplymind.foundation.model.DailyRecordV1;
import com.supplymind.foundation.model.ManifestV1;
import com.supplymind.foundation.model.ProcessingStage;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.QualityStatus;
import com.supplymind.foundation.model.ValidationStatus;
import com.supplymind.foundation.storage.AtomicFileStore;
import com.supplymind.foundation.storage.AtomicMoveSupport;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.DirtyMarkerCodec;
import com.supplymind.foundation.storage.DirtyTargetRole;
import com.supplymind.foundation.storage.DirtyTransactionType;
import com.supplymind.foundation.storage.FileTransactionTarget;
import com.supplymind.foundation.storage.ManifestFactory;
import com.supplymind.foundation.model.SchemaValidationException;
import com.supplymind.history.HistoryQueryService;
import com.supplymind.warning.WarningRecordV1;
import com.supplymind.warning.WarningRuleV1;
import com.supplymind.warning.WarningService;
import com.supplymind.warning.WarningStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Independent post-fix A6/A7 attacks against real WarningService/WarningRuleV1. */
class Day5R2WarningAttackTest {

    private static final String ITEM = "FX.R2.WARNING.USD";
    private static final OffsetDateTime LINEAGE_AT = OffsetDateTime.parse("2026-08-10T09:00:00+08:00");

    @TempDir
    Path temporaryDirectory;

    @Test
    void a6CrossClockAttackKeepsWarningIdentityLineageAndPersistedBytesIdentical() throws Exception {
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve("a6 warning cross-clock"));
        AtomicMoveSupport.probeOrFail(root);
        AtomicFileStore files = new AtomicFileStore(root, new DirtyMarkerCodec());
        String juneSha = writeDaily(files, root, YearMonth.of(2026, 6), "2026-06-10", "100.00000000", "r2-june");
        String julySha = writeDaily(files, root, YearMonth.of(2026, 7), "2026-07-10", "200.00000000", "r2-july");
        writeAggregate(files, root, List.of(
                aggregate("2026-06-01", "2026-06-30", "100.00000000", juneSha),
                aggregate("2026-07-01", "2026-07-31", "200.00000000", julySha)));

        WarningRuleV1 rule = new WarningRuleV1("r2-cross-clock", "demo-r2", WarningRuleV1.RuleKind.PRICE_CHANGE,
                ITEM, "month", "0.50", WarningRuleV1.Direction.ABOVE, 1, true,
                "TEST/DEMO only; EXT-07/EXT-08 are not final production thresholds");
        WarningService clockA = service(root, Instant.parse("2026-08-10T02:00:00Z"));
        WarningRecordV1 first = clockA.evaluate(rule, "2026-07-01", "2026-07-31");
        Path warningPath = root.resolveDataRef(DataPaths.warningRef(YearMonth.of(2026, 7), first.warningId()));
        byte[] firstBytes = Files.readAllBytes(warningPath);

        WarningService clockB = service(root, Instant.parse("2032-01-01T00:00:00Z"));
        WarningRecordV1 second = clockB.evaluate(rule, "2026-07-01", "2026-07-31");
        byte[] secondBytes = Files.readAllBytes(warningPath);

        assertEquals(first.warningId(), second.warningId());
        assertEquals(first.inputFingerprint(), second.inputFingerprint());
        assertEquals(first.evaluatedAt(), second.evaluatedAt(), "evaluatedAt must be input lineage, never either runtime clock");
        assertEquals(first.riskLevel(), second.riskLevel());
        assertArrayEquals(firstBytes, secondBytes, "re-evaluation under a different clock must preserve warning evidence bytes");
    }

    @Test
    void a7FormalThresholdRuleIsFailClosedWhileTheOnlyAllowedRuleIsExplicitlyDemo() {
        assertThrows(SchemaValidationException.class, () -> new WarningRuleV1(
                "r2-formal-leak", "v1", WarningRuleV1.RuleKind.PRICE_CHANGE, ITEM, "month", "0.1",
                WarningRuleV1.Direction.ABOVE, 1, false, "attempt to use EXT-07 threshold as final production rule"));

        WarningRuleV1 demo = new WarningRuleV1("r2-demo", "demo-r2", WarningRuleV1.RuleKind.PRICE_CHANGE,
                ITEM, "month", "0.1", WarningRuleV1.Direction.ABOVE, 1, true,
                "TEST/DEMO only; not a final EXT-07/EXT-08 production threshold");
        assertTrue(demo.demoRule());
        assertTrue(demo.description().contains("TEST/DEMO"));
        assertTrue(demo.description().contains("not a final"));
        assertNotEquals("production", demo.ruleVersion());
    }

    private WarningService service(DataRoot root, Instant instant) {
        Clock clock = Clock.fixed(instant, ZoneOffset.UTC);
        return new WarningService(root, new WarningStore(root, new AtomicFileStore(root, new DirtyMarkerCodec()), clock),
                clock, new HistoryQueryService(root));
    }

    private String writeDaily(AtomicFileStore files, DataRoot root, YearMonth month, String date, String value,
                              String runId) throws Exception {
        DailyRecordV1 row = new DailyRecordV1("1.0", date, ITEM, ProviderType.OFFICIAL_WEB,
                "R2 warning fixture source", AccessMethod.PUBLIC_OFFICIAL_HTML, ProcessingStage.PUBLISHED,
                ValidationStatus.VERIFIED, "pboc-basic-validation-v1", List.of(1), "arithmetic-mean-v1", 8, 4,
                RoundingMode.HALF_UP, "weekday-asia-shanghai-v1", value, 1, value, 1, 0, true, "CNY",
                "CNY/1 USD", List.of(new DailyInputRefV1(runId,
                "raw/formal/official_web/" + ITEM + "/2026/" + String.format("%02d", month.getMonthValue())
                        + "/" + runId + ".json", 4)), LINEAGE_AT, null);
        String ref = DataPaths.dailyRef(ITEM, month);
        byte[] data = CsvV1Codec.encodeDaily(List.of(row));
        ManifestV1 manifest = ManifestFactory.csv(ref, data, 1, date, date, List.of(runId), LINEAGE_AT);
        commit(files, ref, data, JsonV1Codec.encodeFile(manifest));
        return manifest.fileSha256();
    }

    private void writeAggregate(AtomicFileStore files, DataRoot root, List<AggregateRecordV1> rows) {
        String ref = DataPaths.aggregateRef(ITEM, "month", 2026);
        byte[] data = CsvV1Codec.encodeAggregate(rows);
        ManifestV1 manifest = ManifestFactory.csv(ref, data, rows.size(), "2026-06-01", "2026-07-31",
                List.of("r2-june", "r2-july"), LINEAGE_AT);
        commit(files, ref, data, JsonV1Codec.encodeFile(manifest));
    }

    private AggregateRecordV1 aggregate(String start, String end, String average, String dailySha) {
        String fingerprint = com.supplymind.foundation.model.CanonicalJsonV1.sha256LowerHex(
                com.supplymind.foundation.model.CanonicalJsonV1.sourceIdentity(ProviderType.OFFICIAL_WEB,
                        "R2 warning fixture source", AccessMethod.PUBLIC_OFFICIAL_HTML));
        return new AggregateRecordV1("1.0", AggregateGrain.MONTH, start, end, ITEM, ProviderType.OFFICIAL_WEB,
                "R2 warning fixture source", AccessMethod.PUBLIC_OFFICIAL_HTML, ValidationStatus.VERIFIED,
                "pboc-basic-validation-v1", List.of(1), "arithmetic-mean-v1", 8, 4, RoundingMode.HALF_UP,
                "weekday-asia-shanghai-v1", average, 1, average, average, average, 1, 0, true,
                QualityStatus.COMPLETE, "CNY", "CNY/1 USD", fingerprint,
                List.of(new AggregateInputRefV1(DataPaths.dailyRef(ITEM, YearMonth.parse(start.substring(0, 7))),
                        start, "pboc-basic-validation-v1", dailySha)), LINEAGE_AT, null);
    }

    private static void commit(AtomicFileStore files, String ref, byte[] data, byte[] manifest) {
        files.commit("r2-warning-" + ref.replace('/', '-').replace('.', '-'), DirtyTransactionType.SINGLE_FILE,
                LINEAGE_AT, List.of(new FileTransactionTarget(DirtyTargetRole.BUSINESS_FILE, ref, data, manifest, false)));
    }
}
