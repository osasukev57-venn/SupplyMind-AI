package com.supplymind.foundation.storage;

/** Raised only after immutable RawConflictEvidenceV1 and its adjacent manifest are committed. */
public final class RawReceiptConflictException extends StorageException {

    private final String conflictRef;

    public RawReceiptConflictException(String conflictRef, Throwable cause) {
        super("Incoming RawReceiptV1 conflicts with immutable raw; evidence committed at " + conflictRef, cause);
        this.conflictRef = conflictRef;
    }

    public String conflictRef() {
        return conflictRef;
    }
}
