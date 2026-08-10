package com.supplymind.provider;

/** Fail-closed registry outcome for duplicate provider identities or unknown provider lookups. */
public final class ProviderRegistryException extends RuntimeException {

    public ProviderRegistryException(String message) {
        super(message);
    }
}
