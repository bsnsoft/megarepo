package de.bsnsoft.megarepo.format.npm.publish;

import de.bsnsoft.megarepo.core.format.FormatResponse.CreatedResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.ErrorResponse;
import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.core.repository.RepositoryType;
import de.bsnsoft.megarepo.core.storage.Blob;
import de.bsnsoft.megarepo.core.storage.BlobProperties;
import de.bsnsoft.megarepo.core.storage.BlobRef;
import de.bsnsoft.megarepo.core.storage.BlobStore;
import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import de.bsnsoft.megarepo.database.repository.AssetJpaRepository;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import de.bsnsoft.megarepo.format.npm.scope.ScopedPackageResolver;
import de.bsnsoft.megarepo.storage.BlobStoreManager;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
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
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NpmPublishHandlerTest {

    @Mock
    private BlobStoreManager blobStoreManager;

    @Mock
    private ComponentJpaRepository componentRepository;

    @Mock
    private AssetJpaRepository assetRepository;

    @Mock
    private BlobStore blobStore;

    @Mock
    private HttpServletRequest request;

    private NpmPublishHandler handler;

    private static final UUID REPO_ID = UUID.randomUUID();
    private static final String BLOB_STORE_NAME = "default";
    private static final RepositoryConfig REPO_CONFIG = new RepositoryConfig(
            REPO_ID, "npm-hosted", "npm", RepositoryType.HOSTED, true, BLOB_STORE_NAME, Map.of());

    @BeforeEach
    void setUp() {
        handler = new NpmPublishHandler(
                blobStoreManager, componentRepository, assetRepository, new ScopedPackageResolver());
    }

    @Test
    void handlePublish_unscopedPackage_createsComponentAndAsset() throws IOException {
        byte[] tarballData = "fake-tarball-content".getBytes(StandardCharsets.UTF_8);
        String base64Tarball = Base64.getEncoder().encodeToString(tarballData);

        String publishJson = """
                {
                    "name": "my-package",
                    "versions": {
                        "1.0.0": {
                            "name": "my-package",
                            "version": "1.0.0",
                            "description": "A test package"
                        }
                    },
                    "_attachments": {
                        "my-package-1.0.0.tgz": {
                            "data": "%s",
                            "length": %d
                        }
                    }
                }
                """.formatted(base64Tarball, tarballData.length);

        when(request.getInputStream()).thenReturn(createServletInputStream(publishJson.getBytes()));
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");

        BlobRef blobRef = new BlobRef(BLOB_STORE_NAME, "new-blob-id");
        BlobProperties props = new BlobProperties(
                tarballData.length, "application/gzip", Map.of("sha1", "abc"), Instant.now(), Map.of());
        Blob blob = new Blob(blobRef, new ByteArrayInputStream(tarballData), props);

        when(blobStoreManager.get(BLOB_STORE_NAME)).thenReturn(blobStore);
        when(blobStore.store(any(), anyLong(), any())).thenReturn(blobRef);
        when(blobStore.get(blobRef)).thenReturn(Optional.of(blob));

        ComponentEntity component = new ComponentEntity();
        component.setId(UUID.randomUUID());
        component.setCreatedAt(Instant.now());
        component.setUpdatedAt(Instant.now());
        when(componentRepository.findByRepositoryIdAndNamespaceAndNameAndVersion(
                        eq(REPO_ID), eq(null), eq("my-package"), eq("1.0.0")))
                .thenReturn(Optional.empty());
        when(componentRepository.save(any(ComponentEntity.class))).thenReturn(component);

        when(assetRepository.findByRepositoryIdAndPath(eq(REPO_ID), eq("-/my-package-1.0.0.tgz")))
                .thenReturn(Optional.empty());
        when(assetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var response = handler.handlePublish(REPO_CONFIG, "my-package", request);

        assertInstanceOf(CreatedResponse.class, response);
    }

    @Test
    void handlePublish_scopedPackage_createsWithNamespace() throws IOException {
        byte[] tarballData = "fake-tarball".getBytes(StandardCharsets.UTF_8);
        String base64Tarball = Base64.getEncoder().encodeToString(tarballData);

        String publishJson = """
                {
                    "name": "@myorg/utils",
                    "versions": {
                        "2.0.0": {
                            "name": "@myorg/utils",
                            "version": "2.0.0",
                            "description": "Org utilities"
                        }
                    },
                    "_attachments": {
                        "utils-2.0.0.tgz": {
                            "data": "%s",
                            "length": %d
                        }
                    }
                }
                """.formatted(base64Tarball, tarballData.length);

        when(request.getInputStream()).thenReturn(createServletInputStream(publishJson.getBytes()));
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");

        BlobRef blobRef = new BlobRef(BLOB_STORE_NAME, "scoped-blob");
        BlobProperties props = new BlobProperties(
                tarballData.length, "application/gzip", Map.of("sha1", "def"), Instant.now(), Map.of());
        Blob blob = new Blob(blobRef, new ByteArrayInputStream(tarballData), props);

        when(blobStoreManager.get(BLOB_STORE_NAME)).thenReturn(blobStore);
        when(blobStore.store(any(), anyLong(), any())).thenReturn(blobRef);
        when(blobStore.get(blobRef)).thenReturn(Optional.of(blob));

        ComponentEntity component = new ComponentEntity();
        component.setId(UUID.randomUUID());
        component.setCreatedAt(Instant.now());
        component.setUpdatedAt(Instant.now());
        when(componentRepository.findByRepositoryIdAndNamespaceAndNameAndVersion(
                        eq(REPO_ID), eq("@myorg"), eq("utils"), eq("2.0.0")))
                .thenReturn(Optional.empty());
        when(componentRepository.save(any(ComponentEntity.class))).thenReturn(component);

        when(assetRepository.findByRepositoryIdAndPath(eq(REPO_ID), eq("@myorg/utils/-/utils-2.0.0.tgz")))
                .thenReturn(Optional.empty());
        when(assetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var response = handler.handlePublish(REPO_CONFIG, "@myorg/utils", request);

        assertInstanceOf(CreatedResponse.class, response);
    }

    @Test
    void handlePublish_invalidJson_returnsError() throws IOException {
        when(request.getInputStream()).thenReturn(createServletInputStream("not-valid-json".getBytes()));

        var response = handler.handlePublish(REPO_CONFIG, "bad-pkg", request);

        assertInstanceOf(ErrorResponse.class, response);
        assertEquals(400, ((ErrorResponse) response).statusCode());
    }

    @Test
    void handlePublish_missingVersions_returnsError() throws IOException {
        String json = """
                {
                    "name": "bad-pkg",
                    "_attachments": {}
                }
                """;
        when(request.getInputStream()).thenReturn(createServletInputStream(json.getBytes()));

        var response = handler.handlePublish(REPO_CONFIG, "bad-pkg", request);

        assertInstanceOf(ErrorResponse.class, response);
        assertEquals(400, ((ErrorResponse) response).statusCode());
    }

    @Test
    void handlePublish_missingAttachments_returnsError() throws IOException {
        String json = """
                {
                    "name": "bad-pkg",
                    "versions": {
                        "1.0.0": {"name": "bad-pkg", "version": "1.0.0"}
                    }
                }
                """;
        when(request.getInputStream()).thenReturn(createServletInputStream(json.getBytes()));

        var response = handler.handlePublish(REPO_CONFIG, "bad-pkg", request);

        assertInstanceOf(ErrorResponse.class, response);
        assertEquals(400, ((ErrorResponse) response).statusCode());
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
