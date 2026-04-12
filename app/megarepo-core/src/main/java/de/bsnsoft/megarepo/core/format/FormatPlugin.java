package de.bsnsoft.megarepo.core.format;

import de.bsnsoft.megarepo.core.repository.RepositoryType;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface FormatPlugin {

    String getFormat();

    String getDisplayName();

    Set<RepositoryType> getSupportedTypes();

    Optional<String> getDefaultRemoteUrl();

    FormatRequestHandler getRequestHandler();

    ComponentCoordinateExtractor getCoordinateExtractor();

    Optional<FormatSearchContributor> getSearchContributor();

    UploadDefinition getUploadDefinition();

    void validateRepositoryConfig(RepositoryType type, Map<String, Object> attributes);
}
