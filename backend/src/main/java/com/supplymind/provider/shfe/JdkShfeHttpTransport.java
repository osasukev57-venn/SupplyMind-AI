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
        CapturedBody capture = new CapturedBody();
        try {
            HttpResponse<byte[]> response = client.send(request, info -> { capture.info = info; return capture; });
            return new ShfeHttpResponse(response.uri(), response.statusCode(),
                    response.headers().firstValue("Content-Type").orElse(null), response.body());
        } catch (IOException exception) {
            if (capture.info != null) return capture.partial(uri);
            throw new IllegalStateException("SHFE public HTTPS request failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (capture.info != null) return capture.partial(uri);
            throw new IllegalStateException("SHFE public HTTPS request was interrupted", exception);
        }
    }

    static final class CapturedBody implements HttpResponse.BodySubscriber<byte[]> {
        volatile HttpResponse.ResponseInfo info;
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        public java.util.concurrent.CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) { delegate.onSubscribe(subscription); }
        public synchronized void onNext(java.util.List<java.nio.ByteBuffer> buffers) {
            for (var buffer : buffers) { var copy = buffer.asReadOnlyBuffer(); byte[] part = new byte[copy.remaining()]; copy.get(part); bytes.writeBytes(part); }
            delegate.onNext(buffers);
        }
        public void onError(Throwable error) { delegate.onError(error); }
        public void onComplete() { delegate.onComplete(); }
        synchronized ShfeHttpResponse partial(URI uri) {
            return new ShfeHttpResponse(uri, info.statusCode(), info.headers().firstValue("Content-Type").orElse(null), bytes.toByteArray(), false);
        }
    }
}
