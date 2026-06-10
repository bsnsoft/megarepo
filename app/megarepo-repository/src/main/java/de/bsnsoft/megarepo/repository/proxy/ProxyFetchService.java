package de.bsnsoft.megarepo.repository.proxy;

import de.bsnsoft.megarepo.core.format.ComponentCoordinateExtractor;
import de.bsnsoft.megarepo.core.format.ComponentCoordinates;
import de.bsnsoft.megarepo.core.format.FormatResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.ContentResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.ErrorResponse;
import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.core.storage.BlobRef;
import de.bsnsoft.megarepo.core.storage.BlobStore;
import de.bsnsoft.megarepo.database.entity.AssetEntity;
import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import de.bsnsoft.megarepo.database.repository.AssetJpaRepository;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import de.bsnsoft.megarepo.repository.AuditService;
import de.bsnsoft.megarepo.repository.RepositoryRouter;
import de.bsnsoft.megarepo.storage.BlobStoreManager;
import de.bsnsoft.megarepo.storage.MultiDigestInputStream;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class ProxyFetchService {

    private static final Logger log = LoggerFactory.getLogger(ProxyFetchService.class);

    private final RemoteHttpClient remoteHttpClient;
    private final AssetJpaRepository assetRepository;
    private final ComponentJpaRepository componentRepository;
    private final BlobStoreManager blobStoreManager;
    private final NegativeCacheService negativeCacheService;
    private final BlacklistService blacklistService;
    private final AuditService auditService;

    /**
     * In-flight request coalescing map. When multiple threads request the same artifact
     * simultaneously, only the first thread fetches from upstream. Other threads wait
     * for the result. The future completes with a {@link CachedFetchResult} that holds
     * the raw byte array, allowing each waiting thread to create its own independent
     * {@link ByteArrayInputStream} (avoiding shared-stream corruption).
     */
    private final ConcurrentHashMap<String, CompletableFuture<CachedFetchResult>> inFlightRequests =
            new ConcurrentHashMap<>();

    public ProxyFetchService(
            RemoteHttpClient remoteHttpClient,
            AssetJpaRepository assetRepository,
            ComponentJpaRepository componentRepository,
            BlobStoreManager blobStoreManager,
            NegativeCacheService negativeCacheService,
            BlacklistService blacklistService,
            AuditService auditService) {
        this.remoteHttpClient = remoteHttpClient;
        this.assetRepository = assetRepository;
        this.componentRepository = componentRepository;
        this.blobStoreManager = blobStoreManager;
        this.negativeCacheService = negativeCacheService;
        this.blacklistService = blacklistService;
        this.auditService = auditService;
    }

    public Optional<FormatResponse> fetchAndCache(
            RepositoryConfig repo, String path, ComponentCoordinateExtractor extractor) {
        String key = repo.id() + ":" + path;

        CompletableFuture<CachedFetchResult> newFuture = new CompletableFuture<>();
        CompletableFuture<CachedFetchResult> existing = inFlightRequests.putIfAbsent(key, newFuture);

        if (existing != null) {
            // Another thread is already fetching this artifact — wait and build a fresh response
            log.debug("Coalescing proxy request for repo={} path={}", repo.name(), path);
            return awaitAndBuildResponse(existing, repo, path);
        }

        // We are the fetching thread
        try {
            Optional<FormatResponse> result = doFetchAndCache(repo, path, extractor);
            CachedFetchResult cached = CachedFetchResult.from(result);
            newFuture.complete(cached);
            // Return a fresh response for this thread too (the original stream was consumed by from())
            return cached.toFreshResponse();
        } catch (Exception e) {
            newFuture.completeExceptionally(e);
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException("Fetch failed for repo=%s path=%s".formatted(repo.name(), path), e);
        } finally {
            inFlightRequests.remove(key, newFuture);
        }
    }

    /**
     * Waits for an in-flight fetch to complete and builds a fresh response from the cached data.
     * Each waiting thread gets its own {@link ByteArrayInputStream}, avoiding the bug where
     * multiple threads share a single stream (only the first reader gets data).
     */
    private Optional<FormatResponse> awaitAndBuildResponse(
            CompletableFuture<CachedFetchResult> future, RepositoryConfig repo, String path) {
        try {
            CachedFetchResult cached = future.get(60, TimeUnit.SECONDS);
            return cached.toFreshResponse();
        } catch (TimeoutException e) {
            log.error("Timeout waiting for in-flight fetch for repo={} path={}", repo.name(), path);
            return Optional.empty();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException("Fetch failed for repo=%s path=%s".formatted(repo.name(), path), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /**
     * Immutable snapshot of a fetch result that can safely produce independent responses
     * for multiple waiting threads. Holds raw byte arrays instead of streams.
     */
    sealed interface CachedFetchResult {

        Optional<FormatResponse> toFreshResponse();

        static CachedFetchResult from(Optional<FormatResponse> response) {
            if (response.isEmpty()) {
                return new EmptyResult();
            }
            FormatResponse r = response.get();
            if (r instanceof ContentResponse content) {
                try {
                    byte[] data = content.content().readAllBytes();
                    return new ContentResult(
                            data, content.contentType(), content.contentLength(), content.headers(), content.checksums());
                } catch (IOException e) {
                    log.warn("Failed to read content for coalescing cache: {}", e.getMessage());
                    return new ErrorResult(502, "Failed to read upstream content");
                }
            }
            if (r instanceof ErrorResponse error) {
                return new ErrorResult(error.statusCode(), error.message());
            }
            if (r instanceof FormatResponse.NotFoundResponse notFound) {
                return new NotFoundResult(notFound.message());
            }
            // Fallback for other response types — wrap as empty
            return new EmptyResult();
        }
    }

    record ContentResult(byte[] data, String contentType, long contentLength,
                         Map<String, String> headers, Map<String, String> checksums) implements CachedFetchResult {
        @Override
        public Optional<FormatResponse> toFreshResponse() {
            return Optional.of(new ContentResponse(
                    new ByteArrayInputStream(data), contentType, contentLength, headers, checksums));
        }
    }

    record ErrorResult(int statusCode, String message) implements CachedFetchResult {
        @Override
        public Optional<FormatResponse> toFreshResponse() {
            return Optional.of(new ErrorResponse(statusCode, message));
        }
    }

    record NotFoundResult(String message) implements CachedFetchResult {
        @Override
        public Optional<FormatResponse> toFreshResponse() {
            return Optional.of(new FormatResponse.NotFoundResponse(message));
        }
    }

    record EmptyResult() implements CachedFetchResult {
        @Override
        public Optional<FormatResponse> toFreshResponse() {
            return Optional.empty();
        }
    }

    @Transactional
    protected Optional<FormatResponse> doFetchAndCache(
            RepositoryConfig repo, String path, ComponentCoordinateExtractor extractor) {
        // Check blacklist before any remote fetch
        if (blacklistService.isBlacklisted(repo, path)) {
            log.info("Blacklisted artifact blocked for repo={} path={}", repo.name(), path);
            return Optional.of(new ErrorResponse(403, "Artifact is blacklisted: " + path));
        }

        // Check negative cache first
        if (negativeCacheService.isEnabled(repo)
                && negativeCacheService.isNegativelyCached(repo.id(), path)) {
            log.debug("Negative cache hit for repo={} path={}", repo.name(), path);
            return Optional.empty();
        }

        String remoteUrl = getRemoteUrl(repo);
        String fullUrl = remoteUrl.endsWith("/") ? remoteUrl + path : remoteUrl + "/" + path;

        try {
            RemoteHttpClient.RemoteResponse response = fetchFromRemote(repo, fullUrl);

            if (response.statusCode() == 404) {
                closeQuietly(response.body());
                if (negativeCacheService.isEnabled(repo)) {
                    int ttl = negativeCacheService.getNegativeCacheTtl(repo);
                    negativeCacheService.cacheNegativeResult(repo.id(), path, ttl);
                }
                log.debug("Remote 404 for repo={} path={}", repo.name(), path);
                return Optional.of(new ErrorResponse(404, "Not found on upstream: " + path));
            }

            if (response.statusCode() >= 500) {
                closeQuietly(response.body());
                log.warn(
                        "Upstream server error {} for repo={} path={}",
                        response.statusCode(),
                        repo.name(),
                        path);
                return Optional.of(new ErrorResponse(
                        502,
                        "Upstream server error (HTTP %d) for path: %s".formatted(response.statusCode(), path)));
            }

            if (response.statusCode() != 200) {
                closeQuietly(response.body());
                log.warn(
                        "Remote returned status {} for repo={} path={}",
                        response.statusCode(),
                        repo.name(),
                        path);
                return Optional.of(new ErrorResponse(
                        502,
                        "Remote returned status %d for path: %s".formatted(response.statusCode(), path)));
            }

            // Defensive: a 200 with a null body (can occur through some forward-proxy setups)
            // must not crash the digest pipeline with an NPE — treat it as "nothing to cache".
            if (response.body() == null) {
                log.warn("Remote returned 200 with null body for repo={} path={}", repo.name(), path);
                return Optional.of(new ErrorResponse(502, "Upstream returned an empty response for path: " + path));
            }

            // Successful fetch - stream through digest, store blob, create asset
            long fetchStart = System.currentTimeMillis();
            FormatResponse cached = cacheRemoteContent(repo, path, response, extractor);
            long fetchDuration = System.currentTimeMillis() - fetchStart;

            if (cached instanceof ContentResponse content) {
                HttpServletRequest currentRequest = resolveCurrentRequest();
                String ip = currentRequest != null ? RepositoryRouter.clientIp(currentRequest) : null;
                String user = currentRequest != null ? RepositoryRouter.currentUser(currentRequest) : "anonymous";
                auditService.logProxyFetch(
                        user,
                        repo.name(),
                        path,
                        fullUrl,
                        repo.format(),
                        content.contentLength(),
                        ip,
                        fetchDuration);
            }

            return Optional.of(cached);
        } catch (UpstreamTimeoutException e) {
            log.warn("Upstream timeout for repo={} path={}: {}", repo.name(), path, e.getMessage());
            return Optional.of(new ErrorResponse(502, "Upstream timeout for path: " + path));
        } catch (IOException e) {
            log.error("Failed to fetch from remote for repo={} path={}: {}", repo.name(), path, e.getMessage());
            return Optional.of(new ErrorResponse(502, "Failed to fetch from remote: " + e.getMessage()));
        }
    }

    @SuppressWarnings("unchecked")
    String getRemoteUrl(RepositoryConfig repo) {
        Object proxyObj = repo.attributes().get("proxy");
        if (proxyObj instanceof Map<?, ?> proxyMap) {
            Object url = proxyMap.get("remoteUrl");
            if (url instanceof String s) {
                // Strip trailing slash for consistent concatenation
                return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
            }
        }
        throw new IllegalStateException(
                "Proxy repository '%s' has no remoteUrl configured".formatted(repo.name()));
    }

    private RemoteHttpClient.RemoteResponse fetchFromRemote(
            RepositoryConfig repo, String fullUrl) throws IOException {
        Optional<ProxyAuth> auth = getProxyAuth(repo);
        Optional<RemoteHttpClient.HttpProxyConfig> httpProxy = getHttpProxyConfig(repo);

        if (auth.isPresent() && httpProxy.isPresent()) {
            ProxyAuth credentials = auth.get();
            return remoteHttpClient.fetchWithAuthViaProxy(
                    fullUrl, credentials.username(), credentials.password(), httpProxy.get());
        } else if (auth.isPresent()) {
            ProxyAuth credentials = auth.get();
            return remoteHttpClient.fetchWithAuth(fullUrl, credentials.username(), credentials.password());
        } else if (httpProxy.isPresent()) {
            return remoteHttpClient.fetchViaProxy(fullUrl, Map.of(), httpProxy.get());
        }
        return remoteHttpClient.fetch(fullUrl, Map.of());
    }

    @SuppressWarnings("unchecked")
    private Optional<RemoteHttpClient.HttpProxyConfig> getHttpProxyConfig(RepositoryConfig repo) {
        Object proxyObj = repo.attributes().get("proxy");
        if (proxyObj instanceof Map<?, ?> proxyMap) {
            Object httpProxyObj = proxyMap.get("httpProxy");
            if (httpProxyObj instanceof Map<?, ?> httpProxyMap) {
                Object host = httpProxyMap.get("host");
                Object port = httpProxyMap.get("port");
                if (host instanceof String h && port != null) {
                    int p = port instanceof Number n ? n.intValue() : Integer.parseInt(port.toString());
                    Object username = httpProxyMap.get("username");
                    Object password = httpProxyMap.get("password");
                    if (username instanceof String u && password instanceof String pw) {
                        return Optional.of(new RemoteHttpClient.HttpProxyConfig(h, p, u, pw));
                    }
                    return Optional.of(new RemoteHttpClient.HttpProxyConfig(h, p));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Reads upstream authentication credentials from the repository's proxy attributes.
     * Supports two formats:
     * <ul>
     *   <li>{@code proxy.username} / {@code proxy.password} — simple flat format (preferred)</li>
     *   <li>{@code proxy.authentication.username} / {@code proxy.authentication.password} — nested format (backward compat)</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private Optional<ProxyAuth> getProxyAuth(RepositoryConfig repo) {
        Object proxyObj = repo.attributes().get("proxy");
        if (proxyObj instanceof Map<?, ?> proxyMap) {
            // Preferred: flat proxy.username / proxy.password
            Object username = proxyMap.get("username");
            Object password = proxyMap.get("password");
            if (username instanceof String u && !u.isBlank() && password instanceof String p) {
                return Optional.of(new ProxyAuth(u, p));
            }

            // Backward compat: nested proxy.authentication.username / proxy.authentication.password
            Object authObj = proxyMap.get("authentication");
            if (authObj instanceof Map<?, ?> authMap) {
                Object authUsername = authMap.get("username");
                Object authPassword = authMap.get("password");
                if (authUsername instanceof String u2 && authPassword instanceof String p2) {
                    return Optional.of(new ProxyAuth(u2, p2));
                }
            }
        }
        return Optional.empty();
    }

    private FormatResponse cacheRemoteContent(
            RepositoryConfig repo,
            String path,
            RemoteHttpClient.RemoteResponse response,
            ComponentCoordinateExtractor extractor) throws IOException {
        try {
            var digestStream = new MultiDigestInputStream(response.body());

            // Read all content into memory so we can store blob and return it
            byte[] content = digestStream.readAllBytes();
            Map<String, String> checksums = digestStream.getChecksums();
            long size = content.length;

            // Store blob
            BlobStore blobStore = blobStoreManager.get(repo.blobStoreName());
            BlobRef blobRef = blobStore.store(
                    new ByteArrayInputStream(content),
                    size,
                    Map.of("Content-Type", response.contentType()));

            // Extract coordinates and find/create component
            ComponentEntity component = null;
            if (extractor != null) {
                Optional<ComponentCoordinates> coords = extractor.extractFromPath(path);
                if (coords.isPresent()) {
                    component = findOrCreateComponent(repo, coords.get());
                }
            }

            // Create or update asset
            Instant now = Instant.now();
            AssetEntity asset =
                    assetRepository.findByRepositoryIdAndPath(repo.id(), path).orElseGet(() -> {
                        var newAsset = new AssetEntity();
                        newAsset.setRepositoryId(repo.id());
                        newAsset.setPath(path);
                        newAsset.setFormat(repo.format());
                        newAsset.setCreatedAt(now);
                        return newAsset;
                    });

            if (component != null) {
                asset.setComponentId(component.getId());
            }
            asset.setBlobRef(blobRef.toExternalForm());
            asset.setContentType(response.contentType());
            asset.setSize(size);
            asset.setChecksumMd5(checksums.get("md5"));
            asset.setChecksumSha1(checksums.get("sha1"));
            asset.setChecksumSha256(checksums.get("sha256"));
            asset.setChecksumSha512(checksums.get("sha512"));
            asset.setCreatedBy("proxy");
            asset.setLastModified(now);
            asset.setUpdatedAt(now);
            assetRepository.save(asset);

            log.debug("Cached remote asset for repo={} path={} size={}", repo.name(), path, size);

            // Build checksums map for the response
            Map<String, String> responseChecksums = new HashMap<>();
            if (asset.getChecksumMd5() != null) {
                responseChecksums.put("md5", asset.getChecksumMd5());
            }
            if (asset.getChecksumSha1() != null) {
                responseChecksums.put("sha1", asset.getChecksumSha1());
            }
            if (asset.getChecksumSha256() != null) {
                responseChecksums.put("sha256", asset.getChecksumSha256());
            }

            return new ContentResponse(
                    new ByteArrayInputStream(content),
                    response.contentType(),
                    size,
                    Map.of(),
                    responseChecksums);

        } catch (NoSuchAlgorithmException e) {
            throw new IOException("Required digest algorithm not available", e);
        }
    }

    private ComponentEntity findOrCreateComponent(RepositoryConfig repo, ComponentCoordinates coords) {
        return componentRepository
                .findByRepositoryIdAndNamespaceAndNameAndVersion(
                        repo.id(), coords.namespace(), coords.name(), coords.version())
                .orElseGet(() -> {
                    var component = new ComponentEntity();
                    component.setRepositoryId(repo.id());
                    component.setFormat(repo.format());
                    component.setNamespace(coords.namespace());
                    component.setName(coords.name());
                    component.setVersion(coords.version());

                    Instant now = Instant.now();
                    component.setCreatedAt(now);
                    component.setUpdatedAt(now);
                    return componentRepository.save(component);
                });
    }

    /**
     * Fetches content from an explicit upstream URL (instead of constructing it from the repo's
     * remote URL + path). Used for PyPI proxy where download URLs point to a different host
     * (e.g., files.pythonhosted.org) than the index (pypi.org).
     *
     * @param repo       the proxy repository configuration
     * @param path       the local asset path to cache under
     * @param upstreamUrl the full upstream URL to fetch from
     * @param extractor  coordinate extractor for component creation
     * @return the format response with the fetched content
     */
    public FormatResponse fetchFromUrl(
            RepositoryConfig repo, String path, String upstreamUrl, ComponentCoordinateExtractor extractor) {
        String key = repo.id() + ":" + path;

        CompletableFuture<CachedFetchResult> newFuture = new CompletableFuture<>();
        CompletableFuture<CachedFetchResult> existing = inFlightRequests.putIfAbsent(key, newFuture);

        if (existing != null) {
            log.debug("Coalescing proxy URL request for repo={} path={}", repo.name(), path);
            Optional<FormatResponse> result = awaitAndBuildResponse(existing, repo, path);
            return result.orElse(new FormatResponse.NotFoundResponse("Asset not found: " + path));
        }

        try {
            Optional<FormatResponse> result = doFetchFromUrl(repo, path, upstreamUrl, extractor);
            CachedFetchResult cached = CachedFetchResult.from(result);
            newFuture.complete(cached);
            return cached.toFreshResponse()
                    .orElse(new FormatResponse.NotFoundResponse("Asset not found on remote: " + path));
        } catch (Exception e) {
            newFuture.completeExceptionally(e);
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(
                    "Fetch failed for repo=%s url=%s".formatted(repo.name(), upstreamUrl), e);
        } finally {
            inFlightRequests.remove(key, newFuture);
        }
    }

    @Transactional
    protected Optional<FormatResponse> doFetchFromUrl(
            RepositoryConfig repo, String path, String upstreamUrl, ComponentCoordinateExtractor extractor) {
        if (blacklistService.isBlacklisted(repo, path)) {
            log.info("Blacklisted artifact blocked for repo={} path={}", repo.name(), path);
            return Optional.of(new FormatResponse.ErrorResponse(403, "Artifact is blacklisted: " + path));
        }

        if (negativeCacheService.isEnabled(repo)
                && negativeCacheService.isNegativelyCached(repo.id(), path)) {
            log.debug("Negative cache hit for repo={} path={}", repo.name(), path);
            return Optional.empty();
        }

        try {
            RemoteHttpClient.RemoteResponse response = fetchFromRemote(repo, upstreamUrl);

            if (response.statusCode() == 404) {
                closeQuietly(response.body());
                if (negativeCacheService.isEnabled(repo)) {
                    int ttl = negativeCacheService.getNegativeCacheTtl(repo);
                    negativeCacheService.cacheNegativeResult(repo.id(), path, ttl);
                }
                log.debug("Remote 404 for repo={} url={}", repo.name(), upstreamUrl);
                return Optional.of(new FormatResponse.ErrorResponse(404, "Not found on upstream: " + path));
            }

            if (response.statusCode() >= 500) {
                closeQuietly(response.body());
                log.warn(
                        "Upstream server error {} for repo={} url={}",
                        response.statusCode(),
                        repo.name(),
                        upstreamUrl);
                return Optional.of(new FormatResponse.ErrorResponse(
                        502,
                        "Upstream server error (HTTP %d) for path: %s".formatted(response.statusCode(), path)));
            }

            if (response.statusCode() != 200) {
                closeQuietly(response.body());
                log.warn("Remote returned status {} for repo={} url={}", response.statusCode(), repo.name(), upstreamUrl);
                return Optional.of(new FormatResponse.ErrorResponse(
                        502, "Remote returned status %d for url: %s".formatted(response.statusCode(), upstreamUrl)));
            }

            if (response.body() == null) {
                log.warn("Remote returned 200 with null body for repo={} url={}", repo.name(), upstreamUrl);
                return Optional.of(
                        new FormatResponse.ErrorResponse(502, "Upstream returned an empty response for path: " + path));
            }

            long fetchStart = System.currentTimeMillis();
            FormatResponse cached = cacheRemoteContent(repo, path, response, extractor);
            long fetchDuration = System.currentTimeMillis() - fetchStart;

            if (cached instanceof ContentResponse content) {
                HttpServletRequest currentRequest = resolveCurrentRequest();
                String ip = currentRequest != null ? RepositoryRouter.clientIp(currentRequest) : null;
                String user = currentRequest != null ? RepositoryRouter.currentUser(currentRequest) : "anonymous";
                auditService.logProxyFetch(
                        user, repo.name(), path, upstreamUrl, repo.format(), content.contentLength(), ip, fetchDuration);
            }

            return Optional.of(cached);
        } catch (UpstreamTimeoutException e) {
            log.warn("Upstream timeout for repo={} url={}: {}", repo.name(), upstreamUrl, e.getMessage());
            return Optional.of(
                    new FormatResponse.ErrorResponse(502, "Upstream timeout for path: " + path));
        } catch (IOException e) {
            log.error("Failed to fetch from upstream for repo={} url={}: {}", repo.name(), upstreamUrl, e.getMessage());
            return Optional.of(new FormatResponse.ErrorResponse(502, "Failed to fetch from remote: " + e.getMessage()));
        }
    }

    /**
     * Closes an upstream response body, tolerating a {@code null} stream.
     *
     * <p>The JDK {@link java.net.http.HttpClient} normally yields a non-null (possibly empty)
     * body for every status code. However, when fetching through a forward HTTP proxy, certain
     * proxy-mediated responses (e.g. {@code 407 Proxy Authentication Required}, proxy-injected
     * error pages, or some redirect/tunnel edge cases) can surface a {@code RemoteResponse} whose
     * {@code body()} is {@code null}. Guarding here keeps the error-handling branches from turning
     * a clean non-2xx upstream status into an internal {@code 500} NPE.
     */
    private static void closeQuietly(InputStream body) {
        if (body == null) {
            return;
        }
        try {
            body.close();
        } catch (IOException e) {
            log.debug("Failed to close upstream response body: {}", e.getMessage());
        }
    }

    private HttpServletRequest resolveCurrentRequest() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                return attrs.getRequest();
            }
        } catch (Exception e) {
            log.debug("Could not resolve current request for audit log", e);
        }
        return null;
    }

    private record ProxyAuth(String username, String password) {}
}
