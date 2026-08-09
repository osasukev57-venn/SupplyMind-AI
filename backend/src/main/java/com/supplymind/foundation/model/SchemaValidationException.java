package com.supplymind.foundation.model;

/** Raised when an object cannot be represented by the frozen v1 file contract. */
public final class SchemaValidationException extends IllegalArgumentException {
    public SchemaValidationException(String message) {
        super(message);
    }
}
