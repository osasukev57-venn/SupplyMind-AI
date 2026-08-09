package com.supplymind.provider.pboc;

import java.net.URI;

/** Narrow injectable HTTP seam for the PBOC-only adapter; it is not a Provider registry. */
@FunctionalInterface
public interface PbocHttpTransport {
    PbocHttpResponse get(URI uri);
}