package de.bsnsoft.megarepo.format.nuget.push;

import de.bsnsoft.megarepo.core.format.FormatResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.CreatedResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.ErrorResponse;
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
import de.bsnsoft.megarepo.format.nuget.TestNupkgs;
import de.bsnsoft.megarepo.format.nuget.meta.NupkgReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NugetPushHandlerTest {

    private static final UUID REPO_ID = UUID.randomUUID();
    private static final RepositoryConfig REPO = new RepositoryConfig(
            REPO_ID, "nuget-hosted", "nuget", RepositoryType.HOSTED, true, "default", Map.of());

    @Mock
    private de.bsnsoft.megarepo.storage.BlobStoreManager blobStoreManager;

    @Mock
    private ComponentJpaRepository componentRepository;

    @Mock
    private AssetJpaRepository assetRepository;

    @Mock
    private BlobStore blobStore;

    private NugetPushHandler handler;

    @BeforeEach
    void setUp() {
        handler = new NugetPushHandler(
                blobStoreManager, componentRepository, assetRepository,
                new NupkgReader(), new MultipartNupkgExtractor());
    }

    @Test
    void storePackage_createsLowercaseFlatContainerAssets() {
        byte[] nupkg = TestNupkgs.nupkg("My.Package", "1.0.0", "Test package");
        mockHappyPathStorage();

        FormatResponse response = handler.storePackage(REPO, nupkg, "admin", "127.0.0.1");

        CreatedResponse created = assertInstanceOf(CreatedResponse.class, response);
        assertEquals("v3-flatcontainer/my.package/1.0.0/my.package.1.0.0.nupkg", created.path());

        ArgumentCaptor<AssetEntity> assetCaptor = ArgumentCaptor.forClass(AssetEntity.class);
        verify(assetRepository, atLeastOnce()).save(assetCaptor.capture());
        List<String> paths = assetCaptor.getAllValues().stream().map(AssetEntity::getPath).toList();
        assertTrue(paths.contains("v3-flatcontainer/my.package/1.0.0/my.package.1.0.0.nupkg"));
        assertTrue(paths.contains("v3-flatcontainer/my.package/1.0.0/my.package.nuspec"));
    }

    @Test
    void storePackage_normalizesVersion() {
        byte[] nupkg = TestNupkgs.nupkg("Pkg", "1.0.0.0", "Revision dropped");
        mockHappyPathStorage();

        FormatResponse response = handler.storePackage(REPO, nupkg, "admin", "127.0.0.1");

        CreatedResponse created = assertInstanceOf(CreatedResponse.class, response);
        assertEquals("v3-flatcontainer/pkg/1.0.0/pkg.1.0.0.nupkg", created.path());
    }

    @Test
    void storePackage_storesMetadataAsComponentAttributes() {
        byte[] nupkg = TestNupkgs.nupkgWithNuspec(
                "deps.nuspec", TestNupkgs.nuspecWithDependencies("Deps.Pkg", "2.0.0"));
        mockHappyPathStorage();

        handler.storePackage(REPO, nupkg, "admin", "127.0.0.1");

        ArgumentCaptor<ComponentEntity> captor = ArgumentCaptor.forClass(ComponentEntity.class);
        verify(componentRepository, atLeastOnce()).save(captor.capture());
        ComponentEntity component = captor.getAllValues().getLast();
        assertEquals("deps.pkg", component.getName());
        assertEquals("2.0.0", component.getVersion());
        assertEquals("Deps.Pkg", component.getAttributes().get("originalId"));
        assertTrue(component.getAttributes().get("dependencies").toString().contains("Newtonsoft.Json"));
    }

    @Test
    void storePackage_duplicateVersion_returns409() {
        byte[] nupkg = TestNupkgs.nupkg("Dup", "1.0.0", "Duplicate");
        var existing = new AssetEntity();
        existing.setBlobRef("default@existing-blob");
        when(assetRepository.findByRepositoryIdAndPath(
                        eq(REPO_ID), eq("v3-flatcontainer/dup/1.0.0/dup.1.0.0.nupkg")))
                .thenReturn(Optional.of(existing));

        FormatResponse response = handler.storePackage(REPO, nupkg, "admin", "127.0.0.1");

        ErrorResponse error = assertInstanceOf(ErrorResponse.class, response);
        assertEquals(409, error.statusCode());
    }

    @Test
    void storePackage_invalidPackage_returns400() {
        FormatResponse response = handler.storePackage(REPO, "not a zip".getBytes(), "admin", "127.0.0.1");

        ErrorResponse error = assertInstanceOf(ErrorResponse.class, response);
        assertEquals(400, error.statusCode());
    }

    private void mockHappyPathStorage() {
        when(blobStoreManager.get("default")).thenReturn(blobStore);
        when(blobStore.store(any(), anyLong(), any()))
                .thenAnswer(inv -> new BlobRef("default", UUID.randomUUID().toString()));
        when(blobStore.get(any(BlobRef.class))).thenAnswer(inv -> {
            BlobRef ref = inv.getArgument(0);
            var props = new BlobProperties(
                    42L, "application/zip", Map.of("sha256", "cafe"), Instant.now(), Map.of());
            return Optional.of(new Blob(ref, new ByteArrayInputStream(new byte[0]), props));
        });
        when(assetRepository.findByRepositoryIdAndPath(eq(REPO_ID), any())).thenReturn(Optional.empty());
        when(assetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(componentRepository.findByRepositoryIdAndNamespaceAndNameAndVersion(eq(REPO_ID), eq(null), any(), any()))
                .thenReturn(Optional.empty());
        when(componentRepository.save(any(ComponentEntity.class))).thenAnswer(inv -> {
            ComponentEntity component = inv.getArgument(0);
            if (component.getId() == null) {
                component.setId(UUID.randomUUID());
            }
            return component;
        });
    }
}
