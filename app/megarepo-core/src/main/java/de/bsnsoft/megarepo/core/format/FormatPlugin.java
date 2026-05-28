package de.bsnsoft.megarepo.core.format;

import de.bsnsoft.megarepo.core.repository.RepositoryType;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface FormatPlugin {

    String getFormat();

    /**
     * Alternative format strings the registry should accept for this plugin.
     * Defaults to none. Used to forgive historical or Nexus-aligned naming
     * (e.g. the Maven plugin's canonical key is {@code "maven2"} but users
     * coming from other repository managers, hand-edited configs, or
     * pre-fix DB seeds frequently write {@code "maven"}). Aliases are
     * indexed in {@link FormatRegistry} so that a request for any of them
     * resolves to the same plugin instance — never returns a different
     * implementation, so there is no risk of plugin shadowing.
     */
    default Set<String> getAliases() {
        return Set.of();
    }

    String getDisplayName();

    Set<RepositoryType> getSupportedTypes();

    Optional<String> getDefaultRemoteUrl();

    FormatRequestHandler getRequestHandler();

    ComponentCoordinateExtractor getCoordinateExtractor();

    Optional<FormatSearchContributor> getSearchContributor();

    UploadDefinition getUploadDefinition();

    void validateRepositoryConfig(RepositoryType type, Map<String, Object> attributes);
}
