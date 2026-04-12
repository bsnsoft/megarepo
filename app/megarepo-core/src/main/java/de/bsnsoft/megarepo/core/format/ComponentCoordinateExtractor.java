package de.bsnsoft.megarepo.core.format;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

public interface ComponentCoordinateExtractor {

    Optional<ComponentCoordinates> extractFromPath(String path);

    Optional<ComponentCoordinates> extractFromContent(InputStream content, String path, Map<String, String> attributes);
}
