package de.bsnsoft.megarepo.format.pypi;

import de.bsnsoft.megarepo.core.format.ComponentCoordinates;
import de.bsnsoft.megarepo.format.pypi.naming.PythonNameNormalizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PypiCoordinateExtractorTest {

    private PypiCoordinateExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new PypiCoordinateExtractor(new PythonNameNormalizer());
    }

    @Test
    void extractFromPath_tarGz() {
        Optional<ComponentCoordinates> result = extractor.extractFromPath("packages/requests-2.28.0.tar.gz");

        assertTrue(result.isPresent());
        ComponentCoordinates coords = result.get();
        assertNull(coords.namespace());
        assertEquals("requests", coords.name());
        assertEquals("2.28.0", coords.version());
    }

    @Test
    void extractFromPath_wheel() {
        Optional<ComponentCoordinates> result =
                extractor.extractFromPath("packages/numpy-1.24.0-cp311-cp311-manylinux_2_17_x86_64.whl");

        assertTrue(result.isPresent());
        ComponentCoordinates coords = result.get();
        assertNull(coords.namespace());
        assertEquals("numpy", coords.name());
        assertEquals("1.24.0", coords.version());
    }

    @Test
    void extractFromPath_wheelSimple() {
        Optional<ComponentCoordinates> result =
                extractor.extractFromPath("packages/flask-2.3.2-py3-none-any.whl");

        assertTrue(result.isPresent());
        ComponentCoordinates coords = result.get();
        assertEquals("flask", coords.name());
        assertEquals("2.3.2", coords.version());
    }

    @Test
    void extractFromPath_zip() {
        Optional<ComponentCoordinates> result = extractor.extractFromPath("packages/my_package-1.0.0.zip");

        assertTrue(result.isPresent());
        ComponentCoordinates coords = result.get();
        assertEquals("my-package", coords.name());
        assertEquals("1.0.0", coords.version());
    }

    @Test
    void extractFromPath_egg() {
        Optional<ComponentCoordinates> result = extractor.extractFromPath("packages/old_lib-0.9.egg");

        assertTrue(result.isPresent());
        ComponentCoordinates coords = result.get();
        assertEquals("old-lib", coords.name());
        assertEquals("0.9", coords.version());
    }

    @Test
    void extractFromPath_normalizesName() {
        Optional<ComponentCoordinates> result =
                extractor.extractFromPath("packages/My_Package.Name-1.0.0.tar.gz");

        assertTrue(result.isPresent());
        assertEquals("my-package-name", result.get().name());
    }

    @Test
    void extractFromPath_nullPath() {
        assertTrue(extractor.extractFromPath(null).isEmpty());
    }

    @Test
    void extractFromPath_blankPath() {
        assertTrue(extractor.extractFromPath("  ").isEmpty());
    }

    @Test
    void extractFromPath_emptyPath() {
        assertTrue(extractor.extractFromPath("").isEmpty());
    }

    @Test
    void extractFromPath_unrecognizedExtension() {
        assertTrue(extractor.extractFromPath("packages/something.txt").isEmpty());
    }

    @Test
    void extractFromPath_leadingSlash() {
        Optional<ComponentCoordinates> result = extractor.extractFromPath("/packages/requests-2.28.0.tar.gz");

        assertTrue(result.isPresent());
        assertEquals("requests", result.get().name());
        assertEquals("2.28.0", result.get().version());
    }

    @Test
    void extractFromContent_usesAttributesWhenPresent() {
        Map<String, String> attrs = Map.of("name", "My_Package", "version", "1.0.0");
        Optional<ComponentCoordinates> result =
                extractor.extractFromContent(InputStream.nullInputStream(), "packages/something.tar.gz", attrs);

        assertTrue(result.isPresent());
        assertEquals("my-package", result.get().name());
        assertEquals("1.0.0", result.get().version());
    }

    @Test
    void extractFromContent_fallsBackToPathWhenNoAttributes() {
        Optional<ComponentCoordinates> result = extractor.extractFromContent(
                InputStream.nullInputStream(), "packages/requests-2.28.0.tar.gz", Map.of());

        assertTrue(result.isPresent());
        assertEquals("requests", result.get().name());
        assertEquals("2.28.0", result.get().version());
    }
}
