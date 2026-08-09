package com.supplymind.provider.pboc;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

/** JDK HTTPS transport with platform certificate validation and no cookie/token state. */
final class JdkPbocHttpTransport implements PbocHttpTransport {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private final HttpClient client;

    JdkPbocHttpTransport() {
        this(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build());
    }

    JdkPbocHttpTransport(HttpClient client) { this.client = Objects.requireNonNull(client, "client"); }

    @Override
    public PbocHttpResponse get(URI uri) {
        Objects.requireNonNull(uri, "uri");
        HttpRequest request = HttpRequest.newBuilder(uri).GET().timeout(REQUEST_TIMEOUT)
                .header("Accept", "text/html,application/xhtml+xml").build();
        try {
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            return new PbocHttpResponse(response.uri(), response.statusCode(), contentType, response.body());
        } catch (IOException exception) {
            throw new PbocCollectionException(PbocCollectionFailureKind.EXTERNAL_ACCESS_BLOCKED, "HTTP", uri, null,
                    "PBOC public HTTPS request failed before a usable response was received", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PbocCollectionException(PbocCollectionFailureKind.EXTERNAL_ACCESS_BLOCKED, "HTTP", uri, null,
                    "PBOC public HTTPS request was interrupted before a usable response was received", exception);
        }
    }
}