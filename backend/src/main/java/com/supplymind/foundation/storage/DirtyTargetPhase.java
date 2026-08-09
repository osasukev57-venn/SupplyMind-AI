package com.supplymind.foundation.storage;

/** Per-file persistence state, advancing only in this order. */
public enum DirtyTargetPhase {
    PREPARED,
    DATA_COMMITTED,
    MANIFEST_COMMITTED;

    public boolean canAdvanceTo(DirtyTargetPhase next) {
        return next != null && next.ordinal() == ordinal() + 1;
    }
}
