package de.bsnsoft.megarepo.format.maven.policy;

import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class LayoutValidator {

    public void validatePath(RepositoryConfig repo, String path) {
        String layoutPolicy = getLayoutPolicy(repo);

        if ("PERMISSIVE".equals(layoutPolicy)) {
            return;
        }

        // STRICT validation: path must match Maven layout convention
        // Minimum: {groupId}/{artifactId}/{version}/{filename}
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        String[] segments = normalized.split("/");

        if (segments.length < 4) {
            throw new ValidationException(
                    "Path '" + path + "' does not match Maven repository layout (STRICT mode requires"
                            + " at least groupId/artifactId/version/filename)");
        }

        String version = segments[segments.length - 2];
        String artifactId = segments[segments.length - 3];
        String filename = segments[segments.length - 1];

        // Filename should start with artifactId-version or artifactId-version-
        String expectedPrefix = artifactId + "-" + version;
        // For snapshot timestamped versions, just check artifactId prefix
        if (!filename.startsWith(artifactId + "-")) {
            throw new ValidationException(
                    "Filename '" + filename + "' does not match expected pattern '" + expectedPrefix
                            + "[-classifier].extension' (STRICT mode)");
        }
    }

    @SuppressWarnings("unchecked")
    private String getLayoutPolicy(RepositoryConfig repo) {
        Map<String, Object> attributes = repo.attributes();
        if (attributes == null) {
            return "STRICT";
        }

        Object mavenConfig = attributes.get("maven");
        if (mavenConfig instanceof Map<?, ?> mavenMap) {
            Object layoutPolicy = mavenMap.get("layoutPolicy");
            if (layoutPolicy instanceof String policy) {
                return policy;
            }
        }

        return "STRICT";
    }
}
