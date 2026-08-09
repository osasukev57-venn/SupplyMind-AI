package com.supplymind.provider.pboc;

import java.net.URI;

/** Failure with only safe, response-metadata-level diagnostic fields. */
public final class PbocCollectionException extends RuntimeException {
    private final PbocCollectionFailureKind failureKind;
    private final String stage;
    private final URI uri;
    private final Integer httpStatus;

    public PbocCollectionException(PbocCollectionFailureKind failureKind, String stage, URI uri, Integer httpStatus, String message) {
        this(failureKind, stage, uri, httpStatus, message, null);
    }

    public PbocCollectionException(PbocCollectionFailureKind failureKind, String stage, URI uri, Integer httpStatus, String message, Throwable cause) {
        super(message, cause);
        this.failureKind = java.util.Objects.requireNonNull(failureKind, "failureKind");
        this.stage = java.util.Objects.requireNonNull(stage, "stage");
        this.uri = uri;
        this.httpStatus = httpStatus;
    }

    public PbocCollectionFailureKind failureKind() { return failureKind; }
    public String stage() { return stage; }
    public URI uri() { return uri; }
    public Integer httpStatus() { return httpStatus; }
}