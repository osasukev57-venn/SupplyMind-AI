package com.supplymind.foundation.storage;

/** A CREATE_NEW target exists with bytes different from the incoming immutable document. */
public final class ImmutableFileConflictException extends StorageException {

    private final String dataRef;
    private final String existingFileSha256;
    private final String incomingFileSha256;

    public ImmutableFileConflictException(String dataRef, String existingFileSha256, String incomingFileSha256) {
        super("Immutable target conflict for " + dataRef + ": existing=" + existingFileSha256
                + ", incoming=" + incomingFileSha256);
        this.dataRef = dataRef;
        this.existingFileSha256 = existingFileSha256;
        this.incomingFileSha256 = incomingFileSha256;
    }

    public String dataRef() {
        return dataRef;
    }

    public String existingFileSha256() {
        return existingFileSha256;
    }

    public String incomingFileSha256() {
        return incomingFileSha256;
    }
}
