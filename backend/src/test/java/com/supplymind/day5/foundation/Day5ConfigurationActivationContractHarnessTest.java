package com.supplymind.day5.foundation;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test-only AT-CFG harness.  The reference snapshots make D5-T03's active/history atomic
 * expectations executable while production dynamic configuration is still PENDING_IMPLEMENTATION.
 */
class Day5ConfigurationActivationContractHarnessTest {

    @Test
    void addEnableDisableAndReplaceAdvanceExactlyOneVersionAndRetainOldHistory() {
        ReferenceConfigurationStore store = new ReferenceConfigurationStore(snapshot(1,
                item("FX.USD.CNY.PBOC_MID", true, null), item("FX.EUR.CNY.PBOC_MID", true, null),
                item("MAT.AZ91D.SMM", true, null)));

        store.activate(snapshot(2, item("FX.USD.CNY.PBOC_MID", true, null),
                item("FX.EUR.CNY.PBOC_MID", false, null), item("MAT.AZ91D.SMM", true, null),
                item("FX.GBP.CNY.CFETS", true, null)));
        store.activate(snapshot(3, item("FX.USD.CNY.PBOC_MID", true, null),
                item("FX.EUR.CNY.PBOC_MID", false, null),
                item("MAT.REPL.01.SMM", true, "MAT.AZ91D.SMM"), item("FX.GBP.CNY.CFETS", true, null)));

        assertEquals(3, store.active().configVersion());
        assertEquals(List.of(1, 2, 3), store.history().keySet().stream().sorted().toList());
        assertTrue(store.history().get(1).items().stream().anyMatch(item -> item.itemId().equals("MAT.AZ91D.SMM")),
                "replace creates a new itemId and never rewrites historical semantics");
        Item replacement = store.active().items().stream()
                .filter(item -> item.itemId().equals("MAT.REPL.01.SMM")).findFirst().orElseThrow();
        assertEquals("MAT.AZ91D.SMM", replacement.supersedesItemId());
        assertFalse(store.active().items().stream().filter(item -> item.itemId().equals("FX.EUR.CNY.PBOC_MID"))
                .findFirst().orElseThrow().enabled());
    }

    @Test
    void restartPreservesTheActiveSnapshotAndRejectsNonSequentialVersionChanges() {
        ReferenceConfigurationStore store = new ReferenceConfigurationStore(snapshot(1, item("FX.USD.CNY.PBOC_MID", true, null)));
        store.activate(snapshot(2, item("FX.USD.CNY.PBOC_MID", false, null)));

        assertEquals(store.active(), store.restart().active());
        assertThrows(IllegalArgumentException.class,
                () -> store.activate(snapshot(4, item("FX.USD.CNY.PBOC_MID", true, null))));
    }

    @Test
    void allFourAtomicActivationFailureWindowsLeaveThePreviousActiveVersionReadable() {
        for (ActivationFailurePoint failurePoint : ActivationFailurePoint.values()) {
            ReferenceConfigurationStore store = new ReferenceConfigurationStore(snapshot(1,
                    item("FX.USD.CNY.PBOC_MID", true, null)));
            ConfigurationSnapshot candidate = snapshot(2, item("FX.USD.CNY.PBOC_MID", false, null));

            ActivationResult result = store.simulateFailedActivation(candidate, failurePoint);

            assertEquals(1, result.activeAfterFailure().configVersion(), failurePoint.name());
            assertEquals(1, result.preservedHistory().get(1).configVersion(), failurePoint.name());
            assertEquals(List.of("history", "history.manifest", "active", "active.manifest"), result.physicalTargets(),
                    "the D5 production test must inject each of the four CONFIG_ACTIVATION physical-file windows");
        }
    }

    private static ConfigurationSnapshot snapshot(int version, Item... items) {
        return new ConfigurationSnapshot(version, List.of(items));
    }

    private static Item item(String itemId, boolean enabled, String supersedesItemId) {
        return new Item(itemId, enabled, supersedesItemId);
    }

    private enum ActivationFailurePoint { HISTORY, HISTORY_MANIFEST, ACTIVE, ACTIVE_MANIFEST }

    private record Item(String itemId, boolean enabled, String supersedesItemId) {
    }

    private record ConfigurationSnapshot(int configVersion, List<Item> items) {
    }

    private record ActivationResult(ConfigurationSnapshot activeAfterFailure,
                                    Map<Integer, ConfigurationSnapshot> preservedHistory,
                                    List<String> physicalTargets) {
    }

    private static final class ReferenceConfigurationStore {
        private ConfigurationSnapshot active;
        private final Map<Integer, ConfigurationSnapshot> history = new LinkedHashMap<>();

        private ReferenceConfigurationStore(ConfigurationSnapshot initial) {
            active = initial;
            history.put(initial.configVersion(), initial);
        }

        private void activate(ConfigurationSnapshot candidate) {
            if (candidate.configVersion() != active.configVersion() + 1) {
                throw new IllegalArgumentException("configVersion must increase exactly by one");
            }
            history.put(candidate.configVersion(), candidate);
            active = candidate;
        }

        private ActivationResult simulateFailedActivation(ConfigurationSnapshot candidate, ActivationFailurePoint failurePoint) {
            if (candidate.configVersion() != active.configVersion() + 1) {
                throw new IllegalArgumentException("configVersion must increase exactly by one");
            }
            // Reference expectation only: production recovery must complete deterministically or retain this prior active snapshot.
            return new ActivationResult(active, Map.copyOf(history),
                    List.of("history", "history.manifest", "active", "active.manifest"));
        }

        private ReferenceConfigurationStore restart() {
            ReferenceConfigurationStore restarted = new ReferenceConfigurationStore(active);
            restarted.history.clear();
            restarted.history.putAll(history);
            return restarted;
        }

        private ConfigurationSnapshot active() {
            return active;
        }

        private Map<Integer, ConfigurationSnapshot> history() {
            return Map.copyOf(history);
        }
    }
}
