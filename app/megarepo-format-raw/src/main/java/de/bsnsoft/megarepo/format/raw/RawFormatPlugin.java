package de.bsnsoft.megarepo.format.raw;

import de.bsnsoft.megarepo.core.format.ComponentCoordinateExtractor;
import de.bsnsoft.megarepo.core.format.ComponentUploadHandler;
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
public class RawFormatPlugin implements FormatPlugin {

    private final RawRequestHandler requestHandler;
    private final RawCoordinateExtractor coordinateExtractor;
    private final RawUploadHandler uploadHandler;

    public RawFormatPlugin(
            RawRequestHandler requestHandler,
            RawCoordinateExtractor coordinateExtractor,
            RawUploadHandler uploadHandler) {
        this.requestHandler = requestHandler;
        this.coordinateExtractor = coordinateExtractor;
        this.uploadHandler = uploadHandler;
    }

    @Override
    public String getFormat() {
        return "raw";
    }

    @Override
    public String getDisplayName() {
        return "Raw";
    }

    @Override
    public Set<RepositoryType> getSupportedTypes() {
        return Set.of(RepositoryType.HOSTED, RepositoryType.PROXY, RepositoryType.GROUP);
    }

    @Override
    public Optional<String> getDefaultRemoteUrl() {
        return Optional.empty();
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
                "raw",
                false,
                List.of(),
                List.of(new UploadFieldDefinition("file", "file", "File to upload", false, "content")));
    }

    @Override
    public void validateRepositoryConfig(RepositoryType type, Map<String, Object> attributes) {
        // Raw format has no format-specific configuration to validate
    }
}
