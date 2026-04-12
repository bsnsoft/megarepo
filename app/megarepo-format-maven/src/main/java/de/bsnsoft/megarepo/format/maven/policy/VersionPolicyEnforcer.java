package de.bsnsoft.megarepo.format.maven.policy;

import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class VersionPolicyEnforcer {

    public void enforceVersionPolicy(RepositoryConfig repo, String version) {
        String policy = getVersionPolicy(repo);

        switch (policy) {
            case "RELEASE" -> {
                if (isSnapshotVersion(version)) {
                    throw new ValidationException(
                            "Repository '" + repo.name() + "' does not allow snapshot versions (policy=RELEASE)");
                }
            }
            case "SNAPSHOT" -> {
                if (!isSnapshotVersion(version)) {
                    throw new ValidationException(
                            "Repository '" + repo.name() + "' only allows snapshot versions (policy=SNAPSHOT)");
                }
            }
            case "MIXED" -> {
                // Both allowed
            }
            default -> {
                // Unknown policy, treat as MIXED
            }
        }
    }

    public boolean isSnapshotVersion(String version) {
        return version != null && version.endsWith("-SNAPSHOT");
    }

    @SuppressWarnings("unchecked")
    private String getVersionPolicy(RepositoryConfig repo) {
        Map<String, Object> attributes = repo.attributes();
        if (attributes == null) {
            return "MIXED";
        }

        Object mavenConfig = attributes.get("maven");
        if (mavenConfig instanceof Map<?, ?> mavenMap) {
            Object versionPolicy = mavenMap.get("versionPolicy");
            if (versionPolicy instanceof String policy) {
                return policy;
            }
        }

        return "MIXED";
    }
}
