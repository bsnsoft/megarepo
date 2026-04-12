package de.bsnsoft.megarepo.format.raw;

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
class RawFormatPluginTest {

    @Mock
    private RawRequestHandler requestHandler;

    private RawCoordinateExtractor coordinateExtractor;

    private RawFormatPlugin plugin;

    @BeforeEach
    void setUp() {
        coordinateExtractor = new RawCoordinateExtractor();
        plugin = new RawFormatPlugin(requestHandler, coordinateExtractor);
    }

    @Test
    void getFormat_returnsRaw() {
        assertEquals("raw", plugin.getFormat());
    }

    @Test
    void getDisplayName_returnsRaw() {
        assertEquals("Raw", plugin.getDisplayName());
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
    void getDefaultRemoteUrl_isEmpty() {
        assertTrue(plugin.getDefaultRemoteUrl().isEmpty());
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
        assertEquals("raw", definition.format());
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
        // Should be a no-op for raw format
        plugin.validateRepositoryConfig(RepositoryType.HOSTED, Map.of());
        plugin.validateRepositoryConfig(RepositoryType.PROXY, Map.of("anything", "goes"));
        plugin.validateRepositoryConfig(RepositoryType.GROUP, Map.of());
    }
}
