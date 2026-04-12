package de.bsnsoft.megarepo.repository.hosted;

import de.bsnsoft.megarepo.core.format.ComponentCoordinates;
import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.core.repository.RepositoryType;
import de.bsnsoft.megarepo.core.storage.Blob;
import de.bsnsoft.megarepo.core.storage.BlobProperties;
import de.bsnsoft.megarepo.core.storage.BlobRef;
import de.bsnsoft.megarepo.database.entity.AssetEntity;
import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import de.bsnsoft.megarepo.repository.AssetService;
import de.bsnsoft.megarepo.repository.ComponentService;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HostedHandlerTest {

    @Mock
    private AssetService assetService;

    @Mock
    private ComponentService componentService;

    private HostedHandler handler;

    @BeforeEach
    void setUp() {
        handler = new HostedHandler(assetService, componentService);
    }

    @Test
    void storeAsset_allowPolicy() {
        var repo = createRepo("my-repo", Map.of("storage", Map.of("writePolicy", "ALLOW")));
        var coords = new ComponentCoordinates("com.example", "artifact", "1.0", Map.of());
        var content = new ByteArrayInputStream("data".getBytes());
        var component = createComponent(repo.id());
        var asset = createAsset(repo.id());

        when(componentService.findOrCreate(repo.id(), "maven2", coords)).thenReturn(component);
        when(assetService.createAsset(
                        eq(repo.id()),
                        eq(component.getId()),
                        eq("maven2"),
                        eq("com/example/artifact.jar"),
                        eq(content),
                        eq("application/java-archive"),
                        eq("admin"),
                        eq("127.0.0.1"),
                        eq("default")))
                .thenReturn(asset);

        AssetEntity result = handler.storeAsset(
                repo, "com/example/artifact.jar", content, "application/java-archive", coords, "admin", "127.0.0.1");

        assertNotNull(result);
    }

    @Test
    void storeAsset_denyPolicy_throws() {
        var repo = createRepo("my-repo", Map.of("storage", Map.of("writePolicy", "DENY")));
        var coords = new ComponentCoordinates("com.example", "artifact", "1.0", Map.of());
        var content = new ByteArrayInputStream("data".getBytes());

        assertThrows(WriteNotAllowedException.class, () -> handler.storeAsset(
                repo, "com/example/artifact.jar", content, "application/java-archive", coords, "admin", "127.0.0.1"));
    }

    @Test
    void storeAsset_allowOnce_existingAsset_throws() {
        var repo = createRepo("my-repo", Map.of("storage", Map.of("writePolicy", "ALLOW_ONCE")));
        var coords = new ComponentCoordinates("com.example", "artifact", "1.0", Map.of());
        var content = new ByteArrayInputStream("data".getBytes());

        when(assetService.getAsset(repo.id(), "com/example/artifact.jar"))
                .thenReturn(Optional.of(createAsset(repo.id())));

        assertThrows(WriteNotAllowedException.class, () -> handler.storeAsset(
                repo, "com/example/artifact.jar", content, "application/java-archive", coords, "admin", "127.0.0.1"));
    }

    @Test
    void storeAsset_allowOnce_newAsset_succeeds() {
        var repo = createRepo("my-repo", Map.of("storage", Map.of("writePolicy", "ALLOW_ONCE")));
        var coords = new ComponentCoordinates("com.example", "artifact", "1.0", Map.of());
        var content = new ByteArrayInputStream("data".getBytes());
        var component = createComponent(repo.id());
        var asset = createAsset(repo.id());

        when(assetService.getAsset(repo.id(), "com/example/artifact.jar")).thenReturn(Optional.empty());
        when(componentService.findOrCreate(repo.id(), "maven2", coords)).thenReturn(component);
        when(assetService.createAsset(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(asset);

        AssetEntity result = handler.storeAsset(
                repo, "com/example/artifact.jar", content, "application/java-archive", coords, "admin", "127.0.0.1");

        assertNotNull(result);
    }

    @Test
    void getAsset_found() {
        var repo = createRepo("my-repo", Map.of());
        var asset = createAsset(repo.id());
        var blobRef = new BlobRef("default", "blob-123");
        var blob = new Blob(blobRef, InputStream.nullInputStream(), new BlobProperties(100, "application/octet-stream", Map.of(), Instant.now(), Map.of()));

        when(assetService.getAsset(repo.id(), "some/path")).thenReturn(Optional.of(asset));
        when(assetService.getAssetContent(asset)).thenReturn(Optional.of(blob));

        Optional<Blob> result = handler.getAsset(repo, "some/path");

        assertTrue(result.isPresent());
        verify(assetService).updateLastDownloaded(asset.getId(), repo.id());
    }

    @Test
    void getAsset_notFound() {
        var repo = createRepo("my-repo", Map.of());
        when(assetService.getAsset(repo.id(), "missing/path")).thenReturn(Optional.empty());

        Optional<Blob> result = handler.getAsset(repo, "missing/path");

        assertFalse(result.isPresent());
    }

    @Test
    void deleteAsset_found() {
        var repo = createRepo("my-repo", Map.of());
        var asset = createAsset(repo.id());
        when(assetService.getAsset(repo.id(), "some/path")).thenReturn(Optional.of(asset));
        when(assetService.deleteAsset(asset.getId())).thenReturn(true);

        boolean result = handler.deleteAsset(repo, "some/path");

        assertTrue(result);
    }

    @Test
    void deleteAsset_notFound() {
        var repo = createRepo("my-repo", Map.of());
        when(assetService.getAsset(repo.id(), "missing/path")).thenReturn(Optional.empty());

        boolean result = handler.deleteAsset(repo, "missing/path");

        assertFalse(result);
    }

    @Test
    void storeAsset_defaultWritePolicy_allows() {
        // No storage attributes -> defaults to ALLOW
        var repo = createRepo("my-repo", Map.of());
        var coords = new ComponentCoordinates("com.example", "artifact", "1.0", Map.of());
        var content = new ByteArrayInputStream("data".getBytes());
        var component = createComponent(repo.id());
        var asset = createAsset(repo.id());

        when(componentService.findOrCreate(repo.id(), "maven2", coords)).thenReturn(component);
        when(assetService.createAsset(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(asset);

        AssetEntity result = handler.storeAsset(
                repo, "com/example/artifact.jar", content, "application/java-archive", coords, "admin", "127.0.0.1");

        assertNotNull(result);
    }

    private RepositoryConfig createRepo(String name, Map<String, Object> attributes) {
        return new RepositoryConfig(
                UUID.randomUUID(), name, "maven2", RepositoryType.HOSTED, true, "default", attributes);
    }

    private ComponentEntity createComponent(UUID repoId) {
        var component = new ComponentEntity();
        component.setId(UUID.randomUUID());
        component.setRepositoryId(repoId);
        component.setFormat("maven2");
        component.setNamespace("com.example");
        component.setName("artifact");
        component.setVersion("1.0");
        component.setCreatedAt(Instant.now());
        component.setUpdatedAt(Instant.now());
        return component;
    }

    private AssetEntity createAsset(UUID repoId) {
        var asset = new AssetEntity();
        asset.setId(UUID.randomUUID());
        asset.setRepositoryId(repoId);
        asset.setFormat("maven2");
        asset.setPath("com/example/artifact.jar");
        asset.setBlobRef("default@blob-123");
        asset.setContentType("application/java-archive");
        asset.setSize(100L);
        asset.setCreatedAt(Instant.now());
        asset.setUpdatedAt(Instant.now());
        asset.setLastModified(Instant.now());
        return asset;
    }
}
