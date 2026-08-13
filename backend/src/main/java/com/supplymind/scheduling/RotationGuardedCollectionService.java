package com.supplymind.scheduling;

import com.supplymind.rotation.TimeRotationService;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * D5-T01/F1 production integration: the scheduled collection/processing trigger consults the
 * rotation guard before running a cycle. On a detected rollback the guarded cycle is skipped
 * entirely (rollback must never re-trigger publish/daily/aggregate work); the rotation
 * high-water mark already guarantees the boundary was consumed exactly once. The guard only
 * observes - it never fabricates data and never creates business rows for future periods.
 */
public final class RotationGuardedCollectionService {

    private final TimeRotationService rotation;
    private final Runnable cycle;

    public RotationGuardedCollectionService(TimeRotationService rotation, Runnable cycle) {
        this.rotation = Objects.requireNonNull(rotation, "rotation");
        this.cycle = Objects.requireNonNull(cycle, "cycle");
    }

    /** Returns true when the cycle actually ran; false when rollback suppressed it. */
    public boolean runIfNotRollback(OffsetDateTime now) {
        TimeRotationService.RotationCheckResult check = rotation.check(now);
        if (check.rollbackDetected()) {
            return false;
        }
        cycle.run();
        return true;
    }
}
