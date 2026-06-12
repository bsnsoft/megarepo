package de.bsnsoft.megarepo.format.nuget;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.bsnsoft.megarepo.core.format.FormatResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.ContentResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.ErrorResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.NotFoundResponse;
import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.core.repository.RepositoryType;
import de.bsnsoft.megarepo.core.storage.Blob;
import de.bsnsoft.megarepo.core.storage.BlobProperties;
import de.bsnsoft.megarepo.core.storage.BlobRef;
import de.bsnsoft.megarepo.core.storage.BlobStore;
import de.bsnsoft.megarepo.database.entity.AssetEntity;
import de.bsnsoft.megarepo.database.repository.AssetJpaRepository;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import de.bsnsoft.megarepo.format.nuget.index.FlatContainerGenerator;
import de.bsnsoft.megarepo.format.nuget.index.RegistrationGenerator;
import de.bsnsoft.megarepo.format.nuget.index.ServiceIndexGenerator;
import de.bsnsoft.megarepo.format.nuget.proxy.NugetProxyUrlRewriter;
import de.bsnsoft.megarepo.format.nuget.push.MultipartNupkgExtractor;
import de.bsnsoft.megarepo.format.nuget.push.NugetPushHandler;
import de.bsnsoft.megarepo.format.nuget.search.NugetSearchService;
import de.bsnsoft.megarepo.storage.BlobStoreManager;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NugetRequestHandlerTest {

    private static final UUID REPO_ID = UUID.randomUUID();
    private static final RepositoryConfig HOSTED = new RepositoryConfig(
            REPO_ID, "nuget-hosted", "nuget", RepositoryType.HOSTED, true, "default", Map.of());

    @Mock
    private AssetJpaRepository assetRepository;

    @Mock
    private ComponentJpaRepository componentRepository;

    @Mock
    private BlobStoreManager blobStoreManager;

    @Mock
    private BlobStore blobStore;

    @Mock
    private HttpServletRequest request;

    private NugetRequestHandler handler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        handler = new NugetRequestHandler(
                assetRepository,
                componentRepository,
                blobStoreManager,
                new ServiceIndexGenerator(),
                new FlatContainerGenerator(componentRepository),
                new RegistrationGenerator(componentRepository),
                new NugetSearchService(componentRepository),
                new NugetPushHandler(
                        blobStoreManager, componentRepository, assetRepository,
                        new de.bsnsoft.megarepo.format.nuget.meta.NupkgReader(),
                        new MultipartNupkgExtractor()),
                new NugetCoordinateExtractor(),
                new NugetProxyUrlRewriter());

        lenient().when(request.getScheme()).thenReturn("http");
        lenient().when(request.getServerName()).thenReturn("localhost");
        lenient().when(request.getServerPort()).thenReturn(8080);
    }

    @Test
    void hostedGet_serviceIndex() throws IOException {
        FormatResponse response = handler.handleHostedGet(HOSTED, "index.json", request);

        ContentResponse content = assertInstanceOf(ContentResponse.class, response);
        JsonNode root = objectMapper.readTree(content.content());
        assertEquals("3.0.0", root.path("version").asText());
        assertTrue(root.path("resources").size() > 0);
        String firstId = root.path("resources").get(0).path("@id").asText();
        assertTrue(firstId.startsWith("http://localhost:8080/repository/nuget-hosted/"), firstId);
    }

    @Test
    void hostedGet_flatContainerDownload_lowercasesPath() {
        var asset = new AssetEntity();
        asset.setPath("v3-flatcontainer/my.pkg/1.0.0/my.pkg.1.0.0.nupkg");
        asset.setBlobRef("default@blob1");
        asset.setContentType("application/zip");
        asset.setSize(4L);
        when(assetRepository.findByRepositoryIdAndPath(
                        eq(REPO_ID), eq("v3-flatcontainer/my.pkg/1.0.0/my.pkg.1.0.0.nupkg")))
                .thenReturn(Optional.of(asset));
        when(assetRepository.save(asset)).thenReturn(asset);
        when(blobStoreManager.get("default")).thenReturn(blobStore);
        when(blobStore.get(new BlobRef("default", "blob1"))).thenReturn(Optional.of(new Blob(
                new BlobRef("default", "blob1"),
                new ByteArrayInputStream("PK".getBytes(StandardCharsets.ISO_8859_1)),
                new BlobProperties(4, "application/zip", Map.of(), Instant.now(), Map.of()))));

        // Request with mixed case must still resolve (client should lowercase, but be tolerant)
        FormatResponse response = handler.handleHostedGet(
                HOSTED, "v3-flatcontainer/My.Pkg/1.0.0/My.Pkg.1.0.0.nupkg", request);

        assertInstanceOf(ContentResponse.class, response);
    }

    @Test
    void hostedGet_unknownPath_returns404() {
        FormatResponse response = handler.handleHostedGet(HOSTED, "some/random/path.txt", request);
        assertInstanceOf(NotFoundResponse.class, response);
    }

    @Test
    void hostedPut_wrongPath_returns400() {
        FormatResponse response = handler.handleHostedPut(HOSTED, "v3-flatcontainer/x/1.0.0/x.nupkg", request);
        ErrorResponse error = assertInstanceOf(ErrorResponse.class, response);
        assertEquals(400, error.statusCode());
    }

    @Test
    void isMetadataPath_classifiesCorrectly() {
        assertTrue(handler.isMetadataPath("index.json"));
        assertTrue(handler.isMetadataPath("v3/search"));
        assertTrue(handler.isMetadataPath("v3/registrations/my.pkg/index.json"));
        assertTrue(handler.isMetadataPath("v3-flatcontainer/my.pkg/index.json"));
        assertTrue(!handler.isMetadataPath("v3-flatcontainer/my.pkg/1.0.0/my.pkg.1.0.0.nupkg"));
    }

    @Test
    void mergeMetadata_versionIndexes_unionsAndSortsVersions() throws IOException {
        RepositoryConfig group = new RepositoryConfig(
                UUID.randomUUID(), "nuget-public", "nuget", RepositoryType.GROUP, true, "default", Map.of());

        List<FormatResponse> members = List.of(
                jsonContent("{\"versions\":[\"1.0.0\",\"2.0.0\"]}"),
                new NotFoundResponse("nope"),
                jsonContent("{\"versions\":[\"2.0.0\",\"1.5.0\"]}"));

        Optional<FormatResponse> merged = handler.mergeMetadata(
                group, "v3-flatcontainer/my.pkg/index.json", members);

        assertTrue(merged.isPresent());
        JsonNode root = objectMapper.readTree(((ContentResponse) merged.get()).content());
        assertEquals(3, root.path("versions").size());
        assertEquals("1.0.0", root.path("versions").get(0).asText());
        assertEquals("1.5.0", root.path("versions").get(1).asText());
        assertEquals("2.0.0", root.path("versions").get(2).asText());
    }

    @Test
    void mergeMetadata_registrations_fallsBackToFirstNon404() {
        RepositoryConfig group = new RepositoryConfig(
                UUID.randomUUID(), "nuget-public", "nuget", RepositoryType.GROUP, true, "default", Map.of());

        Optional<FormatResponse> merged = handler.mergeMetadata(
                group, "v3/registrations/my.pkg/index.json", List.of(jsonContent("{}")));

        assertTrue(merged.isEmpty(), "registrations should use the router's first-non-404 fallback");
    }

    @Test
    void proxyGet_serviceIndex_isGeneratedLocally() throws IOException {
        RepositoryConfig proxy = proxyRepo();

        FormatResponse response = handler.handleProxyGet(proxy, "index.json", request);

        ContentResponse content = assertInstanceOf(ContentResponse.class, response);
        JsonNode root = objectMapper.readTree(content.content());
        String firstId = root.path("resources").get(0).path("@id").asText();
        assertTrue(firstId.contains("/repository/nuget-proxy/"),
                "proxy service index must point at MegaRepo, got: " + firstId);
    }

    @Test
    void proxyGet_flatContainer_servesFreshCacheWithoutUpstream() {
        RepositoryConfig proxy = proxyRepo();
        var asset = new AssetEntity();
        asset.setPath("v3-flatcontainer/pkg/1.0.0/pkg.1.0.0.nupkg");
        asset.setBlobRef("default@blob2");
        asset.setContentType("application/zip");
        asset.setSize(2L);
        asset.setLastModified(Instant.now());
        when(assetRepository.findByRepositoryIdAndPath(
                        eq(proxy.id()), eq("v3-flatcontainer/pkg/1.0.0/pkg.1.0.0.nupkg")))
                .thenReturn(Optional.of(asset));
        when(assetRepository.save(asset)).thenReturn(asset);
        when(blobStoreManager.get("default")).thenReturn(blobStore);
        when(blobStore.get(new BlobRef("default", "blob2"))).thenReturn(Optional.of(new Blob(
                new BlobRef("default", "blob2"),
                new ByteArrayInputStream(new byte[] {1, 2}),
                new BlobProperties(2, "application/zip", Map.of(), Instant.now(), Map.of()))));

        // No ProxyFetchService / resolver injected: fresh cache must still serve.
        // (proxyCacheChecker is null => cache treated as fresh)
        FormatResponse response = handler.handleProxyGet(
                proxy, "v3-flatcontainer/pkg/1.0.0/pkg.1.0.0.nupkg", request);

        assertInstanceOf(ContentResponse.class, response);
    }

    @Test
    void gunzipIfNeeded_decompressesGzipAndPassesPlainThrough() throws IOException {
        byte[] plain = "{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (var gzip = new java.util.zip.GZIPOutputStream(out)) {
            gzip.write(plain);
        }

        assertEquals(new String(plain), new String(NugetRequestHandler.gunzipIfNeeded(out.toByteArray())));
        assertEquals(new String(plain), new String(NugetRequestHandler.gunzipIfNeeded(plain)));
    }

    private static RepositoryConfig proxyRepo() {
        return new RepositoryConfig(
                UUID.randomUUID(), "nuget-proxy", "nuget", RepositoryType.PROXY, true, "default",
                Map.of("proxy", Map.of("remoteUrl", "https://api.nuget.org/v3/index.json")));
    }

    private static ContentResponse jsonContent(String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        return new ContentResponse(
                new ByteArrayInputStream(bytes), "application/json", bytes.length, Map.of(), Map.of());
    }
}
