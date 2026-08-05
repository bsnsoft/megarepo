package de.bsnsoft.megarepo.repository.advisory.ghsa;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * An {@link HttpClient} that answers from a queue of canned responses and records the
 * requests it was given. No socket is ever opened — every GHSA test in this package runs
 * without network access, which is the point: a test that depends on api.github.com is a
 * test that fails on an air-gapped customer's CI.
 */
class StubHttpClient extends HttpClient {

    private final Deque<Object> responses = new ArrayDeque<>();
    private final List<HttpRequest> requests = new ArrayList<>();

    /** Queues a response; the queue is consumed in order, one entry per request. */
    StubHttpClient respond(int statusCode, String body, Map<String, String> headers) {
        responses.add(new StubResponse(statusCode, body, headers));
        return this;
    }

    StubHttpClient respond(int statusCode, String body) {
        return respond(statusCode, body, Map.of());
    }

    /** Queues a connection failure. */
    StubHttpClient fail(IOException failure) {
        responses.add(failure);
        return this;
    }

    List<HttpRequest> requests() {
        return List.copyOf(requests);
    }

    HttpRequest lastRequest() {
        if (requests.isEmpty()) {
            throw new IllegalStateException("No request was made");
        }
        return requests.get(requests.size() - 1);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException {
        requests.add(request);
        Object next = responses.poll();
        if (next == null) {
            throw new IllegalStateException("Unexpected request, no response queued: " + request.uri());
        }
        if (next instanceof IOException failure) {
            throw failure;
        }
        StubResponse canned = (StubResponse) next;
        return (HttpResponse<T>) canned.toHttpResponse(request);
    }

    private record StubResponse(int statusCode, String body, Map<String, String> headers) {

        HttpResponse<String> toHttpResponse(HttpRequest request) {
            HttpHeaders httpHeaders = HttpHeaders.of(
                    headers.entrySet().stream()
                            .collect(java.util.stream.Collectors.toMap(
                                    Map.Entry::getKey, e -> List.of(e.getValue()))),
                    (name, value) -> true);
            return new HttpResponse<>() {
                @Override
                public int statusCode() {
                    return statusCode;
                }

                @Override
                public HttpRequest request() {
                    return request;
                }

                @Override
                public Optional<HttpResponse<String>> previousResponse() {
                    return Optional.empty();
                }

                @Override
                public HttpHeaders headers() {
                    return httpHeaders;
                }

                @Override
                public String body() {
                    return body;
                }

                @Override
                public Optional<SSLSession> sslSession() {
                    return Optional.empty();
                }

                @Override
                public URI uri() {
                    return request.uri();
                }

                @Override
                public Version version() {
                    return Version.HTTP_1_1;
                }
            };
        }
    }

    // --- everything below is unused plumbing the abstract class requires -------------

    @Override
    public Optional<CookieHandler> cookieHandler() {
        return Optional.empty();
    }

    @Override
    public Optional<Duration> connectTimeout() {
        return Optional.empty();
    }

    @Override
    public Redirect followRedirects() {
        return Redirect.NEVER;
    }

    @Override
    public Optional<ProxySelector> proxy() {
        return Optional.empty();
    }

    @Override
    public SSLContext sslContext() {
        throw new UnsupportedOperationException();
    }

    @Override
    public SSLParameters sslParameters() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Optional<Authenticator> authenticator() {
        return Optional.empty();
    }

    @Override
    public Version version() {
        return Version.HTTP_1_1;
    }

    @Override
    public Optional<Executor> executor() {
        return Optional.empty();
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        throw new UnsupportedOperationException("The GHSA source only uses the blocking API");
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request,
            HttpResponse.BodyHandler<T> handler,
            HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
        throw new UnsupportedOperationException("The GHSA source only uses the blocking API");
    }
}
