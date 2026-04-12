package de.bsnsoft.megarepo.format.pypi;

import de.bsnsoft.megarepo.core.format.ComponentCoordinateExtractor;
import de.bsnsoft.megarepo.core.format.ComponentCoordinates;
import de.bsnsoft.megarepo.format.pypi.naming.PythonNameNormalizer;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts component coordinates from PyPI package paths.
 * Handles paths like "packages/requests-2.28.0.tar.gz" or
 * "packages/numpy-1.24.0-cp311-cp311-manylinux_2_17_x86_64.manylinux2014_x86_64.whl".
 */
@Component
public class PypiCoordinateExtractor implements ComponentCoordinateExtractor {

    /**
     * Pattern for sdist: {name}-{version}.tar.gz, .tar.bz2, .zip, .tar.xz
     */
    private static final Pattern SDIST_PATTERN =
            Pattern.compile("^(.+?)-([\\d][^-]*?)\\.(tar\\.gz|tar\\.bz2|tar\\.xz|zip)$");

    /**
     * Pattern for wheel: {name}-{version}(-{python}-{abi}-{platform}).whl
     */
    private static final Pattern WHEEL_PATTERN =
            Pattern.compile("^(.+?)-([\\d][^-]*?)(-[^-]+-[^-]+-[^-]+)?\\.whl$");

    /**
     * Fallback pattern for egg: {name}-{version}.egg
     */
    private static final Pattern EGG_PATTERN = Pattern.compile("^(.+?)-([\\d][^-]*?)\\.egg$");

    private final PythonNameNormalizer nameNormalizer;

    public PypiCoordinateExtractor(PythonNameNormalizer nameNormalizer) {
        this.nameNormalizer = nameNormalizer;
    }

    @Override
    public Optional<ComponentCoordinates> extractFromPath(String path) {
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }

        String normalized = path.startsWith("/") ? path.substring(1) : path;
        if (normalized.isEmpty()) {
            return Optional.empty();
        }

        // Extract the filename from the path (e.g., "packages/requests-2.28.0.tar.gz" -> "requests-2.28.0.tar.gz")
        String filename;
        int lastSlash = normalized.lastIndexOf('/');
        if (lastSlash >= 0) {
            filename = normalized.substring(lastSlash + 1);
        } else {
            filename = normalized;
        }

        if (filename.isEmpty()) {
            return Optional.empty();
        }

        return parseFilename(filename);
    }

    @Override
    public Optional<ComponentCoordinates> extractFromContent(
            InputStream content, String path, Map<String, String> attributes) {
        // If name and version are provided as attributes (e.g., from upload form), prefer those
        String name = attributes.get("name");
        String version = attributes.get("version");
        if (name != null && !name.isBlank() && version != null && !version.isBlank()) {
            return Optional.of(new ComponentCoordinates(
                    null, nameNormalizer.normalize(name), version, Map.of()));
        }
        return extractFromPath(path);
    }

    private Optional<ComponentCoordinates> parseFilename(String filename) {
        // Try wheel first
        Matcher wheelMatcher = WHEEL_PATTERN.matcher(filename);
        if (wheelMatcher.matches()) {
            String name = nameNormalizer.normalize(wheelMatcher.group(1));
            String version = wheelMatcher.group(2);
            return Optional.of(new ComponentCoordinates(null, name, version, Map.of()));
        }

        // Try sdist
        Matcher sdistMatcher = SDIST_PATTERN.matcher(filename);
        if (sdistMatcher.matches()) {
            String name = nameNormalizer.normalize(sdistMatcher.group(1));
            String version = sdistMatcher.group(2);
            return Optional.of(new ComponentCoordinates(null, name, version, Map.of()));
        }

        // Try egg
        Matcher eggMatcher = EGG_PATTERN.matcher(filename);
        if (eggMatcher.matches()) {
            String name = nameNormalizer.normalize(eggMatcher.group(1));
            String version = eggMatcher.group(2);
            return Optional.of(new ComponentCoordinates(null, name, version, Map.of()));
        }

        return Optional.empty();
    }
}
