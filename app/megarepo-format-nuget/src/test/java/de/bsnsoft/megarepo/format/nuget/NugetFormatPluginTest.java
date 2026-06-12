package de.bsnsoft.megarepo.format.nuget;

import de.bsnsoft.megarepo.core.repository.RepositoryType;
import de.bsnsoft.megarepo.format.nuget.upload.NugetUploadHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
class NugetFormatPluginTest {

    @Mock
    private NugetRequestHandler requestHandler;

    @Mock
    private NugetCoordinateExtractor coordinateExtractor;

    @Mock
    private NugetUploadHandler uploadHandler;

    @Test
    void pluginDescribesNugetFormat() {
        var plugin = new NugetFormatPlugin(requestHandler, coordinateExtractor, uploadHandler);

        assertEquals("nuget", plugin.getFormat());
        assertEquals("NuGet", plugin.getDisplayName());
        assertEquals(
                Set.of(RepositoryType.HOSTED, RepositoryType.PROXY, RepositoryType.GROUP),
                plugin.getSupportedTypes());
        assertEquals("https://api.nuget.org/v3/index.json", plugin.getDefaultRemoteUrl().orElseThrow());
        assertTrue(plugin.getComponentUploadHandler().isPresent());
        assertEquals("nuget", plugin.getUploadDefinition().format());
        assertEquals(1, plugin.getUploadDefinition().assetFields().size());
    }
}
