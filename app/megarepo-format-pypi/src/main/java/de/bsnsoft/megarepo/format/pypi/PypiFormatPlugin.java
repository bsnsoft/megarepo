package de.bsnsoft.megarepo.format.pypi;

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
public class PypiFormatPlugin implements FormatPlugin {

    private final PypiRequestHandler requestHandler;
    private final PypiCoordinateExtractor coordinateExtractor;

    public PypiFormatPlugin(PypiRequestHandler requestHandler, PypiCoordinateExtractor coordinateExtractor) {
        this.requestHandler = requestHandler;
        this.coordinateExtractor = coordinateExtractor;
    }

    @Override
    public String getFormat() {
        return "pypi";
    }

    @Override
    public String getDisplayName() {
        return "PyPI";
    }

    @Override
    public Set<RepositoryType> getSupportedTypes() {
        return Set.of(RepositoryType.HOSTED, RepositoryType.PROXY, RepositoryType.GROUP);
    }

    @Override
    public Optional<String> getDefaultRemoteUrl() {
        return Optional.of("https://pypi.org/");
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
        return new UploadDefinition(
                "pypi",
                false,
                List.of(
                        new UploadFieldDefinition("name", "text", "Package name", false, "component"),
                        new UploadFieldDefinition("version", "text", "Package version", false, "component")),
                List.of(new UploadFieldDefinition("content", "file", "Distribution file", false, "content")));
    }

    @Override
    public void validateRepositoryConfig(RepositoryType type, Map<String, Object> attributes) {
        // PyPI format has no format-specific configuration to validate
    }
}
