package de.bsnsoft.megarepo.format.nuget.index;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.bsnsoft.megarepo.core.format.FormatResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.ContentResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.NotFoundResponse;
import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import de.bsnsoft.megarepo.format.nuget.naming.NugetNames;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Generates the flat-container version index
 * ({@code v3-flatcontainer/{id-lower}/index.json}) from the hosted
 * repository's own component data. Versions are emitted lowercase and
 * ascending — the shape the dotnet client expects.
 */
@Component
public class FlatContainerGenerator {

    private final ComponentJpaRepository componentRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public FlatContainerGenerator(ComponentJpaRepository componentRepository) {
        this.componentRepository = componentRepository;
    }

    public FormatResponse versionsIndex(RepositoryConfig repo, String idLower) {
        List<ComponentEntity> components =
                componentRepository.findByRepositoryIdAndNamespaceAndName(repo.id(), null, idLower);
        if (components.isEmpty()) {
            return new NotFoundResponse("Package not found: " + idLower);
        }

        List<String> versions = components.stream()
                .map(ComponentEntity::getVersion)
                .map(v -> v.toLowerCase(Locale.ROOT))
                .distinct()
                .sorted(NugetNames.versionOrder())
                .toList();

        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode versionsNode = objectMapper.createArrayNode();
        versions.forEach(versionsNode::add);
        root.set("versions", versionsNode);

        try {
            byte[] json = objectMapper.writeValueAsBytes(root);
            return new ContentResponse(
                    new ByteArrayInputStream(json), "application/json", json.length, Map.of(), Map.of());
        } catch (Exception e) {
            return new FormatResponse.ErrorResponse(500, "Failed to build version index: " + e.getMessage());
        }
    }
}
