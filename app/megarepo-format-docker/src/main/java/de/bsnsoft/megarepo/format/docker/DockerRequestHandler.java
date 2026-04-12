package de.bsnsoft.megarepo.format.docker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.bsnsoft.megarepo.core.format.ComponentCoordinates;
import de.bsnsoft.megarepo.core.format.FormatRequestHandler;
import de.bsnsoft.megarepo.core.format.FormatResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.ContentResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.CreatedResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.ErrorResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.ErrorResponseWithHeaders;
import de.bsnsoft.megarepo.core.format.FormatResponse.NotFoundResponse;
import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.core.storage.Blob;
import de.bsnsoft.megarepo.core.storage.BlobRef;
import de.bsnsoft.megarepo.core.storage.BlobStore;
import de.bsnsoft.megarepo.database.entity.AssetEntity;
import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import de.bsnsoft.megarepo.database.repository.AssetJpaRepository;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import de.bsnsoft.megarepo.format.docker.model.DockerManifestList;
import de.bsnsoft.megarepo.repository.proxy.ProxyCacheChecker;
import de.bsnsoft.megarepo.security.auth.JwtTokenProvider;
import de.bsnsoft.megarepo.storage.BlobStoreManager;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Handles Docker Registry V2 API requests.
 *
 * <p>The Docker registry protocol uses a different path structure than typical artifact repositories.
 * All paths are under {@code v2/} and use the following patterns:
 * <ul>
 *   <li>{@code v2/} - API version check</li>
 *   <li>{@code v2/_catalog} - list repositories</li>
 *   <li>{@code v2/{name}/tags/list} - list tags for an image</li>
 *   <li>{@code v2/{name}/manifests/{reference}} - pull/push manifest</li>
 *   <li>{@code v2/{name}/blobs/{digest}} - pull blob (layer/config)</li>
 *   <li>{@code v2/{name}/blobs/uploads/} - initiate blob upload</li>
 *   <li>{@code v2/{name}/blobs/uploads/{uuid}} - chunked upload / complete upload</li>
 * </ul>
 */
@Component
public class DockerRequestHandler implements FormatRequestHandler {

    private static final Logger log = LoggerFactory.getLogger(DockerRequestHandler.class);
    private static final String FORMAT = "docker";

    // Path patterns for Docker V2 API (paths arrive without leading slash)
    private static final Pattern V2_BASE = Pattern.compile("^v2/?$");
    private static final Pattern V2_TOKEN = Pattern.compile("^v2/token$");
    private static final Pattern V2_CATALOG = Pattern.compile("^v2/_catalog$");
    private static final Pattern V2_TAGS_LIST = Pattern.compile("^v2/(.+)/tags/list$");
    private static final Pattern V2_MANIFESTS = Pattern.compile("^v2/(.+)/manifests/(.+)$");
    private static final Pattern V2_BLOBS = Pattern.compile("^v2/(.+)/blobs/(sha256:.+)$");
    private static final Pattern V2_BLOB_UPLOAD_INIT = Pattern.compile("^v2/(.+)/blobs/uploads/?$");
    private static final Pattern V2_BLOB_UPLOAD = Pattern.compile("^v2/(.+)/blobs/uploads/([a-f0-9\\-]+)$");

    private final AssetJpaRepository assetRepository;
    private final ComponentJpaRepository componentRepository;
    private final BlobStoreManager blobStoreManager;
    private final DockerCoordinateExtractor coordinateExtractor;
    private final ObjectMapper objectMapper;
    private final JwtTokenProvider jwtTokenProvider;
    private final AuthenticationProvider authenticationProvider;

    @Autowired(required = false)
    private DockerProxyClient dockerProxyClient;

    @Autowired(required = false)
    private ProxyCacheChecker proxyCacheChecker;

    /**
     * In-memory tracking of in-progress blob uploads. Maps upload UUID to the temp file
     * holding partial data. Abandoned uploads are cleaned up after {@link #UPLOAD_TTL}.
     */
    private final ConcurrentHashMap<String, UploadState> activeUploads = new ConcurrentHashMap<>();

    private static final Duration UPLOAD_TTL = Duration.ofHours(1);

    private record UploadState(UUID repositoryId, String imageName, Path tempFile, long bytesReceived, Instant createdAt) {}

    public DockerRequestHandler(
            AssetJpaRepository assetRepository,
            ComponentJpaRepository componentRepository,
            BlobStoreManager blobStoreManager,
            DockerCoordinateExtractor coordinateExtractor,
            JwtTokenProvider jwtTokenProvider,
            AuthenticationProvider authenticationProvider) {
        this.assetRepository = assetRepository;
        this.componentRepository = componentRepository;
        this.blobStoreManager = blobStoreManager;
        this.coordinateExtractor = coordinateExtractor;
        this.jwtTokenProvider = jwtTokenProvider;
        this.authenticationProvider = authenticationProvider;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public FormatResponse handleHostedGet(RepositoryConfig repo, String path, HttpServletRequest request) {
        log.debug("Docker GET: repo={} path={} method={}", repo.name(), path, request.getMethod());

        // HEAD requests also route through handleHostedGet in the framework
        boolean isHead = "HEAD".equalsIgnoreCase(request.getMethod());

        // GET /v2/token - Docker token authentication endpoint
        if (V2_TOKEN.matcher(path).matches()) {
            return handleTokenRequest(repo, request);
        }

        // GET /v2/ - API version check (returns 401 challenge if unauthenticated)
        if (V2_BASE.matcher(path).matches()) {
            return apiVersionCheck(repo, request);
        }

        // GET /v2/_catalog - list repositories
        Matcher catalogMatcher = V2_CATALOG.matcher(path);
        if (catalogMatcher.matches()) {
            return listRepositories(repo);
        }

        // GET /v2/{name}/tags/list
        Matcher tagsMatcher = V2_TAGS_LIST.matcher(path);
        if (tagsMatcher.matches()) {
            String imageName = tagsMatcher.group(1);
            return listTags(repo, imageName);
        }

        // GET/HEAD /v2/{name}/manifests/{reference}
        Matcher manifestMatcher = V2_MANIFESTS.matcher(path);
        if (manifestMatcher.matches()) {
            String imageName = manifestMatcher.group(1);
            String reference = manifestMatcher.group(2);
            return getManifest(repo, imageName, reference, isHead);
        }

        // GET/HEAD /v2/{name}/blobs/{digest}
        Matcher blobMatcher = V2_BLOBS.matcher(path);
        if (blobMatcher.matches()) {
            String imageName = blobMatcher.group(1);
            String digest = blobMatcher.group(2);
            return getBlob(repo, imageName, digest, isHead);
        }

        return new NotFoundResponse("Unknown Docker registry endpoint: " + path);
    }

    @Override
    public FormatResponse handleHostedPut(RepositoryConfig repo, String path, HttpServletRequest request) {
        log.debug("Docker PUT: repo={} path={}", repo.name(), path);

        // PUT /v2/{name}/manifests/{reference} - push manifest
        Matcher manifestMatcher = V2_MANIFESTS.matcher(path);
        if (manifestMatcher.matches()) {
            String imageName = manifestMatcher.group(1);
            String reference = manifestMatcher.group(2);
            return putManifest(repo, imageName, reference, request);
        }

        // PUT /v2/{name}/blobs/uploads/{uuid}?digest=sha256:... - complete blob upload
        Matcher uploadMatcher = V2_BLOB_UPLOAD.matcher(path);
        if (uploadMatcher.matches()) {
            String imageName = uploadMatcher.group(1);
            String uploadUuid = uploadMatcher.group(2);
            String digest = request.getParameter("digest");
            if (digest != null) {
                return completeBlobUpload(repo, imageName, uploadUuid, digest, request);
            }
            // PUT without digest: chunked upload continuation
            return uploadBlobChunk(repo, imageName, uploadUuid, request);
        }

        // POST /v2/{name}/blobs/uploads/ - initiate blob upload
        // Note: POST requests may be routed to PUT handler depending on framework config
        Matcher uploadInitMatcher = V2_BLOB_UPLOAD_INIT.matcher(path);
        if (uploadInitMatcher.matches()) {
            String imageName = uploadInitMatcher.group(1);
            return initiateBlobUpload(repo, imageName, request);
        }

        return new ErrorResponse(405, "Method not allowed for path: " + path);
    }

    @Override
    public FormatResponse handleHostedDelete(RepositoryConfig repo, String path, HttpServletRequest request) {
        // DELETE /v2/{name}/manifests/{reference}
        Matcher manifestMatcher = V2_MANIFESTS.matcher(path);
        if (manifestMatcher.matches()) {
            String imageName = manifestMatcher.group(1);
            String reference = manifestMatcher.group(2);
            return deleteManifest(repo, imageName, reference);
        }

        // DELETE /v2/{name}/blobs/{digest}
        Matcher blobMatcher = V2_BLOBS.matcher(path);
        if (blobMatcher.matches()) {
            String digest = blobMatcher.group(2);
            return deleteBlob(repo, digest);
        }

        return new ErrorResponse(405, "Method not allowed for path: " + path);
    }

    @Override
    public FormatResponse handleProxyGet(RepositoryConfig repo, String path, HttpServletRequest request) {
        log.debug("Docker PROXY GET: repo={} path={}", repo.name(), path);

        boolean isHead = "HEAD".equalsIgnoreCase(request.getMethod());

        // GET /v2/token — proxy repos handle tokens locally (clients auth against MegaRepo, not upstream)
        if (V2_TOKEN.matcher(path).matches()) {
            return handleTokenRequest(repo, request);
        }

        // GET /v2/ — API version check (handled locally)
        if (V2_BASE.matcher(path).matches()) {
            return apiVersionCheck(repo, request);
        }

        // GET /v2/_catalog — list locally cached images
        if (V2_CATALOG.matcher(path).matches()) {
            return listRepositories(repo);
        }

        // GET /v2/{name}/tags/list — proxy to upstream for tag listing
        Matcher tagsMatcher = V2_TAGS_LIST.matcher(path);
        if (tagsMatcher.matches()) {
            String imageName = tagsMatcher.group(1);
            return proxyTagsList(repo, imageName);
        }

        // GET/HEAD /v2/{name}/manifests/{reference}
        Matcher manifestMatcher = V2_MANIFESTS.matcher(path);
        if (manifestMatcher.matches()) {
            String imageName = manifestMatcher.group(1);
            String reference = manifestMatcher.group(2);
            return proxyManifest(repo, imageName, reference, isHead);
        }

        // GET/HEAD /v2/{name}/blobs/{digest}
        Matcher blobMatcher = V2_BLOBS.matcher(path);
        if (blobMatcher.matches()) {
            String imageName = blobMatcher.group(1);
            String digest = blobMatcher.group(2);
            return proxyBlob(repo, imageName, digest, isHead);
        }

        return new NotFoundResponse("Unknown Docker registry endpoint: " + path);
    }

    @Override
    public FormatResponse handleGroupGet(RepositoryConfig repo, String path, HttpServletRequest request) {
        // Group not yet implemented for Docker
        return new ErrorResponse(501, "Docker group repositories are not yet supported");
    }

    @Override
    public boolean isMetadataPath(String path) {
        return V2_CATALOG.matcher(path).matches()
                || V2_TAGS_LIST.matcher(path).matches()
                || V2_BASE.matcher(path).matches()
                || V2_TOKEN.matcher(path).matches();
    }

    @Override
    public Optional<FormatResponse> mergeMetadata(
            RepositoryConfig groupRepo, String path, List<FormatResponse> memberResponses) {
        return Optional.empty();
    }

    // ---- Docker V2 API endpoint implementations ----

    /**
     * Handles {@code GET /v2/} — the Docker API version check.
     *
     * <p>If the request is authenticated (via JWT Bearer, Basic auth, or anonymous access),
     * returns 200 OK. Otherwise returns 401 with a {@code Www-Authenticate} header pointing
     * the Docker CLI to our token endpoint so it can exchange Basic credentials for a Bearer token.
     */
    private FormatResponse apiVersionCheck(RepositoryConfig repo, HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean authenticated = auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(auth.getPrincipal());

        if (!authenticated) {
            // Build the token endpoint URL from the incoming request
            String scheme = request.getHeader("X-Forwarded-Proto");
            if (scheme == null) {
                scheme = request.getScheme();
            }
            String host = request.getHeader("X-Forwarded-Host");
            if (host == null) {
                host = request.getHeader("Host");
            }
            if (host == null) {
                host = request.getServerName() + ":" + request.getServerPort();
            }
            String realm = scheme + "://" + host + "/repository/" + repo.name() + "/v2/token";
            String wwwAuthenticate =
                    "Bearer realm=\"%s\",service=\"megarepo\",scope=\"repository:%s:pull,push\"".formatted(realm, repo.name());

            return new ErrorResponseWithHeaders(
                    401,
                    "UNAUTHORIZED",
                    Map.of("Www-Authenticate", wwwAuthenticate, "Docker-Distribution-API-Version", "registry/2.0"),
                    "{\"errors\":[{\"code\":\"UNAUTHORIZED\",\"message\":\"authentication required\"}]}");
        }

        String body = "{}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = Map.of(
                "Docker-Distribution-API-Version", "registry/2.0");
        return new ContentResponse(
                new ByteArrayInputStream(bytes), "application/json", bytes.length, headers, Map.of());
    }

    /**
     * Handles {@code GET /v2/token} — the Docker token authentication endpoint.
     *
     * <p>Docker CLI sends Basic auth credentials (from {@code docker login}) to this endpoint.
     * We verify the credentials and return a JWT Bearer token that the CLI will use for
     * subsequent registry requests.
     */
    private FormatResponse handleTokenRequest(RepositoryConfig repo, HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");

        // Try to authenticate via Basic auth header
        if (authHeader != null && authHeader.startsWith("Basic ")) {
            try {
                String decoded = new String(
                        Base64.getDecoder().decode(authHeader.substring(6)), StandardCharsets.UTF_8);
                int colon = decoded.indexOf(':');
                if (colon > 0) {
                    String username = decoded.substring(0, colon);
                    String password = decoded.substring(colon + 1);

                    var authToken = new UsernamePasswordAuthenticationToken(username, password);
                    Authentication authenticated = authenticationProvider.authenticate(authToken);

                    if (authenticated.isAuthenticated()) {
                        Set<String> roles = authenticated.getAuthorities().stream()
                                .map(GrantedAuthority::getAuthority)
                                .filter(a -> a.startsWith("ROLE_"))
                                .map(a -> a.substring(5))
                                .collect(Collectors.toSet());
                        String token = jwtTokenProvider.generateAccessToken(username, roles);

                        String body =
                                "{\"token\":\"%s\",\"access_token\":\"%s\",\"expires_in\":3600}".formatted(token, token);
                        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                        return new ContentResponse(
                                new ByteArrayInputStream(bytes), "application/json", bytes.length, Map.of(), Map.of());
                    }
                }
            } catch (AuthenticationException e) {
                log.debug("Docker token auth failed: {}", e.getMessage());
            } catch (IllegalArgumentException e) {
                log.debug("Invalid Basic auth header for Docker token endpoint");
            }
        }

        // Already authenticated via JWT/session — issue a token for the current user
        Authentication existingAuth = SecurityContextHolder.getContext().getAuthentication();
        if (existingAuth != null && existingAuth.isAuthenticated()
                && !"anonymousUser".equals(existingAuth.getPrincipal())) {
            String username = existingAuth.getName();
            Set<String> roles = existingAuth.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .filter(a -> a.startsWith("ROLE_"))
                    .map(a -> a.substring(5))
                    .collect(Collectors.toSet());
            String token = jwtTokenProvider.generateAccessToken(username, roles);

            String body = "{\"token\":\"%s\",\"access_token\":\"%s\",\"expires_in\":3600}".formatted(token, token);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            return new ContentResponse(
                    new ByteArrayInputStream(bytes), "application/json", bytes.length, Map.of(), Map.of());
        }

        return new ErrorResponseWithHeaders(
                401,
                "UNAUTHORIZED",
                Map.of("Docker-Distribution-API-Version", "registry/2.0"),
                "{\"errors\":[{\"code\":\"UNAUTHORIZED\",\"message\":\"authentication required\"}]}");
    }

    private FormatResponse listRepositories(RepositoryConfig repo) {
        // Find all distinct image names by looking at manifest assets
        List<AssetEntity> manifests = assetRepository.findByRepositoryIdAndPathStartingWith(
                repo.id(), "v2/");

        List<String> repositories = manifests.stream()
                .map(AssetEntity::getPath)
                .filter(p -> p.contains("/manifests/"))
                .map(p -> {
                    // Extract image name from path: v2/{name}/manifests/{ref}
                    String withoutV2 = p.substring(3); // remove "v2/"
                    int manifestsIdx = withoutV2.indexOf("/manifests/");
                    return manifestsIdx > 0 ? withoutV2.substring(0, manifestsIdx) : null;
                })
                .filter(n -> n != null)
                .distinct()
                .sorted()
                .toList();

        String json = formatJson(Map.of("repositories", repositories));
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = Map.of("Docker-Distribution-API-Version", "registry/2.0");
        return new ContentResponse(
                new ByteArrayInputStream(bytes), "application/json", bytes.length, headers, Map.of());
    }

    private FormatResponse listTags(RepositoryConfig repo, String imageName) {
        String pathPrefix = "v2/" + imageName + "/manifests/";
        List<AssetEntity> manifests = assetRepository.findByRepositoryIdAndPathStartingWith(
                repo.id(), pathPrefix);

        List<String> tags = manifests.stream()
                .map(a -> a.getPath().substring(pathPrefix.length()))
                .filter(ref -> !ref.startsWith("sha256:")) // exclude digest references, show tags only
                .sorted()
                .toList();

        String json = formatJson(Map.of("name", imageName, "tags", tags));
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = Map.of("Docker-Distribution-API-Version", "registry/2.0");
        return new ContentResponse(
                new ByteArrayInputStream(bytes), "application/json", bytes.length, headers, Map.of());
    }

    private FormatResponse getManifest(RepositoryConfig repo, String imageName, String reference, boolean headOnly) {
        String path = "v2/" + imageName + "/manifests/" + reference;
        Optional<AssetEntity> assetOpt = assetRepository.findByRepositoryIdAndPath(repo.id(), path);

        // If reference is a tag, try to resolve to a digest-based asset
        if (assetOpt.isEmpty() && !reference.startsWith("sha256:")) {
            // Tag not found
            return dockerError("MANIFEST_UNKNOWN", "manifest unknown", 404);
        }

        if (assetOpt.isEmpty()) {
            return dockerError("MANIFEST_UNKNOWN", "manifest unknown", 404);
        }

        AssetEntity asset = assetOpt.get();
        if (asset.getBlobRef() == null) {
            return dockerError("MANIFEST_UNKNOWN", "manifest has no content", 404);
        }

        BlobRef blobRef = BlobRef.parse(asset.getBlobRef());
        BlobStore blobStore = blobStoreManager.get(repo.blobStoreName());
        Optional<Blob> blobOpt = blobStore.get(blobRef);

        if (blobOpt.isEmpty()) {
            return dockerError("MANIFEST_UNKNOWN", "manifest blob not found", 404);
        }

        Blob blob = blobOpt.get();
        String contentType = asset.getContentType() != null ? asset.getContentType()
                : "application/vnd.docker.distribution.manifest.v2+json";
        long size = asset.getSize() != null ? asset.getSize() : blob.properties().size();

        Map<String, String> headers = new HashMap<>();
        headers.put("Docker-Distribution-API-Version", "registry/2.0");
        headers.put("Docker-Content-Digest", asset.getChecksumSha256() != null
                ? "sha256:" + asset.getChecksumSha256()
                : "");
        headers.put("Content-Type", contentType);

        if (headOnly) {
            try {
                blob.close();
            } catch (IOException e) {
                // ignore
            }
            return new ContentResponse(InputStream.nullInputStream(), contentType, size, headers, Map.of());
        }

        asset.setLastDownloaded(Instant.now());
        assetRepository.save(asset);

        return new ContentResponse(blob.inputStream(), contentType, size, headers, Map.of());
    }

    private FormatResponse getBlob(RepositoryConfig repo, String imageName, String digest, boolean headOnly) {
        // Blobs are stored with path: v2/{imageName}/blobs/{digest}
        // But they're content-addressable, so check by digest across the repo
        String path = "v2/" + imageName + "/blobs/" + digest;
        Optional<AssetEntity> assetOpt = assetRepository.findByRepositoryIdAndPath(repo.id(), path);

        if (assetOpt.isEmpty()) {
            // Try finding the blob in any image path within this repo (content-addressable)
            String digestHash = digest.startsWith("sha256:") ? digest.substring(7) : digest;
            List<AssetEntity> blobAssets = assetRepository.findByRepositoryIdAndChecksumSha256(
                    repo.id(), digestHash);
            if (!blobAssets.isEmpty()) {
                assetOpt = Optional.of(blobAssets.getFirst());
            }
        }

        if (assetOpt.isEmpty()) {
            return dockerError("BLOB_UNKNOWN", "blob unknown to registry", 404);
        }

        AssetEntity asset = assetOpt.get();
        if (asset.getBlobRef() == null) {
            return dockerError("BLOB_UNKNOWN", "blob has no content", 404);
        }

        BlobRef blobRef = BlobRef.parse(asset.getBlobRef());
        BlobStore blobStore = blobStoreManager.get(repo.blobStoreName());
        Optional<Blob> blobOpt = blobStore.get(blobRef);

        if (blobOpt.isEmpty()) {
            return dockerError("BLOB_UNKNOWN", "blob data not found", 404);
        }

        Blob blob = blobOpt.get();
        String contentType = "application/octet-stream";
        long size = asset.getSize() != null ? asset.getSize() : blob.properties().size();

        Map<String, String> headers = new HashMap<>();
        headers.put("Docker-Distribution-API-Version", "registry/2.0");
        headers.put("Docker-Content-Digest", digest);

        if (headOnly) {
            try {
                blob.close();
            } catch (IOException e) {
                // ignore
            }
            return new ContentResponse(InputStream.nullInputStream(), contentType, size, headers, Map.of());
        }

        return new ContentResponse(blob.inputStream(), contentType, size, headers, Map.of());
    }

    private FormatResponse putManifest(
            RepositoryConfig repo, String imageName, String reference, HttpServletRequest request) {
        try {
            byte[] manifestBytes = request.getInputStream().readAllBytes();
            String contentType = request.getContentType();
            if (contentType == null || contentType.isBlank()) {
                contentType = "application/vnd.docker.distribution.manifest.v2+json";
            }

            // Detect manifest list / OCI index from the body if content type is generic
            contentType = resolveManifestContentType(contentType, manifestBytes);

            boolean isManifestList = DockerManifestList.isManifestListMediaType(contentType);

            // Compute digest of manifest content
            String digest = computeSha256(manifestBytes);
            String digestString = "sha256:" + digest;

            // Store manifest blob
            BlobStore blobStore = blobStoreManager.get(repo.blobStoreName());
            Map<String, String> blobHeaders = Map.of("Content-Type", contentType);
            BlobRef blobRef = blobStore.store(
                    new ByteArrayInputStream(manifestBytes), manifestBytes.length, blobHeaders);

            Instant now = Instant.now();

            // Store asset by tag reference (e.g., v2/library/nginx/manifests/latest)
            String tagPath = "v2/" + imageName + "/manifests/" + reference;
            storeManifestAsset(repo, tagPath, blobRef, contentType, manifestBytes.length, digest, now, request);

            // Also store asset by digest reference for content-addressable lookup
            if (!reference.equals(digestString)) {
                String digestPath = "v2/" + imageName + "/manifests/" + digestString;
                storeManifestAsset(repo, digestPath, blobRef, contentType, manifestBytes.length, digest, now, request);
            }

            // Create/update component for this image:tag
            Optional<ComponentCoordinates> coords = coordinateExtractor.extractFromPath(tagPath);
            if (coords.isPresent()) {
                findOrCreateComponent(repo, coords.get());
            }

            // Parse manifest to log layer/platform info (best-effort)
            parseManifestInfo(manifestBytes, imageName, reference, isManifestList);

            Map<String, String> headers = Map.of(
                    "Docker-Content-Digest", digestString,
                    "Docker-Distribution-API-Version", "registry/2.0",
                    "Location", "/repository/" + repo.name() + "/v2/" + imageName + "/manifests/" + digestString);

            return new CreatedResponse(tagPath, headers);

        } catch (IOException e) {
            log.error("Failed to read manifest upload for {}/{}", imageName, reference, e);
            return new ErrorResponse(500, "Failed to read manifest: " + e.getMessage());
        }
    }

    private FormatResponse initiateBlobUpload(RepositoryConfig repo, String imageName, HttpServletRequest request) {
        String uploadUuid = UUID.randomUUID().toString();

        // Check for monolithic upload (POST with digest parameter)
        String digest = request.getParameter("digest");
        if (digest != null) {
            return monolithicBlobUpload(repo, imageName, digest, request);
        }

        // Check for cross-repo mount
        String mount = request.getParameter("mount");
        String from = request.getParameter("from");
        if (mount != null && from != null) {
            return mountBlob(repo, imageName, mount, from, uploadUuid, request);
        }

        // Evict abandoned uploads before creating a new one
        evictExpiredUploads();

        // Standard chunked upload initiation — create a temp file for streaming chunks
        try {
            Path tempFile = Files.createTempFile("docker-upload-" + uploadUuid + "-", ".tmp");
            activeUploads.put(uploadUuid, new UploadState(repo.id(), imageName, tempFile, 0, Instant.now()));

            Map<String, String> headers = Map.of(
                    "Docker-Upload-UUID", uploadUuid,
                    "Docker-Distribution-API-Version", "registry/2.0",
                    "Location",
                            "/repository/" + repo.name() + "/v2/" + imageName + "/blobs/uploads/" + uploadUuid,
                    "Range", "0-0");

            return new CreatedResponse("v2/" + imageName + "/blobs/uploads/" + uploadUuid, headers);
        } catch (IOException e) {
            log.error("Failed to create temp file for upload {}", uploadUuid, e);
            return new ErrorResponse(500, "Failed to initiate upload: " + e.getMessage());
        }
    }

    private FormatResponse uploadBlobChunk(
            RepositoryConfig repo, String imageName, String uploadUuid, HttpServletRequest request) {
        try {
            UploadState current = activeUploads.get(uploadUuid);

            if (current == null) {
                return dockerError("BLOB_UPLOAD_UNKNOWN", "upload not found", 404);
            }

            // Stream the chunk directly to the temp file (append mode) — no memory buffering
            long chunkBytes = 0;
            try (InputStream in = request.getInputStream();
                    OutputStream out = new FileOutputStream(current.tempFile().toFile(), true)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    chunkBytes += n;
                }
            }

            long totalBytes = current.bytesReceived() + chunkBytes;
            activeUploads.put(uploadUuid,
                    new UploadState(repo.id(), imageName, current.tempFile(), totalBytes, current.createdAt()));

            Map<String, String> headers = Map.of(
                    "Docker-Upload-UUID", uploadUuid,
                    "Docker-Distribution-API-Version", "registry/2.0",
                    "Location",
                            "/repository/" + repo.name() + "/v2/" + imageName + "/blobs/uploads/" + uploadUuid,
                    "Range", "0-" + (totalBytes > 0 ? totalBytes - 1 : 0));

            return new CreatedResponse("v2/" + imageName + "/blobs/uploads/" + uploadUuid, headers);

        } catch (IOException e) {
            log.error("Failed to process blob chunk for upload {}", uploadUuid, e);
            return new ErrorResponse(500, "Failed to process blob chunk: " + e.getMessage());
        }
    }

    private FormatResponse completeBlobUpload(
            RepositoryConfig repo,
            String imageName,
            String uploadUuid,
            String digest,
            HttpServletRequest request) {
        try {
            BlobStore blobStore = blobStoreManager.get(repo.blobStoreName());
            UploadState state = activeUploads.remove(uploadUuid);

            // Determine the temp file — create one if this is a single-PUT upload without prior PATCH
            Path tempFile;
            if (state != null && state.tempFile() != null) {
                tempFile = state.tempFile();
            } else {
                tempFile = Files.createTempFile("docker-upload-" + uploadUuid + "-", ".tmp");
            }

            // Append any final chunk sent with the PUT to the temp file
            try (InputStream in = request.getInputStream();
                    OutputStream out = new FileOutputStream(tempFile.toFile(), true)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            }

            long totalSize = Files.size(tempFile);

            // Compute digest incrementally by streaming from temp file
            String computedDigest = "sha256:" + computeSha256FromFile(tempFile);
            if (!computedDigest.equals(digest)) {
                log.warn("Blob digest mismatch: expected={} computed={}", digest, computedDigest);
                Files.deleteIfExists(tempFile);
                return dockerError("DIGEST_INVALID", "provided digest does not match uploaded content", 400);
            }

            // Stream from temp file into blob store — no full read into memory
            BlobRef blobRef;
            try (InputStream fileIn = new BufferedInputStream(new FileInputStream(tempFile.toFile()))) {
                blobRef = blobStore.store(fileIn, totalSize, Map.of("Content-Type", "application/octet-stream"));
            } finally {
                Files.deleteIfExists(tempFile);
            }

            // Create asset for this blob
            String blobPath = "v2/" + imageName + "/blobs/" + digest;
            Instant now = Instant.now();
            String digestHash = digest.startsWith("sha256:") ? digest.substring(7) : digest;

            AssetEntity asset = assetRepository
                    .findByRepositoryIdAndPath(repo.id(), blobPath)
                    .orElseGet(() -> {
                        var newAsset = new AssetEntity();
                        newAsset.setRepositoryId(repo.id());
                        newAsset.setPath(blobPath);
                        newAsset.setFormat(FORMAT);
                        newAsset.setCreatedAt(now);
                        return newAsset;
                    });

            asset.setBlobRef(blobRef.toExternalForm());
            asset.setContentType("application/octet-stream");
            asset.setSize(totalSize);
            asset.setLastModified(now);
            asset.setUpdatedAt(now);
            asset.setChecksumSha256(digestHash);
            asset.setCreatedBy(request.getRemoteUser());
            asset.setCreatedByIp(request.getRemoteAddr());
            assetRepository.save(asset);

            log.info("Docker blob upload complete: repo={} image={} digest={} size={}",
                    repo.name(), imageName, digest, totalSize);

            Map<String, String> headers = Map.of(
                    "Docker-Content-Digest", digest,
                    "Docker-Distribution-API-Version", "registry/2.0",
                    "Location", "/repository/" + repo.name() + "/v2/" + imageName + "/blobs/" + digest);

            return new CreatedResponse(blobPath, headers);

        } catch (IOException e) {
            log.error("Failed to complete blob upload {}", uploadUuid, e);
            return new ErrorResponse(500, "Failed to complete blob upload: " + e.getMessage());
        }
    }

    private FormatResponse monolithicBlobUpload(
            RepositoryConfig repo, String imageName, String digest, HttpServletRequest request) {
        Path tempFile = null;
        try {
            // Stream upload to temp file with incremental digest computation
            tempFile = Files.createTempFile("docker-monolithic-", ".tmp");
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            long totalSize;

            try (InputStream in = request.getInputStream();
                    OutputStream out = new FileOutputStream(tempFile.toFile())) {
                byte[] buf = new byte[8192];
                int n;
                long written = 0;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    sha256.update(buf, 0, n);
                    written += n;
                }
                totalSize = written;
            }

            String computedDigest = "sha256:" + HexFormat.of().formatHex(sha256.digest());
            if (!computedDigest.equals(digest)) {
                return dockerError("DIGEST_INVALID", "provided digest does not match uploaded content", 400);
            }

            BlobStore blobStore = blobStoreManager.get(repo.blobStoreName());
            BlobRef blobRef;
            try (InputStream fileIn = new BufferedInputStream(new FileInputStream(tempFile.toFile()))) {
                blobRef = blobStore.store(fileIn, totalSize, Map.of("Content-Type", "application/octet-stream"));
            }

            String blobPath = "v2/" + imageName + "/blobs/" + digest;
            String digestHash = digest.startsWith("sha256:") ? digest.substring(7) : digest;
            Instant now = Instant.now();

            AssetEntity asset = assetRepository
                    .findByRepositoryIdAndPath(repo.id(), blobPath)
                    .orElseGet(() -> {
                        var newAsset = new AssetEntity();
                        newAsset.setRepositoryId(repo.id());
                        newAsset.setPath(blobPath);
                        newAsset.setFormat(FORMAT);
                        newAsset.setCreatedAt(now);
                        return newAsset;
                    });

            asset.setBlobRef(blobRef.toExternalForm());
            asset.setContentType("application/octet-stream");
            asset.setSize(totalSize);
            asset.setLastModified(now);
            asset.setUpdatedAt(now);
            asset.setChecksumSha256(digestHash);
            asset.setCreatedBy(request.getRemoteUser());
            asset.setCreatedByIp(request.getRemoteAddr());
            assetRepository.save(asset);

            Map<String, String> headers = Map.of(
                    "Docker-Content-Digest", digest,
                    "Docker-Distribution-API-Version", "registry/2.0",
                    "Location", "/repository/" + repo.name() + "/v2/" + imageName + "/blobs/" + digest);

            return new CreatedResponse(blobPath, headers);

        } catch (IOException | NoSuchAlgorithmException e) {
            return new ErrorResponse(500, "Failed to process monolithic upload: " + e.getMessage());
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            }
        }
    }

    private FormatResponse mountBlob(
            RepositoryConfig repo,
            String imageName,
            String digest,
            String fromImage,
            String uploadUuid,
            HttpServletRequest request) {
        // Try to find existing blob by digest in this repository
        String digestHash = digest.startsWith("sha256:") ? digest.substring(7) : digest;
        List<AssetEntity> existingBlobs = assetRepository.findByRepositoryIdAndChecksumSha256(
                repo.id(), digestHash);

        if (!existingBlobs.isEmpty()) {
            // Blob exists, create a new path reference for the target image
            AssetEntity sourceAsset = existingBlobs.getFirst();
            String blobPath = "v2/" + imageName + "/blobs/" + digest;

            Instant now = Instant.now();
            AssetEntity asset = assetRepository
                    .findByRepositoryIdAndPath(repo.id(), blobPath)
                    .orElseGet(() -> {
                        var newAsset = new AssetEntity();
                        newAsset.setRepositoryId(repo.id());
                        newAsset.setPath(blobPath);
                        newAsset.setFormat(FORMAT);
                        newAsset.setCreatedAt(now);
                        return newAsset;
                    });

            asset.setBlobRef(sourceAsset.getBlobRef());
            asset.setContentType(sourceAsset.getContentType());
            asset.setSize(sourceAsset.getSize());
            asset.setLastModified(now);
            asset.setUpdatedAt(now);
            asset.setChecksumSha256(digestHash);
            assetRepository.save(asset);

            Map<String, String> headers = Map.of(
                    "Docker-Content-Digest", digest,
                    "Docker-Distribution-API-Version", "registry/2.0",
                    "Location", "/repository/" + repo.name() + "/v2/" + imageName + "/blobs/" + digest);

            return new CreatedResponse(blobPath, headers);
        }

        // Mount failed, fall back to initiating regular upload
        try {
            Path tempFile = Files.createTempFile("docker-upload-" + uploadUuid + "-", ".tmp");
            activeUploads.put(uploadUuid, new UploadState(repo.id(), imageName, tempFile, 0, Instant.now()));
        } catch (IOException e) {
            log.error("Failed to create temp file for mount fallback upload {}", uploadUuid, e);
            return new ErrorResponse(500, "Failed to initiate upload: " + e.getMessage());
        }

        Map<String, String> headers = Map.of(
                "Docker-Upload-UUID", uploadUuid,
                "Docker-Distribution-API-Version", "registry/2.0",
                "Location",
                        "/repository/" + repo.name() + "/v2/" + imageName + "/blobs/uploads/" + uploadUuid,
                "Range", "0-0");

        return new CreatedResponse("v2/" + imageName + "/blobs/uploads/" + uploadUuid, headers);
    }

    private FormatResponse deleteManifest(RepositoryConfig repo, String imageName, String reference) {
        String path = "v2/" + imageName + "/manifests/" + reference;
        Optional<AssetEntity> assetOpt = assetRepository.findByRepositoryIdAndPath(repo.id(), path);

        if (assetOpt.isEmpty()) {
            return dockerError("MANIFEST_UNKNOWN", "manifest unknown", 404);
        }

        AssetEntity asset = assetOpt.get();
        if (asset.getBlobRef() != null) {
            BlobRef blobRef = BlobRef.parse(asset.getBlobRef());
            BlobStore blobStore = blobStoreManager.get(repo.blobStoreName());
            blobStore.delete(blobRef);
        }
        assetRepository.delete(asset);

        return new ContentResponse(InputStream.nullInputStream(), "application/json", 0,
                Map.of("Docker-Distribution-API-Version", "registry/2.0"), Map.of());
    }

    private FormatResponse deleteBlob(RepositoryConfig repo, String digest) {
        String digestHash = digest.startsWith("sha256:") ? digest.substring(7) : digest;
        List<AssetEntity> blobs = assetRepository.findByRepositoryIdAndChecksumSha256(repo.id(), digestHash);

        if (blobs.isEmpty()) {
            return dockerError("BLOB_UNKNOWN", "blob unknown to registry", 404);
        }

        BlobStore blobStore = blobStoreManager.get(repo.blobStoreName());
        for (AssetEntity asset : blobs) {
            if (asset.getBlobRef() != null) {
                blobStore.delete(BlobRef.parse(asset.getBlobRef()));
            }
            assetRepository.delete(asset);
        }

        return new ContentResponse(InputStream.nullInputStream(), "application/json", 0,
                Map.of("Docker-Distribution-API-Version", "registry/2.0"), Map.of());
    }

    // ---- Docker proxy methods ----

    private String getUpstreamRegistryUrl(RepositoryConfig repo) {
        Object proxyObj = repo.attributes().get("proxy");
        if (proxyObj instanceof Map<?, ?> proxyMap) {
            Object url = proxyMap.get("remoteUrl");
            if (url instanceof String s) {
                return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
            }
        }
        return "https://registry-1.docker.io";
    }

    /**
     * Proxy a manifest request: check local cache first, then fetch from upstream.
     * Manifests referenced by tag are always revalidated (tags are mutable), while
     * manifests referenced by digest are immutable and can be served from cache indefinitely.
     */
    private FormatResponse proxyManifest(
            RepositoryConfig repo, String imageName, String reference, boolean headOnly) {
        String path = "v2/" + imageName + "/manifests/" + reference;
        boolean isDigestRef = reference.startsWith("sha256:");

        // Check local cache
        Optional<AssetEntity> cachedAsset = assetRepository.findByRepositoryIdAndPath(repo.id(), path);
        if (cachedAsset.isPresent() && cachedAsset.get().getBlobRef() != null) {
            AssetEntity asset = cachedAsset.get();
            // Digest references are immutable — always serve from cache
            // Tag references need revalidation based on TTL
            if (isDigestRef) {
                log.debug("Docker proxy cache hit (digest ref) for repo={} path={}", repo.name(), path);
                return serveDockerCachedManifest(repo, asset, headOnly);
            }
            boolean expired = proxyCacheChecker != null && proxyCacheChecker.isMetadataExpired(asset, repo);
            if (!expired) {
                log.debug("Docker proxy cache hit for repo={} path={}", repo.name(), path);
                return serveDockerCachedManifest(repo, asset, headOnly);
            }
            log.debug("Docker proxy cache expired for repo={} path={}", repo.name(), path);
        }

        // Fetch from upstream
        if (dockerProxyClient == null) {
            return dockerError("MANIFEST_UNKNOWN", "Docker proxy client not available", 500);
        }

        String registryUrl = getUpstreamRegistryUrl(repo);
        String upstreamUrl = registryUrl + "/v2/" + imageName + "/manifests/" + reference;

        // Docker clients send Accept headers to indicate which manifest types they support
        Map<String, String> headers = new HashMap<>();
        String accept = "application/vnd.docker.distribution.manifest.v2+json, "
                + "application/vnd.docker.distribution.manifest.list.v2+json, "
                + "application/vnd.oci.image.manifest.v1+json, "
                + "application/vnd.oci.image.index.v1+json";
        headers.put("Accept", accept);

        try {
            String scope = "repository:" + imageName + ":pull";
            DockerProxyClient.UpstreamResponse upstream =
                    dockerProxyClient.fetchWithScope(upstreamUrl, registryUrl, scope, headers);

            if (upstream.statusCode() == 404) {
                upstream.body().close();
                return dockerError("MANIFEST_UNKNOWN", "manifest unknown", 404);
            }
            if (upstream.statusCode() != 200) {
                upstream.body().close();
                log.warn("Upstream returned {} for manifest {}/{}", upstream.statusCode(), imageName, reference);
                return dockerError("MANIFEST_UNKNOWN",
                        "upstream returned status " + upstream.statusCode(), 502);
            }

            // Read and cache the manifest
            byte[] manifestBytes = upstream.body().readAllBytes();
            String contentType = upstream.contentType();
            String digest = computeSha256(manifestBytes);
            String digestString = "sha256:" + digest;

            // Use upstream's Docker-Content-Digest if available
            if (upstream.dockerContentDigest() != null && upstream.dockerContentDigest().startsWith("sha256:")) {
                digestString = upstream.dockerContentDigest();
                digest = digestString.substring(7);
            }

            BlobStore blobStore = blobStoreManager.get(repo.blobStoreName());
            BlobRef blobRef = blobStore.store(
                    new ByteArrayInputStream(manifestBytes), manifestBytes.length,
                    Map.of("Content-Type", contentType));

            Instant now = Instant.now();

            // Cache by the requested path (tag or digest)
            cacheProxyManifestAsset(repo, path, blobRef, contentType, manifestBytes.length, digest, now);

            // Also cache by digest path for content-addressable lookup
            if (!reference.equals(digestString)) {
                String digestPath = "v2/" + imageName + "/manifests/" + digestString;
                cacheProxyManifestAsset(repo, digestPath, blobRef, contentType, manifestBytes.length, digest, now);
            }

            // Create component
            Optional<ComponentCoordinates> coords = coordinateExtractor.extractFromPath(path);
            if (coords.isPresent()) {
                findOrCreateComponent(repo, coords.get());
            }

            log.info("Docker proxy cached manifest: repo={} image={} ref={} size={}",
                    repo.name(), imageName, reference, manifestBytes.length);

            Map<String, String> responseHeaders = new HashMap<>();
            responseHeaders.put("Docker-Distribution-API-Version", "registry/2.0");
            responseHeaders.put("Docker-Content-Digest", digestString);
            responseHeaders.put("Content-Type", contentType);

            if (headOnly) {
                return new ContentResponse(
                        InputStream.nullInputStream(), contentType, manifestBytes.length, responseHeaders, Map.of());
            }

            return new ContentResponse(
                    new ByteArrayInputStream(manifestBytes), contentType, manifestBytes.length,
                    responseHeaders, Map.of());

        } catch (IOException e) {
            log.error("Failed to proxy manifest for {}/{}: {}", imageName, reference, e.getMessage());
            // Try to serve stale cache on upstream failure
            if (cachedAsset.isPresent() && cachedAsset.get().getBlobRef() != null) {
                log.info("Serving stale cached manifest after upstream failure for {}/{}", imageName, reference);
                return serveDockerCachedManifest(repo, cachedAsset.get(), headOnly);
            }
            return dockerError("MANIFEST_UNKNOWN", "Failed to fetch manifest from upstream", 502);
        }
    }

    /**
     * Proxy a blob request: check local cache, then fetch from upstream.
     * Blobs are content-addressable (keyed by digest) and immutable, so a cached blob
     * never needs revalidation.
     */
    private FormatResponse proxyBlob(RepositoryConfig repo, String imageName, String digest, boolean headOnly) {
        String path = "v2/" + imageName + "/blobs/" + digest;

        // Check local cache — blobs are immutable, so any cached copy is valid
        Optional<AssetEntity> cachedAsset = assetRepository.findByRepositoryIdAndPath(repo.id(), path);
        if (cachedAsset.isEmpty()) {
            // Also check by digest across the repo (content-addressable)
            String digestHash = digest.startsWith("sha256:") ? digest.substring(7) : digest;
            List<AssetEntity> blobAssets = assetRepository.findByRepositoryIdAndChecksumSha256(repo.id(), digestHash);
            if (!blobAssets.isEmpty()) {
                cachedAsset = Optional.of(blobAssets.getFirst());
            }
        }

        if (cachedAsset.isPresent() && cachedAsset.get().getBlobRef() != null) {
            log.debug("Docker proxy cache hit for blob repo={} digest={}", repo.name(), digest);
            return getBlob(repo, imageName, digest, headOnly);
        }

        // Fetch from upstream
        if (dockerProxyClient == null) {
            return dockerError("BLOB_UNKNOWN", "Docker proxy client not available", 500);
        }

        String registryUrl = getUpstreamRegistryUrl(repo);
        String upstreamUrl = registryUrl + "/v2/" + imageName + "/blobs/" + digest;

        try {
            String scope = "repository:" + imageName + ":pull";
            DockerProxyClient.UpstreamResponse upstream =
                    dockerProxyClient.fetchWithScope(upstreamUrl, registryUrl, scope, Map.of());

            if (upstream.statusCode() == 404) {
                upstream.body().close();
                return dockerError("BLOB_UNKNOWN", "blob unknown to registry", 404);
            }
            if (upstream.statusCode() != 200) {
                upstream.body().close();
                log.warn("Upstream returned {} for blob {}", upstream.statusCode(), digest);
                return dockerError("BLOB_UNKNOWN",
                        "upstream returned status " + upstream.statusCode(), 502);
            }

            // Stream from upstream to temp file, then into blob store — avoids holding blob in memory
            String digestHash = digest.startsWith("sha256:") ? digest.substring(7) : digest;
            Path tempFile = Files.createTempFile("docker-proxy-blob-", ".tmp");
            long totalSize;
            try {
                try (InputStream upstreamIn = upstream.body();
                        OutputStream out = new FileOutputStream(tempFile.toFile())) {
                    byte[] buf = new byte[8192];
                    int n;
                    long written = 0;
                    while ((n = upstreamIn.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        written += n;
                    }
                    totalSize = written;
                }

                BlobStore blobStore = blobStoreManager.get(repo.blobStoreName());
                BlobRef blobRef;
                try (InputStream fileIn = new BufferedInputStream(new FileInputStream(tempFile.toFile()))) {
                    blobRef = blobStore.store(fileIn, totalSize, Map.of("Content-Type", "application/octet-stream"));
                }

                Instant now = Instant.now();
                AssetEntity asset = assetRepository
                        .findByRepositoryIdAndPath(repo.id(), path)
                        .orElseGet(() -> {
                            var newAsset = new AssetEntity();
                            newAsset.setRepositoryId(repo.id());
                            newAsset.setPath(path);
                            newAsset.setFormat(FORMAT);
                            newAsset.setCreatedAt(now);
                            return newAsset;
                        });

                asset.setBlobRef(blobRef.toExternalForm());
                asset.setContentType("application/octet-stream");
                asset.setSize(totalSize);
                asset.setLastModified(now);
                asset.setUpdatedAt(now);
                asset.setChecksumSha256(digestHash);
                asset.setCreatedBy("proxy");
                assetRepository.save(asset);

                log.info("Docker proxy cached blob: repo={} image={} digest={} size={}",
                        repo.name(), imageName, digest, totalSize);
            } finally {
                Files.deleteIfExists(tempFile);
            }

            Map<String, String> responseHeaders = new HashMap<>();
            responseHeaders.put("Docker-Distribution-API-Version", "registry/2.0");
            responseHeaders.put("Docker-Content-Digest", digest);

            if (headOnly) {
                return new ContentResponse(
                        InputStream.nullInputStream(), "application/octet-stream", totalSize,
                        responseHeaders, Map.of());
            }

            // Serve from blob store (temp file is deleted, data is in blob store now)
            return getBlob(repo, imageName, digest, false);

        } catch (IOException e) {
            log.error("Failed to proxy blob {} for image {}: {}", digest, imageName, e.getMessage());
            return dockerError("BLOB_UNKNOWN", "Failed to fetch blob from upstream", 502);
        }
    }

    /**
     * Proxy a tags list request to the upstream registry.
     */
    private FormatResponse proxyTagsList(RepositoryConfig repo, String imageName) {
        if (dockerProxyClient == null) {
            // Fall back to locally cached tags
            return listTags(repo, imageName);
        }

        String registryUrl = getUpstreamRegistryUrl(repo);
        String upstreamUrl = registryUrl + "/v2/" + imageName + "/tags/list";

        try {
            String scope = "repository:" + imageName + ":pull";
            DockerProxyClient.UpstreamResponse upstream =
                    dockerProxyClient.fetchWithScope(upstreamUrl, registryUrl, scope, Map.of());

            if (upstream.statusCode() == 404) {
                upstream.body().close();
                return dockerError("NAME_UNKNOWN", "repository name not known to registry", 404);
            }
            if (upstream.statusCode() != 200) {
                upstream.body().close();
                // Fall back to local cache
                log.warn("Upstream returned {} for tags of {}, falling back to local cache",
                        upstream.statusCode(), imageName);
                return listTags(repo, imageName);
            }

            byte[] body = upstream.body().readAllBytes();
            Map<String, String> headers = Map.of("Docker-Distribution-API-Version", "registry/2.0");
            return new ContentResponse(
                    new ByteArrayInputStream(body), "application/json", body.length, headers, Map.of());

        } catch (IOException e) {
            log.warn("Failed to proxy tags for {}, falling back to local cache: {}", imageName, e.getMessage());
            return listTags(repo, imageName);
        }
    }

    private void cacheProxyManifestAsset(
            RepositoryConfig repo, String path, BlobRef blobRef, String contentType,
            long size, String sha256, Instant now) {
        AssetEntity asset = assetRepository
                .findByRepositoryIdAndPath(repo.id(), path)
                .orElseGet(() -> {
                    var newAsset = new AssetEntity();
                    newAsset.setRepositoryId(repo.id());
                    newAsset.setPath(path);
                    newAsset.setFormat(FORMAT);
                    newAsset.setCreatedAt(now);
                    return newAsset;
                });

        asset.setBlobRef(blobRef.toExternalForm());
        asset.setContentType(contentType);
        asset.setSize(size);
        asset.setLastModified(now);
        asset.setUpdatedAt(now);
        asset.setChecksumSha256(sha256);
        asset.setCreatedBy("proxy");
        assetRepository.save(asset);
    }

    private FormatResponse serveDockerCachedManifest(RepositoryConfig repo, AssetEntity asset, boolean headOnly) {
        BlobRef blobRef = BlobRef.parse(asset.getBlobRef());
        BlobStore blobStore = blobStoreManager.get(repo.blobStoreName());
        Optional<Blob> blobOpt = blobStore.get(blobRef);

        if (blobOpt.isEmpty()) {
            return dockerError("MANIFEST_UNKNOWN", "cached manifest blob not found", 404);
        }

        Blob blob = blobOpt.get();
        String contentType = asset.getContentType() != null ? asset.getContentType()
                : "application/vnd.docker.distribution.manifest.v2+json";
        long size = asset.getSize() != null ? asset.getSize() : blob.properties().size();

        Map<String, String> headers = new HashMap<>();
        headers.put("Docker-Distribution-API-Version", "registry/2.0");
        headers.put("Docker-Content-Digest", asset.getChecksumSha256() != null
                ? "sha256:" + asset.getChecksumSha256()
                : "");
        headers.put("Content-Type", contentType);

        if (headOnly) {
            try {
                blob.close();
            } catch (IOException e) {
                // ignore
            }
            return new ContentResponse(InputStream.nullInputStream(), contentType, size, headers, Map.of());
        }

        asset.setLastDownloaded(Instant.now());
        assetRepository.save(asset);

        return new ContentResponse(blob.inputStream(), contentType, size, headers, Map.of());
    }

    // ---- Helper methods ----

    private void storeManifestAsset(
            RepositoryConfig repo,
            String path,
            BlobRef blobRef,
            String contentType,
            long size,
            String sha256,
            Instant now,
            HttpServletRequest request) {
        AssetEntity asset = assetRepository
                .findByRepositoryIdAndPath(repo.id(), path)
                .orElseGet(() -> {
                    var newAsset = new AssetEntity();
                    newAsset.setRepositoryId(repo.id());
                    newAsset.setPath(path);
                    newAsset.setFormat(FORMAT);
                    newAsset.setCreatedAt(now);
                    return newAsset;
                });

        asset.setBlobRef(blobRef.toExternalForm());
        asset.setContentType(contentType);
        asset.setSize(size);
        asset.setLastModified(now);
        asset.setUpdatedAt(now);
        asset.setChecksumSha256(sha256);
        asset.setCreatedBy(request.getRemoteUser());
        asset.setCreatedByIp(request.getRemoteAddr());
        assetRepository.save(asset);
    }

    private ComponentEntity findOrCreateComponent(RepositoryConfig repo, ComponentCoordinates coords) {
        return componentRepository
                .findByRepositoryIdAndNamespaceAndNameAndVersion(
                        repo.id(), coords.namespace(), coords.name(), coords.version())
                .orElseGet(() -> {
                    try {
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
                    } catch (org.springframework.dao.DataIntegrityViolationException e) {
                        return componentRepository
                                .findByRepositoryIdAndNamespaceAndNameAndVersion(
                                        repo.id(), coords.namespace(), coords.name(), coords.version())
                                .orElseThrow(() -> e);
                    }
                });
    }

    private void parseManifestInfo(byte[] manifestBytes, String imageName, String reference, boolean isManifestList) {
        try {
            JsonNode root = objectMapper.readTree(manifestBytes);
            int schemaVersion = root.has("schemaVersion") ? root.get("schemaVersion").asInt() : 0;
            String mediaType = root.has("mediaType") ? root.get("mediaType").asText() : "unknown";

            if (isManifestList) {
                int platformCount = root.has("manifests") ? root.get("manifests").size() : 0;
                log.info("Docker manifest list pushed: image={} ref={} schemaVersion={} mediaType={} platforms={}",
                        imageName, reference, schemaVersion, mediaType, platformCount);

                if (root.has("manifests")) {
                    for (JsonNode manifestRef : root.get("manifests")) {
                        String digest = manifestRef.has("digest") ? manifestRef.get("digest").asText() : "unknown";
                        String platformDesc = "unknown";
                        if (manifestRef.has("platform")) {
                            JsonNode platform = manifestRef.get("platform");
                            String os = platform.has("os") ? platform.get("os").asText() : "";
                            String arch = platform.has("architecture") ? platform.get("architecture").asText() : "";
                            String variant = platform.has("variant") ? platform.get("variant").asText() : "";
                            platformDesc = os + "/" + arch + (variant.isEmpty() ? "" : "/" + variant);
                        }
                        log.info("  Platform {}: digest={}", platformDesc, digest);
                    }
                }
            } else {
                log.info("Docker manifest pushed: image={} ref={} schemaVersion={} mediaType={}",
                        imageName, reference, schemaVersion, mediaType);

                if (root.has("layers")) {
                    int layerCount = root.get("layers").size();
                    log.info("Docker manifest has {} layers", layerCount);
                }
            }
        } catch (Exception e) {
            log.debug("Could not parse manifest JSON for {}/{}: {}", imageName, reference, e.getMessage());
        }
    }

    /**
     * Resolves the actual manifest content type by inspecting the body when the provided
     * Content-Type header is generic or absent.
     *
     * <p>Docker clients typically send the correct Content-Type, but some older clients or
     * tools may not. This method parses the manifest JSON to detect manifest lists and
     * OCI indexes based on the {@code mediaType} field in the body.
     */
    private String resolveManifestContentType(String headerContentType, byte[] manifestBytes) {
        // If the client already sent a specific Docker/OCI media type, trust it
        if (headerContentType != null && (
                headerContentType.contains("vnd.docker.distribution.manifest")
                || headerContentType.contains("vnd.oci.image"))) {
            return headerContentType;
        }

        // Try to detect from the body's mediaType field
        try {
            JsonNode root = objectMapper.readTree(manifestBytes);
            if (root.has("mediaType")) {
                String bodyMediaType = root.get("mediaType").asText();
                if (bodyMediaType != null && !bodyMediaType.isBlank()) {
                    return bodyMediaType;
                }
            }
        } catch (Exception e) {
            log.debug("Could not parse manifest to detect media type: {}", e.getMessage());
        }

        return headerContentType;
    }

    private String computeSha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Computes SHA-256 digest by streaming from a file — never loads the full file into memory.
     */
    private String computeSha256FromFile(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new BufferedInputStream(new FileInputStream(file.toFile()))) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    md.update(buf, 0, n);
                }
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Evicts upload sessions that have been idle longer than {@link #UPLOAD_TTL},
     * deleting their temp files to prevent disk and memory leaks from abandoned uploads.
     */
    private void evictExpiredUploads() {
        Instant cutoff = Instant.now().minus(UPLOAD_TTL);
        Iterator<Map.Entry<String, UploadState>> it = activeUploads.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, UploadState> entry = it.next();
            UploadState state = entry.getValue();
            if (state.createdAt().isBefore(cutoff)) {
                log.info("Evicting expired Docker upload session: uuid={} age={}min",
                        entry.getKey(), Duration.between(state.createdAt(), Instant.now()).toMinutes());
                if (state.tempFile() != null) {
                    try {
                        Files.deleteIfExists(state.tempFile());
                    } catch (IOException e) {
                        log.warn("Failed to delete temp file for expired upload {}: {}",
                                entry.getKey(), e.getMessage());
                    }
                }
                it.remove();
            }
        }
    }

    private FormatResponse dockerError(String code, String message, int statusCode) {
        String json = formatJson(Map.of("errors", List.of(Map.of(
                "code", code,
                "message", message,
                "detail", Map.of()))));
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (statusCode == 404) {
            return new NotFoundResponse(json);
        }
        return new ErrorResponse(statusCode, json);
    }

    private String formatJson(Map<String, ?> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }
}
