package de.bsnsoft.megarepo.format.docker;

import de.bsnsoft.megarepo.core.format.ComponentCoordinates;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerCoordinateExtractorTest {

    private final DockerCoordinateExtractor extractor = new DockerCoordinateExtractor();

    @Test
    void extractsSimpleImageTag() {
        Optional<ComponentCoordinates> coords = extractor.extractFromPath("v2/nginx/manifests/latest");
        assertTrue(coords.isPresent());
        assertEquals("", coords.get().namespace());
        assertEquals("nginx", coords.get().name());
        assertEquals("latest", coords.get().version());
    }

    @Test
    void extractsNamespacedImageTag() {
        Optional<ComponentCoordinates> coords = extractor.extractFromPath("v2/library/nginx/manifests/1.25");
        assertTrue(coords.isPresent());
        assertEquals("library", coords.get().namespace());
        assertEquals("nginx", coords.get().name());
        assertEquals("1.25", coords.get().version());
    }

    @Test
    void extractsDeepNamespacedImage() {
        Optional<ComponentCoordinates> coords = extractor.extractFromPath("v2/my-org/sub/app/manifests/v2.0");
        assertTrue(coords.isPresent());
        assertEquals("my-org/sub", coords.get().namespace());
        assertEquals("app", coords.get().name());
        assertEquals("v2.0", coords.get().version());
    }

    @Test
    void extractsDigestReference() {
        Optional<ComponentCoordinates> coords = extractor.extractFromPath(
                "v2/nginx/manifests/sha256:abc123");
        assertTrue(coords.isPresent());
        assertEquals("sha256:abc123", coords.get().version());
    }

    @Test
    void returnsEmptyForBlobPath() {
        Optional<ComponentCoordinates> coords = extractor.extractFromPath(
                "v2/nginx/blobs/sha256:abc123");
        assertTrue(coords.isEmpty());
    }

    @Test
    void returnsEmptyForNonV2Path() {
        Optional<ComponentCoordinates> coords = extractor.extractFromPath("some/other/path");
        assertTrue(coords.isEmpty());
    }
}
