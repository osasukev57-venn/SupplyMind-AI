package com.supplymind.provider.shfe;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

/** Java 17 HTTPS client using the platform trust store; no cookies, credentials or TLS bypass. */
final class JdkShfeHttpTransport implements ShfeHttpTransport {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private final HttpClient client;

    JdkShfeHttpTransport() {
        this(HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    JdkShfeHttpTransport(HttpClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public ShfeHttpResponse get(URI uri) {
        Objects.requireNonNull(uri, "uri");
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !"www.shfe.com.cn".equalsIgnoreCase(uri.getHost())) {
            throw new IllegalArgumentException("SHFE transport only permits https://www.shfe.com.cn");
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .build();
        try {
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            return new ShfeHttpResponse(response.uri(), response.statusCode(),
                    response.headers().firstValue("Content-Type").orElse(""), response.body());
        } catch (IOException exception) {
            throw new IllegalStateException("SHFE public HTTPS request failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("SHFE public HTTPS request was interrupted", exception);
        }
    }
}
