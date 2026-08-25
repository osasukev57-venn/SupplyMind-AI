package com.supplymind.warning;

import com.supplymind.config.ConfigManagementService;
import com.supplymind.foundation.model.MonitorSeriesDefaults;
import com.supplymind.foundation.storage.AtomicFileStore;
import com.supplymind.foundation.storage.AtomicMoveSupport;
import com.supplymind.foundation.storage.ConfigActivationStore;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.DirtyMarkerCodec;
import com.supplymind.history.HistoryQueryService;
import com.supplymind.provider.DataProviderRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WarningRuleManagementServiceTest {

    @TempDir
    Path temp;

    @Test
    void everyEnabledSeriesGetsTypeDefaultAndOneOverrideIsVersionedAcrossRestart() {
        Harness first = harness();

        WarningRuleManagementService.BoundRule fx = first.management().listRules().stream()
                .filter(rule -> rule.itemId().equals(MonitorSeriesDefaults.USD_CNY_ITEM_ID))
                .findFirst().orElseThrow();
        WarningRuleManagementService.BoundRule material = first.management().listRules().stream()
                .filter(rule -> rule.itemId().equals("MAT.ADC12.SMM"))
                .findFirst().orElseThrow();

        assertEquals(1, fx.rule().lookbackDays());
        assertEquals("0.01", fx.rule().threshold());
        assertEquals(WarningRuleConfigV1.Origin.SYSTEM_DEFAULT, fx.rule().origin());
        assertEquals(7, material.rule().lookbackDays());
        assertEquals("0.05", material.rule().threshold());

        WarningRuleManagementService.BoundRule custom = first.management().update(
                "MAT.ADC12.SMM", 14, "0.03", WarningRuleConfigV1.Direction.UP,
                true, "14 日上涨达到 3%");
        assertEquals(WarningRuleConfigV1.Origin.USER_CUSTOM, custom.rule().origin());
        assertEquals("0.03", custom.rule().threshold());
        assertTrue(Files.isRegularFile(first.root().resolveDataRef(DataPaths.warningRulesActiveRef())));
        assertTrue(Files.isRegularFile(first.root().resolveDataRef(DataPaths.warningRulesHistoryRef(1))));

        Harness restarted = harness(first.root());
        WarningRuleManagementService.BoundRule reloaded = restarted.management().listRules().stream()
                .filter(rule -> rule.itemId().equals("MAT.ADC12.SMM"))
                .findFirst().orElseThrow();
        assertEquals("0.03", reloaded.rule().threshold());
        assertEquals(14, reloaded.rule().lookbackDays());

        WarningRuleManagementService.BoundRule restored = restarted.management().reset("MAT.ADC12.SMM");
        assertEquals(WarningRuleConfigV1.Origin.SYSTEM_DEFAULT, restored.rule().origin());
        assertEquals("0.05", restored.rule().threshold());
        assertTrue(Files.isRegularFile(first.root().resolveDataRef(DataPaths.warningRulesHistoryRef(2))));
    }

    private Harness harness() {
        DataRoot root = DataRoot.forTest(temp.resolve("rules"));
        AtomicMoveSupport.probeOrFail(root);
        return harness(root);
    }

    private Harness harness(DataRoot root) {
        AtomicFileStore files = new AtomicFileStore(root, new DirtyMarkerCodec());
        Clock clock = Clock.fixed(Instant.parse("2026-08-25T02:00:00Z"), ZoneOffset.UTC);
        ConfigActivationStore activation = new ConfigActivationStore(root, files, clock);
        activation.ensureInitialDefault();
        ConfigManagementService configs = new ConfigManagementService(
                activation, new DataProviderRegistry());
        WarningRuleStore store = new WarningRuleStore(root, files, clock);
        FormalWarningEvaluationService evaluator = new FormalWarningEvaluationService(
                new HistoryQueryService(root), new WarningStore(root, files, clock));
        return new Harness(root, new WarningRuleManagementService(configs, store, evaluator, clock));
    }

    private record Harness(DataRoot root, WarningRuleManagementService management) {
    }
}
