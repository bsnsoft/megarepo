package de.bsnsoft.megarepo.format.raw;

import de.bsnsoft.megarepo.core.format.ComponentCoordinates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RawCoordinateExtractorTest {

    private RawCoordinateExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new RawCoordinateExtractor();
    }

    @Test
    void extractFromPath_fileInSubdirectory() {
        Optional<ComponentCoordinates> result = extractor.extractFromPath("docs/readme.txt");

        assertTrue(result.isPresent());
        ComponentCoordinates coords = result.get();
        assertEquals("docs", coords.namespace());
        assertEquals("readme.txt", coords.name());
        assertEquals("1", coords.version());
        assertNotNull(coords.formatAttributes());
        assertTrue(coords.formatAttributes().isEmpty());
    }

    @Test
    void extractFromPath_fileInRoot() {
        Optional<ComponentCoordinates> result = extractor.extractFromPath("file.bin");

        assertTrue(result.isPresent());
        ComponentCoordinates coords = result.get();
        assertNull(coords.namespace());
        assertEquals("file.bin", coords.name());
        assertEquals("1", coords.version());
    }

    @Test
    void extractFromPath_deeplyNestedFile() {
        Optional<ComponentCoordinates> result = extractor.extractFromPath("a/b/c/d/artifact.jar");

        assertTrue(result.isPresent());
        ComponentCoordinates coords = result.get();
        assertEquals("a/b/c/d", coords.namespace());
        assertEquals("artifact.jar", coords.name());
        assertEquals("1", coords.version());
    }

    @Test
    void extractFromPath_leadingSlashIsStripped() {
        Optional<ComponentCoordinates> result = extractor.extractFromPath("/some/path/file.txt");

        assertTrue(result.isPresent());
        ComponentCoordinates coords = result.get();
        assertEquals("some/path", coords.namespace());
        assertEquals("file.txt", coords.name());
    }

    @Test
    void extractFromPath_nullPath() {
        Optional<ComponentCoordinates> result = extractor.extractFromPath(null);
        assertTrue(result.isEmpty());
    }

    @Test
    void extractFromPath_blankPath() {
        Optional<ComponentCoordinates> result = extractor.extractFromPath("   ");
        assertTrue(result.isEmpty());
    }

    @Test
    void extractFromPath_emptyPath() {
        Optional<ComponentCoordinates> result = extractor.extractFromPath("");
        assertTrue(result.isEmpty());
    }

    @Test
    void extractFromPath_slashOnly() {
        Optional<ComponentCoordinates> result = extractor.extractFromPath("/");
        assertTrue(result.isEmpty());
    }

    @Test
    void extractFromContent_delegatesToExtractFromPath() {
        Optional<ComponentCoordinates> result =
                extractor.extractFromContent(InputStream.nullInputStream(), "folder/test.dat", Map.of());

        assertTrue(result.isPresent());
        ComponentCoordinates coords = result.get();
        assertEquals("folder", coords.namespace());
        assertEquals("test.dat", coords.name());
        assertEquals("1", coords.version());
    }
}
