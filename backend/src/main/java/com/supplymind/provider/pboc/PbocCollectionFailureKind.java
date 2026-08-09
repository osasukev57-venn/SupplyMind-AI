package com.supplymind.provider.pboc;

/** Explicit result classes for the narrow PBOC OfficialWeb collection boundary. */
public enum PbocCollectionFailureKind {
    EXTERNAL_ACCESS_BLOCKED,
    HTTP_REJECTED,
    CONTENT_TYPE_REJECTED,
    PARSE_REJECTED,
    CONFIG_REJECTED,
    PERSISTENCE_FAILED
}