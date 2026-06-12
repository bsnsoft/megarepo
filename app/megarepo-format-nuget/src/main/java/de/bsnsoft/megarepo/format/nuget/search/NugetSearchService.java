package de.bsnsoft.megarepo.format.nuget.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.bsnsoft.megarepo.core.format.FormatResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.ContentResponse;
import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import de.bsnsoft.megarepo.format.nuget.naming.NugetNames;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Simple {@code SearchQueryService} implementation over the repository's own
 * component data — enough for {@code dotnet package search} and the IDE
 * package browser against hosted repositories. Groups components by package
 * id, returns the latest version plus the full version list per package.
 */
@Component
public class NugetSearchService {

    private final ComponentJpaRepository componentRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public NugetSearchService(ComponentJpaRepository componentRepository) {
        this.componentRepository = componentRepository;
    }

    public FormatResponse search(RepositoryConfig repo, String query, int skip, int take, String baseUrl) {
        List<ComponentEntity> components = (query == null || query.isBlank())
                ? componentRepository.findByRepositoryId(repo.id(), Pageable.unpaged()).getContent()
                : componentRepository
                        .findByRepositoryIdAndFilter(repo.id(), query.trim(), Pageable.unpaged())
                        .getContent();

        // Group by package id (component name = lowercase id), versions ascending
        Map<String, List<ComponentEntity>> byId = new LinkedHashMap<>();
        components.stream()
                .sorted(Comparator.comparing(ComponentEntity::getName))
                .forEach(c -> byId.computeIfAbsent(c.getName(), k -> new java.util.ArrayList<>()).add(c));

        String repoBase = baseUrl + "/repository/" + repo.name();

        ObjectNode root = objectMapper.createObjectNode();
        root.put("totalHits", byId.size());
        ArrayNode data = objectMapper.createArrayNode();

        byId.entrySet().stream()
                .skip(Math.max(skip, 0))
                .limit(take > 0 ? take : 20)
                .forEach(entry -> data.add(searchResult(entry.getKey(), entry.getValue(), repoBase)));

        root.set("data", data);

        try {
            byte[] json = objectMapper.writeValueAsBytes(root);
            return new ContentResponse(
                    new ByteArrayInputStream(json), "application/json", json.length, Map.of(), Map.of());
        } catch (Exception e) {
            return new FormatResponse.ErrorResponse(500, "Failed to build search response: " + e.getMessage());
        }
    }

    private ObjectNode searchResult(String idLower, List<ComponentEntity> components, String repoBase) {
        components.sort(Comparator.comparing(ComponentEntity::getVersion, NugetNames.versionOrder()));
        ComponentEntity latest = components.getLast();
        Map<String, Object> attributes = latest.getAttributes();
        String originalId = attributes.get("originalId") instanceof String s ? s : idLower;
        String registrationUrl = repoBase + "/v3/registrations/" + idLower + "/index.json";

        ObjectNode result = objectMapper.createObjectNode();
        result.put("@id", registrationUrl);
        result.put("@type", "Package");
        result.put("registration", registrationUrl);
        result.put("id", originalId);
        result.put("version", latest.getVersion());
        if (attributes.get("description") instanceof String description) {
            result.put("description", description);
        }
        if (attributes.get("authors") instanceof String authors) {
            ArrayNode authorsNode = objectMapper.createArrayNode();
            for (String author : authors.split(",")) {
                if (!author.isBlank()) {
                    authorsNode.add(author.trim());
                }
            }
            result.set("authors", authorsNode);
        }
        result.put("totalDownloads", 0);
        result.put("verified", false);

        ArrayNode versions = objectMapper.createArrayNode();
        for (ComponentEntity component : components) {
            String versionLower = component.getVersion().toLowerCase(Locale.ROOT);
            ObjectNode versionNode = objectMapper.createObjectNode();
            versionNode.put("version", component.getVersion());
            versionNode.put("downloads", 0);
            versionNode.put("@id", repoBase + "/v3/registrations/" + idLower + "/" + versionLower + ".json");
            versions.add(versionNode);
        }
        result.set("versions", versions);
        return result;
    }
}
