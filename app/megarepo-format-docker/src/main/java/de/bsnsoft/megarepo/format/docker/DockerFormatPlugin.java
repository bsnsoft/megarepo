package de.bsnsoft.megarepo.format.docker;

import de.bsnsoft.megarepo.core.format.ComponentCoordinateExtractor;
import de.bsnsoft.megarepo.core.format.FormatPlugin;
import de.bsnsoft.megarepo.core.format.FormatRequestHandler;
import de.bsnsoft.megarepo.core.format.FormatSearchContributor;
import de.bsnsoft.megarepo.core.format.UploadDefinition;
import de.bsnsoft.megarepo.core.repository.RepositoryType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class DockerFormatPlugin implements FormatPlugin {

    private final DockerRequestHandler requestHandler;
    private final DockerCoordinateExtractor coordinateExtractor;

    public DockerFormatPlugin(DockerRequestHandler requestHandler, DockerCoordinateExtractor coordinateExtractor) {
        this.requestHandler = requestHandler;
        this.coordinateExtractor = coordinateExtractor;
    }

    @Override
    public String getFormat() {
        return "docker";
    }

    @Override
    public String getDisplayName() {
        return "Docker";
    }

    @Override
    public Set<RepositoryType> getSupportedTypes() {
        return Set.of(RepositoryType.HOSTED, RepositoryType.PROXY);
    }

    @Override
    public Optional<String> getDefaultRemoteUrl() {
        return Optional.of("https://registry-1.docker.io");
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
        return Optional.empty();
    }

    @Override
    public UploadDefinition getUploadDefinition() {
        // Docker images are pushed via the registry V2 API, not via file upload
        return new UploadDefinition("docker", false, List.of(), List.of());
    }

    @Override
    public void validateRepositoryConfig(RepositoryType type, Map<String, Object> attributes) {
        // No format-specific config validation needed for Docker
    }
}
