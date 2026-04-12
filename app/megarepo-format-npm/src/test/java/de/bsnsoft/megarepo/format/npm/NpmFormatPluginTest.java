package de.bsnsoft.megarepo.format.npm;

import de.bsnsoft.megarepo.core.repository.RepositoryType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
class NpmFormatPluginTest {

    @Mock
    private NpmRequestHandler requestHandler;

    private NpmCoordinateExtractor coordinateExtractor;

    private NpmFormatPlugin plugin;

    @BeforeEach
    void setUp() {
        coordinateExtractor = new NpmCoordinateExtractor();
        plugin = new NpmFormatPlugin(requestHandler, coordinateExtractor);
    }

    @Test
    void getFormat_returnsNpm() {
        assertEquals("npm", plugin.getFormat());
    }

    @Test
    void getDisplayName_returnsNpm() {
        assertEquals("npm", plugin.getDisplayName());
    }

    @Test
    void getSupportedTypes_includesAllTypes() {
        Set<RepositoryType> types = plugin.getSupportedTypes();
        assertEquals(3, types.size());
        assertTrue(types.contains(RepositoryType.HOSTED));
        assertTrue(types.contains(RepositoryType.PROXY));
        assertTrue(types.contains(RepositoryType.GROUP));
    }

    @Test
    void getDefaultRemoteUrl_isNpmjsOrg() {
        assertTrue(plugin.getDefaultRemoteUrl().isPresent());
        assertEquals("https://registry.npmjs.org/", plugin.getDefaultRemoteUrl().get());
    }

    @Test
    void getRequestHandler_returnsInjectedHandler() {
        assertSame(requestHandler, plugin.getRequestHandler());
    }

    @Test
    void getCoordinateExtractor_returnsInjectedExtractor() {
        assertSame(coordinateExtractor, plugin.getCoordinateExtractor());
    }

    @Test
    void getSearchContributor_isEmpty() {
        assertTrue(plugin.getSearchContributor().isEmpty());
    }

    @Test
    void getUploadDefinition_hasSingleFileField() {
        var definition = plugin.getUploadDefinition();
        assertNotNull(definition);
        assertEquals("npm", definition.format());
        assertFalse(definition.multipleUpload());
        assertTrue(definition.componentFields().isEmpty());
        assertEquals(1, definition.assetFields().size());

        var fileField = definition.assetFields().getFirst();
        assertEquals("file", fileField.name());
        assertEquals("file", fileField.type());
        assertFalse(fileField.optional());
    }

    @Test
    void validateRepositoryConfig_doesNotThrow() {
        plugin.validateRepositoryConfig(RepositoryType.HOSTED, Map.of());
        plugin.validateRepositoryConfig(RepositoryType.PROXY, Map.of("anything", "goes"));
        plugin.validateRepositoryConfig(RepositoryType.GROUP, Map.of());
    }
}
