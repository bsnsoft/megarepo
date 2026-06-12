package de.bsnsoft.megarepo.format.maven;

import de.bsnsoft.megarepo.core.format.FormatPlugin;
import de.bsnsoft.megarepo.core.format.UploadDefinition;
import de.bsnsoft.megarepo.core.repository.RepositoryType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
class MavenFormatPluginTest {

    @Mock
    private MavenRequestHandler requestHandler;

    @Mock
    private MavenCoordinateExtractor coordinateExtractor;

    @Mock
    private MavenSearchContributor searchContributor;

    @Mock
    private MavenUploadHandler uploadHandler;

    private MavenFormatPlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = new MavenFormatPlugin(requestHandler, coordinateExtractor, searchContributor, uploadHandler);
    }

    @Test
    void format_isMaven2() {
        assertEquals("maven2", plugin.getFormat());
    }

    @Test
    void displayName_isMaven() {
        assertEquals("Maven", plugin.getDisplayName());
    }

    @Test
    void supportsAllThreeRepositoryTypes() {
        Set<RepositoryType> supportedTypes = plugin.getSupportedTypes();

        assertTrue(supportedTypes.contains(RepositoryType.HOSTED));
        assertTrue(supportedTypes.contains(RepositoryType.PROXY));
        assertTrue(supportedTypes.contains(RepositoryType.GROUP));
        assertEquals(3, supportedTypes.size());
    }

    @Test
    void defaultRemoteUrl_isMavenCentral() {
        assertTrue(plugin.getDefaultRemoteUrl().isPresent());
        assertEquals("https://repo1.maven.org/maven2/", plugin.getDefaultRemoteUrl().get());
    }

    @Test
    void requestHandler_isNotNull() {
        assertNotNull(plugin.getRequestHandler());
    }

    @Test
    void coordinateExtractor_isNotNull() {
        assertNotNull(plugin.getCoordinateExtractor());
    }

    @Test
    void uploadDefinition_hasCorrectFormat() {
        UploadDefinition definition = plugin.getUploadDefinition();

        assertEquals("maven2", definition.format());
    }

    @Test
    void uploadDefinition_hasGroupIdField() {
        UploadDefinition definition = plugin.getUploadDefinition();

        boolean hasGroupId = definition.componentFields().stream()
                .anyMatch(field -> "groupId".equals(field.name()));
        assertTrue(hasGroupId, "Upload definition should have a groupId component field");
    }

    @Test
    void uploadDefinition_hasArtifactIdField() {
        UploadDefinition definition = plugin.getUploadDefinition();

        boolean hasArtifactId = definition.componentFields().stream()
                .anyMatch(field -> "artifactId".equals(field.name()));
        assertTrue(hasArtifactId, "Upload definition should have an artifactId component field");
    }

    @Test
    void uploadDefinition_hasVersionField() {
        UploadDefinition definition = plugin.getUploadDefinition();

        boolean hasVersion = definition.componentFields().stream()
                .anyMatch(field -> "version".equals(field.name()));
        assertTrue(hasVersion, "Upload definition should have a version component field");
    }

    @Test
    void uploadDefinition_supportsMultipleUpload() {
        UploadDefinition definition = plugin.getUploadDefinition();

        // Maven supports uploading multiple assets per component (jar, pom, sources, javadoc)
        assertTrue(definition.multipleUpload());
    }

    @Test
    void uploadDefinition_hasAssetFields() {
        UploadDefinition definition = plugin.getUploadDefinition();

        assertNotNull(definition.assetFields());
        assertTrue(definition.assetFields().size() > 0, "Upload definition should have at least one asset field");
    }

    @Test
    void implementsFormatPlugin() {
        assertTrue(plugin instanceof FormatPlugin);
    }
}
