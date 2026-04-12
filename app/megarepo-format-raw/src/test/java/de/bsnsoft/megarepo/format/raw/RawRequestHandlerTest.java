package de.bsnsoft.megarepo.format.raw;

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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RawRequestHandlerTest {

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

    private RawRequestHandler handler;

    private static final UUID REPO_ID = UUID.randomUUID();
    private static final String BLOB_STORE_NAME = "default";
    private static final RepositoryConfig REPO_CONFIG = new RepositoryConfig(
            REPO_ID, "raw-hosted", "raw", RepositoryType.HOSTED, true, BLOB_STORE_NAME, Map.of());

    @BeforeEach
    void setUp() {
        RawCoordinateExtractor extractor = new RawCoordinateExtractor();
        handler = new RawRequestHandler(assetRepository, componentRepository, blobStoreManager, extractor);
    }

    @Test
    void handleHostedGet_assetNotFound_returnsNotFound() {
        when(assetRepository.findByRepositoryIdAndPath(REPO_ID, "test.txt")).thenReturn(Optional.empty());

        var response = handler.handleHostedGet(REPO_CONFIG, "test.txt", request);

        assertInstanceOf(NotFoundResponse.class, response);
    }

    @Test
    void handleHostedGet_assetFound_returnsContent() {
        AssetEntity asset = createAssetEntity("test.txt", "default@blob-123");
        BlobRef blobRef = new BlobRef("default", "blob-123");
        BlobProperties props =
                new BlobProperties(42L, "text/plain", Map.of("md5", "abc123"), Instant.now(), Map.of());
        Blob blob = new Blob(blobRef, new ByteArrayInputStream(new byte[42]), props);

        when(assetRepository.findByRepositoryIdAndPath(REPO_ID, "test.txt")).thenReturn(Optional.of(asset));
        when(blobStoreManager.get(BLOB_STORE_NAME)).thenReturn(blobStore);
        when(blobStore.get(any(BlobRef.class))).thenReturn(Optional.of(blob));
        when(assetRepository.save(any(AssetEntity.class))).thenReturn(asset);

        var response = handler.handleHostedGet(REPO_CONFIG, "test.txt", request);

        assertInstanceOf(ContentResponse.class, response);
        ContentResponse content = (ContentResponse) response;
        assertEquals("text/plain", content.contentType());
        assertEquals(42L, content.contentLength());
        assertTrue(content.checksums().containsKey("md5"));
    }

    @Test
    void handleHostedPut_storesBlob_createsAssetAndComponent() throws IOException {
        byte[] data = "hello world".getBytes();
        ServletInputStream servletInputStream = createServletInputStream(data);

        when(request.getInputStream()).thenReturn(servletInputStream);
        when(request.getContentType()).thenReturn("text/plain");
        when(request.getContentLengthLong()).thenReturn((long) data.length);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");

        BlobRef blobRef = new BlobRef(BLOB_STORE_NAME, "new-blob-id");
        BlobProperties props =
                new BlobProperties(data.length, "text/plain", Map.of("md5", "abc"), Instant.now(), Map.of());
        Blob blob = new Blob(blobRef, new ByteArrayInputStream(data), props);

        when(blobStoreManager.get(BLOB_STORE_NAME)).thenReturn(blobStore);
        when(blobStore.store(any(InputStream.class), anyLong(), any())).thenReturn(blobRef);
        when(blobStore.get(blobRef)).thenReturn(Optional.of(blob));

        ComponentEntity component = new ComponentEntity();
        component.setId(UUID.randomUUID());
        when(componentRepository.findByRepositoryIdAndNamespaceAndNameAndVersion(
                        eq(REPO_ID), eq("docs"), eq("readme.txt"), eq("1")))
                .thenReturn(Optional.empty());
        when(componentRepository.save(any(ComponentEntity.class))).thenReturn(component);

        when(assetRepository.findByRepositoryIdAndPath(REPO_ID, "docs/readme.txt"))
                .thenReturn(Optional.empty());
        when(assetRepository.save(any(AssetEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = handler.handleHostedPut(REPO_CONFIG, "docs/readme.txt", request);

        assertInstanceOf(CreatedResponse.class, response);
        assertEquals("docs/readme.txt", ((CreatedResponse) response).path());

        ArgumentCaptor<AssetEntity> assetCaptor = ArgumentCaptor.forClass(AssetEntity.class);
        verify(assetRepository).save(assetCaptor.capture());
        AssetEntity savedAsset = assetCaptor.getValue();
        assertEquals("raw", savedAsset.getFormat());
        assertEquals("docs/readme.txt", savedAsset.getPath());
        assertEquals("text/plain", savedAsset.getContentType());
    }

    @Test
    void handleHostedDelete_assetNotFound_returnsNotFound() {
        when(assetRepository.findByRepositoryIdAndPath(REPO_ID, "missing.txt")).thenReturn(Optional.empty());

        var response = handler.handleHostedDelete(REPO_CONFIG, "missing.txt", request);

        assertInstanceOf(NotFoundResponse.class, response);
    }

    @Test
    void handleHostedDelete_assetFound_deletesAndReturnsContent() {
        AssetEntity asset = createAssetEntity("delete-me.txt", "default@blob-xyz");

        when(assetRepository.findByRepositoryIdAndPath(REPO_ID, "delete-me.txt")).thenReturn(Optional.of(asset));
        when(blobStoreManager.get(BLOB_STORE_NAME)).thenReturn(blobStore);
        when(blobStore.delete(any(BlobRef.class))).thenReturn(true);

        var response = handler.handleHostedDelete(REPO_CONFIG, "delete-me.txt", request);

        assertInstanceOf(ContentResponse.class, response);
        verify(assetRepository).delete(asset);
        verify(blobStore).delete(any(BlobRef.class));
    }

    @Test
    void handleProxyGet_delegatesToLookup() {
        when(assetRepository.findByRepositoryIdAndPath(REPO_ID, "proxy-file.txt"))
                .thenReturn(Optional.empty());

        var response = handler.handleProxyGet(REPO_CONFIG, "proxy-file.txt", request);

        assertInstanceOf(NotFoundResponse.class, response);
    }

    @Test
    void handleGroupGet_delegatesToLookup() {
        when(assetRepository.findByRepositoryIdAndPath(REPO_ID, "group-file.txt"))
                .thenReturn(Optional.empty());

        var response = handler.handleGroupGet(REPO_CONFIG, "group-file.txt", request);

        assertInstanceOf(NotFoundResponse.class, response);
    }

    @Test
    void isMetadataPath_alwaysFalse() {
        assertFalse(handler.isMetadataPath("anything"));
        assertFalse(handler.isMetadataPath("maven-metadata.xml"));
    }

    @Test
    void mergeMetadata_alwaysEmpty() {
        assertTrue(handler.mergeMetadata(REPO_CONFIG, "path", java.util.List.of()).isEmpty());
    }

    @Test
    void handleHostedPut_invalidPath_returnsError() throws IOException {
        ServletInputStream servletInputStream =
                createServletInputStream("data".getBytes());
        when(request.getInputStream()).thenReturn(servletInputStream);
        when(request.getContentType()).thenReturn("application/octet-stream");

        var response = handler.handleHostedPut(REPO_CONFIG, "/", request);

        assertInstanceOf(ErrorResponse.class, response);
        assertEquals(400, ((ErrorResponse) response).statusCode());
    }

    private AssetEntity createAssetEntity(String path, String blobRefStr) {
        AssetEntity asset = new AssetEntity();
        asset.setId(UUID.randomUUID());
        asset.setRepositoryId(REPO_ID);
        asset.setPath(path);
        asset.setFormat("raw");
        asset.setBlobRef(blobRefStr);
        asset.setContentType("text/plain");
        asset.setSize(42L);
        asset.setChecksumMd5("abc123");
        asset.setChecksumSha1("def456");
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
