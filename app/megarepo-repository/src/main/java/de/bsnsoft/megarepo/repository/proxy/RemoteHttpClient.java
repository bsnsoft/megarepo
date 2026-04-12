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
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RemoteHttpClient {

    private static final Logger log = LoggerFactory.getLogger(RemoteHttpClient.class);

    private final HttpClient defaultHttpClient;
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
            @Value("${megarepo.proxy.retry-on-timeout:1}") int retryOnTimeout) {
        this.userAgent = userAgent;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
        this.retryOnTimeout = retryOnTimeout;
        this.defaultHttpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public RemoteResponse fetch(String remoteUrl, Map<String, String> extraHeaders) throws IOException {
        return doFetch(defaultHttpClient, remoteUrl, extraHeaders);
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
        String credentials = username + ":" + password;
        String encoded = Base64.getEncoder().encodeToString(credentials.getBytes());
        return fetch(remoteUrl, Map.of("Authorization", "Basic " + encoded));
    }

    /**
     * Fetch with both upstream auth and HTTP proxy.
     */
    public RemoteResponse fetchWithAuthViaProxy(
            String remoteUrl, String username, String password, HttpProxyConfig proxyConfig) throws IOException {
        String credentials = username + ":" + password;
        String encoded = Base64.getEncoder().encodeToString(credentials.getBytes());
        return fetchViaProxy(remoteUrl, Map.of("Authorization", "Basic " + encoded), proxyConfig);
    }

    private RemoteResponse doFetch(
            HttpClient httpClient, String remoteUrl, Map<String, String> extraHeaders) throws IOException {
        log.debug("Fetching remote URL: {}", remoteUrl);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(remoteUrl))
                .GET()
                .header("User-Agent", userAgent)
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

                return new RemoteResponse(
                        response.statusCode(),
                        response.body(),
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
