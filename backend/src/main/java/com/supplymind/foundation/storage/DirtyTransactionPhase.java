package com.supplymind.foundation.storage;

/** Transaction-level state; it is deliberately separate from lifecycle state. */
public enum DirtyTransactionPhase {
    OPEN,
    COMMITTED
}
