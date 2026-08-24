package de.bsnsoft.megarepo.repository.proxy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;

@Component
public class RemoteHttpClient {

    private static final Logger log = LoggerFactory.getLogger(RemoteHttpClient.class);

    /**
     * The client used for all upstream fetches. Rebuilt in place when the runtime
     * outbound-proxy configuration changes (see {@link #applyRuntimeConfig}), so a
     * UI proxy change takes effect without an application restart. Marked
     * {@code volatile} for safe publication across request threads.
     */
    private volatile HttpClient defaultHttpClient;
    private final String userAgent;
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final int retryOnTimeout;

    /**
     * Cache of HttpClient instances keyed by proxy config string (host:port[:user]).
     * Avoids creating a new HttpClient on every proxied request.
     */
    private final ConcurrentHashMap<String, HttpClient> proxyClientCache = new ConcurrentHashMap<>();

    public RemoteHttpClient(
            @Value("${megarepo.proxy.connect-timeout:10s}") Duration connectTimeout,
            @Value("${megarepo.proxy.read-timeout:30s}") Duration readTimeout,
            @Value("${megarepo.proxy.user-agent:MegaRepo/1.0}") String userAgent,
            @Value("${megarepo.proxy.retry-on-timeout:1}") int retryOnTimeout,
            OutboundProxyProperties outboundProxy) {
        this.userAgent = userAgent;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
        this.retryOnTimeout = retryOnTimeout;
        this.defaultHttpClient = buildDefaultClient(connectTimeout, outboundProxy);
    }

    /**
     * Replaces the default upstream client with one built from the given (effective)
     * outbound-proxy configuration, taking effect immediately for subsequent fetches.
     *
     * <p>Called by the runtime configuration layer (UI {@code System → HTTP}) when the
     * persisted proxy settings change, and once at startup once the persisted settings
     * are resolved. When the UI has not been configured, the effective configuration is
     * the deployment-side fallback, so behavior is identical to a fresh boot.
     *
     * @param effective the effective outbound-proxy configuration to apply
     */
    public void applyRuntimeConfig(OutboundProxyProperties effective) {
        this.defaultHttpClient = buildDefaultClient(connectTimeout, effective);
    }

    /**
     * Builds the default client used for all upstream fetches.
     *
     * <p>When the global outbound proxy ({@code megarepo.outbound-proxy.*}) is enabled,
     * the client routes all traffic through the configured forward proxy (with optional
     * proxy authentication and non-proxy-host bypass). Otherwise the JDK default proxy
     * selector applies, so legacy JVM properties ({@code -Dhttp.proxyHost=...} via
     * {@code JAVA_TOOL_OPTIONS}) keep working exactly as before.
     */
    private static HttpClient buildDefaultClient(Duration connectTimeout, OutboundProxyProperties outboundProxy) {
        var builder = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL);

        if (outboundProxy != null && outboundProxy.enabled()) {
            if (outboundProxy.host() == null || outboundProxy.host().isBlank()) {
                throw new IllegalStateException(
                        "megarepo.outbound-proxy.enabled=true but megarepo.outbound-proxy.host is not set");
            }
            builder.proxy(new OutboundProxySelector(outboundProxy));
            if (outboundProxy.hasAuth()) {
                // The JDK disables Basic auth for CONNECT tunneling (i.e. proxying HTTPS)
                // by default via the jdk.http.auth.tunneling.disabledSchemes net property.
                // Clear it programmatically (unless the operator already set it) so that
                // authenticated proxies work for https:// upstreams, too.
                if (System.getProperty("jdk.http.auth.tunneling.disabledSchemes") == null) {
                    System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
                }
                builder.authenticator(new ProxyAuthenticator(
                        outboundProxy.username(), outboundProxy.password()));
            }
            log.info(
                    "Outbound proxy enabled: {}:{} (auth: {}, non-proxy-hosts: {})",
                    outboundProxy.host(),
                    outboundProxy.port(),
                    outboundProxy.hasAuth() ? "yes" : "no",
                    outboundProxy.nonProxyHosts());
        }

        return builder.build();
    }

    public RemoteResponse fetch(String remoteUrl, Map<String, String> extraHeaders) throws IOException {
        return doFetch(defaultHttpClient, remoteUrl, extraHeaders);
    }

    /**
     * The client every upstream fetch uses, reflecting the outbound-proxy configuration
     * currently in effect (including a change made in the UI since startup).
     *
     * <p>For callers that need more of the HTTP response than {@link RemoteResponse}
     * carries — the advisory sources read {@code X-RateLimit-*} and {@code Retry-After}
     * headers to pace themselves. They must not build their own {@link HttpClient}:
     * that would silently bypass {@code megarepo.outbound-proxy.*} and, on a customer
     * network with no direct egress, fail in a way that looks like the feed being down.
     *
     * <p>Call this per request rather than caching the result, or a proxy change made
     * after startup will not reach the caller.
     */
    public HttpClient upstreamHttpClient() {
        return defaultHttpClient;
    }

    /**
     * Fetch a remote URL using the given HTTP proxy configuration.
     *
     * @param remoteUrl the URL to fetch
     * @param extraHeaders additional headers to send
     * @param proxyConfig proxy configuration (host, port, optional username/password)
     * @return the remote response
     */
    public RemoteResponse fetchViaProxy(
            String remoteUrl, Map<String, String> extraHeaders, HttpProxyConfig proxyConfig) throws IOException {
        HttpClient client = getOrCreateProxyClient(proxyConfig);
        return doFetch(client, remoteUrl, extraHeaders);
    }

    public RemoteResponse fetchWithAuth(
            String remoteUrl, String username, String password) throws IOException {
        return fetchWithAuth(remoteUrl, username, password, Map.of());
    }

    public RemoteResponse fetchWithAuth(
            String remoteUrl, String username, String password, Map<String, String> extraHeaders)
            throws IOException {
        return fetch(remoteUrl, withBasicAuth(username, password, extraHeaders));
    }

    /**
     * Fetch with both upstream auth and HTTP proxy.
     */
    public RemoteResponse fetchWithAuthViaProxy(
            String remoteUrl, String username, String password, HttpProxyConfig proxyConfig) throws IOException {
        return fetchWithAuthViaProxy(remoteUrl, username, password, proxyConfig, Map.of());
    }

    public RemoteResponse fetchWithAuthViaProxy(
            String remoteUrl,
            String username,
            String password,
            HttpProxyConfig proxyConfig,
            Map<String, String> extraHeaders)
            throws IOException {
        return fetchViaProxy(remoteUrl, withBasicAuth(username, password, extraHeaders), proxyConfig);
    }

    /**
     * Combines caller-supplied headers with an upstream Basic {@code Authorization} header.
     * The credentials always win, so a format can never accidentally drop upstream auth.
     */
    private static Map<String, String> withBasicAuth(
            String username, String password, Map<String, String> extraHeaders) {
        String credentials = username + ":" + password;
        String encoded = Base64.getEncoder()
                .encodeToString(credentials.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Map<String, String> headers = new java.util.HashMap<>();
        if (extraHeaders != null) {
            headers.putAll(extraHeaders);
        }
        headers.put("Authorization", "Basic " + encoded);
        return headers;
    }

    private RemoteResponse doFetch(
            HttpClient httpClient, String remoteUrl, Map<String, String> extraHeaders) throws IOException {
        log.debug("Fetching remote URL: {}", remoteUrl);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(remoteUrl))
                .GET()
                .header("User-Agent", userAgent)
                // The JDK HttpClient neither advertises nor negotiates compression on its
                // own. Asking for gzip and inflating the body here cuts upstream transfer
                // dramatically for text metadata — an npm packument such as
                // @typescript-eslint/parser is ~15 MB raw but ~2.9 MB gzipped (GitHub #1).
                .header("Accept-Encoding", "gzip")
                .timeout(readTimeout);

        if (extraHeaders != null) {
            extraHeaders.forEach(requestBuilder::header);
        }

        HttpRequest request = requestBuilder.build();
        HttpTimeoutException lastTimeout = null;

        for (int attempt = 0; attempt <= retryOnTimeout; attempt++) {
            try {
                if (attempt > 0) {
                    log.warn(
                            "Retrying remote fetch (attempt {}/{}) after timeout for URL: {}",
                            attempt + 1,
                            retryOnTimeout + 1,
                            remoteUrl);
                }

                HttpResponse<InputStream> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

                long contentLength = response.headers()
                        .firstValueAsLong("Content-Length")
                        .orElse(-1);

                String contentType = response.headers()
                        .firstValue("Content-Type")
                        .orElse("application/octet-stream");

                InputStream body = response.body();
                if (isGzipEncoded(response) && body != null) {
                    body = new GZIPInputStream(body);
                    // Content-Length described the compressed payload; it no longer
                    // matches the stream the caller will read.
                    contentLength = -1;
                }

                return new RemoteResponse(
                        response.statusCode(),
                        body,
                        contentLength,
                        contentType);
            } catch (HttpTimeoutException e) {
                lastTimeout = e;
                log.warn(
                        "Timeout fetching remote URL (attempt {}/{}): {}",
                        attempt + 1,
                        retryOnTimeout + 1,
                        remoteUrl);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Remote fetch interrupted: " + remoteUrl, e);
            } catch (IOException e) {
                // For non-timeout IOExceptions (e.g. connection refused), check if caused by timeout
                if (e.getCause() instanceof HttpTimeoutException hte) {
                    lastTimeout = hte;
                    log.warn(
                            "Timeout fetching remote URL (attempt {}/{}): {}",
                            attempt + 1,
                            retryOnTimeout + 1,
                            remoteUrl);
                } else {
                    throw e;
                }
            }
        }

        // All retries exhausted
        throw new UpstreamTimeoutException(
                "Upstream timeout after %d attempt(s) for URL: %s".formatted(retryOnTimeout + 1, remoteUrl),
                lastTimeout);
    }

    /**
     * Returns {@code true} when the upstream body is gzip-encoded and must be inflated
     * before it reaches the caller. Only {@code gzip} is negotiated (see the request
     * header set in {@link #doFetch}), so no other encoding is expected here.
     */
    private static boolean isGzipEncoded(HttpResponse<?> response) {
        return response.headers()
                .firstValue("Content-Encoding")
                .map(encoding -> encoding.trim().equalsIgnoreCase("gzip"))
                .orElse(false);
    }

    private HttpClient getOrCreateProxyClient(HttpProxyConfig config) {
        String cacheKey = config.host() + ":" + config.port()
                + (config.username() != null ? ":" + config.username() : "");

        return proxyClientCache.computeIfAbsent(cacheKey, key -> buildProxyClient(config));
    }

    private HttpClient buildProxyClient(HttpProxyConfig config) {
        log.info("Creating HTTP client with proxy {}:{}", config.host(), config.port());

        var builder = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .proxy(ProxySelector.of(new InetSocketAddress(config.host(), config.port())));

        if (config.username() != null && config.password() != null) {
            builder.authenticator(new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(config.username(), config.password().toCharArray());
                }
            });
        }

        return builder.build();
    }

    public record RemoteResponse(
            int statusCode,
            InputStream body,
            long contentLength,
            String contentType) {}

    /**
     * Proxy selector for the global outbound proxy: routes everything through the
     * configured forward proxy except hosts matching the non-proxy-hosts patterns.
     */
    static final class OutboundProxySelector extends ProxySelector {

        private final OutboundProxyProperties config;
        private final Proxy proxy;

        OutboundProxySelector(OutboundProxyProperties config) {
            this.config = config;
            this.proxy = new Proxy(
                    Proxy.Type.HTTP,
                    InetSocketAddress.createUnresolved(config.host(), config.port()));
        }

        @Override
        public List<Proxy> select(URI uri) {
            if (config.isNonProxyHost(uri.getHost())) {
                return List.of(Proxy.NO_PROXY);
            }
            return List.of(proxy);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            log.warn("Failed to connect to outbound proxy {} for {}: {}", sa, uri, ioe.getMessage());
        }
    }

    /**
     * Authenticator that answers only proxy authentication challenges
     * ({@link Authenticator.RequestorType#PROXY}). Upstream (server) auth is handled
     * separately via explicit {@code Authorization} headers in
     * {@link #fetchWithAuth(String, String, String)} and must never receive the
     * proxy credentials.
     */
    static final class ProxyAuthenticator extends Authenticator {

        private final String username;
        private final char[] password;

        ProxyAuthenticator(String username, String password) {
            this.username = username;
            this.password = password.toCharArray();
        }

        @Override
        protected PasswordAuthentication getPasswordAuthentication() {
            return authFor(getRequestorType());
        }

        /**
         * Returns credentials only for proxy challenges; visible for testing.
         */
        PasswordAuthentication authFor(RequestorType type) {
            if (type == RequestorType.PROXY) {
                return new PasswordAuthentication(username, password.clone());
            }
            return null;
        }
    }

    /**
     * Configuration for an HTTP proxy used for upstream fetches.
     */
    public record HttpProxyConfig(String host, int port, String username, String password) {

        /**
         * Creates a proxy config without authentication.
         */
        public HttpProxyConfig(String host, int port) {
            this(host, port, null, null);
        }
    }
}
