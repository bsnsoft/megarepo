package de.bsnsoft.megarepo.format.raw;

import de.bsnsoft.megarepo.core.format.ComponentCoordinateExtractor;
import de.bsnsoft.megarepo.core.format.ComponentCoordinates;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

@Component
public class RawCoordinateExtractor implements ComponentCoordinateExtractor {

    private static final String DEFAULT_VERSION = "1";

    @Override
    public Optional<ComponentCoordinates> extractFromPath(String path) {
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }

        var normalized = path.startsWith("/") ? path.substring(1) : path;
        if (normalized.isEmpty()) {
            return Optional.empty();
        }

        int lastSlash = normalized.lastIndexOf('/');
        String namespace;
        String name;

        if (lastSlash >= 0) {
            namespace = normalized.substring(0, lastSlash);
            name = normalized.substring(lastSlash + 1);
        } else {
            namespace = null;
            name = normalized;
        }

        if (name.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new ComponentCoordinates(namespace, name, DEFAULT_VERSION, Map.of()));
    }

    @Override
    public Optional<ComponentCoordinates> extractFromContent(
            InputStream content, String path, Map<String, String> attributes) {
        return extractFromPath(path);
    }
}
