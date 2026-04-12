package de.bsnsoft.megarepo.format.docker;

import de.bsnsoft.megarepo.core.format.ComponentCoordinateExtractor;
import de.bsnsoft.megarepo.core.format.ComponentCoordinates;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts Docker image coordinates from registry V2 API paths.
 *
 * <p>Docker images are identified by repository name + tag or digest.
 * Path format: v2/{name}/manifests/{reference} or v2/{name}/blobs/{digest}
 */
@Component
public class DockerCoordinateExtractor implements ComponentCoordinateExtractor {

    private static final Pattern MANIFEST_PATH =
            Pattern.compile("^v2/(.+)/manifests/(.+)$");

    @Override
    public Optional<ComponentCoordinates> extractFromPath(String path) {
        Matcher matcher = MANIFEST_PATH.matcher(path);
        if (!matcher.matches()) {
            return Optional.empty();
        }

        String imageName = matcher.group(1);
        String reference = matcher.group(2);

        // Namespace is the image name (may contain slashes, e.g. "library/nginx")
        // Name is the last segment of the image name
        // Version is the tag or digest
        String namespace = imageName.contains("/") ? imageName.substring(0, imageName.lastIndexOf('/')) : "";
        String name = imageName.contains("/") ? imageName.substring(imageName.lastIndexOf('/') + 1) : imageName;

        return Optional.of(new ComponentCoordinates(namespace, name, reference, Map.of("imageName", imageName)));
    }

    @Override
    public Optional<ComponentCoordinates> extractFromContent(
            InputStream content, String path, Map<String, String> attributes) {
        return extractFromPath(path);
    }
}
