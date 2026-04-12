package de.bsnsoft.megarepo.format.docker;

import de.bsnsoft.megarepo.core.repository.RepositoryType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class DockerFormatPluginTest {

    private DockerFormatPlugin plugin;

    @BeforeEach
    void setUp() {
        var requestHandler = mock(DockerRequestHandler.class);
        var coordinateExtractor = new DockerCoordinateExtractor();
        plugin = new DockerFormatPlugin(requestHandler, coordinateExtractor);
    }

    @Test
    void formatNameIsDocker() {
        assertEquals("docker", plugin.getFormat());
    }

    @Test
    void displayNameIsDocker() {
        assertEquals("Docker", plugin.getDisplayName());
    }

    @Test
    void supportsHostedType() {
        assertTrue(plugin.getSupportedTypes().contains(RepositoryType.HOSTED));
    }

    @Test
    void hasDefaultRemoteUrl() {
        assertTrue(plugin.getDefaultRemoteUrl().isPresent());
        assertEquals("https://registry-1.docker.io", plugin.getDefaultRemoteUrl().get());
    }

    @Test
    void noSearchContributor() {
        assertTrue(plugin.getSearchContributor().isEmpty());
    }

    @Test
    void uploadDefinitionIsEmpty() {
        var uploadDef = plugin.getUploadDefinition();
        assertEquals("docker", uploadDef.format());
        assertFalse(uploadDef.multipleUpload());
        assertTrue(uploadDef.componentFields().isEmpty());
        assertTrue(uploadDef.assetFields().isEmpty());
    }

    @Test
    void validateRepositoryConfigAcceptsNull() {
        // Should not throw
        plugin.validateRepositoryConfig(RepositoryType.HOSTED, null);
        plugin.validateRepositoryConfig(RepositoryType.HOSTED, Map.of());
    }
}
