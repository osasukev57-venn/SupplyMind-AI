package com.supplymind.provider.pboc;

/** Sanitized collection event: no response body, headers, credentials, query string, or token is exposed. */
public record PbocDiagnosticEvent(String outcome, String stage, String sanitizedUrl, Integer httpStatus, String exceptionType) {
    public PbocDiagnosticEvent {
        outcome = required(outcome, "outcome");
        stage = required(stage, "stage");
        sanitizedUrl = required(sanitizedUrl, "sanitizedUrl");
        exceptionType = required(exceptionType, "exceptionType");
    }
    private static String required(String value, String field) {
        if (value == null || value.isBlank()) { throw new IllegalArgumentException(field + " is required"); }
        return value;
    }
}