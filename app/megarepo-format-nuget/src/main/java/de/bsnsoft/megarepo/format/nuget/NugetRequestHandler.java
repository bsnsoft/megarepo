package de.bsnsoft.megarepo.format.nuget;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import de.bsnsoft.megarepo.database.repository.AssetJpaRepository;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import de.bsnsoft.megarepo.format.nuget.index.FlatContainerGenerator;
import de.bsnsoft.megarepo.format.nuget.index.RegistrationGenerator;
import de.bsnsoft.megarepo.format.nuget.naming.NugetNames;
import de.bsnsoft.megarepo.format.nuget.proxy.NugetProxyUrlRewriter;
import de.bsnsoft.megarepo.format.nuget.proxy.UpstreamServiceIndexResolver;
import de.bsnsoft.megarepo.format.nuget.proxy.UpstreamServiceIndexResolver.UpstreamResources;
import de.bsnsoft.megarepo.format.nuget.push.NugetPushHandler;
import de.bsnsoft.megarepo.format.nuget.search.NugetSearchService;
import de.bsnsoft.megarepo.format.nuget.index.ServiceIndexGenerator;
import de.bsnsoft.megarepo.format.nuget.v2.NugetV2FeedGenerator;
import de.bsnsoft.megarepo.repository.proxy.ProxyCacheChecker;
import de.bsnsoft.megarepo.repository.proxy.ProxyFetchService;
import de.bsnsoft.megarepo.repository.proxy.RemoteHttpClient;
import de.bsnsoft.megarepo.storage.BlobStoreManager;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;

/**
 * NuGet V3 protocol handler.
 *
 * <p>Hosted layout (all paths lowercase, dotnet-client convention):
 * <pre>
 *   index.json                                            service index
 *   api/v2/package                                        push (PUT) / delete (DELETE …/{id}/{version})
 *   v3-flatcontainer/{id}/index.json                      version list
 *   v3-flatcontainer/{id}/{version}/{id}.{version}.nupkg  package download
 *   v3-flatcontainer/{id}/{version}/{id}.nuspec           manifest download
 *   v3/registrations/{id}/index.json                      registration index
 *   v3/registrations/{id}/{version}.json                  registration leaf
 *   v3/search?q=…&amp;skip=…&amp;take=…                   search
 * </pre>
 *
 * <p>Proxy repositories serve a locally generated service index whose
 * resources point back at MegaRepo; flat-container and registration requests
 * are resolved against the upstream feed's service index and cached.
 */
@Component
public class NugetRequestHandler implements FormatRequestHandler {

    private static final Logger log = LoggerFactory.getLogger(NugetRequestHandler.class);
    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private static final String SERVICE_INDEX = "index.json";
    private static final String FLAT_CONTAINER_PREFIX = "v3-flatcontainer/";
    private static final String REGISTRATIONS_PREFIX = "v3/registrations/";
    private static final String SEARCH_PATH = "v3/search";
    private static final String PUSH_PATH = "api/v2/package";

    private final AssetJpaRepository assetRepository;
    private final ComponentJpaRepository componentRepository;
    private final BlobStoreManager blobStoreManager;
    private final ServiceIndexGenerator serviceIndexGenerator;
    private final FlatContainerGenerator flatContainerGenerator;
    private final RegistrationGenerator registrationGenerator;
    private final NugetSearchService searchService;
    private final NugetPushHandler pushHandler;
    private final NugetCoordinateExtractor coordinateExtractor;
    private final NugetProxyUrlRewriter proxyUrlRewriter;
    private final NugetV2FeedGenerator v2FeedGenerator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired(required = false)
    private ProxyFetchService proxyFetchService;

    @Autowired(required = false)
    private ProxyCacheChecker proxyCacheChecker;

    @Autowired(required = false)
    private UpstreamServiceIndexResolver upstreamResolver;

    public NugetRequestHandler(
            AssetJpaRepository assetRepository,
            ComponentJpaRepository componentRepository,
            BlobStoreManager blobStoreManager,
            ServiceIndexGenerator serviceIndexGenerator,
            FlatContainerGenerator flatContainerGenerator,
            RegistrationGenerator registrationGenerator,
            NugetSearchService searchService,
            NugetPushHandler pushHandler,
            NugetCoordinateExtractor coordinateExtractor,
            NugetProxyUrlRewriter proxyUrlRewriter,
            NugetV2FeedGenerator v2FeedGenerator) {
        this.assetRepository = assetRepository;
        this.componentRepository = componentRepository;
        this.blobStoreManager = blobStoreManager;
        this.serviceIndexGenerator = serviceIndexGenerator;
        this.flatContainerGenerator = flatContainerGenerator;
        this.registrationGenerator = registrationGenerator;
        this.searchService = searchService;
        this.pushHandler = pushHandler;
        this.coordinateExtractor = coordinateExtractor;
        this.proxyUrlRewriter = proxyUrlRewriter;
        this.v2FeedGenerator = v2FeedGenerator;
    }

    // ── Hosted ──────────────────────────────────────────────────────────

    @Override
    public FormatResponse handleHostedGet(RepositoryConfig repo, String path, HttpServletRequest request) {
        String normalized = normalize(path);

        if (normalized.isEmpty() || normalized.equals(SERVICE_INDEX)) {
            return serviceIndexGenerator.generate(repo.name(), resolveBaseUrl(request));
        }

        if (normalized.startsWith(FLAT_CONTAINER_PREFIX)) {
            return handleHostedFlatContainer(repo, normalized);
        }

        if (normalized.startsWith(REGISTRATIONS_PREFIX)) {
            return handleHostedRegistration(repo, normalized, resolveBaseUrl(request));
        }

        if (normalized.equals(SEARCH_PATH)) {
            return searchService.search(
                    repo,
                    request.getParameter("q"),
                    intParam(request, "skip", 0),
                    intParam(request, "take", 20),
                    resolveBaseUrl(request));
        }

        FormatResponse v2 = handleV2(repo, normalized, request);
        if (v2 != null) {
            return v2;
        }

        return new NotFoundResponse("Not found: " + path);
    }

    // ── NuGet V2 (OData) read endpoints ─────────────────────────────────

    private static final String V2_METADATA = "$metadata";
    private static final String V2_FIND = "FindPackagesById";
    private static final String V2_SEARCH = "Search";
    private static final String V2_PACKAGES = "Packages";

    /**
     * Handle the legacy NuGet V2 (OData) read surface for hosted repositories.
     * Returns {@code null} when the path is not a recognized V2 endpoint so the
     * caller can fall through to its 404.
     */
    private FormatResponse handleV2(RepositoryConfig repo, String rawNormalized, HttpServletRequest request) {
        // The router hands us the still-URL-encoded path. NuGet V2 clients may
        // percent-encode the OData segments ($metadata, Packages(Id='…'…)); decode
        // so the literal and encoded forms both match.
        String normalized = urlDecode(rawNormalized);

        if (normalized.equals(V2_METADATA)) {
            return v2FeedGenerator.metadata();
        }

        // FindPackagesById() / FindPackagesById  (id from query string, OData-quoted)
        if (normalized.equals(V2_FIND) || normalized.equals(V2_FIND + "()")) {
            String id = odataParam(request, "id");
            if (id == null || id.isBlank()) {
                return new ErrorResponse(400, "FindPackagesById requires an 'id' parameter");
            }
            return v2FeedGenerator.findPackagesById(repo, id, resolveBaseUrl(request));
        }

        // Search()  (searchTerm from query string, OData-quoted)
        if (normalized.equals(V2_SEARCH) || normalized.equals(V2_SEARCH + "()")) {
            String term = odataParam(request, "searchTerm");
            return v2FeedGenerator.search(repo, term, resolveBaseUrl(request));
        }

        // Packages(Id='X',Version='Y')  — id+version embedded in the path segment
        if (normalized.startsWith(V2_PACKAGES + "(") && normalized.endsWith(")")) {
            String inner = normalized.substring(V2_PACKAGES.length() + 1, normalized.length() - 1);
            String id = odataKey(inner, "Id");
            String version = odataKey(inner, "Version");
            if (id == null || version == null) {
                return new ErrorResponse(400, "Expected Packages(Id='…',Version='…')");
            }
            return v2FeedGenerator.packageEntry(repo, id, version, resolveBaseUrl(request));
        }

        return null;
    }

    /** Reads an OData function parameter, stripping the surrounding single quotes NuGet sends. */
    private static String odataParam(HttpServletRequest request, String name) {
        return unquote(request.getParameter(name));
    }

    /** Extracts a key like {@code Id='X'} from a {@code Packages(...)} argument list. */
    private static String odataKey(String inner, String key) {
        for (String part : inner.split(",")) {
            String trimmed = part.trim();
            if (trimmed.regionMatches(true, 0, key + "=", 0, key.length() + 1)) {
                return unquote(trimmed.substring(key.length() + 1));
            }
        }
        return null;
    }

    private static String unquote(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if (v.length() >= 2 && v.startsWith("'") && v.endsWith("'")) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    private static String urlDecode(String value) {
        if (value == null || value.indexOf('%') < 0) {
            return value;
        }
        try {
            return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value; // malformed encoding — match against the raw form
        }
    }

    private FormatResponse handleHostedFlatContainer(RepositoryConfig repo, String normalized) {
        String rest = normalized.substring(FLAT_CONTAINER_PREFIX.length()).toLowerCase(Locale.ROOT);
        String[] segments = rest.split("/");
        if (segments.length == 2 && "index.json".equals(segments[1])) {
            return flatContainerGenerator.versionsIndex(repo, segments[0]);
        }
        if (segments.length == 3) {
            return lookupAsset(repo, FLAT_CONTAINER_PREFIX + rest);
        }
        return new NotFoundResponse("Not found: " + normalized);
    }

    private FormatResponse handleHostedRegistration(RepositoryConfig repo, String normalized, String baseUrl) {
        String rest = normalized.substring(REGISTRATIONS_PREFIX.length()).toLowerCase(Locale.ROOT);
        String[] segments = rest.split("/");
        if (segments.length == 2 && "index.json".equals(segments[1])) {
            return registrationGenerator.registrationIndex(repo, segments[0], baseUrl);
        }
        if (segments.length == 2 && segments[1].endsWith(".json")) {
            String version = segments[1].substring(0, segments[1].length() - ".json".length());
            return registrationGenerator.registrationLeaf(repo, segments[0], version, baseUrl);
        }
        return new NotFoundResponse("Not found: " + normalized);
    }

    @Override
    public FormatResponse handleHostedPut(RepositoryConfig repo, String path, HttpServletRequest request) {
        String normalized = normalize(path);
        if (normalized.equals(PUSH_PATH) || normalized.equals(PUSH_PATH + "/")) {
            return pushHandler.handlePush(repo, request);
        }
        return new ErrorResponse(400,
                "NuGet packages must be pushed to api/v2/package (dotnet nuget push)");
    }

    @Override
    public FormatResponse handleHostedDelete(RepositoryConfig repo, String path, HttpServletRequest request) {
        String normalized = normalize(path);

        // DELETE api/v2/package/{id}/{version} — the protocol's delete/unlist endpoint
        if (normalized.startsWith(PUSH_PATH + "/")) {
            String[] segments = normalized.substring(PUSH_PATH.length() + 1).split("/");
            if (segments.length == 2) {
                return deletePackageVersion(repo, NugetNames.lowerId(segments[0]),
                        NugetNames.lowerVersion(segments[1]));
            }
            return new ErrorResponse(400, "Expected api/v2/package/{id}/{version}");
        }

        // Direct asset path delete (Web-UI / housekeeping)
        return deleteAssetByPath(repo, normalized.toLowerCase(Locale.ROOT));
    }

    private FormatResponse deletePackageVersion(RepositoryConfig repo, String idLower, String versionLower) {
        Optional<ComponentEntity> componentOpt = componentRepository
                .findByRepositoryIdAndNamespaceAndNameAndVersion(repo.id(), null, idLower, versionLower);
        if (componentOpt.isEmpty()) {
            return new NotFoundResponse("Package not found: " + idLower + " " + versionLower);
        }

        String basePath = FLAT_CONTAINER_PREFIX + idLower + "/" + versionLower + "/";
        BlobStore blobStore = blobStoreManager.get(repo.blobStoreName());
        List<AssetEntity> assets = assetRepository.findByRepositoryIdAndPathStartingWith(repo.id(), basePath);
        for (AssetEntity asset : assets) {
            if (asset.getBlobRef() != null) {
                blobStore.delete(BlobRef.parse(asset.getBlobRef()));
            }
            assetRepository.delete(asset);
        }
        componentRepository.delete(componentOpt.get());

        log.info("Deleted NuGet package {} {} from repository {}", idLower, versionLower, repo.name());
        return new ContentResponse(InputStream.nullInputStream(), "application/json", 0, Map.of(), Map.of());
    }

    private FormatResponse deleteAssetByPath(RepositoryConfig repo, String path) {
        Optional<AssetEntity> assetOpt = assetRepository.findByRepositoryIdAndPath(repo.id(), path);
        if (assetOpt.isEmpty()) {
            return new NotFoundResponse("Asset not found: " + path);
        }
        AssetEntity asset = assetOpt.get();
        if (asset.getBlobRef() != null) {
            blobStoreManager.get(repo.blobStoreName()).delete(BlobRef.parse(asset.getBlobRef()));
        }
        assetRepository.delete(asset);
        return new ContentResponse(InputStream.nullInputStream(), "application/json", 0, Map.of(), Map.of());
    }

    // ── Proxy ───────────────────────────────────────────────────────────

    @Override
    public FormatResponse handleProxyGet(RepositoryConfig repo, String path, HttpServletRequest request) {
        String normalized = normalize(path);

        // The service index is always generated locally: its resources must
        // point at MegaRepo, never at the upstream feed.
        if (normalized.isEmpty() || normalized.equals(SERVICE_INDEX)) {
            return serviceIndexGenerator.generate(repo.name(), resolveBaseUrl(request));
        }

        if (normalized.startsWith(FLAT_CONTAINER_PREFIX)) {
            return handleProxyFlatContainer(repo, normalized.toLowerCase(Locale.ROOT));
        }

        if (normalized.startsWith(REGISTRATIONS_PREFIX)) {
            return handleProxyRegistration(repo, normalized.toLowerCase(Locale.ROOT), resolveBaseUrl(request));
        }

        if (normalized.equals(SEARCH_PATH)) {
            return handleProxySearch(repo, request);
        }

        return new NotFoundResponse("Not found: " + path);
    }

    private FormatResponse handleProxyFlatContainer(RepositoryConfig repo, String path) {
        boolean metadata = path.endsWith("/index.json");

        Optional<AssetEntity> cachedAsset = assetRepository.findByRepositoryIdAndPath(repo.id(), path);
        if (cachedAsset.isPresent() && cachedAsset.get().getBlobRef() != null) {
            AssetEntity asset = cachedAsset.get();
            boolean expired = proxyCacheChecker != null
                    && (metadata
                            ? proxyCacheChecker.isMetadataExpired(asset, repo)
                            : proxyCacheChecker.isExpired(asset, repo));
            if (!expired) {
                log.debug("NuGet proxy cache hit for repo={} path={}", repo.name(), path);
                return serveCachedAsset(repo, asset);
            }
        }

        if (proxyFetchService == null || upstreamResolver == null) {
            return lookupAsset(repo, path);
        }

        Optional<UpstreamResources> upstream = upstreamResolver.resolve(repo);
        if (upstream.isEmpty()) {
            return staleOr(repo, cachedAsset,
                    new ErrorResponse(502, "Failed to resolve upstream NuGet service index"));
        }

        String suffix = path.substring(FLAT_CONTAINER_PREFIX.length());
        String upstreamUrl = upstream.get().flatContainerBase() + "/" + suffix;
        FormatResponse fetched = proxyFetchService.fetchFromUrl(repo, path, upstreamUrl, coordinateExtractor);
        if (fetched instanceof ErrorResponse err && err.statusCode() >= 500) {
            return staleOr(repo, cachedAsset, fetched);
        }
        return fetched;
    }

    private FormatResponse handleProxyRegistration(RepositoryConfig repo, String path, String baseUrl) {
        Optional<AssetEntity> cachedAsset = assetRepository.findByRepositoryIdAndPath(repo.id(), path);
        if (cachedAsset.isPresent() && cachedAsset.get().getBlobRef() != null) {
            AssetEntity asset = cachedAsset.get();
            boolean expired = proxyCacheChecker != null && proxyCacheChecker.isMetadataExpired(asset, repo);
            if (!expired) {
                log.debug("NuGet proxy registration cache hit for repo={} path={}", repo.name(), path);
                return serveCachedAsset(repo, asset);
            }
        }

        if (upstreamResolver == null) {
            return lookupAsset(repo, path);
        }

        Optional<UpstreamResources> upstream = upstreamResolver.resolve(repo);
        if (upstream.isEmpty() || upstream.get().registrationsBase() == null) {
            return staleOr(repo, cachedAsset,
                    new ErrorResponse(502, "Upstream feed exposes no registrations resource"));
        }

        String suffix = path.substring(REGISTRATIONS_PREFIX.length());
        String upstreamUrl = upstream.get().registrationsBase() + "/" + suffix;
        try {
            RemoteHttpClient.RemoteResponse response = upstreamResolver.fetchWithRepoAuth(repo, upstreamUrl);
            if (response.statusCode() == 404) {
                closeQuietly(response.body());
                return new NotFoundResponse("Not found on upstream: " + path);
            }
            if (response.statusCode() != 200 || response.body() == null) {
                closeQuietly(response.body());
                return staleOr(repo, cachedAsset, new ErrorResponse(
                        502, "Upstream returned status %d for: %s".formatted(response.statusCode(), path)));
            }

            byte[] raw;
            try (InputStream body = response.body()) {
                raw = body.readAllBytes();
            }
            String json = new String(gunzipIfNeeded(raw), StandardCharsets.UTF_8);
            String rewritten = proxyUrlRewriter.rewrite(
                    json, upstream.get(), baseUrl + "/repository/" + repo.name());
            byte[] rewrittenBytes = rewritten.getBytes(StandardCharsets.UTF_8);

            cacheMetadataAsset(repo, path, rewrittenBytes);

            return new ContentResponse(
                    new ByteArrayInputStream(rewrittenBytes),
                    "application/json",
                    rewrittenBytes.length,
                    Map.of(),
                    Map.of());
        } catch (IOException e) {
            log.warn("Failed to proxy NuGet registration for repo={} path={}: {}",
                    repo.name(), path, e.getMessage());
            return staleOr(repo, cachedAsset,
                    new ErrorResponse(502, "Failed to fetch from upstream: " + e.getMessage()));
        }
    }

    private FormatResponse handleProxySearch(RepositoryConfig repo, HttpServletRequest request) {
        if (upstreamResolver == null) {
            return new ErrorResponse(502, "Proxy support is not available");
        }
        Optional<UpstreamResources> upstream = upstreamResolver.resolve(repo);
        if (upstream.isEmpty() || upstream.get().searchBase() == null) {
            return new ErrorResponse(502, "Upstream feed exposes no search resource");
        }

        String query = request.getQueryString();
        String upstreamUrl = upstream.get().searchBase() + (query != null ? "?" + query : "");
        try {
            RemoteHttpClient.RemoteResponse response = upstreamResolver.fetchWithRepoAuth(repo, upstreamUrl);
            if (response.statusCode() != 200 || response.body() == null) {
                closeQuietly(response.body());
                return new ErrorResponse(502,
                        "Upstream search returned status %d".formatted(response.statusCode()));
            }
            byte[] raw;
            try (InputStream body = response.body()) {
                raw = body.readAllBytes();
            }
            String json = new String(gunzipIfNeeded(raw), StandardCharsets.UTF_8);
            String rewritten = proxyUrlRewriter.rewrite(
                    json, upstream.get(), resolveBaseUrl(request) + "/repository/" + repo.name());
            byte[] rewrittenBytes = rewritten.getBytes(StandardCharsets.UTF_8);
            return new ContentResponse(
                    new ByteArrayInputStream(rewrittenBytes),
                    "application/json",
                    rewrittenBytes.length,
                    Map.of(),
                    Map.of());
        } catch (IOException e) {
            return new ErrorResponse(502, "Failed to query upstream search: " + e.getMessage());
        }
    }

    // ── Group ───────────────────────────────────────────────────────────

    @Override
    public FormatResponse handleGroupGet(RepositoryConfig repo, String path, HttpServletRequest request) {
        // Group dispatch happens in GroupHandler; this is the fallback for
        // nested groups — serve from the local cache only.
        return lookupAsset(repo, normalize(path).toLowerCase(Locale.ROOT));
    }

    @Override
    public boolean isMetadataPath(String path) {
        String normalized = normalize(path);
        if (normalized.isEmpty() || normalized.equals(SERVICE_INDEX) || normalized.equals(SEARCH_PATH)) {
            return true;
        }
        if (normalized.startsWith(REGISTRATIONS_PREFIX)) {
            return true;
        }
        return normalized.startsWith(FLAT_CONTAINER_PREFIX) && normalized.endsWith("/index.json");
    }

    @Override
    public Optional<FormatResponse> mergeMetadata(
            RepositoryConfig groupRepo, String path, List<FormatResponse> memberResponses) {
        String normalized = normalize(path);

        // The service index must carry the group's own URLs — member indexes
        // would point clients at the member repositories.
        if (normalized.isEmpty() || normalized.equals(SERVICE_INDEX)) {
            String baseUrl = resolveCurrentBaseUrl();
            if (baseUrl != null) {
                return Optional.of(serviceIndexGenerator.generate(groupRepo.name(), baseUrl));
            }
            return Optional.empty();
        }

        // Flat-container version list: union of all member version lists.
        if (normalized.startsWith(FLAT_CONTAINER_PREFIX) && normalized.endsWith("/index.json")) {
            return mergeVersionIndexes(memberResponses);
        }

        // Registrations/search: first non-404 member wins (router fallback).
        return Optional.empty();
    }

    private Optional<FormatResponse> mergeVersionIndexes(List<FormatResponse> memberResponses) {
        TreeSet<String> versions = new TreeSet<>(NugetNames.versionOrder());
        boolean anyFound = false;
        for (FormatResponse response : memberResponses) {
            if (!(response instanceof ContentResponse content)) {
                continue;
            }
            try (InputStream in = content.content()) {
                JsonNode root = objectMapper.readTree(in);
                JsonNode versionsNode = root.path("versions");
                if (versionsNode.isArray()) {
                    anyFound = true;
                    versionsNode.forEach(v -> versions.add(v.asText().toLowerCase(Locale.ROOT)));
                }
            } catch (IOException e) {
                log.debug("Skipping unparseable member version index: {}", e.getMessage());
            }
        }
        if (!anyFound) {
            return Optional.empty();
        }

        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode versionsNode = objectMapper.createArrayNode();
        versions.forEach(versionsNode::add);
        root.set("versions", versionsNode);
        try {
            byte[] json = objectMapper.writeValueAsBytes(root);
            return Optional.of(new ContentResponse(
                    new ByteArrayInputStream(json), "application/json", json.length, Map.of(), Map.of()));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private static String normalize(String path) {
        if (path == null) {
            return "";
        }
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    private static int intParam(HttpServletRequest request, String name, int defaultValue) {
        String value = request.getParameter(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    static byte[] gunzipIfNeeded(byte[] data) throws IOException {
        if (data.length >= 2 && (data[0] & 0xFF) == 0x1f && (data[1] & 0xFF) == 0x8b) {
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(data))) {
                return gzip.readAllBytes();
            }
        }
        return data;
    }

    private FormatResponse staleOr(
            RepositoryConfig repo, Optional<AssetEntity> cachedAsset, FormatResponse fallback) {
        if (cachedAsset.isPresent() && cachedAsset.get().getBlobRef() != null) {
            log.info("Upstream unavailable, serving stale cache for repo={} path={}",
                    repo.name(), cachedAsset.get().getPath());
            return serveCachedAsset(repo, cachedAsset.get());
        }
        return fallback;
    }

    /** Creates or refreshes the cached (rewritten) registration JSON asset. */
    private void cacheMetadataAsset(RepositoryConfig repo, String path, byte[] content) {
        try {
            BlobStore blobStore = blobStoreManager.get(repo.blobStoreName());
            Instant now = Instant.now();
            AssetEntity asset = assetRepository
                    .findByRepositoryIdAndPath(repo.id(), path)
                    .orElseGet(() -> {
                        var newAsset = new AssetEntity();
                        newAsset.setRepositoryId(repo.id());
                        newAsset.setPath(path);
                        newAsset.setFormat(repo.format());
                        newAsset.setCreatedAt(now);
                        newAsset.setCreatedBy("proxy");
                        return newAsset;
                    });

            if (asset.getBlobRef() != null) {
                blobStore.delete(BlobRef.parse(asset.getBlobRef()));
            }
            BlobRef blobRef = blobStore.store(
                    new ByteArrayInputStream(content), content.length, Map.of("Content-Type", "application/json"));
            asset.setBlobRef(blobRef.toExternalForm());
            asset.setContentType("application/json");
            asset.setSize((long) content.length);
            asset.setLastModified(now);
            asset.setUpdatedAt(now);
            assetRepository.save(asset);
        } catch (Exception e) {
            log.warn("Failed to cache NuGet registration metadata for repo={} path={}: {}",
                    repo.name(), path, e.getMessage());
        }
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

    private String resolveBaseUrl(HttpServletRequest request) {
        String scheme = request.getScheme();
        String serverName = request.getServerName();
        int serverPort = request.getServerPort();

        String forwardedProto = request.getHeader("X-Forwarded-Proto");
        String forwardedHost = request.getHeader("X-Forwarded-Host");
        if (forwardedProto != null) {
            scheme = forwardedProto;
        }
        if (forwardedHost != null) {
            serverName = forwardedHost;
            serverPort = -1; // host header may include the port
        }

        if (serverPort == 80 || serverPort == 443 || serverPort <= 0) {
            return scheme + "://" + serverName;
        }
        return scheme + "://" + serverName + ":" + serverPort;
    }

    /** Base URL of the current request, for code paths without direct request access (group merge). */
    private String resolveCurrentBaseUrl() {
        try {
            if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
                return resolveBaseUrl(attrs.getRequest());
            }
        } catch (Exception e) {
            log.debug("Could not resolve current request base URL", e);
        }
        return null;
    }

    private static void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (IOException ignored) {
                // best effort
            }
        }
    }
}
