package de.bsnsoft.megarepo.format.nuget;

import de.bsnsoft.megarepo.core.format.ComponentCoordinates;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NugetCoordinateExtractorTest {

    private final NugetCoordinateExtractor extractor = new NugetCoordinateExtractor();

    @Test
    void extractsFromNupkgPath() {
        Optional<ComponentCoordinates> coords =
                extractor.extractFromPath("v3-flatcontainer/serilog/3.1.1/serilog.3.1.1.nupkg");

        assertTrue(coords.isPresent());
        assertNull(coords.get().namespace());
        assertEquals("serilog", coords.get().name());
        assertEquals("3.1.1", coords.get().version());
    }

    @Test
    void extractsFromNuspecPath() {
        Optional<ComponentCoordinates> coords =
                extractor.extractFromPath("v3-flatcontainer/my.pkg/1.0.0-beta/my.pkg.nuspec");

        assertTrue(coords.isPresent());
        assertEquals("my.pkg", coords.get().name());
        assertEquals("1.0.0-beta", coords.get().version());
    }

    @Test
    void versionIndexHasNoCoordinates() {
        assertTrue(extractor.extractFromPath("v3-flatcontainer/my.pkg/index.json").isEmpty());
    }

    @Test
    void nonFlatContainerPathsHaveNoCoordinates() {
        assertTrue(extractor.extractFromPath("index.json").isEmpty());
        assertTrue(extractor.extractFromPath("v3/registrations/my.pkg/index.json").isEmpty());
        assertTrue(extractor.extractFromPath(null).isEmpty());
        assertTrue(extractor.extractFromPath("").isEmpty());
    }
}
