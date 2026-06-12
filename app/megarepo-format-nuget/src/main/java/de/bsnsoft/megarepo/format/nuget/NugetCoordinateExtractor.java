package de.bsnsoft.megarepo.format.nuget;

import de.bsnsoft.megarepo.core.format.ComponentCoordinateExtractor;
import de.bsnsoft.megarepo.core.format.ComponentCoordinates;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts package coordinates from flat-container paths:
 * {@code v3-flatcontainer/{id-lower}/{version-lower}/{file}}.
 * Namespace is always {@code null} — NuGet has no scoping concept.
 */
@Component
public class NugetCoordinateExtractor implements ComponentCoordinateExtractor {

    private static final Pattern FLAT_CONTAINER_PATTERN =
            Pattern.compile("^v3-flatcontainer/([^/]+)/([^/]+)/[^/]+$");

    @Override
    public Optional<ComponentCoordinates> extractFromPath(String path) {
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        String normalized = path.startsWith("/") ? path.substring(1) : path;

        Matcher matcher = FLAT_CONTAINER_PATTERN.matcher(normalized);
        if (matcher.matches() && !"index.json".equals(matcher.group(2))) {
            return Optional.of(new ComponentCoordinates(null, matcher.group(1), matcher.group(2), Map.of()));
        }
        return Optional.empty();
    }

    @Override
    public Optional<ComponentCoordinates> extractFromContent(
            InputStream content, String path, Map<String, String> attributes) {
        return extractFromPath(path);
    }
}
