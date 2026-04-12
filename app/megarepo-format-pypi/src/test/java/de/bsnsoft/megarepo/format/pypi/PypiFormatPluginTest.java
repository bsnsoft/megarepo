package de.bsnsoft.megarepo.format.pypi;

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
class PypiFormatPluginTest {

    @Mock
    private PypiRequestHandler requestHandler;

    @Mock
    private PypiCoordinateExtractor coordinateExtractor;

    private PypiFormatPlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = new PypiFormatPlugin(requestHandler, coordinateExtractor);
    }

    @Test
    void getFormat_returnsPypi() {
        assertEquals("pypi", plugin.getFormat());
    }

    @Test
    void getDisplayName_returnsPyPI() {
        assertEquals("PyPI", plugin.getDisplayName());
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
    void getDefaultRemoteUrl_returnsPypiOrg() {
        assertTrue(plugin.getDefaultRemoteUrl().isPresent());
        assertEquals("https://pypi.org/", plugin.getDefaultRemoteUrl().get());
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
    void getUploadDefinition_hasComponentAndAssetFields() {
        var definition = plugin.getUploadDefinition();
        assertNotNull(definition);
        assertEquals("pypi", definition.format());
        assertFalse(definition.multipleUpload());

        // Component fields: name and version
        assertEquals(2, definition.componentFields().size());
        assertEquals("name", definition.componentFields().get(0).name());
        assertEquals("version", definition.componentFields().get(1).name());

        // Asset fields: content (file)
        assertEquals(1, definition.assetFields().size());
        assertEquals("content", definition.assetFields().getFirst().name());
        assertEquals("file", definition.assetFields().getFirst().type());
        assertFalse(definition.assetFields().getFirst().optional());
    }

    @Test
    void validateRepositoryConfig_doesNotThrow() {
        plugin.validateRepositoryConfig(RepositoryType.HOSTED, Map.of());
        plugin.validateRepositoryConfig(RepositoryType.PROXY, Map.of("anything", "goes"));
        plugin.validateRepositoryConfig(RepositoryType.GROUP, Map.of());
    }
}
