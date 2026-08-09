package com.supplymind.foundation.storage;

/** Logical role in a DirtyMarkerV1 transaction. */
public enum DirtyTargetRole {
    BUSINESS_FILE,
    CONFIG_HISTORY,
    CONFIG_ACTIVE
}
