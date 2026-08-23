package de.bsnsoft.megarepo.format.npm;

import de.bsnsoft.megarepo.core.format.ComponentCoordinateExtractor;
import de.bsnsoft.megarepo.core.format.ComponentCoordinates;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class NpmCoordinateExtractor implements ComponentCoordinateExtractor {

    // Scoped tarball: @scope/package/-/package-1.0.0.tgz
    private static final Pattern SCOPED_TARBALL_PATTERN =
            Pattern.compile("^(@[^/]+)/([^/]+)/-/\\2-(.+)\\.tgz$");

    // Unscoped tarball in the registry layout used by npm itself and by proxied
    // upstreams: package/-/package-1.0.0.tgz
    private static final Pattern UNSCOPED_REGISTRY_TARBALL_PATTERN =
            Pattern.compile("^([^@/][^/]*)/-/\\1-(.+)\\.tgz$");

    // Unscoped tarball, short form: -/package-1.0.0.tgz
    // Version starts with a digit, so we match name greedily then "-" followed by digit-led version
    private static final Pattern UNSCOPED_TARBALL_PATTERN =
            Pattern.compile("^-/(.+)-(\\d.+)\\.tgz$");

    @Override
    public Optional<ComponentCoordinates> extractFromPath(String path) {
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }

        String normalized = path.startsWith("/") ? path.substring(1) : path;
        if (normalized.isEmpty()) {
            return Optional.empty();
        }

        // Try scoped tarball pattern: @scope/name/-/name-version.tgz
        Matcher scopedMatcher = SCOPED_TARBALL_PATTERN.matcher(normalized);
        if (scopedMatcher.matches()) {
            String scope = scopedMatcher.group(1);
            String name = scopedMatcher.group(2);
            String version = scopedMatcher.group(3);
            return Optional.of(new ComponentCoordinates(scope, name, version, Map.of()));
        }

        // Try unscoped registry layout: name/-/name-version.tgz
        // This is the form real npm registries use, so it is also the form that
        // proxied tarball downloads arrive in. Without it, proxied unscoped
        // packages get cached but never registered as components (GitHub #1).
        Matcher registryMatcher = UNSCOPED_REGISTRY_TARBALL_PATTERN.matcher(normalized);
        if (registryMatcher.matches()) {
            String name = registryMatcher.group(1);
            String version = registryMatcher.group(2);
            return Optional.of(new ComponentCoordinates(null, name, version, Map.of()));
        }

        // Try unscoped tarball pattern: -/name-version.tgz
        Matcher unscopedMatcher = UNSCOPED_TARBALL_PATTERN.matcher(normalized);
        if (unscopedMatcher.matches()) {
            String name = unscopedMatcher.group(1);
            String version = unscopedMatcher.group(2);
            return Optional.of(new ComponentCoordinates(null, name, version, Map.of()));
        }

        // Metadata paths (package name lookups) - no coordinates to extract
        return Optional.empty();
    }

    @Override
    public Optional<ComponentCoordinates> extractFromContent(
            InputStream content, String path, Map<String, String> attributes) {
        return extractFromPath(path);
    }
}
