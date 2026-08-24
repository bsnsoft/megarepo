package de.bsnsoft.megarepo.format.npm;

import de.bsnsoft.megarepo.core.format.FormatResponse.ContentResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.NotFoundResponse;
import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.core.repository.RepositoryType;
import de.bsnsoft.megarepo.core.storage.Blob;
import de.bsnsoft.megarepo.core.storage.BlobProperties;
import de.bsnsoft.megarepo.core.storage.BlobRef;
import de.bsnsoft.megarepo.core.storage.BlobStore;
import de.bsnsoft.megarepo.database.entity.AssetEntity;
import de.bsnsoft.megarepo.database.repository.AssetJpaRepository;
import de.bsnsoft.megarepo.format.npm.proxy.NpmProxyUrlRewriter;
import de.bsnsoft.megarepo.format.npm.publish.NpmPublishHandler;
import de.bsnsoft.megarepo.format.npm.registry.NpmPackageMetadataBuilder;
import de.bsnsoft.megarepo.storage.BlobStoreManager;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NpmRequestHandlerTest {

    @Mock
    private AssetJpaRepository assetRepository;

    @Mock
    private BlobStoreManager blobStoreManager;

    @Mock
    private NpmPackageMetadataBuilder metadataBuilder;

    @Mock
    private NpmPublishHandler publishHandler;

    @Mock
    private BlobStore blobStore;

    @Mock
    private HttpServletRequest request;

    private NpmRequestHandler handler;

    private static final UUID REPO_ID = UUID.randomUUID();
    private static final String BLOB_STORE_NAME = "default";
    private static final RepositoryConfig REPO_CONFIG = new RepositoryConfig(
            REPO_ID, "npm-hosted", "npm", RepositoryType.HOSTED, true, BLOB_STORE_NAME, Map.of());

    @BeforeEach
    void setUp() {
        NpmCoordinateExtractor extractor = new NpmCoordinateExtractor();
        handler = new NpmRequestHandler(
                assetRepository,
                blobStoreManager,
                metadataBuilder,
                publishHandler,
                extractor,
                new NpmProxyUrlRewriter());
    }

    @Test
    void handleHostedGet_tarball_looksUpAsset() {
        when(assetRepository.findByRepositoryIdAndPath(REPO_ID, "-/lodash-4.17.21.tgz"))
                .thenReturn(Optional.empty());

        var response = handler.handleHostedGet(REPO_CONFIG, "-/lodash-4.17.21.tgz", request);

        assertInstanceOf(NotFoundResponse.class, response);
    }

    @Test
    void handleHostedGet_tarballFound_returnsContent() {
        AssetEntity asset = createAssetEntity("-/lodash-4.17.21.tgz", "default@blob-123");
        BlobRef blobRef = new BlobRef("default", "blob-123");
        BlobProperties props = new BlobProperties(
                1024L, "application/gzip", Map.of("sha1", "abc123"), Instant.now(), Map.of());
        Blob blob = new Blob(blobRef, new ByteArrayInputStream(new byte[1024]), props);

        when(assetRepository.findByRepositoryIdAndPath(REPO_ID, "-/lodash-4.17.21.tgz"))
                .thenReturn(Optional.of(asset));
        when(blobStoreManager.get(BLOB_STORE_NAME)).thenReturn(blobStore);
        when(blobStore.get(any(BlobRef.class))).thenReturn(Optional.of(blob));
        when(assetRepository.save(any(AssetEntity.class))).thenReturn(asset);

        var response = handler.handleHostedGet(REPO_CONFIG, "-/lodash-4.17.21.tgz", request);

        assertInstanceOf(ContentResponse.class, response);
        ContentResponse content = (ContentResponse) response;
        assertEquals("application/gzip", content.contentType());
        assertEquals(1024L, content.contentLength());
    }

    @Test
    void handleHostedGet_metadataPath_delegatesToBuilder() {
        when(request.getScheme()).thenReturn("http");
        when(request.getServerName()).thenReturn("localhost");
        when(request.getServerPort()).thenReturn(8080);
        when(request.getHeader("X-Forwarded-Proto")).thenReturn(null);
        when(request.getHeader("X-Forwarded-Host")).thenReturn(null);

        ContentResponse metadataResponse = new ContentResponse(
                new ByteArrayInputStream("{}".getBytes()), "application/json", 2, Map.of(), Map.of());
        when(metadataBuilder.buildMetadata(any(), any(), any())).thenReturn(metadataResponse);

        var response = handler.handleHostedGet(REPO_CONFIG, "lodash", request);

        assertInstanceOf(ContentResponse.class, response);
        assertEquals("application/json", ((ContentResponse) response).contentType());
    }

    @Test
    void handleHostedDelete_assetNotFound_returnsNotFound() {
        when(assetRepository.findByRepositoryIdAndPath(REPO_ID, "-/lodash-4.17.21.tgz"))
                .thenReturn(Optional.empty());

        var response = handler.handleHostedDelete(REPO_CONFIG, "-/lodash-4.17.21.tgz", request);

        assertInstanceOf(NotFoundResponse.class, response);
    }

    @Test
    void handleHostedDelete_assetFound_deletesIt() {
        AssetEntity asset = createAssetEntity("-/lodash-4.17.21.tgz", "default@blob-xyz");

        when(assetRepository.findByRepositoryIdAndPath(REPO_ID, "-/lodash-4.17.21.tgz"))
                .thenReturn(Optional.of(asset));
        when(blobStoreManager.get(BLOB_STORE_NAME)).thenReturn(blobStore);
        when(blobStore.delete(any(BlobRef.class))).thenReturn(true);

        var response = handler.handleHostedDelete(REPO_CONFIG, "-/lodash-4.17.21.tgz", request);

        assertInstanceOf(ContentResponse.class, response);
        verify(assetRepository).delete(asset);
        verify(blobStore).delete(any(BlobRef.class));
    }

    @Test
    void isMetadataPath_tarballPath_returnsFalse() {
        assertFalse(handler.isMetadataPath("-/lodash-4.17.21.tgz"));
        assertFalse(handler.isMetadataPath("@scope/pkg/-/pkg-1.0.0.tgz"));
    }

    @Test
    void isMetadataPath_packageName_returnsTrue() {
        assertTrue(handler.isMetadataPath("lodash"));
        assertTrue(handler.isMetadataPath("@scope/package"));
    }

    @Test
    void mergeMetadata_returnsEmpty() {
        assertTrue(handler.mergeMetadata(REPO_CONFIG, "lodash", java.util.List.of()).isEmpty());
    }

    @Test
    void extractPackageName_simplePackage() {
        assertEquals("lodash", handler.extractPackageName("lodash"));
    }

    @Test
    void extractPackageName_scopedPackage() {
        assertEquals("@scope/package", handler.extractPackageName("@scope/package"));
    }

    @Test
    void extractPackageName_nullOrBlank() {
        assertEquals(null, handler.extractPackageName(null));
        assertEquals(null, handler.extractPackageName(""));
        assertEquals(null, handler.extractPackageName("   "));
    }

    @Test
    void extractPackageName_tarballPath_returnsNull() {
        assertEquals(null, handler.extractPackageName("-/lodash-1.0.0.tgz"));
    }

    @Test
    void handleProxyGet_noCache_returnsNotFound() {
        when(assetRepository.findByRepositoryIdAndPath(REPO_ID, "-/lodash-4.17.21.tgz"))
                .thenReturn(Optional.empty());

        var response = handler.handleProxyGet(REPO_CONFIG, "-/lodash-4.17.21.tgz", request);

        // Without proxyFetchService, falls back to lookupAsset
        assertInstanceOf(NotFoundResponse.class, response);
    }

    @Test
    void handleGroupGet_noCache_returnsNotFound() {
        when(assetRepository.findByRepositoryIdAndPath(REPO_ID, "-/lodash-4.17.21.tgz"))
                .thenReturn(Optional.empty());

        var response = handler.handleGroupGet(REPO_CONFIG, "-/lodash-4.17.21.tgz", request);

        assertInstanceOf(NotFoundResponse.class, response);
    }

    private AssetEntity createAssetEntity(String path, String blobRefStr) {
        AssetEntity asset = new AssetEntity();
        asset.setId(UUID.randomUUID());
        asset.setRepositoryId(REPO_ID);
        asset.setPath(path);
        asset.setFormat("npm");
        asset.setBlobRef(blobRefStr);
        asset.setContentType("application/gzip");
        asset.setSize(1024L);
        asset.setChecksumSha1("abc123sha1");
        asset.setLastModified(Instant.now());
        asset.setCreatedAt(Instant.now());
        asset.setUpdatedAt(Instant.now());
        return asset;
    }
}
