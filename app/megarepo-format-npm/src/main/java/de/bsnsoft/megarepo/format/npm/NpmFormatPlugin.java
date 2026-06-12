package de.bsnsoft.megarepo.format.npm;

import de.bsnsoft.megarepo.core.format.ComponentCoordinateExtractor;
import de.bsnsoft.megarepo.core.format.ComponentUploadHandler;
import de.bsnsoft.megarepo.core.format.FormatPlugin;
import de.bsnsoft.megarepo.core.format.FormatRequestHandler;
import de.bsnsoft.megarepo.core.format.FormatSearchContributor;
import de.bsnsoft.megarepo.core.format.UploadDefinition;
import de.bsnsoft.megarepo.core.format.UploadDefinition.UploadFieldDefinition;
import de.bsnsoft.megarepo.core.repository.RepositoryType;
import de.bsnsoft.megarepo.format.npm.upload.NpmUploadHandler;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class NpmFormatPlugin implements FormatPlugin {

    private final NpmRequestHandler requestHandler;
    private final NpmCoordinateExtractor coordinateExtractor;
    private final NpmUploadHandler uploadHandler;

    public NpmFormatPlugin(
            NpmRequestHandler requestHandler,
            NpmCoordinateExtractor coordinateExtractor,
            NpmUploadHandler uploadHandler) {
        this.requestHandler = requestHandler;
        this.coordinateExtractor = coordinateExtractor;
        this.uploadHandler = uploadHandler;
    }

    @Override
    public String getFormat() {
        return "npm";
    }

    @Override
    public String getDisplayName() {
        return "npm";
    }

    @Override
    public Set<RepositoryType> getSupportedTypes() {
        return Set.of(RepositoryType.HOSTED, RepositoryType.PROXY, RepositoryType.GROUP);
    }

    @Override
    public Optional<String> getDefaultRemoteUrl() {
        return Optional.of("https://registry.npmjs.org/");
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
    public Optional<ComponentUploadHandler> getComponentUploadHandler() {
        return Optional.of(uploadHandler);
    }

    @Override
    public UploadDefinition getUploadDefinition() {
        return new UploadDefinition(
                "npm",
                false,
                List.of(),
                List.of(new UploadFieldDefinition("file", "file", "npm package tarball (.tgz)", false, "content")));
    }

    @Override
    public void validateRepositoryConfig(RepositoryType type, Map<String, Object> attributes) {
        // npm format has no format-specific configuration to validate
    }
}
