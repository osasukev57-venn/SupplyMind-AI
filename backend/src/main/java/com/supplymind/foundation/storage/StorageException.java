package com.supplymind.foundation.storage;

/**
 * Raised when the local file store cannot prove a safe, single-root operation.
 *
 * <p>The foundation deliberately fails closed: callers must never substitute a
 * second data directory or silently downgrade an atomic operation.</p>
 */
public class StorageException extends RuntimeException {

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
