package com.supplymind.manual;

import java.util.Objects;

/**
 * D3-T04 server-side operator identity source (authentication context). operatorRef MUST come
 * from this context and MUST be persisted by the server; it is never accepted from the
 * client. The default implementation reads a server-side configured operator reference and
 * fails closed when none is configured.
 */
public interface OperatorContext {

    String currentOperatorRef();

    static OperatorContext configured(String propertyValue) {
        return () -> {
            if (propertyValue == null || propertyValue.isBlank()) {
                throw new IllegalStateException(
                        "supplymind.manual.operator-ref is not configured; manual intake is disabled");
            }
            return Objects.requireNonNull(propertyValue).trim();
        };
    }
}
