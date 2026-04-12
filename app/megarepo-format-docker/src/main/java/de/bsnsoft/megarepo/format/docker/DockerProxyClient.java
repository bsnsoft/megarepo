package de.bsnsoft.megarepo.format.docker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import de.bsnsoft.megarepo.core.validation.UrlSsrfValidator;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP client for proxying Docker registry requests to upstream registries (e.g. Docker Hub).
 *
 * <p>Handles the Docker registry token authentication flow:
 * <ol>
 *   <li>Initial request returns 401 with {@code Www-Authenticate: Bearer realm=...,service=...,scope=...}</li>
 *   <li>GET the token from the realm URL with service and scope parameters</li>
 *   <li>Retry the original request with the Bearer token</li>
 * </ol>
 */
@Component
public class DockerProxyClient {

    private static final Logger log = LoggerFactory.getLogger(DockerProxyClient.class);

    private static final Pattern WWW_AUTHENTICATE_PATTERN =
            Pattern.compile("Bearer\\s+realm=\"([^\"]+)\"(?:,service=\"([^\"]+)\")?(?:,scope=\"([^\"]+)\")?");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    /**
     * Cache of bearer tokens keyed by "realm|service|scope". Tokens are typically valid for 300s
     * but we cache them for slightly less to account for clock skew.
     */
    private final ConcurrentHashMap<String, CachedToken> tokenCache = new ConcurrentHashMap<>();

    private record CachedToken(String token, Instant expiresAt) {
        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    public DockerProxyClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Fetch a resource from an upstream Docker registry, handling token authentication.
     *
     * @param url          the full URL to fetch (e.g. https://registry-1.docker.io/v2/library/nginx/manifests/latest)
     * @param extraHeaders additional headers to send (e.g. Accept for manifest media types)
     * @return the response from the upstream registry
     */
    public UpstreamResponse fetch(String url, Map<String, String> extraHeaders) throws IOException {
        UrlSsrfValidator.validateUrlNotInternal(url);
        try {
            // First attempt — may return 401 if auth is required
            HttpResponse<InputStream> initialResponse = doRequest(url, extraHeaders, null);

            HttpResponse<InputStream> response;
            if (initialResponse.statusCode() == 401) {
                initialResponse.body().close();
                // Parse Www-Authenticate header and obtain a token
                String wwwAuth = initialResponse.headers()
                        .firstValue("Www-Authenticate")
                        .or(() -> initialResponse.headers().firstValue("www-authenticate"))
                        .orElse(null);

                if (wwwAuth == null) {
                    log.warn("Upstream returned 401 without Www-Authenticate header for URL: {}", url);
                    return new UpstreamResponse(401, InputStream.nullInputStream(), -1, "application/json");
                }

                String token = obtainToken(wwwAuth);
                if (token == null) {
                    log.warn("Failed to obtain Docker token for URL: {}", url);
                    return new UpstreamResponse(401, InputStream.nullInputStream(), -1, "application/json");
                }

                // Retry with token
                response = doRequest(url, extraHeaders, token);
            } else {
                response = initialResponse;
            }

            long contentLength = response.headers()
                    .firstValueAsLong("Content-Length")
                    .orElse(-1);
            String contentType = response.headers()
                    .firstValue("Content-Type")
                    .orElse("application/octet-stream");
            String dockerDigest = response.headers()
                    .firstValue("Docker-Content-Digest")
                    .orElse(null);

            return new UpstreamResponse(response.statusCode(), response.body(), contentLength, contentType,
                    dockerDigest);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Docker proxy request interrupted: " + url, e);
        }
    }

    /**
     * Fetch a resource using a pre-determined scope for token auth, without making the initial
     * 401 round-trip. Useful when we already know the image scope.
     */
    public UpstreamResponse fetchWithScope(String url, String registryBase, String scope,
            Map<String, String> extraHeaders) throws IOException {
        UrlSsrfValidator.validateUrlNotInternal(url);
        // Try to get a cached or fresh token for this scope
        String realm = registryBase.replace("registry-1.docker.io", "auth.docker.io/token")
                .replace("/v2", "");
        if (!realm.contains("auth.docker.io")) {
            // For non-Docker-Hub registries, fall back to standard fetch with 401 challenge
            return fetch(url, extraHeaders);
        }

        String tokenKey = realm + "|registry.docker.io|" + scope;
        CachedToken cached = tokenCache.get(tokenKey);
        String token = null;
        if (cached != null && !cached.isExpired()) {
            token = cached.token();
        } else {
            token = fetchToken("https://auth.docker.io/token", "registry.docker.io", scope);
            if (token != null) {
                tokenCache.put(tokenKey, new CachedToken(token, Instant.now().plusSeconds(250)));
            }
        }

        if (token == null) {
            // Fallback to regular fetch with 401 challenge
            return fetch(url, extraHeaders);
        }

        try {
            HttpResponse<InputStream> response = doRequest(url, extraHeaders, token);
            long contentLength = response.headers()
                    .firstValueAsLong("Content-Length")
                    .orElse(-1);
            String contentType = response.headers()
                    .firstValue("Content-Type")
                    .orElse("application/octet-stream");
            String dockerDigest = response.headers()
                    .firstValue("Docker-Content-Digest")
                    .orElse(null);

            return new UpstreamResponse(response.statusCode(), response.body(), contentLength, contentType,
                    dockerDigest);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Docker proxy request interrupted: " + url, e);
        }
    }

    private HttpResponse<InputStream> doRequest(String url, Map<String, String> extraHeaders, String bearerToken)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .header("User-Agent", "MegaRepo/1.0 Docker-Proxy")
                .timeout(Duration.ofSeconds(30));

        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        if (extraHeaders != null) {
            extraHeaders.forEach(builder::header);
        }

        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
    }

    /**
     * Parse a Www-Authenticate header and obtain a Bearer token.
     */
    private String obtainToken(String wwwAuthenticate) throws IOException {
        Matcher m = WWW_AUTHENTICATE_PATTERN.matcher(wwwAuthenticate);
        if (!m.find()) {
            log.warn("Could not parse Www-Authenticate header: {}", wwwAuthenticate);
            return null;
        }

        String realm = m.group(1);
        String service = m.group(2);
        String scope = m.group(3);

        // Check cache
        String cacheKey = realm + "|" + service + "|" + scope;
        CachedToken cached = tokenCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return cached.token();
        }

        String token = fetchToken(realm, service, scope);
        if (token != null) {
            tokenCache.put(cacheKey, new CachedToken(token, Instant.now().plusSeconds(250)));
        }
        return token;
    }

    private String fetchToken(String realm, String service, String scope) throws IOException {
        StringBuilder tokenUrl = new StringBuilder(realm);
        char separator = realm.contains("?") ? '&' : '?';
        if (service != null) {
            tokenUrl.append(separator).append("service=").append(service);
            separator = '&';
        }
        if (scope != null) {
            tokenUrl.append(separator).append("scope=").append(scope);
        }

        log.debug("Fetching Docker token from: {}", tokenUrl);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(tokenUrl.toString()))
                    .GET()
                    .header("User-Agent", "MegaRepo/1.0 Docker-Proxy")
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("Token endpoint returned status {} for URL: {}", response.statusCode(), tokenUrl);
                return null;
            }

            JsonNode root = objectMapper.readTree(response.body());
            // Docker Hub returns "token", some registries return "access_token"
            if (root.has("token")) {
                return root.get("token").asText();
            }
            if (root.has("access_token")) {
                return root.get("access_token").asText();
            }

            log.warn("Token response has no 'token' or 'access_token' field");
            return null;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Token fetch interrupted", e);
        }
    }

    /**
     * Response from an upstream Docker registry.
     */
    public record UpstreamResponse(
            int statusCode,
            InputStream body,
            long contentLength,
            String contentType,
            String dockerContentDigest) {

        public UpstreamResponse(int statusCode, InputStream body, long contentLength, String contentType) {
            this(statusCode, body, contentLength, contentType, null);
        }
    }
}
