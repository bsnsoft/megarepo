package de.bsnsoft.megarepo.format.npm;

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

class NpmCoordinateExtractorTest {

    private NpmCoordinateExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new NpmCoordinateExtractor();
    }

    @Test
    void extractFromPath_scopedTarball() {
        Optional<ComponentCoordinates> result = extractor.extractFromPath("@scope/package/-/package-1.0.0.tgz");

        assertTrue(result.isPresent());
        ComponentCoordinates coords = result.get();
        assertEquals("@scope", coords.namespace());
        assertEquals("package", coords.name());
        assertEquals("1.0.0", coords.version());
        assertNotNull(coords.formatAttributes());
        assertTrue(coords.formatAttributes().isEmpty());
    }

    @Test
    void extractFromPath_unscopedTarball() {
        Optional<ComponentCoordinates> result = extractor.extractFromPath("-/lodash-4.17.21.tgz");

        assertTrue(result.isPresent());
        ComponentCoordinates coords = result.get();
        assertNull(coords.namespace());
        assertEquals("lodash", coords.name());
        assertEquals("4.17.21", coords.version());
    }

    @Test
    void extractFromPath_metadataPath_returnsEmpty() {
        Optional<ComponentCoordinates> result = extractor.extractFromPath("lodash");
        assertTrue(result.isEmpty());
    }

    @Test
    void extractFromPath_scopedMetadataPath_returnsEmpty() {
        Optional<ComponentCoordinates> result = extractor.extractFromPath("@angular/core");
        assertTrue(result.isEmpty());
    }

    @Test
    void extractFromPath_nullPath_returnsEmpty() {
        Optional<ComponentCoordinates> result = extractor.extractFromPath(null);
        assertTrue(result.isEmpty());
    }

    @Test
    void extractFromPath_blankPath_returnsEmpty() {
        Optional<ComponentCoordinates> result = extractor.extractFromPath("   ");
        assertTrue(result.isEmpty());
    }

    @Test
    void extractFromPath_emptyPath_returnsEmpty() {
        Optional<ComponentCoordinates> result = extractor.extractFromPath("");
        assertTrue(result.isEmpty());
    }

    @Test
    void extractFromPath_leadingSlashStripped() {
        Optional<ComponentCoordinates> result = extractor.extractFromPath("/-/express-4.18.2.tgz");

        assertTrue(result.isPresent());
        ComponentCoordinates coords = result.get();
        assertNull(coords.namespace());
        assertEquals("express", coords.name());
        assertEquals("4.18.2", coords.version());
    }

    @Test
    void extractFromPath_scopedTarballWithLeadingSlash() {
        Optional<ComponentCoordinates> result =
                extractor.extractFromPath("/@babel/core/-/core-7.23.0.tgz");

        assertTrue(result.isPresent());
        ComponentCoordinates coords = result.get();
        assertEquals("@babel", coords.namespace());
        assertEquals("core", coords.name());
        assertEquals("7.23.0", coords.version());
    }

    @Test
    void extractFromPath_versionWithPrerelease() {
        Optional<ComponentCoordinates> result = extractor.extractFromPath("-/react-18.0.0-beta.1.tgz");

        assertTrue(result.isPresent());
        ComponentCoordinates coords = result.get();
        assertNull(coords.namespace());
        assertEquals("react", coords.name());
        assertEquals("18.0.0-beta.1", coords.version());
    }

    @Test
    void extractFromContent_delegatesToExtractFromPath() {
        Optional<ComponentCoordinates> result =
                extractor.extractFromContent(InputStream.nullInputStream(), "-/lodash-4.17.21.tgz", Map.of());

        assertTrue(result.isPresent());
        ComponentCoordinates coords = result.get();
        assertNull(coords.namespace());
        assertEquals("lodash", coords.name());
        assertEquals("4.17.21", coords.version());
    }
}
