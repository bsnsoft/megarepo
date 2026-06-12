package de.bsnsoft.megarepo.format.raw;

import de.bsnsoft.megarepo.core.format.ComponentCoordinates;
import de.bsnsoft.megarepo.core.format.FormatRequestHandler;
import de.bsnsoft.megarepo.core.format.FormatResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.ContentResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.CreatedResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.ErrorResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.NotFoundResponse;
import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.core.storage.Blob;
import de.bsnsoft.megarepo.core.storage.BlobRef;
import de.bsnsoft.megarepo.core.storage.BlobStore;
import de.bsnsoft.megarepo.database.entity.AssetEntity;
import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import de.bsnsoft.megarepo.database.repository.AssetJpaRepository;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import de.bsnsoft.megarepo.repository.proxy.ProxyCacheChecker;
import de.bsnsoft.megarepo.repository.proxy.ProxyFetchService;
import de.bsnsoft.megarepo.storage.BlobStoreManager;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLConnection;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class RawRequestHandler implements FormatRequestHandler {

    private static final Logger log = LoggerFactory.getLogger(RawRequestHandler.class);
    private static final String FORMAT = "raw";
    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private final AssetJpaRepository assetRepository;
    private final ComponentJpaRepository componentRepository;
    private final BlobStoreManager blobStoreManager;
    private final RawCoordinateExtractor coordinateExtractor;

    @Autowired(required = false)
    private ProxyFetchService proxyFetchService;

    @Autowired(required = false)
    private ProxyCacheChecker proxyCacheChecker;

    public RawRequestHandler(
            AssetJpaRepository assetRepository,
            ComponentJpaRepository componentRepository,
            BlobStoreManager blobStoreManager,
            RawCoordinateExtractor coordinateExtractor) {
        this.assetRepository = assetRepository;
        this.componentRepository = componentRepository;
        this.blobStoreManager = blobStoreManager;
        this.coordinateExtractor = coordinateExtractor;
    }

    @Override
    public FormatResponse handleHostedGet(RepositoryConfig repo, String path, HttpServletRequest request) {
        return lookupAsset(repo, path);
    }

    @Override
    public FormatResponse handleHostedPut(RepositoryConfig repo, String path, HttpServletRequest request) {
        try {
            return putContent(
                    repo,
                    path,
                    request.getInputStream(),
                    request.getContentLengthLong(),
                    determineContentType(request, path),
                    request.getRemoteUser(),
                    request.getRemoteAddr());
        } catch (IOException e) {
            return new ErrorResponse(500, "Failed to read upload: " + e.getMessage());
        }
    }

    /**
     * Stores content at the given repository path. Servlet-independent so the
     * manual upload path ({@link RawUploadHandler}) can reuse it.
     */
    public FormatResponse putContent(
            RepositoryConfig repo,
            String path,
            InputStream inputStream,
            long contentLength,
            String contentType,
            String username,
            String clientIp) {
        try {
            Optional<ComponentCoordinates> coordinates = coordinateExtractor.extractFromPath(path);
            if (coordinates.isEmpty()) {
                return new ErrorResponse(400, "Invalid path: " + path);
            }

            ComponentCoordinates coords = coordinates.get();
            ComponentEntity component = findOrCreateComponent(repo, coords);

            BlobStore blobStore = blobStoreManager.get(repo.blobStoreName());
            Map<String, String> headers = Map.of("Content-Type", contentType);
            BlobRef blobRef;

            if (contentLength > 0) {
                blobRef = blobStore.store(inputStream, contentLength, headers);
            } else {
                blobRef = blobStore.store(inputStream, headers);
            }

            Optional<Blob> storedBlob = blobStore.get(blobRef);
            if (storedBlob.isEmpty()) {
                return new ErrorResponse(500, "Failed to store blob");
            }

            Instant now = Instant.now();
            try (Blob blob = storedBlob.get()) {
                AssetEntity asset =
                        assetRepository.findByRepositoryIdAndPath(repo.id(), path).orElseGet(() -> {
                            var newAsset = new AssetEntity();
                            newAsset.setRepositoryId(repo.id());
                            newAsset.setPath(path);
                            newAsset.setFormat(FORMAT);
                            newAsset.setCreatedAt(now);
                            return newAsset;
                        });

                asset.setComponentId(component.getId());
                asset.setBlobRef(blobRef.toExternalForm());
                asset.setContentType(contentType);
                asset.setSize(blob.properties().size());
                asset.setLastModified(now);
                asset.setUpdatedAt(now);
                asset.setCreatedBy(username);
                asset.setCreatedByIp(clientIp);

                Map<String, String> checksums = blob.properties().checksums();
                if (checksums != null) {
                    asset.setChecksumMd5(checksums.get("md5"));
                    asset.setChecksumSha1(checksums.get("sha1"));
                    asset.setChecksumSha256(checksums.get("sha256"));
                    asset.setChecksumSha512(checksums.get("sha512"));
                }

                assetRepository.save(asset);
            }

            return new CreatedResponse(path, Map.of());

        } catch (IOException e) {
            return new ErrorResponse(500, "Failed to read upload: " + e.getMessage());
        }
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
        Optional<AssetEntity> cachedAsset = assetRepository.findByRepositoryIdAndPath(repo.id(), path);
        if (cachedAsset.isPresent() && cachedAsset.get().getBlobRef() != null) {
            AssetEntity asset = cachedAsset.get();
            boolean expired = proxyCacheChecker != null && proxyCacheChecker.isExpired(asset, repo);
            if (!expired) {
                log.debug("Proxy cache hit for repo={} path={}", repo.name(), path);
                return serveCachedAsset(repo, asset);
            }
            log.debug("Proxy cache expired for repo={} path={}", repo.name(), path);
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
                    return serveCachedAsset(repo, cachedAsset.get());
                }
                return response;
            }
            return new NotFoundResponse("Asset not found on remote: " + path);
        }

        // Fallback: if ProxyFetchService not available, just do local lookup
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
        return false;
    }

    @Override
    public Optional<FormatResponse> mergeMetadata(
            RepositoryConfig groupRepo, String path, List<FormatResponse> memberResponses) {
        return Optional.empty();
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

    private ComponentEntity findOrCreateComponent(RepositoryConfig repo, ComponentCoordinates coords) {
        return componentRepository
                .findByRepositoryIdAndNamespaceAndNameAndVersion(
                        repo.id(), coords.namespace(), coords.name(), coords.version())
                .orElseGet(() -> {
                    var component = new ComponentEntity();
                    component.setRepositoryId(repo.id());
                    component.setFormat(FORMAT);
                    component.setNamespace(coords.namespace());
                    component.setName(coords.name());
                    component.setVersion(coords.version());

                    Instant now = Instant.now();
                    component.setCreatedAt(now);
                    component.setUpdatedAt(now);
                    return componentRepository.save(component);
                });
    }

    private String determineContentType(HttpServletRequest request, String path) {
        String requestContentType = request.getContentType();
        if (requestContentType != null && !requestContentType.isBlank()) {
            return requestContentType;
        }

        String guessed = URLConnection.guessContentTypeFromName(path);
        if (guessed != null) {
            return guessed;
        }

        return DEFAULT_CONTENT_TYPE;
    }
}
