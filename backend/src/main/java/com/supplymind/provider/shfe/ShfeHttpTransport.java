package com.supplymind.provider.shfe;

import java.net.URI;

/** Injectable HTTPS boundary for the public SHFE data files. */
public interface ShfeHttpTransport {
    ShfeHttpResponse get(URI uri);
}
