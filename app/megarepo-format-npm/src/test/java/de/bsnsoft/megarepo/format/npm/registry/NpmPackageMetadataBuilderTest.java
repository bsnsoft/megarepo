package de.bsnsoft.megarepo.format.npm.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.bsnsoft.megarepo.core.format.FormatResponse.ContentResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.NotFoundResponse;
import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.core.repository.RepositoryType;
import de.bsnsoft.megarepo.database.entity.AssetEntity;
import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import de.bsnsoft.megarepo.database.repository.AssetJpaRepository;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import de.bsnsoft.megarepo.format.npm.scope.ScopedPackageResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NpmPackageMetadataBuilderTest {

    @Mock
    private ComponentJpaRepository componentRepository;

    @Mock
    private AssetJpaRepository assetRepository;

    private NpmPackageMetadataBuilder builder;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final UUID REPO_ID = UUID.randomUUID();
    private static final String BASE_URL = "http://localhost:8080";
    private static final RepositoryConfig REPO_CONFIG = new RepositoryConfig(
            REPO_ID, "npm-hosted", "npm", RepositoryType.HOSTED, true, "default", Map.of());

    @BeforeEach
    void setUp() {
        builder = new NpmPackageMetadataBuilder(componentRepository, assetRepository, new ScopedPackageResolver());
    }

    @Test
    void buildMetadata_singleVersion_returnsValidJson() throws IOException {
        ComponentEntity component = createComponent(null, "lodash", "4.17.21");
        when(componentRepository.findByRepositoryIdAndNamespaceAndName(REPO_ID, null, "lodash"))
                .thenReturn(List.of(component));

        AssetEntity asset = createAsset("-/lodash-4.17.21.tgz", "abc123sha1");
        when(assetRepository.findByRepositoryIdAndPath(eq(REPO_ID), eq("-/lodash-4.17.21.tgz")))
                .thenReturn(Optional.of(asset));

        var response = builder.buildMetadata(REPO_CONFIG, "lodash", BASE_URL);

        assertInstanceOf(ContentResponse.class, response);
        ContentResponse content = (ContentResponse) response;
        assertEquals("application/json", content.contentType());

        JsonNode json = objectMapper.readTree(content.content().readAllBytes());
        assertEquals("lodash", json.get("name").asText());
        assertNotNull(json.get("versions").get("4.17.21"));
        assertEquals("4.17.21", json.get("dist-tags").get("latest").asText());
        assertEquals("abc123sha1",
                json.get("versions").get("4.17.21").get("dist").get("shasum").asText());
        assertTrue(json.get("versions").get("4.17.21").get("dist").get("tarball").asText()
                .contains("/repository/npm-hosted/-/lodash-4.17.21.tgz"));
    }

    @Test
    void buildMetadata_multipleVersions_returnsAllVersions() throws IOException {
        ComponentEntity v1 = createComponent(null, "express", "4.17.0");
        ComponentEntity v2 = createComponent(null, "express", "4.18.2");
        when(componentRepository.findByRepositoryIdAndNamespaceAndName(REPO_ID, null, "express"))
                .thenReturn(List.of(v1, v2));

        when(assetRepository.findByRepositoryIdAndPath(eq(REPO_ID), eq("-/express-4.17.0.tgz")))
                .thenReturn(Optional.empty());
        when(assetRepository.findByRepositoryIdAndPath(eq(REPO_ID), eq("-/express-4.18.2.tgz")))
                .thenReturn(Optional.empty());

        var response = builder.buildMetadata(REPO_CONFIG, "express", BASE_URL);

        assertInstanceOf(ContentResponse.class, response);
        ContentResponse content = (ContentResponse) response;

        JsonNode json = objectMapper.readTree(content.content().readAllBytes());
        assertEquals("express", json.get("name").asText());
        assertNotNull(json.get("versions").get("4.17.0"));
        assertNotNull(json.get("versions").get("4.18.2"));
        assertEquals("4.18.2", json.get("dist-tags").get("latest").asText());
    }

    @Test
    void buildMetadata_scopedPackage_usesCorrectNamespaceAndPaths() throws IOException {
        ComponentEntity component = createComponent("@angular", "core", "17.0.0");
        when(componentRepository.findByRepositoryIdAndNamespaceAndName(REPO_ID, "@angular", "core"))
                .thenReturn(List.of(component));

        when(assetRepository.findByRepositoryIdAndPath(
                        eq(REPO_ID), eq("@angular/core/-/core-17.0.0.tgz")))
                .thenReturn(Optional.empty());

        var response = builder.buildMetadata(REPO_CONFIG, "@angular/core", BASE_URL);

        assertInstanceOf(ContentResponse.class, response);
        ContentResponse content = (ContentResponse) response;

        JsonNode json = objectMapper.readTree(content.content().readAllBytes());
        assertEquals("@angular/core", json.get("name").asText());
        assertNotNull(json.get("versions").get("17.0.0"));
        assertTrue(json.get("versions").get("17.0.0").get("dist").get("tarball").asText()
                .contains("@angular/core/-/core-17.0.0.tgz"));
    }

    @Test
    void buildMetadata_noComponents_returnsNotFound() {
        when(componentRepository.findByRepositoryIdAndNamespaceAndName(REPO_ID, null, "nonexistent"))
                .thenReturn(List.of());

        var response = builder.buildMetadata(REPO_CONFIG, "nonexistent", BASE_URL);

        assertInstanceOf(NotFoundResponse.class, response);
    }

    private ComponentEntity createComponent(String namespace, String name, String version) {
        ComponentEntity component = new ComponentEntity();
        component.setId(UUID.randomUUID());
        component.setRepositoryId(REPO_ID);
        component.setFormat("npm");
        component.setNamespace(namespace);
        component.setName(name);
        component.setVersion(version);
        component.setCreatedAt(Instant.now());
        component.setUpdatedAt(Instant.now());
        return component;
    }

    private AssetEntity createAsset(String path, String sha1) {
        AssetEntity asset = new AssetEntity();
        asset.setId(UUID.randomUUID());
        asset.setRepositoryId(REPO_ID);
        asset.setPath(path);
        asset.setFormat("npm");
        asset.setContentType("application/gzip");
        asset.setSize(1024L);
        asset.setChecksumSha1(sha1);
        asset.setLastModified(Instant.now());
        asset.setCreatedAt(Instant.now());
        asset.setUpdatedAt(Instant.now());
        return asset;
    }
}
