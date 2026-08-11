package com.supplymind.routing;

/** Fail-closed outcome for unresolvable material routes (unknown provider / no legal candidate). */
public final class ProviderRouteException extends RuntimeException {

    public ProviderRouteException(String message) {
        super(message);
    }
}
