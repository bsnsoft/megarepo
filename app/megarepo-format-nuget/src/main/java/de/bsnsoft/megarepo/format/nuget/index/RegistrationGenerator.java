package de.bsnsoft.megarepo.format.nuget.index;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Generates NuGet V3 registration blobs (package metadata JSON) for hosted
 * repositories. The index is a single inlined page — fine for hosted repos,
 * which rarely hold thousands of versions per package.
 */
@Component
public class RegistrationGenerator {

    private static final Logger log = LoggerFactory.getLogger(RegistrationGenerator.class);

    private final ComponentJpaRepository componentRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RegistrationGenerator(ComponentJpaRepository componentRepository) {
        this.componentRepository = componentRepository;
    }

    /** Registration index: {@code v3/registrations/{id-lower}/index.json}. */
    public FormatResponse registrationIndex(RepositoryConfig repo, String idLower, String baseUrl) {
        List<ComponentEntity> components = sortedComponents(repo, idLower);
        if (components.isEmpty()) {
            return new NotFoundResponse("Package not found: " + idLower);
        }

        String repoBase = baseUrl + "/repository/" + repo.name();
        String indexUrl = repoBase + "/v3/registrations/" + idLower + "/index.json";

        ObjectNode root = objectMapper.createObjectNode();
        root.put("@id", indexUrl);
        root.put("count", 1);

        ObjectNode page = objectMapper.createObjectNode();
        page.put("@id", indexUrl);
        page.put("count", components.size());
        page.put("lower", components.getFirst().getVersion().toLowerCase(Locale.ROOT));
        page.put("upper", components.getLast().getVersion().toLowerCase(Locale.ROOT));
        page.put("parent", indexUrl);

        ArrayNode items = objectMapper.createArrayNode();
        for (ComponentEntity component : components) {
            items.add(leafNode(repo, idLower, component, repoBase));
        }
        page.set("items", items);

        ArrayNode pages = objectMapper.createArrayNode();
        pages.add(page);
        root.set("items", pages);

        return toJsonResponse(root);
    }

    /** Registration leaf: {@code v3/registrations/{id-lower}/{version-lower}.json}. */
    public FormatResponse registrationLeaf(
            RepositoryConfig repo, String idLower, String versionLower, String baseUrl) {
        List<ComponentEntity> components = sortedComponents(repo, idLower);
        String repoBase = baseUrl + "/repository/" + repo.name();
        for (ComponentEntity component : components) {
            if (component.getVersion().toLowerCase(Locale.ROOT).equals(versionLower)) {
                return toJsonResponse(leafNode(repo, idLower, component, repoBase));
            }
        }
        return new NotFoundResponse("Package version not found: " + idLower + " " + versionLower);
    }

    private List<ComponentEntity> sortedComponents(RepositoryConfig repo, String idLower) {
        return componentRepository.findByRepositoryIdAndNamespaceAndName(repo.id(), null, idLower).stream()
                .sorted(Comparator.comparing(ComponentEntity::getVersion, NugetNames.versionOrder()))
                .toList();
    }

    private ObjectNode leafNode(
            RepositoryConfig repo, String idLower, ComponentEntity component, String repoBase) {
        String versionLower = component.getVersion().toLowerCase(Locale.ROOT);
        String leafUrl = repoBase + "/v3/registrations/" + idLower + "/" + versionLower + ".json";
        String contentUrl = repoBase + "/v3-flatcontainer/" + idLower + "/" + versionLower
                + "/" + idLower + "." + versionLower + ".nupkg";

        Map<String, Object> attributes = component.getAttributes();
        String originalId = attributes.get("originalId") instanceof String s ? s : idLower;

        ObjectNode catalogEntry = objectMapper.createObjectNode();
        catalogEntry.put("@id", leafUrl);
        catalogEntry.put("@type", "PackageDetails");
        catalogEntry.put("id", originalId);
        catalogEntry.put("version", component.getVersion());
        catalogEntry.put("packageContent", contentUrl);
        catalogEntry.put("listed", true);
        if (attributes.get("description") instanceof String description) {
            catalogEntry.put("description", description);
        }
        if (attributes.get("authors") instanceof String authors) {
            catalogEntry.put("authors", authors);
        }
        if (component.getCreatedAt() != null) {
            catalogEntry.put("published", component.getCreatedAt().toString());
        }
        catalogEntry.set("dependencyGroups", dependencyGroups(attributes, repoBase, idLower, versionLower));

        ObjectNode leaf = objectMapper.createObjectNode();
        leaf.put("@id", leafUrl);
        leaf.put("@type", "Package");
        leaf.set("catalogEntry", catalogEntry);
        leaf.put("packageContent", contentUrl);
        leaf.put("registration", repoBase + "/v3/registrations/" + idLower + "/index.json");
        return leaf;
    }

    /**
     * Rebuilds the dependencyGroups structure from the dependency list stored
     * at push time ({@code dependencies} component attribute, JSON array of
     * {id, range, targetFramework}).
     */
    private ArrayNode dependencyGroups(
            Map<String, Object> attributes, String repoBase, String idLower, String versionLower) {
        ArrayNode groups = objectMapper.createArrayNode();
        Object raw = attributes.get("dependencies");
        if (!(raw instanceof String json) || json.isBlank()) {
            return groups;
        }
        try {
            JsonNode list = objectMapper.readTree(json);
            if (!list.isArray()) {
                return groups;
            }
            // Group by targetFramework, preserving insertion order
            Map<String, ArrayNode> byFramework = new java.util.LinkedHashMap<>();
            for (JsonNode dep : list) {
                String tfm = dep.path("targetFramework").asText("");
                ArrayNode deps = byFramework.computeIfAbsent(tfm, k -> objectMapper.createArrayNode());
                ObjectNode depNode = objectMapper.createObjectNode();
                depNode.put("@type", "PackageDependency");
                depNode.put("id", dep.path("id").asText());
                depNode.put("range", dep.path("range").asText(""));
                deps.add(depNode);
            }
            String groupBase = repoBase + "/v3/registrations/" + idLower + "/" + versionLower + ".json#dependencygroup";
            byFramework.forEach((tfm, deps) -> {
                ObjectNode group = objectMapper.createObjectNode();
                group.put("@id", tfm.isEmpty() ? groupBase : groupBase + "/" + tfm.toLowerCase(Locale.ROOT));
                group.put("@type", "PackageDependencyGroup");
                if (!tfm.isEmpty()) {
                    group.put("targetFramework", tfm);
                }
                group.set("dependencies", deps);
                groups.add(group);
            });
        } catch (Exception e) {
            log.warn("Failed to parse stored dependency list for {}: {}", idLower, e.getMessage());
        }
        return groups;
    }

    private FormatResponse toJsonResponse(ObjectNode root) {
        try {
            byte[] json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
            return new ContentResponse(
                    new ByteArrayInputStream(json), "application/json", json.length, Map.of(), Map.of());
        } catch (Exception e) {
            return new FormatResponse.ErrorResponse(500, "Failed to build registration JSON: " + e.getMessage());
        }
    }
}
