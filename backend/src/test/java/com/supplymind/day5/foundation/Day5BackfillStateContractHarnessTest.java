package com.supplymind.day5.foundation;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test-only D5-T04 state-machine harness.  It supplies a stable acceptance attack surface but
 * does not invoke a non-existent backfill orchestrator or claim H08/AT-CFG-003 PASS.
 */
class Day5BackfillStateContractHarnessTest {

    @Test
    void stateVocabularyAndManualWaitingSemanticsAreFixed() {
        assertEquals(EnumSet.of(JobState.WAITING, JobState.AWAITING_MANUAL_INPUT, JobState.RUNNING,
                        JobState.PARTIAL_SUCCESS, JobState.SUCCEEDED, JobState.FAILED),
                EnumSet.allOf(JobState.class));

        ReferenceBackfillCoordinator coordinator = new ReferenceBackfillCoordinator();
        BackfillJob manualCurrent = coordinator.start("manual-current", Capability.manualOnly());
        BackfillJob manualHistory = coordinator.start("manual-history", Capability.manualOnly());
        BackfillJob unsupportedHistory = coordinator.start("no-history", Capability.currentOnly());

        assertEquals(JobState.AWAITING_MANUAL_INPUT, manualCurrent.state());
        assertEquals(JobState.AWAITING_MANUAL_INPUT, manualHistory.state());
        assertEquals(JobState.AWAITING_MANUAL_INPUT, unsupportedHistory.state());
        assertFalse(manualCurrent.state() == JobState.SUCCEEDED, "waiting for Manual input is never a success result");
    }

    @Test
    void automaticCurrentHistoryDuplicateStartPartialFailureAndRestartUseTheSameCheckpoint() {
        ReferenceBackfillCoordinator coordinator = new ReferenceBackfillCoordinator();
        BackfillJob first = coordinator.start("gbp-current-and-history", Capability.automaticCurrentAndHistory());
        BackfillJob duplicate = coordinator.start("gbp-current-and-history", Capability.automaticCurrentAndHistory());

        assertSame(first, duplicate, "a duplicate start must join the existing job rather than start a second publication");
        assertEquals(JobState.RUNNING, first.state());
        coordinator.checkpoint("gbp-current-and-history", "2025-12");
        coordinator.partialFailure("gbp-current-and-history");
        assertEquals(JobState.PARTIAL_SUCCESS, first.state());

        ReferenceBackfillCoordinator restarted = coordinator.restart();
        BackfillJob resumed = restarted.job("gbp-current-and-history");
        assertEquals("2025-12", resumed.checkpoint());
        assertEquals(JobState.PARTIAL_SUCCESS, resumed.state());
        restarted.resume("gbp-current-and-history");
        assertEquals(JobState.RUNNING, resumed.state());
        assertTrue(resumed.automaticCurrentRequested());
        assertTrue(resumed.automaticHistoryRequested());
    }

    private enum JobState { WAITING, AWAITING_MANUAL_INPUT, RUNNING, PARTIAL_SUCCESS, SUCCEEDED, FAILED }

    private record Capability(boolean automaticCurrent, boolean automaticHistory, boolean manualRouteAvailable) {
        private static Capability automaticCurrentAndHistory() {
            return new Capability(true, true, false);
        }

        private static Capability currentOnly() {
            return new Capability(true, false, true);
        }

        private static Capability manualOnly() {
            return new Capability(false, false, true);
        }
    }

    private static final class BackfillJob {
        private final boolean automaticCurrentRequested;
        private final boolean automaticHistoryRequested;
        private JobState state;
        private String checkpoint;

        private BackfillJob(Capability capability) {
            automaticCurrentRequested = capability.automaticCurrent();
            automaticHistoryRequested = capability.automaticHistory();
            state = capability.automaticCurrent() && capability.automaticHistory()
                    ? JobState.RUNNING : JobState.AWAITING_MANUAL_INPUT;
        }

        private BackfillJob copy() {
            BackfillJob copy = new BackfillJob(new Capability(automaticCurrentRequested, automaticHistoryRequested, true));
            copy.state = state;
            copy.checkpoint = checkpoint;
            return copy;
        }

        private JobState state() { return state; }
        private String checkpoint() { return checkpoint; }
        private boolean automaticCurrentRequested() { return automaticCurrentRequested; }
        private boolean automaticHistoryRequested() { return automaticHistoryRequested; }
    }

    private static final class ReferenceBackfillCoordinator {
        private final Map<String, BackfillJob> jobs = new LinkedHashMap<>();

        private BackfillJob start(String requestKey, Capability capability) {
            return jobs.computeIfAbsent(requestKey, ignored -> new BackfillJob(capability));
        }

        private void checkpoint(String requestKey, String checkpoint) {
            jobs.get(requestKey).checkpoint = checkpoint;
        }

        private void partialFailure(String requestKey) {
            jobs.get(requestKey).state = JobState.PARTIAL_SUCCESS;
        }

        private void resume(String requestKey) {
            jobs.get(requestKey).state = JobState.RUNNING;
        }

        private BackfillJob job(String requestKey) {
            return jobs.get(requestKey);
        }

        private ReferenceBackfillCoordinator restart() {
            ReferenceBackfillCoordinator restarted = new ReferenceBackfillCoordinator();
            jobs.forEach((key, job) -> restarted.jobs.put(key, job.copy()));
            return restarted;
        }
    }
}
