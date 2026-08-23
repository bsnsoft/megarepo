package de.bsnsoft.megarepo.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import de.bsnsoft.megarepo.database.entity.BlobStoreEntity;
import de.bsnsoft.megarepo.database.entity.RepositoryEntity;
import de.bsnsoft.megarepo.database.repository.AssetJpaRepository;
import de.bsnsoft.megarepo.database.repository.BlobStoreJpaRepository;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import de.bsnsoft.megarepo.database.repository.NegativeCacheJpaRepository;
import de.bsnsoft.megarepo.database.repository.RepositoryJpaRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage for the npm <em>proxy</em> repository — the behaviour reported in
 * GitHub issue #1 ("npm proxy caches nothing, Browse stays empty").
 *
 * <p>Three defects are pinned down here:
 *
 * <ol>
 *   <li><b>Packuments were passed through verbatim.</b> {@code dist.tarball} still pointed at the
 *       upstream registry, so npm/pnpm downloaded every package straight from upstream: nothing was
 *       cached and no components were ever created. Fixed by
 *       {@code de.bsnsoft.megarepo.format.npm.proxy.NpmProxyUrlRewriter}, wired into
 *       {@code NpmRequestHandler.handleProxyMetadata}.</li>
 *   <li><b>The unscoped registry tarball layout was not recognised.</b>
 *       {@code NpmCoordinateExtractor} only knew {@code -/name-version.tgz}, not the
 *       {@code name/-/name-version.tgz} form real registries use — so proxied unscoped packages got
 *       cached as assets but never registered as components.
 *       {@link #downloadingRewrittenTarballCreatesComponent()} is the regression test for that.</li>
 *   <li><b>The abbreviated packument had no separate cache slot.</b> When a client sends
 *       {@code Accept: application/vnd.npm.install-v1+json}, that Accept is now forwarded upstream
 *       and the (much smaller) response is cached under the {@code .npm-abbreviated/} path prefix,
 *       so the two representations can never overwrite one another.</li>
 * </ol>
 *
 * <p>The upstream registry is stubbed with a plain JDK {@link HttpServer} on an ephemeral port
 * (no WireMock/MockWebServer is available, and no dependency may be added for this). It records
 * every request it receives together with the {@code Accept} header, which is what lets the cache
 * and Accept-forwarding assertions below be made from the outside.
 *
 * <p>Note on shared state: the Spring context and the PostgreSQL database are shared across all
 * integration-test classes and are never reset — not even between JVM runs, since the database is
 * external. Fixtures are therefore created defensively (find-or-create), the repository's
 * {@code remoteUrl} is rewritten to the stub's <em>current</em> port on every setup, and this
 * repository's cached assets/components are purged before each test so cache assertions describe
 * this run only.
 */
class NpmProxyRepositoryIntegrationTest extends BaseIntegrationTest {

    /** Deliberately not "npm-proxy": that name belongs to the repository seeded by FirstRunSetup. */
    private static final String REPO_NAME = "it-npm-proxy";

    private static final String BLOB_STORE_NAME = "default";

    /** The media type npm and pnpm use to ask for the abbreviated packument. */
    private static final String ABBREVIATED_MEDIA_TYPE = "application/vnd.npm.install-v1+json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Packages the stub registry knows about. Each test uses its own package so the recorded
     * upstream interactions stay meaningful regardless of the order JUnit picks.
     */
    private static final Map<String, PackageFixture> FIXTURES = buildFixtures();

    /** Every request the stub upstream saw, in arrival order. */
    private static final Queue<UpstreamRequest> UPSTREAM_REQUESTS = new ConcurrentLinkedQueue<>();

    private static HttpServer upstream;
    private static int upstreamPort;

    @Autowired
    private RepositoryJpaRepository repositoryJpaRepository;

    @Autowired
    private BlobStoreJpaRepository blobStoreJpaRepository;

    @Autowired
    private AssetJpaRepository assetJpaRepository;

    @Autowired
    private ComponentJpaRepository componentJpaRepository;

    @Autowired
    private NegativeCacheJpaRepository negativeCacheJpaRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ---------------------------------------------------------------- stub upstream registry

    /**
     * A package the stub registry serves.
     *
     * @param name        the npm package name, exactly as it appears in the request path
     * @param version     the single version in the packument
     * @param tarballPath the registry-layout tarball path, relative to the registry root
     * @param tarball     fixed bytes served for that path (not a real gzip/tar — nothing unpacks it)
     */
    private record PackageFixture(String name, String version, String tarballPath, byte[] tarball) {}

    private record UpstreamRequest(String path, String accept) {}

    private static Map<String, PackageFixture> buildFixtures() {
        Map<String, PackageFixture> fixtures = new LinkedHashMap<>();
        addFixture(fixtures, "left-pad", "1.3.0", "left-pad/-/left-pad-1.3.0.tgz");
        addFixture(fixtures, "@acme/widget", "2.0.0", "@acme/widget/-/widget-2.0.0.tgz");
        addFixture(fixtures, "abbrev-probe", "0.1.0", "abbrev-probe/-/abbrev-probe-0.1.0.tgz");
        addFixture(fixtures, "cache-probe", "0.0.1", "cache-probe/-/cache-probe-0.0.1.tgz");
        return Map.copyOf(fixtures);
    }

    private static void addFixture(
            Map<String, PackageFixture> fixtures, String name, String version, String tarballPath) {
        byte[] tarball = ("tgz-bytes:" + name + "@" + version).getBytes(StandardCharsets.UTF_8);
        fixtures.put(name, new PackageFixture(name, version, tarballPath, tarball));
    }

    @BeforeAll
    static void startStubRegistry() throws IOException {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // One root context with manual dispatch: scoped package names contain a slash
        // ("@acme/widget"), which per-package contexts would turn into a path hierarchy.
        upstream.createContext("/", NpmProxyRepositoryIntegrationTest::handleUpstreamRequest);
        upstream.setExecutor(Executors.newCachedThreadPool());
        upstream.start();
        upstreamPort = upstream.getAddress().getPort();
    }

    @AfterAll
    static void stopStubRegistry() {
        if (upstream != null) {
            upstream.stop(0);
        }
    }

    private static void handleUpstreamRequest(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String accept = exchange.getRequestHeaders().getFirst("Accept");
        UPSTREAM_REQUESTS.add(new UpstreamRequest(path, accept));

        try (exchange) {
            String key = path.startsWith("/") ? path.substring(1) : path;

            PackageFixture tarballOwner = FIXTURES.values().stream()
                    .filter(f -> f.tarballPath().equals(key))
                    .findFirst()
                    .orElse(null);
            if (tarballOwner != null) {
                respond(exchange, 200, "application/octet-stream", tarballOwner.tarball());
                return;
            }

            PackageFixture fixture = FIXTURES.get(key);
            if (fixture != null) {
                boolean abbreviated = accept != null
                        && accept.toLowerCase(Locale.ROOT).contains(ABBREVIATED_MEDIA_TYPE);
                respond(
                        exchange,
                        200,
                        abbreviated ? ABBREVIATED_MEDIA_TYPE : "application/json",
                        packument(fixture, abbreviated));
                return;
            }

            respond(exchange, 404, "application/json",
                    "{\"error\":\"Not found\"}".getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void respond(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /**
     * Builds a packument whose {@code dist.tarball} is an absolute URL back into the stub — exactly
     * what a real registry emits, and exactly what must be rewritten on the way through the proxy.
     *
     * <p>The abbreviated variant carries a {@code _abbreviated} marker and omits {@code readme},
     * so the two representations are trivially distinguishable in an assertion.
     */
    private static byte[] packument(PackageFixture fixture, boolean abbreviated) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("name", fixture.name());
        root.set("dist-tags", MAPPER.createObjectNode().put("latest", fixture.version()));
        if (abbreviated) {
            root.put("_abbreviated", true);
            root.put("modified", "2026-08-23T00:00:00.000Z");
        } else {
            root.put("_id", fixture.name());
            root.put("readme", "# " + fixture.name() + "\n\nFull packument served by the test stub.");
        }

        ObjectNode version = MAPPER.createObjectNode();
        version.put("name", fixture.name());
        version.put("version", fixture.version());
        ObjectNode dist = MAPPER.createObjectNode();
        dist.put("tarball", upstreamBaseUrl() + "/" + fixture.tarballPath());
        dist.put("shasum", "0000000000000000000000000000000000000000");
        version.set("dist", dist);

        ObjectNode versions = MAPPER.createObjectNode();
        versions.set(fixture.version(), version);
        root.set("versions", versions);

        try {
            return MAPPER.writeValueAsBytes(root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String upstreamBaseUrl() {
        return "http://127.0.0.1:" + upstreamPort;
    }

    private static long upstreamRequestCount(String path) {
        return UPSTREAM_REQUESTS.stream().filter(r -> r.path().equals(path)).count();
    }

    // ---------------------------------------------------------------- fixture setup

    @BeforeEach
    void setUp() {
        if (blobStoreJpaRepository.findById(BLOB_STORE_NAME).isEmpty()) {
            var blobStore = new BlobStoreEntity();
            blobStore.setName(BLOB_STORE_NAME);
            blobStore.setType("file");
            blobStore.setConfig(Map.of("path", "data/blobs/default"));
            blobStore.setCreatedAt(Instant.now());
            blobStore.setUpdatedAt(Instant.now());
            blobStoreJpaRepository.save(blobStore);
        }

        // Find-or-create: the row survives previous JVM runs, but the stub's port does not, so the
        // remoteUrl has to be re-pointed at the server that is actually listening right now.
        RepositoryEntity repo = repositoryJpaRepository.findByName(REPO_NAME).orElseGet(() -> {
            var created = new RepositoryEntity();
            created.setName(REPO_NAME);
            created.setCreatedAt(Instant.now());
            return created;
        });
        repo.setFormat("npm");
        repo.setType("PROXY");
        repo.setOnline(true);
        repo.setBlobStoreName(BLOB_STORE_NAME);
        repo.setAttributes(Map.of("proxy", Map.of("remoteUrl", upstreamBaseUrl())));
        repo.setUpdatedAt(Instant.now());
        UUID repoId = repositoryJpaRepository.save(repo).getId();

        // Cached packuments from an earlier run still reference the old stub port and would be
        // served (metadata TTL is 5 minutes) without any upstream call at all. Purge this
        // repository's cache so every test starts from a cold, current state.
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assetJpaRepository.deleteAll(assetJpaRepository.findAllByRepositoryId(repoId));
            componentJpaRepository.deleteAll(
                    componentJpaRepository.findByRepositoryId(repoId, Pageable.unpaged()).getContent());
            negativeCacheJpaRepository.deleteByRepositoryId(repoId);
        });

        UPSTREAM_REQUESTS.clear();
    }

    // ---------------------------------------------------------------- tests

    /**
     * The core of GitHub issue #1: without rewriting, npm would read the upstream URL straight out
     * of the proxied packument and bypass MegaRepo entirely.
     */
    @Test
    void proxyRewritesTarballUrlsToMegaRepo() throws IOException {
        JsonNode packument = fetchPackument("left-pad");
        String tarball = tarballUrl(packument, "1.3.0");

        assertTrue(
                tarball.startsWith(repositoryBase()),
                "dist.tarball must point back at MegaRepo (" + repositoryBase() + "), was: " + tarball);
        assertFalse(
                tarball.contains("127.0.0.1:" + upstreamPort),
                "dist.tarball must no longer reference the upstream registry, was: " + tarball);
        assertTrue(
                tarball.endsWith("left-pad/-/left-pad-1.3.0.tgz"),
                "the upstream path must be preserved verbatim behind the new base, was: " + tarball);
    }

    /**
     * npm and pnpm URL-encode the slash in a scoped name, so the proxy is hit at
     * {@code @acme%2Fwidget}. MegaRepo decodes it before building the upstream URL — the stub is
     * therefore asked for {@code /@acme/widget}, which the assertion below verifies. Had the
     * {@code %2F} been double-encoded on the way out of the test client, the upstream path would
     * have been {@code /@acme%2Fwidget} and this test would fail with a 404 instead.
     */
    @Test
    void scopedPackageMetadataIsProxiedAndRewritten() throws IOException {
        JsonNode packument = fetchPackument("@acme%2Fwidget");
        String tarball = tarballUrl(packument, "2.0.0");

        assertTrue(
                upstreamRequestCount("/@acme/widget") >= 1,
                "the encoded scoped name must reach upstream decoded, saw: " + UPSTREAM_REQUESTS);
        assertTrue(
                tarball.startsWith(repositoryBase()),
                "scoped dist.tarball must point back at MegaRepo, was: " + tarball);
        assertTrue(
                tarball.endsWith("@acme/widget/-/widget-2.0.0.tgz"),
                "scoped tarball path must be preserved, was: " + tarball);
    }

    /**
     * Regression test for the unscoped-extractor bug. The rewritten URL uses the registry layout
     * {@code name/-/name-version.tgz}; before the fix {@code NpmCoordinateExtractor} did not match
     * it, so the tarball was cached as an asset but no component was created and Browse stayed
     * empty.
     */
    @Test
    void downloadingRewrittenTarballCreatesComponent() throws IOException {
        String tarball = tarballUrl(fetchPackument("left-pad"), "1.3.0");

        ResponseEntity<byte[]> download = restTemplate.exchange(
                URI.create(tarball), HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), byte[].class);
        assertEquals(HttpStatus.OK, download.getStatusCode(), "tarball download through the proxy failed");
        assertArrayEquals(
                FIXTURES.get("left-pad").tarball(),
                download.getBody(),
                "the proxied tarball must be the upstream bytes");

        JsonNode components = listComponents();
        JsonNode component = findComponent(components, null, "left-pad", "1.3.0");
        assertNotNull(
                component,
                "Browse must list a component for the proxied unscoped package, got: " + components);
        assertTrue(
                assetPaths(component).contains("left-pad/-/left-pad-1.3.0.tgz"),
                "the cached tarball must be attached to the component, got: " + component);
    }

    @Test
    void scopedTarballDownloadCreatesComponent() throws IOException {
        String tarball = tarballUrl(fetchPackument("@acme%2Fwidget"), "2.0.0");

        ResponseEntity<byte[]> download = restTemplate.exchange(
                URI.create(tarball), HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), byte[].class);
        assertEquals(HttpStatus.OK, download.getStatusCode(), "scoped tarball download failed");
        assertArrayEquals(
                FIXTURES.get("@acme/widget").tarball(),
                download.getBody(),
                "the proxied scoped tarball must be the upstream bytes");

        JsonNode components = listComponents();
        JsonNode component = findComponent(components, "@acme", "widget", "2.0.0");
        assertNotNull(
                component,
                "Browse must list a component for the proxied scoped package, got: " + components);
        assertTrue(
                assetPaths(component).contains("@acme/widget/-/widget-2.0.0.tgz"),
                "the cached scoped tarball must be attached to the component, got: " + component);
    }

    /**
     * The abbreviated packument lives under its own cache path
     * ({@code NpmRequestHandler.ABBREVIATED_CACHE_PREFIX}). If it did not, the second request here
     * would be answered from the abbreviated cache entry and a client asking for the full document
     * would silently receive one stripped of {@code readme} and friends.
     */
    @Test
    void abbreviatedAndFullMetadataAreCachedSeparately() throws IOException {
        HttpHeaders abbreviatedAccept = new HttpHeaders();
        abbreviatedAccept.setAccept(List.of(MediaType.parseMediaType(ABBREVIATED_MEDIA_TYPE)));

        ResponseEntity<String> abbreviatedResponse = restTemplate.exchange(
                URI.create(repositoryBase() + "abbrev-probe"),
                HttpMethod.GET,
                new HttpEntity<>(abbreviatedAccept),
                String.class);
        assertEquals(
                HttpStatus.OK,
                abbreviatedResponse.getStatusCode(),
                "abbreviated packument request failed, body: " + abbreviatedResponse.getBody());

        JsonNode abbreviated = objectMapper.readTree(abbreviatedResponse.getBody());
        assertTrue(
                abbreviated.path("_abbreviated").asBoolean(false),
                "the abbreviated representation must be served, got: " + abbreviated);
        assertTrue(
                abbreviated.path("readme").isMissingNode(),
                "the abbreviated representation must not carry the full document's fields, got: " + abbreviated);

        JsonNode full = fetchPackument("abbrev-probe");
        assertTrue(
                full.path("_abbreviated").isMissingNode(),
                "the full document must not be answered from the abbreviated cache entry, got: " + full);
        assertFalse(
                full.path("readme").asText("").isBlank(),
                "the full document must still carry readme, got: " + full);

        assertTrue(
                UPSTREAM_REQUESTS.stream().anyMatch(r -> "/abbrev-probe".equals(r.path())
                        && r.accept() != null
                        && r.accept().toLowerCase(Locale.ROOT).contains(ABBREVIATED_MEDIA_TYPE)),
                "the client's abbreviated Accept must be forwarded upstream, saw: " + UPSTREAM_REQUESTS);
    }

    /**
     * Metadata is cached for 5 minutes by default, so two identical requests inside one test run
     * must produce exactly one upstream call.
     */
    @Test
    void metadataIsServedFromCacheOnSecondRequest() throws IOException {
        fetchPackument("cache-probe");
        fetchPackument("cache-probe");

        assertEquals(
                1,
                upstreamRequestCount("/cache-probe"),
                "the second packument request must be a cache hit, saw: " + UPSTREAM_REQUESTS);
    }

    // ---------------------------------------------------------------- helpers

    private String repositoryBase() {
        return baseUrl() + "/repository/" + REPO_NAME + "/";
    }

    /**
     * Fetches a packument through the proxy. The URL is handed over as a {@link URI} so that an
     * already-encoded path such as {@code @acme%2Fwidget} reaches the server as written — passing
     * the same string to a uriVariables overload would re-encode the {@code %}.
     */
    private JsonNode fetchPackument(String path) throws IOException {
        ResponseEntity<String> response = restTemplate.exchange(
                URI.create(repositoryBase() + path),
                HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()),
                String.class);
        assertEquals(
                HttpStatus.OK,
                response.getStatusCode(),
                "packument request for '" + path + "' failed, body: " + response.getBody());
        return objectMapper.readTree(response.getBody());
    }

    private static String tarballUrl(JsonNode packument, String version) {
        JsonNode tarball = packument.path("versions").path(version).path("dist").path("tarball");
        assertFalse(
                tarball.isMissingNode(),
                "packument has no versions." + version + ".dist.tarball, got: " + packument);
        return tarball.asText();
    }

    private JsonNode listComponents() throws IOException {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken());
        ResponseEntity<String> response = restTemplate.exchange(
                URI.create(baseUrl() + "/api/v1/components?repository=" + REPO_NAME),
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);
        assertEquals(
                HttpStatus.OK,
                response.getStatusCode(),
                "component listing failed, body: " + response.getBody());
        return objectMapper.readTree(response.getBody());
    }

    /**
     * @param group the expected npm scope, or {@code null} for an unscoped package
     */
    private static JsonNode findComponent(JsonNode listing, String group, String name, String version) {
        for (JsonNode item : listing.path("items")) {
            if (!name.equals(item.path("name").asText()) || !version.equals(item.path("version").asText())) {
                continue;
            }
            JsonNode groupNode = item.path("group");
            boolean groupMatches = group == null
                    ? groupNode.isNull() || groupNode.isMissingNode() || groupNode.asText().isEmpty()
                    : group.equals(groupNode.asText());
            if (groupMatches) {
                return item;
            }
        }
        return null;
    }

    private static List<String> assetPaths(JsonNode component) {
        List<String> paths = new ArrayList<>();
        component.path("assets").forEach(asset -> paths.add(asset.path("path").asText()));
        return paths;
    }

    private String adminToken() throws IOException {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = restTemplate.exchange(
                URI.create(baseUrl() + "/api/v1/security/auth/login"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("username", "admin", "password", "admin123"), headers),
                String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "admin login failed, body: " + response.getBody());
        return objectMapper.readTree(response.getBody()).path("token").asText();
    }
}
