package de.bsnsoft.megarepo.format.maven;

import de.bsnsoft.megarepo.core.format.ComponentCoordinateExtractor;
import de.bsnsoft.megarepo.core.format.FormatPlugin;
import de.bsnsoft.megarepo.core.format.FormatRequestHandler;
import de.bsnsoft.megarepo.core.format.FormatSearchContributor;
import de.bsnsoft.megarepo.core.format.UploadDefinition;
import de.bsnsoft.megarepo.core.format.UploadDefinition.UploadFieldDefinition;
import de.bsnsoft.megarepo.core.repository.RepositoryType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class MavenFormatPlugin implements FormatPlugin {

    private final MavenRequestHandler requestHandler;
    private final MavenCoordinateExtractor coordinateExtractor;
    private final MavenSearchContributor searchContributor;

    public MavenFormatPlugin(
            MavenRequestHandler requestHandler,
            MavenCoordinateExtractor coordinateExtractor,
            MavenSearchContributor searchContributor) {
        this.requestHandler = requestHandler;
        this.coordinateExtractor = coordinateExtractor;
        this.searchContributor = searchContributor;
    }

    @Override
    public String getFormat() {
        return "maven2";
    }

    @Override
    public String getDisplayName() {
        return "Maven";
    }

    @Override
    public Set<RepositoryType> getSupportedTypes() {
        return Set.of(RepositoryType.HOSTED, RepositoryType.PROXY, RepositoryType.GROUP);
    }

    @Override
    public Optional<String> getDefaultRemoteUrl() {
        return Optional.of("https://repo1.maven.org/maven2/");
    }

    @Override
    public FormatRequestHandler getRequestHandler() {
        return requestHandler;
    }

    @Override
    public ComponentCoordinateExtractor getCoordinateExtractor() {
        return coordinateExtractor;
    }

    @Override
    public Optional<FormatSearchContributor> getSearchContributor() {
        return Optional.of(searchContributor);
    }

    @Override
    public UploadDefinition getUploadDefinition() {
        return new UploadDefinition(
                "maven2",
                true,
                List.of(
                        new UploadFieldDefinition("groupId", "string", "Group ID", false, "coordinates"),
                        new UploadFieldDefinition("artifactId", "string", "Artifact ID", false, "coordinates"),
                        new UploadFieldDefinition("version", "string", "Version", false, "coordinates")),
                List.of(
                        new UploadFieldDefinition("file", "file", "File to upload", false, "content"),
                        new UploadFieldDefinition("extension", "string", "Extension (e.g. jar, pom)", false, "content"),
                        new UploadFieldDefinition(
                                "classifier", "string", "Classifier (e.g. sources, javadoc)", true, "content")));
    }

    @Override
    @SuppressWarnings("unchecked")
    public void validateRepositoryConfig(RepositoryType type, Map<String, Object> attributes) {
        if (attributes == null) {
            return;
        }

        Object mavenConfig = attributes.get("maven");
        if (mavenConfig == null) {
            return;
        }

        if (!(mavenConfig instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("'maven' attribute must be a map");
        }

        Map<String, Object> maven = (Map<String, Object>) mavenConfig;

        Object versionPolicy = maven.get("versionPolicy");
        if (versionPolicy != null) {
            if (!(versionPolicy instanceof String policy)
                    || (!policy.equals("RELEASE") && !policy.equals("SNAPSHOT") && !policy.equals("MIXED"))) {
                throw new IllegalArgumentException(
                        "maven.versionPolicy must be one of: RELEASE, SNAPSHOT, MIXED");
            }
        }

        Object layoutPolicy = maven.get("layoutPolicy");
        if (layoutPolicy != null) {
            if (!(layoutPolicy instanceof String policy)
                    || (!policy.equals("STRICT") && !policy.equals("PERMISSIVE"))) {
                throw new IllegalArgumentException("maven.layoutPolicy must be one of: STRICT, PERMISSIVE");
            }
        }
    }
}
