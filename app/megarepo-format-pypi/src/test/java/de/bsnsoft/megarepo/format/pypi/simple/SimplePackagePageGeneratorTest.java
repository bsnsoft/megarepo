package de.bsnsoft.megarepo.format.pypi.simple;

import de.bsnsoft.megarepo.core.format.FormatResponse.ContentResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.NotFoundResponse;
import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.core.repository.RepositoryType;
import de.bsnsoft.megarepo.database.entity.AssetEntity;
import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import de.bsnsoft.megarepo.database.repository.AssetJpaRepository;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import de.bsnsoft.megarepo.format.pypi.naming.PythonNameNormalizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SimplePackagePageGeneratorTest {

    @Mock
    private ComponentJpaRepository componentRepository;

    @Mock
    private AssetJpaRepository assetRepository;

    private SimplePackagePageGenerator generator;

    private static final UUID REPO_ID = UUID.randomUUID();
    private static final RepositoryConfig REPO = new RepositoryConfig(
            REPO_ID, "pypi-hosted", "pypi", RepositoryType.HOSTED, true, "default", Map.of());

    @BeforeEach
    void setUp() {
        generator = new SimplePackagePageGenerator(componentRepository, assetRepository, new PythonNameNormalizer());
    }

    @Test
    void generate_packageNotFound() {
        when(componentRepository.findByRepositoryId(eq(REPO_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        var response = generator.generate(REPO, "nonexistent");
        assertInstanceOf(NotFoundResponse.class, response);
    }

    @Test
    void generate_packageWithVersions() throws IOException {
        UUID compId1 = UUID.randomUUID();
        UUID compId2 = UUID.randomUUID();
        var comp1 = createComponent(compId1, "requests", "2.28.0");
        var comp2 = createComponent(compId2, "requests", "2.29.0");

        when(componentRepository.findByRepositoryId(eq(REPO_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(comp1, comp2)));

        var asset1 = createAsset(compId1, "packages/requests-2.28.0.tar.gz", "abc123");
        var asset2 = createAsset(compId2, "packages/requests-2.29.0.tar.gz", "def456");

        when(assetRepository.findByComponentId(eq(compId1), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(asset1)));
        when(assetRepository.findByComponentId(eq(compId2), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(asset2)));

        var response = generator.generate(REPO, "requests");
        assertInstanceOf(ContentResponse.class, response);

        ContentResponse content = (ContentResponse) response;
        String html = new String(content.content().readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(html.contains("<h1>Links for requests</h1>"));
        assertTrue(html.contains("packages/requests-2.28.0.tar.gz#sha256=abc123"));
        assertTrue(html.contains("packages/requests-2.29.0.tar.gz#sha256=def456"));
        assertTrue(html.contains(">requests-2.28.0.tar.gz</a>"));
        assertTrue(html.contains(">requests-2.29.0.tar.gz</a>"));
    }

    @Test
    void generate_sha256InLinks() throws IOException {
        UUID compId = UUID.randomUUID();
        var comp = createComponent(compId, "flask", "2.3.2");

        when(componentRepository.findByRepositoryId(eq(REPO_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(comp)));

        var asset = createAsset(compId, "packages/flask-2.3.2.tar.gz", "sha256hash");
        when(assetRepository.findByComponentId(eq(compId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(asset)));

        var response = generator.generate(REPO, "flask");
        ContentResponse content = (ContentResponse) response;
        String html = new String(content.content().readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(html.contains("#sha256=sha256hash"));
    }

    @Test
    void generate_normalizedNameMatching() throws IOException {
        UUID compId = UUID.randomUUID();
        var comp = createComponent(compId, "My_Package", "1.0.0");

        when(componentRepository.findByRepositoryId(eq(REPO_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(comp)));

        var asset = createAsset(compId, "packages/My_Package-1.0.0.tar.gz", "hash");
        when(assetRepository.findByComponentId(eq(compId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(asset)));

        // Query with different casing/separators should still match via normalization
        var response = generator.generate(REPO, "my-package");
        assertInstanceOf(ContentResponse.class, response);
    }

    @Test
    void generate_assetWithoutSha256() throws IOException {
        UUID compId = UUID.randomUUID();
        var comp = createComponent(compId, "simple-lib", "0.1");

        when(componentRepository.findByRepositoryId(eq(REPO_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(comp)));

        var asset = createAsset(compId, "packages/simple-lib-0.1.tar.gz", null);
        when(assetRepository.findByComponentId(eq(compId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(asset)));

        var response = generator.generate(REPO, "simple-lib");
        ContentResponse content = (ContentResponse) response;
        String html = new String(content.content().readAllBytes(), StandardCharsets.UTF_8);

        // No hash fragment when sha256 is null
        assertTrue(html.contains("href=\"../../packages/simple-lib-0.1.tar.gz\""));
        assertTrue(!html.contains("#sha256="));
    }

    private ComponentEntity createComponent(UUID id, String name, String version) {
        var component = new ComponentEntity();
        component.setId(id);
        component.setRepositoryId(REPO_ID);
        component.setFormat("pypi");
        component.setName(name);
        component.setVersion(version);
        return component;
    }

    private AssetEntity createAsset(UUID componentId, String path, String sha256) {
        var asset = new AssetEntity();
        asset.setId(UUID.randomUUID());
        asset.setRepositoryId(REPO_ID);
        asset.setComponentId(componentId);
        asset.setFormat("pypi");
        asset.setPath(path);
        asset.setChecksumSha256(sha256);
        asset.setLastModified(Instant.now());
        asset.setCreatedAt(Instant.now());
        asset.setUpdatedAt(Instant.now());
        return asset;
    }
}
