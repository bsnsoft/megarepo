package de.bsnsoft.megarepo.format.npm;

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
import de.bsnsoft.megarepo.format.npm.publish.NpmPublishHandler;
import de.bsnsoft.megarepo.format.npm.registry.NpmPackageMetadataBuilder;
import de.bsnsoft.megarepo.repository.proxy.ProxyCacheChecker;
import de.bsnsoft.megarepo.repository.proxy.ProxyFetchService;
import de.bsnsoft.megarepo.storage.BlobStoreManager;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class NpmRequestHandler implements FormatRequestHandler {

    private static final Logger log = LoggerFactory.getLogger(NpmRequestHandler.class);
    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private final AssetJpaRepository assetRepository;
    private final BlobStoreManager blobStoreManager;
    private final NpmPackageMetadataBuilder metadataBuilder;
    private final NpmPublishHandler publishHandler;
    private final NpmCoordinateExtractor coordinateExtractor;

    @Autowired(required = false)
    private ProxyFetchService proxyFetchService;

    @Autowired(required = false)
    private ProxyCacheChecker proxyCacheChecker;

    public NpmRequestHandler(
            AssetJpaRepository assetRepository,
            BlobStoreManager blobStoreManager,
            NpmPackageMetadataBuilder metadataBuilder,
            NpmPublishHandler publishHandler,
            NpmCoordinateExtractor coordinateExtractor) {
        this.assetRepository = assetRepository;
        this.blobStoreManager = blobStoreManager;
        this.metadataBuilder = metadataBuilder;
        this.publishHandler = publishHandler;
        this.coordinateExtractor = coordinateExtractor;
    }

    @Override
    public FormatResponse handleHostedGet(RepositoryConfig repo, String path, HttpServletRequest request) {
        if (isTarballPath(path)) {
            return lookupAsset(repo, path);
        }

        // Metadata request: package name lookup
        String packageName = extractPackageName(path);
        if (packageName == null || packageName.isBlank()) {
            return new NotFoundResponse("Invalid package path: " + path);
        }

        String baseUrl = resolveBaseUrl(request);
        return metadataBuilder.buildMetadata(repo, packageName, baseUrl);
    }

    @Override
    public FormatResponse handleHostedPut(RepositoryConfig repo, String path, HttpServletRequest request) {
        String packageName = extractPackageName(path);
        if (packageName == null || packageName.isBlank()) {
            return new FormatResponse.ErrorResponse(400, "Invalid package path: " + path);
        }

        return publishHandler.handlePublish(repo, packageName, request);
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
        // Check local cache first
        if (isTarballPath(path)) {
            Optional<AssetEntity> cachedAsset = assetRepository.findByRepositoryIdAndPath(repo.id(), path);
            if (cachedAsset.isPresent() && cachedAsset.get().getBlobRef() != null) {
                AssetEntity asset = cachedAsset.get();
                boolean expired = proxyCacheChecker != null && proxyCacheChecker.isExpired(asset, repo);
                if (!expired) {
                    log.debug("npm proxy cache hit for repo={} path={}", repo.name(), path);
                    return serveCachedAsset(repo, asset);
                }
            }
        } else {
            // Metadata: shorter cache time
            Optional<AssetEntity> cachedAsset = assetRepository.findByRepositoryIdAndPath(repo.id(), path);
            if (cachedAsset.isPresent() && cachedAsset.get().getBlobRef() != null) {
                AssetEntity asset = cachedAsset.get();
                boolean expired = proxyCacheChecker != null && proxyCacheChecker.isMetadataExpired(asset, repo);
                if (!expired) {
                    log.debug("npm proxy metadata cache hit for repo={} path={}", repo.name(), path);
                    return serveCachedAsset(repo, asset);
                }
            }
        }

        // Delegate to ProxyFetchService for remote fetch
        if (proxyFetchService != null) {
            // Determine if we have any stale cached asset to fall back on
            Optional<AssetEntity> staleCandidate = isTarballPath(path)
                    ? assetRepository.findByRepositoryIdAndPath(repo.id(), path)
                    : assetRepository.findByRepositoryIdAndPath(repo.id(), path);
            Optional<FormatResponse> fetched = proxyFetchService.fetchAndCache(repo, path, coordinateExtractor);
            if (fetched.isPresent()) {
                FormatResponse response = fetched.get();
                // Stale-while-revalidate: if upstream returned an error but we have stale cache, serve stale
                if (response instanceof ErrorResponse err
                        && err.statusCode() >= 500
                        && staleCandidate.isPresent()
                        && staleCandidate.get().getBlobRef() != null) {
                    log.info(
                            "Upstream error ({}), serving stale cache for repo={} path={}",
                            err.statusCode(),
                            repo.name(),
                            path);
                    return serveCachedAsset(repo, staleCandidate.get());
                }
                return response;
            }
            return new NotFoundResponse("Asset not found on remote: " + path);
        }

        return lookupAsset(repo, path);
    }

    @Override
    public FormatResponse handleGroupGet(RepositoryConfig repo, String path, HttpServletRequest request) {
        // Group logic is handled by GroupHandler in the router.
        // This method serves as fallback: just do local cache lookup.
        return lookupAsset(repo, path);
    }

    @Override
    public boolean isMetadataPath(String path) {
        return !isTarballPath(path);
    }

    @Override
    public Optional<FormatResponse> mergeMetadata(
            RepositoryConfig groupRepo, String path, List<FormatResponse> memberResponses) {
        // npm metadata merging could be implemented for group repositories
        // For now, return the first successful response
        return Optional.empty();
    }

    private boolean isTarballPath(String path) {
        if (path == null) {
            return false;
        }
        // Matches: -/pkg-1.0.0.tgz, /-/pkg-1.0.0.tgz, @scope/pkg/-/pkg-1.0.0.tgz
        return (path.contains("/-/") || path.startsWith("-/")) && path.endsWith(".tgz");
    }

    /**
     * Extracts the npm package name from the request path.
     * Examples:
     * - "lodash" -> "lodash"
     * - "@scope/package" -> "@scope/package"
     * - "@scope/package/-/package-1.0.0.tgz" -> "@scope/package"
     * - "-/lodash-1.0.0.tgz" -> null (tarball paths should not be used for publish)
     */
    String extractPackageName(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }

        String normalized = path.startsWith("/") ? path.substring(1) : path;
        if (normalized.isEmpty() || normalized.equals("-")) {
            return null;
        }

        // If it starts with "-/", this is a tarball path without a package context
        if (normalized.startsWith("-/")) {
            return null;
        }

        // For scoped packages: @scope/name or @scope/name/-/...
        if (normalized.startsWith("@")) {
            int tarballSeparator = normalized.indexOf("/-/");
            if (tarballSeparator >= 0) {
                return normalized.substring(0, tarballSeparator);
            }
            // Just @scope/name
            return normalized;
        }

        // For unscoped packages: name or name/-/...
        int tarballSeparator = normalized.indexOf("/-/");
        if (tarballSeparator >= 0) {
            return normalized.substring(0, tarballSeparator);
        }

        // Remove trailing slash if present
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        // Simple package name (no slashes expected for unscoped)
        if (!normalized.contains("/")) {
            return normalized;
        }

        return null;
    }

    private String resolveBaseUrl(HttpServletRequest request) {
        String scheme = request.getScheme();
        String serverName = request.getServerName();
        int serverPort = request.getServerPort();

        // Check for forwarded headers
        String forwardedProto = request.getHeader("X-Forwarded-Proto");
        String forwardedHost = request.getHeader("X-Forwarded-Host");

        if (forwardedProto != null) {
            scheme = forwardedProto;
        }
        if (forwardedHost != null) {
            serverName = forwardedHost;
            serverPort = -1; // host header may include port
        }

        if (serverPort == 80 || serverPort == 443 || serverPort <= 0) {
            return scheme + "://" + serverName;
        }
        return scheme + "://" + serverName + ":" + serverPort;
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
