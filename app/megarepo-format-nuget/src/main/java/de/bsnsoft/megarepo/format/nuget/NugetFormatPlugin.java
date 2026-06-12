package de.bsnsoft.megarepo.format.nuget;

import de.bsnsoft.megarepo.core.format.ComponentCoordinateExtractor;
import de.bsnsoft.megarepo.core.format.ComponentUploadHandler;
import de.bsnsoft.megarepo.core.format.FormatPlugin;
import de.bsnsoft.megarepo.core.format.FormatRequestHandler;
import de.bsnsoft.megarepo.core.format.FormatSearchContributor;
import de.bsnsoft.megarepo.core.format.UploadDefinition;
import de.bsnsoft.megarepo.core.format.UploadDefinition.UploadFieldDefinition;
import de.bsnsoft.megarepo.core.repository.RepositoryType;
import de.bsnsoft.megarepo.format.nuget.upload.NugetUploadHandler;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class NugetFormatPlugin implements FormatPlugin {

    private final NugetRequestHandler requestHandler;
    private final NugetCoordinateExtractor coordinateExtractor;
    private final NugetUploadHandler uploadHandler;

    public NugetFormatPlugin(
            NugetRequestHandler requestHandler,
            NugetCoordinateExtractor coordinateExtractor,
            NugetUploadHandler uploadHandler) {
        this.requestHandler = requestHandler;
        this.coordinateExtractor = coordinateExtractor;
        this.uploadHandler = uploadHandler;
    }

    @Override
    public String getFormat() {
        return "nuget";
    }

    @Override
    public String getDisplayName() {
        return "NuGet";
    }

    @Override
    public Set<RepositoryType> getSupportedTypes() {
        return Set.of(RepositoryType.HOSTED, RepositoryType.PROXY, RepositoryType.GROUP);
    }

    @Override
    public Optional<String> getDefaultRemoteUrl() {
        return Optional.of("https://api.nuget.org/v3/index.json");
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
                "nuget",
                false,
                List.of(),
                List.of(new UploadFieldDefinition("file", "file", "NuGet package (.nupkg)", false, "content")));
    }

    @Override
    public void validateRepositoryConfig(RepositoryType type, Map<String, Object> attributes) {
        // nuget format has no format-specific configuration to validate
    }
}
