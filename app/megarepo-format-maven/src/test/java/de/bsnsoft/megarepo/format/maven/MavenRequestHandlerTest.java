package de.bsnsoft.megarepo.format.maven;

import de.bsnsoft.megarepo.core.format.FormatResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.ContentResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.CreatedResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.ErrorResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.NotFoundResponse;
import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.core.repository.RepositoryType;
import de.bsnsoft.megarepo.core.storage.Blob;
import de.bsnsoft.megarepo.core.storage.BlobProperties;
import de.bsnsoft.megarepo.core.storage.BlobRef;
import de.bsnsoft.megarepo.core.storage.BlobStore;
import de.bsnsoft.megarepo.database.entity.AssetEntity;
import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import de.bsnsoft.megarepo.database.repository.AssetJpaRepository;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import de.bsnsoft.megarepo.format.maven.checksum.ChecksumFileHandler;
import de.bsnsoft.megarepo.format.maven.metadata.MavenMetadataGenerator;
import de.bsnsoft.megarepo.format.maven.metadata.MavenMetadataMerger;
import de.bsnsoft.megarepo.format.maven.policy.LayoutValidator;
import de.bsnsoft.megarepo.format.maven.policy.VersionPolicyEnforcer;
import de.bsnsoft.megarepo.storage.BlobStoreManager;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MavenRequestHandlerTest {

    @Mock
    private AssetJpaRepository assetRepository;

    @Mock
    private ComponentJpaRepository componentRepository;

    @Mock
    private BlobStoreManager blobStoreManager;

    @Mock
    private BlobStore blobStore;

    @Mock
    private ChecksumFileHandler checksumFileHandler;

    @Mock
    private MavenMetadataMerger metadataMerger;

    @Mock
    private MavenMetadataGenerator metadataGenerator;

    @Mock
    private HttpServletRequest request;

    private MavenRequestHandler handler;

    private static final UUID REPO_ID = UUID.randomUUID();
    private static final String BLOB_STORE_NAME = "default";
    private static final RepositoryConfig HOSTED_REPO = new RepositoryConfig(
            REPO_ID, "maven-releases", "maven2", RepositoryType.HOSTED, true, BLOB_STORE_NAME, Map.of());
    private static final RepositoryConfig SNAPSHOT_REPO = new RepositoryConfig(
            REPO_ID,
            "maven-snapshots",
            "maven2",
            RepositoryType.HOSTED,
            true,
            BLOB_STORE_NAME,
            Map.of("versionPolicy", "SNAPSHOT"));

    @BeforeEach
    void setUp() {
        MavenCoordinateExtractor extractor = new MavenCoordinateExtractor();
        VersionPolicyEnforcer policyEnforcer = new VersionPolicyEnforcer();
        LayoutValidator layoutValidator = new LayoutValidator();
        handler = new MavenRequestHandler(
                assetRepository, componentRepository, blobStoreManager, extractor, checksumFileHandler,
                policyEnforcer, layoutValidator, metadataMerger, metadataGenerator);
    }

    // --- GET tests ---

    @Test
    void handleHostedGet_assetNotFound_returnsNotFound() {
        when(checksumFileHandler.isChecksumPath("com/example/app/1.0/app-1.0.jar")).thenReturn(false);
        when(assetRepository.findByRepositoryIdAndPath(REPO_ID, "com/example/app/1.0/app-1.0.jar"))
                .thenReturn(Optional.empty());

        var response = handler.handleHostedGet(HOSTED_REPO, "com/example/app/1.0/app-1.0.jar", request);

        assertInstanceOf(NotFoundResponse.class, response);
    }

    @Test
    void handleHostedGet_assetFound_returnsContent() {
        String path = "com/example/app/1.0/app-1.0.jar";
        AssetEntity asset = createAssetEntity(path, "default@blob-123", "application/java-archive");
        BlobRef blobRef = new BlobRef("default", "blob-123");
        BlobProperties props = new BlobProperties(
                1024L, "application/java-archive", Map.of("sha1", "abc123"), Instant.now(), Map.of());
        Blob blob = new Blob(blobRef, new ByteArrayInputStream(new byte[1024]), props);

        when(checksumFileHandler.isChecksumPath(path)).thenReturn(false);
        when(assetRepository.findByRepositoryIdAndPath(REPO_ID, path)).thenReturn(Optional.of(asset));
        when(blobStoreManager.get(BLOB_STORE_NAME)).thenReturn(blobStore);
        when(blobStore.get(any(BlobRef.class))).thenReturn(Optional.of(blob));
        when(assetRepository.save(any(AssetEntity.class))).thenReturn(asset);

        var response = handler.handleHostedGet(HOSTED_REPO, path, request);

        assertInstanceOf(ContentResponse.class, response);
        ContentResponse content = (ContentResponse) response;
        assertEquals("application/java-archive", content.contentType());
        assertEquals(1024L, content.contentLength());
    }

    @Test
    void handleHostedGet_checksumPath_delegatesToChecksumHandler() {
        String path = "com/example/app/1.0/app-1.0.jar.sha1";
        ContentResponse checksumResponse = new ContentResponse(
                new ByteArrayInputStream("abc123".getBytes()), "text/plain", 6, Map.of(), Map.of());

        when(checksumFileHandler.isChecksumPath(path)).thenReturn(true);
        when(checksumFileHandler.handleChecksumRequest(REPO_ID, path)).thenReturn(checksumResponse);

        var response = handler.handleHostedGet(HOSTED_REPO, path, request);

        assertInstanceOf(ContentResponse.class, response);
        verify(assetRepository, never()).findByRepositoryIdAndPath(any(), any());
    }

    @Test
    void handleHostedGet_pomFile_returnsXmlContentType() {
        String path = "com/example/app/1.0/app-1.0.pom";
        AssetEntity asset = createAssetEntity(path, "default@blob-pom", "application/xml");
        BlobRef blobRef = new BlobRef("default", "blob-pom");
        BlobProperties props =
                new BlobProperties(256L, "application/xml", Map.of("sha1", "pom-sha1"), Instant.now(), Map.of());
        Blob blob = new Blob(blobRef, new ByteArrayInputStream(new byte[256]), props);

        when(checksumFileHandler.isChecksumPath(path)).thenReturn(false);
        when(assetRepository.findByRepositoryIdAndPath(REPO_ID, path)).thenReturn(Optional.of(asset));
        when(blobStoreManager.get(BLOB_STORE_NAME)).thenReturn(blobStore);
        when(blobStore.get(any(BlobRef.class))).thenReturn(Optional.of(blob));
        when(assetRepository.save(any(AssetEntity.class))).thenReturn(asset);

        var response = handler.handleHostedGet(HOSTED_REPO, path, request);

        assertInstanceOf(ContentResponse.class, response);
        ContentResponse content = (ContentResponse) response;
        assertEquals("application/xml", content.contentType());
    }

    // --- PUT tests ---

    @Test
    void handleHostedPut_storesJarArtifact() throws IOException {
        String path = "com/example/app/1.0/app-1.0.jar";
        byte[] data = "fake-jar-content".getBytes();

        setupPutMocks(path, data);

        var response = handler.handleHostedPut(HOSTED_REPO, path, request);

        assertInstanceOf(CreatedResponse.class, response);
        assertEquals(path, ((CreatedResponse) response).path());

        ArgumentCaptor<AssetEntity> assetCaptor = ArgumentCaptor.forClass(AssetEntity.class);
        verify(assetRepository).save(assetCaptor.capture());
        AssetEntity savedAsset = assetCaptor.getValue();
        assertEquals("maven2", savedAsset.getFormat());
        assertEquals(path, savedAsset.getPath());
    }

    @Test
    void handleHostedPut_storesPomFile() throws IOException {
        String path = "com/example/app/1.0/app-1.0.pom";
        byte[] data = "<project></project>".getBytes();

        setupPutMocks(path, data);

        var response = handler.handleHostedPut(HOSTED_REPO, path, request);

        assertInstanceOf(CreatedResponse.class, response);
    }

    @Test
    void handleHostedPut_checksumUpload_doesNotStoreBlob() {
        String path = "com/example/app/1.0/app-1.0.jar.sha1";

        when(checksumFileHandler.isChecksumPath(path)).thenReturn(true);

        var response = handler.handleHostedPut(HOSTED_REPO, path, request);

        // Checksum uploads are accepted silently (Maven clients upload them)
        // but should NOT be stored as blobs.
        assertInstanceOf(CreatedResponse.class, response);
        verify(blobStoreManager, never()).get(any());
    }

    @Test
    void handleHostedPut_createsComponent() throws IOException {
        String path = "com/example/app/1.0/app-1.0.jar";
        byte[] data = "jar-bytes".getBytes();

        setupPutMocks(path, data);

        handler.handleHostedPut(HOSTED_REPO, path, request);

        ArgumentCaptor<ComponentEntity> componentCaptor = ArgumentCaptor.forClass(ComponentEntity.class);
        verify(componentRepository).save(componentCaptor.capture());
        ComponentEntity savedComponent = componentCaptor.getValue();
        assertEquals("com.example", savedComponent.getNamespace());
        assertEquals("app", savedComponent.getName());
        assertEquals("1.0", savedComponent.getVersion());
        assertEquals("maven2", savedComponent.getFormat());
    }

    @Test
    void handleHostedPut_existingComponent_doesNotDuplicate() throws IOException {
        String path = "com/example/app/1.0/app-1.0-sources.jar";
        byte[] data = "sources-jar".getBytes();

        ComponentEntity existingComponent = new ComponentEntity();
        existingComponent.setId(UUID.randomUUID());
        existingComponent.setRepositoryId(REPO_ID);
        existingComponent.setNamespace("com.example");
        existingComponent.setName("app");
        existingComponent.setVersion("1.0");

        when(componentRepository.findByRepositoryIdAndNamespaceAndNameAndVersion(
                        eq(REPO_ID), eq("com.example"), eq("app"), eq("1.0")))
                .thenReturn(Optional.of(existingComponent));

        setupPutMocksWithExistingComponent(path, data, existingComponent);

        handler.handleHostedPut(HOSTED_REPO, path, request);

        // Should reuse the existing component, not create a new one
        verify(componentRepository, never()).save(any(ComponentEntity.class));
    }

    @Test
    void handleHostedPut_metadataUpload_accepted() throws IOException {
        String path = "com/example/app/maven-metadata.xml";
        byte[] data = "<metadata></metadata>".getBytes();
        ServletInputStream servletInputStream = createServletInputStream(data);

        when(request.getInputStream()).thenReturn(servletInputStream);
        when(request.getContentType()).thenReturn("application/xml");
        when(request.getContentLengthLong()).thenReturn((long) data.length);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(checksumFileHandler.isChecksumPath(path)).thenReturn(false);

        BlobRef blobRef = new BlobRef(BLOB_STORE_NAME, "metadata-blob-id");
        BlobProperties props = new BlobProperties(
                data.length, "application/xml", Map.of("sha1", "metasha1"), Instant.now(), Map.of());
        Blob blob = new Blob(blobRef, new ByteArrayInputStream(data), props);

        when(blobStoreManager.get(BLOB_STORE_NAME)).thenReturn(blobStore);
        when(blobStore.store(any(InputStream.class), anyLong(), any())).thenReturn(blobRef);
        when(blobStore.get(blobRef)).thenReturn(Optional.of(blob));
        when(assetRepository.findByRepositoryIdAndPath(REPO_ID, path)).thenReturn(Optional.empty());
        when(assetRepository.save(any(AssetEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = handler.handleHostedPut(HOSTED_REPO, path, request);

        // Metadata uploads should be accepted (Maven clients deploy metadata)
        assertInstanceOf(CreatedResponse.class, response);
    }

    // --- DELETE tests ---

    @Test
    void handleHostedDelete_assetNotFound_returnsNotFound() {
        when(assetRepository.findByRepositoryIdAndPath(REPO_ID, "com/example/app/1.0/app-1.0.jar"))
                .thenReturn(Optional.empty());

        var response = handler.handleHostedDelete(HOSTED_REPO, "com/example/app/1.0/app-1.0.jar", request);

        assertInstanceOf(NotFoundResponse.class, response);
    }

    @Test
    void handleHostedDelete_assetFound_deletesAssetAndBlob() {
        String path = "com/example/app/1.0/app-1.0.jar";
        AssetEntity asset = createAssetEntity(path, "default@blob-del", "application/java-archive");

        when(assetRepository.findByRepositoryIdAndPath(REPO_ID, path)).thenReturn(Optional.of(asset));
        when(blobStoreManager.get(BLOB_STORE_NAME)).thenReturn(blobStore);
        when(blobStore.delete(any(BlobRef.class))).thenReturn(true);

        var response = handler.handleHostedDelete(HOSTED_REPO, path, request);

        verify(assetRepository).delete(asset);
        verify(blobStore).delete(any(BlobRef.class));
    }

    // --- Metadata path detection ---

    @Test
    void isMetadataPath_trueForMavenMetadata() {
        assertTrue(handler.isMetadataPath("com/example/app/maven-metadata.xml"));
    }

    @Test
    void isMetadataPath_trueForVersionedMetadata() {
        assertTrue(handler.isMetadataPath("com/example/app/1.0-SNAPSHOT/maven-metadata.xml"));
    }

    @Test
    void isMetadataPath_falseForJar() {
        assertFalse(handler.isMetadataPath("com/example/app/1.0/app-1.0.jar"));
    }

    @Test
    void isMetadataPath_falseForPom() {
        assertFalse(handler.isMetadataPath("com/example/app/1.0/app-1.0.pom"));
    }

    @Test
    void isMetadataPath_falseForMetadataChecksum() {
        // Checksum files for metadata should not be treated as metadata themselves
        assertFalse(handler.isMetadataPath("com/example/app/maven-metadata.xml.sha1"));
    }

    // --- Proxy / Group stubs ---

    @Test
    void handleProxyGet_localCacheMiss_returnsNotFound() {
        when(checksumFileHandler.isChecksumPath("com/example/app/1.0/app-1.0.jar")).thenReturn(false);
        when(assetRepository.findByRepositoryIdAndPath(REPO_ID, "com/example/app/1.0/app-1.0.jar"))
                .thenReturn(Optional.empty());

        RepositoryConfig proxyRepo = new RepositoryConfig(
                REPO_ID,
                "maven-central-proxy",
                "maven2",
                RepositoryType.PROXY,
                true,
                BLOB_STORE_NAME,
                Map.of("remoteUrl", "https://repo1.maven.org/maven2/"));

        var response = handler.handleProxyGet(proxyRepo, "com/example/app/1.0/app-1.0.jar", request);

        // MVP: proxy just checks local cache, returns not found if missing
        assertInstanceOf(NotFoundResponse.class, response);
    }

    @Test
    void handleGroupGet_localCacheMiss_returnsNotFound() {
        when(checksumFileHandler.isChecksumPath("com/example/app/1.0/app-1.0.jar")).thenReturn(false);
        when(assetRepository.findByRepositoryIdAndPath(REPO_ID, "com/example/app/1.0/app-1.0.jar"))
                .thenReturn(Optional.empty());

        RepositoryConfig groupRepo = new RepositoryConfig(
                REPO_ID, "maven-group", "maven2", RepositoryType.GROUP, true, BLOB_STORE_NAME, Map.of());

        var response = handler.handleGroupGet(groupRepo, "com/example/app/1.0/app-1.0.jar", request);

        assertInstanceOf(NotFoundResponse.class, response);
    }

    // --- Content-type detection ---

    @Test
    void handleHostedGet_jarFile_hasCorrectContentType() {
        String path = "com/example/app/1.0/app-1.0.jar";
        AssetEntity asset = createAssetEntity(path, "default@blob-jar", "application/java-archive");
        BlobRef blobRef = new BlobRef("default", "blob-jar");
        BlobProperties props =
                new BlobProperties(512L, "application/java-archive", Map.of(), Instant.now(), Map.of());
        Blob blob = new Blob(blobRef, new ByteArrayInputStream(new byte[512]), props);

        when(checksumFileHandler.isChecksumPath(path)).thenReturn(false);
        when(assetRepository.findByRepositoryIdAndPath(REPO_ID, path)).thenReturn(Optional.of(asset));
        when(blobStoreManager.get(BLOB_STORE_NAME)).thenReturn(blobStore);
        when(blobStore.get(any(BlobRef.class))).thenReturn(Optional.of(blob));
        when(assetRepository.save(any(AssetEntity.class))).thenReturn(asset);

        var response = handler.handleHostedGet(HOSTED_REPO, path, request);

        assertInstanceOf(ContentResponse.class, response);
        assertEquals("application/java-archive", ((ContentResponse) response).contentType());
    }

    // --- Checksum in response ---

    @Test
    void handleHostedGet_responseIncludesChecksums() {
        String path = "com/example/app/1.0/app-1.0.jar";
        AssetEntity asset = createAssetEntity(path, "default@blob-cs", "application/java-archive");
        asset.setChecksumSha1("sha1value");
        asset.setChecksumMd5("md5value");
        asset.setChecksumSha256("sha256value");

        BlobRef blobRef = new BlobRef("default", "blob-cs");
        BlobProperties props = new BlobProperties(100L, "application/java-archive", Map.of(), Instant.now(), Map.of());
        Blob blob = new Blob(blobRef, new ByteArrayInputStream(new byte[100]), props);

        when(checksumFileHandler.isChecksumPath(path)).thenReturn(false);
        when(assetRepository.findByRepositoryIdAndPath(REPO_ID, path)).thenReturn(Optional.of(asset));
        when(blobStoreManager.get(BLOB_STORE_NAME)).thenReturn(blobStore);
        when(blobStore.get(any(BlobRef.class))).thenReturn(Optional.of(blob));
        when(assetRepository.save(any(AssetEntity.class))).thenReturn(asset);

        var response = handler.handleHostedGet(HOSTED_REPO, path, request);

        assertInstanceOf(ContentResponse.class, response);
        ContentResponse content = (ContentResponse) response;
        assertTrue(content.checksums().containsKey("sha1"));
        assertTrue(content.checksums().containsKey("md5"));
    }

    // --- Helper methods ---

    private void setupPutMocks(String path, byte[] data) throws IOException {
        ServletInputStream servletInputStream = createServletInputStream(data);

        when(request.getInputStream()).thenReturn(servletInputStream);
        when(request.getContentType()).thenReturn("application/java-archive");
        when(request.getContentLengthLong()).thenReturn((long) data.length);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(checksumFileHandler.isChecksumPath(path)).thenReturn(false);

        BlobRef blobRef = new BlobRef(BLOB_STORE_NAME, "new-blob-id");
        BlobProperties props = new BlobProperties(
                data.length, "application/java-archive", Map.of("sha1", "abc123"), Instant.now(), Map.of());
        Blob blob = new Blob(blobRef, new ByteArrayInputStream(data), props);

        when(blobStoreManager.get(BLOB_STORE_NAME)).thenReturn(blobStore);
        when(blobStore.store(any(InputStream.class), anyLong(), any())).thenReturn(blobRef);
        when(blobStore.get(blobRef)).thenReturn(Optional.of(blob));

        ComponentEntity component = new ComponentEntity();
        component.setId(UUID.randomUUID());
        when(componentRepository.findByRepositoryIdAndNamespaceAndNameAndVersion(
                        eq(REPO_ID), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(componentRepository.save(any(ComponentEntity.class))).thenReturn(component);

        when(assetRepository.findByRepositoryIdAndPath(REPO_ID, path)).thenReturn(Optional.empty());
        when(assetRepository.save(any(AssetEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private void setupPutMocksWithExistingComponent(String path, byte[] data, ComponentEntity existingComponent)
            throws IOException {
        ServletInputStream servletInputStream = createServletInputStream(data);

        when(request.getInputStream()).thenReturn(servletInputStream);
        when(request.getContentType()).thenReturn("application/java-archive");
        when(request.getContentLengthLong()).thenReturn((long) data.length);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(checksumFileHandler.isChecksumPath(path)).thenReturn(false);

        BlobRef blobRef = new BlobRef(BLOB_STORE_NAME, "new-blob-id");
        BlobProperties props = new BlobProperties(
                data.length, "application/java-archive", Map.of("sha1", "abc123"), Instant.now(), Map.of());
        Blob blob = new Blob(blobRef, new ByteArrayInputStream(data), props);

        when(blobStoreManager.get(BLOB_STORE_NAME)).thenReturn(blobStore);
        when(blobStore.store(any(InputStream.class), anyLong(), any())).thenReturn(blobRef);
        when(blobStore.get(blobRef)).thenReturn(Optional.of(blob));

        when(assetRepository.findByRepositoryIdAndPath(REPO_ID, path)).thenReturn(Optional.empty());
        when(assetRepository.save(any(AssetEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private AssetEntity createAssetEntity(String path, String blobRefStr, String contentType) {
        AssetEntity asset = new AssetEntity();
        asset.setId(UUID.randomUUID());
        asset.setRepositoryId(REPO_ID);
        asset.setPath(path);
        asset.setFormat("maven2");
        asset.setBlobRef(blobRefStr);
        asset.setContentType(contentType);
        asset.setSize(1024L);
        asset.setChecksumMd5("md5hash");
        asset.setChecksumSha1("sha1hash");
        asset.setLastModified(Instant.now());
        asset.setCreatedAt(Instant.now());
        asset.setUpdatedAt(Instant.now());
        return asset;
    }

    private ServletInputStream createServletInputStream(byte[] data) {
        ByteArrayInputStream byteStream = new ByteArrayInputStream(data);
        return new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return byteStream.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                // no-op
            }

            @Override
            public int read() {
                return byteStream.read();
            }
        };
    }
}
