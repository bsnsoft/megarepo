package de.bsnsoft.megarepo.format.pypi;

import de.bsnsoft.megarepo.core.format.FormatRequestHandler;
import de.bsnsoft.megarepo.core.format.FormatResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.ContentResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.ErrorResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.NotFoundResponse;
import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.core.storage.Blob;
import de.bsnsoft.megarepo.core.storage.BlobRef;
import de.bsnsoft.megarepo.core.storage.BlobStore;
import de.bsnsoft.megarepo.database.entity.AssetEntity;
import de.bsnsoft.megarepo.database.repository.AssetJpaRepository;
import de.bsnsoft.megarepo.format.pypi.simple.PypiProxyUrlRewriter;
import de.bsnsoft.megarepo.format.pypi.simple.SimpleIndexGenerator;
import de.bsnsoft.megarepo.format.pypi.simple.SimplePackagePageGenerator;
import de.bsnsoft.megarepo.format.pypi.upload.PypiUploadHandler;
import de.bsnsoft.megarepo.repository.proxy.ProxyCacheChecker;
import de.bsnsoft.megarepo.repository.proxy.ProxyFetchService;
import de.bsnsoft.megarepo.storage.BlobStoreManager;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class PypiRequestHandler implements FormatRequestHandler {

    private static final Logger log = LoggerFactory.getLogger(PypiRequestHandler.class);
    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private static final String ATTR_UPSTREAM_URL = "upstreamUrl";

    private final AssetJpaRepository assetRepository;
    private final BlobStoreManager blobStoreManager;
    private final SimpleIndexGenerator simpleIndexGenerator;
    private final SimplePackagePageGenerator simplePackagePageGenerator;
    private final PypiUploadHandler uploadHandler;
    private final PypiCoordinateExtractor coordinateExtractor;
    private final PypiProxyUrlRewriter proxyUrlRewriter;

    @Autowired(required = false)
    private ProxyFetchService proxyFetchService;

    @Autowired(required = false)
    private ProxyCacheChecker proxyCacheChecker;

    public PypiRequestHandler(
            AssetJpaRepository assetRepository,
            BlobStoreManager blobStoreManager,
            SimpleIndexGenerator simpleIndexGenerator,
            SimplePackagePageGenerator simplePackagePageGenerator,
            PypiUploadHandler uploadHandler,
            PypiCoordinateExtractor coordinateExtractor,
            PypiProxyUrlRewriter proxyUrlRewriter) {
        this.assetRepository = assetRepository;
        this.blobStoreManager = blobStoreManager;
        this.simpleIndexGenerator = simpleIndexGenerator;
        this.simplePackagePageGenerator = simplePackagePageGenerator;
        this.uploadHandler = uploadHandler;
        this.coordinateExtractor = coordinateExtractor;
        this.proxyUrlRewriter = proxyUrlRewriter;
    }

    @Override
    public FormatResponse handleHostedGet(RepositoryConfig repo, String path, HttpServletRequest request) {
        return routeGet(repo, path);
    }

    @Override
    public FormatResponse handleHostedPut(RepositoryConfig repo, String path, HttpServletRequest request) {
        // PyPI uses POST with multipart form data for uploads (twine).
        // The router dispatches write operations via handleHostedPut.
        return uploadHandler.handleUpload(repo, request);
    }

    @Override
    public FormatResponse handleHostedDelete(RepositoryConfig repo, String path, HttpServletRequest request) {
        Optional<AssetEntity> assetOpt = assetRepository.findByRepositoryIdAndPath(repo.id(), path);
        if (assetOpt.isEmpty()) {
            return new NotFoundResponse("Asset not found: " + path);
        }

        AssetEntity asset = assetOpt.get();

        if (asset.getBlobRef() != null) {
            BlobRef blobRef = BlobRef.parse(asset.getBlobRef());
            BlobStore blobStore = blobStoreManager.get(repo.blobStoreName());
            blobStore.delete(blobRef);
        }

        assetRepository.delete(asset);

        return new ContentResponse(InputStream.nullInputStream(), "application/json", 0, Map.of(), Map.of());
    }

    @Override
    public FormatResponse handleProxyGet(RepositoryConfig repo, String path, HttpServletRequest request) {
        String normalized = path.startsWith("/") ? path.substring(1) : path;

        // Package download: check for stored upstream URL and fetch from there
        if (normalized.startsWith("packages/")) {
            return handleProxyPackageDownload(repo, normalized);
        }

        // Check local cache first
        Optional<AssetEntity> cachedAsset = assetRepository.findByRepositoryIdAndPath(repo.id(), path);
        if (cachedAsset.isPresent() && cachedAsset.get().getBlobRef() != null) {
            AssetEntity asset = cachedAsset.get();
            boolean expired;
            if (isMetadataPath(path)) {
                expired = proxyCacheChecker != null && proxyCacheChecker.isMetadataExpired(asset, repo);
            } else {
                expired = proxyCacheChecker != null && proxyCacheChecker.isExpired(asset, repo);
            }
            if (!expired) {
                log.debug("PyPI proxy cache hit for repo={} path={}", repo.name(), path);
                if (isMetadataPath(path)) {
                    return serveCachedMetadata(repo, asset);
                }
                return serveCachedAsset(repo, asset);
            }
            log.debug("PyPI proxy cache expired for repo={} path={}", repo.name(), path);
        }

        // Delegate to ProxyFetchService for remote fetch
        if (proxyFetchService != null) {
            Optional<FormatResponse> fetched = proxyFetchService.fetchAndCache(repo, path, coordinateExtractor);
            if (fetched.isPresent()) {
                FormatResponse response = fetched.get();
                // Stale-while-revalidate: if upstream returned an error but we have stale cache, serve stale
                if (response instanceof ErrorResponse err
                        && err.statusCode() >= 500
                        && cachedAsset.isPresent()
                        && cachedAsset.get().getBlobRef() != null) {
                    log.info(
                            "Upstream error ({}), serving stale cache for repo={} path={}",
                            err.statusCode(),
                            repo.name(),
                            path);
                    if (isMetadataPath(path)) {
                        return serveCachedMetadata(repo, cachedAsset.get());
                    }
                    return serveCachedAsset(repo, cachedAsset.get());
                }
                // Rewrite URLs in simple index pages so downloads go through MegaRepo
                if (isMetadataPath(path) && response instanceof ContentResponse content) {
                    return rewriteProxyMetadataResponse(repo, path, content);
                }
                return response;
            }
            return new NotFoundResponse("Asset not found on remote: " + path);
        }

        // Fallback: local lookup
        return lookupAsset(repo, path);
    }

    @Override
    public FormatResponse handleGroupGet(RepositoryConfig repo, String path, HttpServletRequest request) {
        // Group logic is handled by GroupHandler in the router.
        // This method serves as fallback: just do local cache lookup.
        return routeGet(repo, path);
    }

    @Override
    public boolean isMetadataPath(String path) {
        if (path == null) {
            return false;
        }
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        return normalized.equals("simple")
                || normalized.equals("simple/")
                || normalized.startsWith("simple/");
    }

    @Override
    public Optional<FormatResponse> mergeMetadata(
            RepositoryConfig groupRepo, String path, List<FormatResponse> memberResponses) {
        // PyPI simple index merging is complex; for now, return empty to let the router
        // fall back to first-hit behavior
        return Optional.empty();
    }

    /**
     * Rewrites a freshly-fetched simple index response: replaces upstream download URLs
     * with relative paths through MegaRepo and stores the URL mappings as asset attributes.
     */
    private FormatResponse rewriteProxyMetadataResponse(
            RepositoryConfig repo, String path, ContentResponse content) {
        try {
            byte[] originalBytes = content.content().readAllBytes();
            String html = new String(originalBytes, StandardCharsets.UTF_8);

            PypiProxyUrlRewriter.RewriteResult result = proxyUrlRewriter.rewrite(html);

            // Store URL mappings as attributes on placeholder assets so we can
            // resolve the original URL when a package download is requested
            storeUpstreamUrlMappings(repo, result.urlMappings());

            // Re-cache the rewritten HTML over the original cached asset
            updateCachedMetadata(repo, path, result.rewrittenHtml());

            byte[] rewrittenBytes = result.rewrittenHtml().getBytes(StandardCharsets.UTF_8);
            return new ContentResponse(
                    new ByteArrayInputStream(rewrittenBytes),
                    "text/html;charset=utf-8",
                    rewrittenBytes.length,
                    Map.of(),
                    Map.of());
        } catch (Exception e) {
            log.warn("Failed to rewrite proxy metadata for repo={} path={}: {}", repo.name(), path, e.getMessage());
            // Fall back to serving the original un-rewritten response
            return content;
        }
    }

    /**
     * Serves cached metadata (simple index pages) with URL rewriting applied.
     * The cached blob already contains rewritten HTML from the initial fetch.
     */
    private FormatResponse serveCachedMetadata(RepositoryConfig repo, AssetEntity asset) {
        return serveCachedAsset(repo, asset);
    }

    /**
     * Updates the cached blob for a metadata asset with rewritten HTML content.
     */
    private void updateCachedMetadata(RepositoryConfig repo, String path, String rewrittenHtml) {
        try {
            Optional<AssetEntity> assetOpt = assetRepository.findByRepositoryIdAndPath(repo.id(), path);
            if (assetOpt.isPresent()) {
                AssetEntity asset = assetOpt.get();
                byte[] bytes = rewrittenHtml.getBytes(StandardCharsets.UTF_8);

                BlobStore blobStore = blobStoreManager.get(repo.blobStoreName());

                // Delete old blob if it exists
                if (asset.getBlobRef() != null) {
                    blobStore.delete(BlobRef.parse(asset.getBlobRef()));
                }

                // Store rewritten content
                BlobRef newRef = blobStore.store(
                        new ByteArrayInputStream(bytes), bytes.length, Map.of("Content-Type", "text/html"));
                asset.setBlobRef(newRef.toExternalForm());
                asset.setSize((long) bytes.length);
                asset.setContentType("text/html;charset=utf-8");
                asset.setUpdatedAt(Instant.now());
                assetRepository.save(asset);
            }
        } catch (Exception e) {
            log.warn("Failed to update cached metadata for repo={} path={}: {}", repo.name(), path, e.getMessage());
        }
    }

    /**
     * Stores the mapping of package filename to original upstream URL as asset attributes.
     * Creates placeholder asset entries (without blobs) that will be populated when
     * the actual package is downloaded.
     */
    private void storeUpstreamUrlMappings(RepositoryConfig repo, Map<String, String> urlMappings) {
        Instant now = Instant.now();
        for (Map.Entry<String, String> entry : urlMappings.entrySet()) {
            String filename = entry.getKey();
            String upstreamUrl = entry.getValue();
            String assetPath = "packages/" + filename;

            Optional<AssetEntity> existing = assetRepository.findByRepositoryIdAndPath(repo.id(), assetPath);
            if (existing.isPresent()) {
                // Update the upstream URL attribute in case it changed
                AssetEntity asset = existing.get();
                Map<String, Object> attrs = new HashMap<>(asset.getAttributes());
                attrs.put(ATTR_UPSTREAM_URL, upstreamUrl);
                asset.setAttributes(attrs);
                asset.setUpdatedAt(now);
                assetRepository.save(asset);
            } else {
                // Create a placeholder asset with the upstream URL stored
                var asset = new AssetEntity();
                asset.setRepositoryId(repo.id());
                asset.setPath(assetPath);
                asset.setFormat(repo.format());
                asset.setCreatedAt(now);
                asset.setLastModified(now);
                asset.setUpdatedAt(now);
                asset.setCreatedBy("proxy");
                Map<String, Object> attrs = new HashMap<>();
                attrs.put(ATTR_UPSTREAM_URL, upstreamUrl);
                asset.setAttributes(attrs);
                assetRepository.save(asset);
            }
        }
        log.debug("Stored {} upstream URL mappings for repo={}", urlMappings.size(), repo.name());
    }

    /**
     * Handles proxy package download by fetching from the stored upstream URL.
     * If the package is already cached, serves from cache. Otherwise, fetches
     * from the original upstream URL (e.g., files.pythonhosted.org), caches it,
     * and serves it.
     */
    private FormatResponse handleProxyPackageDownload(RepositoryConfig repo, String path) {
        // Check cache first
        Optional<AssetEntity> cachedAsset = assetRepository.findByRepositoryIdAndPath(repo.id(), path);
        if (cachedAsset.isPresent()) {
            AssetEntity asset = cachedAsset.get();

            // If blob exists and not expired, serve from cache
            if (asset.getBlobRef() != null) {
                boolean expired = proxyCacheChecker != null && proxyCacheChecker.isExpired(asset, repo);
                if (!expired) {
                    log.debug("PyPI proxy package cache hit for repo={} path={}", repo.name(), path);
                    return serveCachedAsset(repo, asset);
                }
            }

            // Get the upstream URL from asset attributes
            String upstreamUrl = getUpstreamUrl(asset);
            if (upstreamUrl != null && proxyFetchService != null) {
                log.debug("Fetching PyPI package from upstream: {}", upstreamUrl);
                return fetchPackageFromUpstream(repo, path, upstreamUrl);
            }
        }

        // No cached asset with upstream URL - try standard proxy fetch as fallback
        if (proxyFetchService != null) {
            Optional<FormatResponse> fetched = proxyFetchService.fetchAndCache(repo, path, coordinateExtractor);
            if (fetched.isPresent()) {
                return fetched.get();
            }
        }

        return new NotFoundResponse("Package not found: " + path);
    }

    /**
     * Fetches a package file directly from the upstream URL, caches it, and serves it.
     */
    private FormatResponse fetchPackageFromUpstream(RepositoryConfig repo, String path, String upstreamUrl) {
        return proxyFetchService.fetchFromUrl(repo, path, upstreamUrl, coordinateExtractor);
    }

    @SuppressWarnings("unchecked")
    private String getUpstreamUrl(AssetEntity asset) {
        Map<String, Object> attrs = asset.getAttributes();
        if (attrs != null) {
            Object url = attrs.get(ATTR_UPSTREAM_URL);
            if (url instanceof String s) {
                return s;
            }
        }
        return null;
    }

    private FormatResponse routeGet(RepositoryConfig repo, String path) {
        if (path == null || path.isBlank()) {
            return new NotFoundResponse("Empty path");
        }

        String normalized = path.startsWith("/") ? path.substring(1) : path;

        // Simple index: /simple/ or /simple
        if (normalized.equals("simple") || normalized.equals("simple/")) {
            return simpleIndexGenerator.generate(repo);
        }

        // Package page: /simple/{package}/ or /simple/{package}
        if (normalized.startsWith("simple/")) {
            String remainder = normalized.substring("simple/".length());
            // Strip trailing slash
            String packageName = remainder.endsWith("/") ? remainder.substring(0, remainder.length() - 1) : remainder;
            if (!packageName.isEmpty() && !packageName.contains("/")) {
                return simplePackagePageGenerator.generate(repo, packageName);
            }
        }

        // Package download: /packages/{filename}
        if (normalized.startsWith("packages/")) {
            return lookupAsset(repo, normalized);
        }

        return new NotFoundResponse("Not found: " + path);
    }

    private FormatResponse lookupAsset(RepositoryConfig repo, String path) {
        Optional<AssetEntity> assetOpt = assetRepository.findByRepositoryIdAndPath(repo.id(), path);
        if (assetOpt.isEmpty()) {
            return new NotFoundResponse("Asset not found: " + path);
        }

        AssetEntity asset = assetOpt.get();
        if (asset.getBlobRef() == null) {
            return new NotFoundResponse("Asset has no content: " + path);
        }

        return serveCachedAsset(repo, asset);
    }

    private FormatResponse serveCachedAsset(RepositoryConfig repo, AssetEntity asset) {
        BlobRef blobRef = BlobRef.parse(asset.getBlobRef());
        BlobStore blobStore = blobStoreManager.get(repo.blobStoreName());
        Optional<Blob> blobOpt = blobStore.get(blobRef);

        if (blobOpt.isEmpty()) {
            return new NotFoundResponse("Blob not found for cached asset: " + asset.getPath());
        }

        Blob blob = blobOpt.get();
        asset.setLastDownloaded(Instant.now());
        assetRepository.save(asset);

        String contentType = asset.getContentType() != null ? asset.getContentType() : DEFAULT_CONTENT_TYPE;
        long size = asset.getSize() != null ? asset.getSize() : blob.properties().size();

        Map<String, String> checksums = buildChecksumMap(asset);
        return new ContentResponse(blob.inputStream(), contentType, size, Map.of(), checksums);
    }

    private Map<String, String> buildChecksumMap(AssetEntity asset) {
        Map<String, String> checksums = new HashMap<>();
        if (asset.getChecksumMd5() != null) {
            checksums.put("md5", asset.getChecksumMd5());
        }
        if (asset.getChecksumSha1() != null) {
            checksums.put("sha1", asset.getChecksumSha1());
        }
        if (asset.getChecksumSha256() != null) {
            checksums.put("sha256", asset.getChecksumSha256());
        }
        if (asset.getChecksumSha512() != null) {
            checksums.put("sha512", asset.getChecksumSha512());
        }
        return checksums;
    }
}
