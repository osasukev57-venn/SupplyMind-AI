package com.supplymind.warning;

import com.supplymind.foundation.codec.CsvV1Codec;
import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.DailyInputRefV1;
import com.supplymind.foundation.model.DailyRecordV1;
import com.supplymind.foundation.model.ManifestV1;
import com.supplymind.foundation.model.ProcessingStage;
import com.supplymind.foundation.model.ProviderType;
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
import com.supplymind.history.HistoryQueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormalWarningEvaluationServiceTest {

    private static final String ITEM = "MAT.AZ91D.DEMO_IMPORT";
    private static final OffsetDateTime UPDATED = OffsetDateTime.parse("2026-08-24T09:00:00+08:00");

    @TempDir
    Path temp;

    @Test
    void materialDefaultBoundaryIsInclusiveAndProducesFormalImmutableWarning() throws Exception {
        Harness harness = harness(List.of(
                row("2026-08-17", "23000.00", "run-a"),
                row("2026-08-24", "24150.00", "run-b")));
        WarningRuleConfigV1 rule = rule("0.05", 7, WarningRuleConfigV1.Direction.BOTH, true);

        WarningEvaluationV1 first = harness.service().evaluate(rule, LocalDate.parse("2026-08-25"));
        WarningEvaluationV1 second = harness.service().evaluate(rule, LocalDate.parse("2026-08-25"));

        assertEquals(WarningEvaluationV1.TRIGGERED, first.status());
        assertEquals("0.050000000000", first.changeRatio());
        assertNotNull(first.warning());
        assertFalse(first.warning().demoRule());
        assertEquals(first.warning().warningId(), second.warning().warningId());
        assertEquals(first.warning().evaluatedAt(), second.warning().evaluatedAt());
        Path warningFile = harness.root().resolveDataRef(
                DataPaths.warningRef(YearMonth.of(2026, 8), first.warning().warningId()));
        assertTrue(Files.isRegularFile(warningFile));
    }

    @Test
    void belowThresholdDoesNotCreateWarning() throws Exception {
        Harness harness = harness(List.of(
                row("2026-08-17", "23000.00", "run-a"),
                row("2026-08-24", "24149.99", "run-b")));
        WarningEvaluationV1 result = harness.service().evaluate(
                rule("0.05", 7, WarningRuleConfigV1.Direction.BOTH, true),
                LocalDate.parse("2026-08-25"));

        assertEquals(WarningEvaluationV1.NOT_TRIGGERED, result.status());
        assertNull(result.warning());
    }

    @Test
    void missingBaselineIsInsufficientAndNeverZeroFilled() throws Exception {
        Harness harness = harness(List.of(row("2026-08-24", "24900.00", "run-a")));
        WarningEvaluationV1 result = harness.service().evaluate(
                rule("0.05", 7, WarningRuleConfigV1.Direction.BOTH, true),
                LocalDate.parse("2026-08-25"));

        assertEquals(WarningEvaluationV1.INSUFFICIENT_DATA, result.status());
        assertNull(result.baselineValue());
        assertNull(result.warning());
    }

    @Test
    void disabledRuleDoesNotReadOrCreateBusinessWarning() {
        DataRoot root = DataRoot.forTest(temp.resolve("disabled"));
        AtomicMoveSupport.probeOrFail(root);
        AtomicFileStore files = new AtomicFileStore(root, new DirtyMarkerCodec());
        Clock clock = Clock.fixed(Instant.parse("2026-08-25T02:00:00Z"), ZoneOffset.UTC);
        FormalWarningEvaluationService service = new FormalWarningEvaluationService(
                new HistoryQueryService(root), new WarningStore(root, files, clock));

        WarningEvaluationV1 result = service.evaluate(
                rule("0.05", 7, WarningRuleConfigV1.Direction.BOTH, false),
                LocalDate.parse("2026-08-25"));
        assertEquals(WarningEvaluationV1.DISABLED, result.status());
        assertNull(result.warning());
    }

    private Harness harness(List<DailyRecordV1> rows) throws Exception {
        DataRoot root = DataRoot.forTest(temp.resolve("formal-" + System.nanoTime()));
        AtomicMoveSupport.probeOrFail(root);
        AtomicFileStore files = new AtomicFileStore(root, new DirtyMarkerCodec());
        Clock clock = Clock.fixed(Instant.parse("2026-08-25T02:00:00Z"), ZoneOffset.UTC);
        byte[] csv = CsvV1Codec.encodeDaily(rows);
        String ref = DataPaths.dailyRef(ITEM, YearMonth.of(2026, 8));
        ManifestV1 manifest = ManifestFactory.csv(
                ref, csv, rows.size(), rows.get(0).businessDate(),
                rows.get(rows.size() - 1).businessDate(),
                rows.stream().map(value -> value.inputRefs().get(0).runId()).toList(), UPDATED);
        files.commit("formal-warning-daily", DirtyTransactionType.SINGLE_FILE, UPDATED,
                List.of(new FileTransactionTarget(
                        DirtyTargetRole.BUSINESS_FILE, ref, csv,
                        JsonV1Codec.encodeFile(manifest), false)));
        FormalWarningEvaluationService service = new FormalWarningEvaluationService(
                new HistoryQueryService(root), new WarningStore(root, files, clock));
        return new Harness(root, service);
    }

    private static WarningRuleConfigV1 rule(
            String threshold,
            int days,
            WarningRuleConfigV1.Direction direction,
            boolean enabled
    ) {
        return new WarningRuleConfigV1(
                "1.0", "default-warning-" + ITEM, WarningRuleConfigV1.VERSION,
                ITEM, "material", days, threshold, direction, enabled,
                WarningRuleConfigV1.Origin.SYSTEM_DEFAULT, "系统通用材料预警模板");
    }

    private static DailyRecordV1 row(String date, String value, String runId) {
        return new DailyRecordV1(
                "1.0", date, ITEM, ProviderType.LOCAL_IMPORT,
                "模拟验收数据（非外部市场信源）", AccessMethod.LOCAL_IMPORT,
                ProcessingStage.PUBLISHED, ValidationStatus.VERIFIED,
                "material-basic-validation-v2", List.of(2), "arithmetic-mean-v1",
                2, 2, RoundingMode.HALF_UP, "weekday-asia-shanghai-v1",
                value, 1, value, 1, 0, true, "CNY", "元/吨",
                List.of(new DailyInputRefV1(
                        runId, "raw/formal/local_import/" + ITEM + "/2026/08/" + runId + ".json", 4)),
                UPDATED, "AZ91D");
    }

    private record Harness(DataRoot root, FormalWarningEvaluationService service) {
    }
}
