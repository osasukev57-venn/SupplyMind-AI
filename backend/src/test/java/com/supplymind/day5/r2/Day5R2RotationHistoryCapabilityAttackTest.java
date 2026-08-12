package com.supplymind.day5.r2;

import com.supplymind.config.ConfigManagementService;
import com.supplymind.foundation.codec.CsvV1Codec;
import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.DailyInputRefV1;
import com.supplymind.foundation.model.DailyRecordV1;
import com.supplymind.foundation.model.ManifestV1;
import com.supplymind.foundation.model.Mode;
import com.supplymind.foundation.model.MonitorSeriesConfigV1;
import com.supplymind.foundation.model.MonitorSeriesItemV1;
import com.supplymind.foundation.model.ProcessingStage;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.RouteDecision;
import com.supplymind.foundation.model.ValidationStatus;
import com.supplymind.foundation.model.TimeStateV1;
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
import com.supplymind.foundation.storage.StorageException;
import com.supplymind.foundation.storage.TimeStateStore;
import com.supplymind.history.HistoryQueryService;
import com.supplymind.provider.DataProvider;
import com.supplymind.provider.DataProviderRegistry;
import com.supplymind.provider.ProviderCollectOutcome;
import com.supplymind.provider.ProviderCollectRequest;
import com.supplymind.provider.ProviderSourceProfile;
import com.supplymind.rotation.TimeRotationService;
import com.supplymind.scheduling.RotationGuardedCollectionService;
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
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Post-fix R2 attacks for findings A1-A3.  These tests call the real production services and use
 * independent file fixtures; they do not reuse the original R2 test's expected-result helpers.
 */
class Day5R2RotationHistoryCapabilityAttackTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-10T02:00:00Z"), ZoneOffset.UTC);
    private static final OffsetDateTime FIXED_AT = OffsetDateTime.parse("2026-08-10T10:00:00+08:00");

    @TempDir
    Path temporaryDirectory;

    @Test
    void a1RollbackHighWaterAttackPersistsMonotonicStateAndSuppressesRollbackTrigger() {
        DataRoot root = root("a1 rotation");
        AtomicFileStore files = fileStore(root);
        TimeStateStore stateStore = new TimeStateStore(root, files, CLOCK);
        TimeRotationService rotation = new TimeRotationService(stateStore);
        AtomicInteger realGuardedCycles = new AtomicInteger();
        RotationGuardedCollectionService guarded = new RotationGuardedCollectionService(rotation,
                realGuardedCycles::incrementAndGet);

        assertTrue(guarded.runIfNotRollback(at("2026-08-31T23:59:50+08:00")));
        assertTrue(guarded.runIfNotRollback(at("2026-09-01T00:00:10+08:00")));
        TimeStateV1 afterFirstSeptember = stateStore.read();
        assertEquals("2026-09", afterFirstSeptember.lastCompletedPeriod());
        assertEquals(LocalDate.parse("2026-09-01"), afterFirstSeptember.effectiveBusinessDate());

        assertFalse(guarded.runIfNotRollback(at("2026-08-31T12:00:00+08:00")),
                "rollback must suppress the actual guarded production trigger");
        TimeStateV1 afterRollback = stateStore.read();
        assertEquals(afterFirstSeptember.effectiveHighWaterTime(), afterRollback.effectiveHighWaterTime());
        assertEquals("2026-09", afterRollback.lastCompletedPeriod());
        assertEquals(2, realGuardedCycles.get(), "rollback must not invoke collection/publish/daily/aggregate work");

        TimeRotationService restarted = new TimeRotationService(
                new TimeStateStore(root, new AtomicFileStore(root, new DirtyMarkerCodec()), CLOCK));
        RotationGuardedCollectionService recoveredGuard = new RotationGuardedCollectionService(restarted,
                realGuardedCycles::incrementAndGet);
        assertTrue(recoveredGuard.runIfNotRollback(at("2026-09-01T10:00:00+08:00")));
        assertTrue(recoveredGuard.runIfNotRollback(at("2026-09-02T10:00:00+08:00")));

        TimeStateV1 finalState = stateStore.read();
        assertEquals("2026-09", finalState.lastCompletedPeriod());
        assertEquals(LocalDate.parse("2026-09-02"), finalState.effectiveBusinessDate());
        assertEquals(4, realGuardedCycles.get(), "only non-rollback observed cycles may run");
        assertEquals(1, Files.exists(root.resolveDataRef(DataPaths.timeStateRef())) ? 1 : 0);
    }

    @Test
    void a2HistoryConflictAttackExcludesConflictingBusinessKeyIndependentlyOfPartitionOrder() throws Exception {
        HistoryQueryService.DailyHistoryResult forward = conflictingHistory("a2 A then B", "100.00000000", "200.00000000");
        HistoryQueryService.DailyHistoryResult reverse = conflictingHistory("a2 B then A", "200.00000000", "100.00000000");

        assertEquals(forward.conflictKeys(), reverse.conflictKeys());
        assertEquals(1, forward.conflictKeys().size());
        assertTrue(forward.rows().isEmpty(), "neither A nor B may survive a semantic conflict");
        assertTrue(reverse.rows().isEmpty(), "read order must never become first-wins or last-wins");

        DataRoot duplicateRoot = root("a2 identical duplicate");
        AtomicFileStore files = fileStore(duplicateRoot);
        DailyRecordV1 identical = daily("2025-12-31", "100.00000000", "run-identical");
        writeDaily(files, duplicateRoot, YearMonth.of(2025, 12), List.of(identical));
        writeDaily(files, duplicateRoot, YearMonth.of(2026, 1), List.of(identical));
        HistoryQueryService.DailyHistoryResult identicalResult = new HistoryQueryService(duplicateRoot).queryDaily(
                "FX.R2.HISTORY", LocalDate.parse("2025-12-20"), LocalDate.parse("2026-01-10"));
        assertEquals(1, identicalResult.rows().size());
        assertTrue(identicalResult.conflictKeys().isEmpty(), "byte/semantic-identical duplicates remain deterministic dedupe");
    }

    @Test
    void a3ProviderCapabilityAttackRejectsTypeOnlyProviderAndAcceptsGenericNewTarget() {
        DataRoot root = root("a3 capability");
        AtomicFileStore files = fileStore(root);
        ConfigActivationStore configs = new ConfigActivationStore(root, files, CLOCK);
        configs.activate(new MonitorSeriesConfigV1("1.0", 1, Mode.TEST, FIXED_AT, List.of(syntheticAnchor())));

        DataProviderRegistry typeOnlyRegistry = new DataProviderRegistry();
        typeOnlyRegistry.register(provider("type-only", item -> false));
        ConfigManagementService rejected = new ConfigManagementService(configs, typeOnlyRegistry);
        MonitorSeriesItemV1 target = genericTarget("FX.R2.NEW.LEGAL.TARGET");
        assertThrows(StorageException.class, () -> rejected.addItem(target));
        assertEquals(1, rejected.active().configVersion(), "failed activation must not mutate active configVersion");

        DataProviderRegistry genericRegistry = new DataProviderRegistry();
        genericRegistry.register(provider("generic-rate-provider", item -> item.providerType() == ProviderType.OFFICIAL_WEB
                && "r2-unseen-rate-kind".equals(item.rateKind())));
        ConfigManagementService accepted = new ConfigManagementService(configs, genericRegistry);
        MonitorSeriesConfigV1 active = accepted.addItem(target);
        assertEquals(2, active.configVersion());
        assertTrue(active.requireItem(target.itemId()).enabled());
    }

    private HistoryQueryService.DailyHistoryResult conflictingHistory(String suffix, String decemberValue,
                                                                       String januaryValue) throws Exception {
        DataRoot root = root(suffix);
        AtomicFileStore files = fileStore(root);
        writeDaily(files, root, YearMonth.of(2025, 12), List.of(daily("2025-12-31", decemberValue, "run-a")));
        writeDaily(files, root, YearMonth.of(2026, 1), List.of(daily("2025-12-31", januaryValue, "run-b")));
        return new HistoryQueryService(root).queryDaily(
                "FX.R2.HISTORY", LocalDate.parse("2025-12-20"), LocalDate.parse("2026-01-10"));
    }

    private void writeDaily(AtomicFileStore files, DataRoot root, YearMonth partition, List<DailyRecordV1> rows)
            throws Exception {
        String ref = DataPaths.dailyRef("FX.R2.HISTORY", partition);
        byte[] data = CsvV1Codec.encodeDaily(rows);
        List<String> sourceRunIds = rows.stream().flatMap(row -> row.inputRefs().stream())
                .map(DailyInputRefV1::runId).distinct().sorted().toList();
        ManifestV1 manifest = ManifestFactory.csv(ref, data, rows.size(), "2025-12-31", "2025-12-31",
                sourceRunIds, FIXED_AT);
        files.commit("a2-" + partition, DirtyTransactionType.SINGLE_FILE, FIXED_AT,
                List.of(new FileTransactionTarget(DirtyTargetRole.BUSINESS_FILE, ref, data,
                        JsonV1Codec.encodeFile(manifest), false)));
    }

    private static DailyRecordV1 daily(String businessDate, String value, String runId) {
        return new DailyRecordV1("1.0", businessDate, "FX.R2.HISTORY", ProviderType.OFFICIAL_WEB,
                "R2 official fixture source", AccessMethod.PUBLIC_OFFICIAL_HTML, ProcessingStage.PUBLISHED,
                ValidationStatus.VERIFIED, "pboc-basic-validation-v1", List.of(1), "arithmetic-mean-v1",
                8, 4, RoundingMode.HALF_UP, "weekday-asia-shanghai-v1", value, 1, value, 1, 0, true,
                "CNY", "CNY/1 USD", List.of(new DailyInputRefV1(runId,
                "raw/formal/official_web/FX.R2.HISTORY/2025/12/" + runId + ".json", 4)), FIXED_AT, null);
    }

    private static MonitorSeriesItemV1 syntheticAnchor() {
        return new MonitorSeriesItemV1("FIXTURE.ANCHOR", "fixture anchor", true, "test", ProviderType.SYNTHETIC_DEMO,
                AccessMethod.SYNTHETIC_DEMO, "test only", RouteDecision.SYNTHETIC_DEMO, null, FIXED_AT, null,
                "anchor", "anchor", "fixture", "arithmetic-mean-v1", 2, 2, RoundingMode.HALF_UP,
                "weekday-asia-shanghai-v1", "CNY", "CNY", "fixture", null);
    }

    private static MonitorSeriesItemV1 genericTarget(String itemId) {
        return new MonitorSeriesItemV1(itemId, "unseen generic target", true, "r2-source", ProviderType.OFFICIAL_WEB,
                AccessMethod.PUBLIC_OFFICIAL_HTML, "R2 generic source", RouteDecision.PRIMARY, null, FIXED_AT, null,
                "unseen", "unseen-field", "r2-unseen-rate-kind", "arithmetic-mean-v1", 8, 4,
                RoundingMode.HALF_UP, "weekday-asia-shanghai-v1", "CNY", "USD", "CNY/1 USD", null);
    }

    private static DataProvider provider(String id, java.util.function.Predicate<MonitorSeriesItemV1> capability) {
        return new DataProvider() {
            @Override public ProviderSourceProfile profile() {
                return ProviderSourceProfile.of(id, ProviderType.OFFICIAL_WEB, AccessMethod.PUBLIC_OFFICIAL_HTML,
                        "R2 generic source", "https://example.test/r2", true, true);
            }
            @Override public Set<String> supportedItemIds() { return Set.of(); }
            @Override public boolean supports(MonitorSeriesItemV1 item) { return capability.test(item); }
            @Override public ProviderCollectOutcome collect(ProviderCollectRequest request) {
                return ProviderCollectOutcome.rejectedOnly(id, java.util.Map.of());
            }
        };
    }

    private DataRoot root(String leaf) {
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve(leaf));
        AtomicMoveSupport.probeOrFail(root);
        return root;
    }

    private static AtomicFileStore fileStore(DataRoot root) {
        return new AtomicFileStore(root, new DirtyMarkerCodec());
    }

    private static OffsetDateTime at(String value) {
        return OffsetDateTime.parse(value);
    }
}
