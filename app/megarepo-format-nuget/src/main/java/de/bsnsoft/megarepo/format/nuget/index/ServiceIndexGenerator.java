package de.bsnsoft.megarepo.format.nuget.index;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.bsnsoft.megarepo.core.format.FormatResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.ContentResponse;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.Map;

/**
 * Generates the NuGet V3 service index ({@code /repository/{name}/index.json}).
 *
 * <p>All resource URLs point back at MegaRepo — for proxy repositories the
 * upstream is contacted only when the individual resources are requested.
 *
 * <p>Every resource is published under <em>all</em> {@code @type} spellings the
 * V3 protocol defines for it, because a client is free to look for any single
 * one of them: modern tooling asks for the newest versioned spelling
 * ({@code SearchQueryService/3.5.0}, {@code RegistrationsBaseUrl/Versioned}),
 * older clients only know the unversioned name. They all denote the same
 * resource at different protocol revisions, so they share one {@code @id} here —
 * a single MegaRepo endpoint serves every revision. The unversioned aliases
 * therefore stay for backwards compatibility even though they rank lowest.
 *
 * <p>The counterpart on the reading side is
 * {@code UpstreamServiceIndexResolver}, which groups a feed's {@code resources}
 * by base type and picks one winner per group; this generator's output is
 * deliberately shaped so that our own resolver consumes it unchanged.
 *
 * <p>Only resources MegaRepo actually serves are advertised — there is no
 * autocomplete, report-abuse or vulnerability endpoint, so those types stay out.
 */
@Component
public class ServiceIndexGenerator {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public FormatResponse generate(String repoName, String baseUrl) {
        String repoBase = baseUrl + "/repository/" + repoName;

        ObjectNode root = objectMapper.createObjectNode();
        root.put("version", "3.0.0");

        ArrayNode resources = objectMapper.createArrayNode();

        addResource(resources, repoBase + "/v3-flatcontainer/",
                "Base URL of where NuGet packages are stored, in the format "
                        + "https://api.nuget.org/v3-flatcontainer/{id-lower}/{version-lower}/{id-lower}.{version-lower}.nupkg",
                "PackageBaseAddress/3.0.0");
        addResource(resources, repoBase + "/api/v2/package",
                "Endpoint for pushing NuGet packages (X-NuGet-ApiKey header)",
                "PackagePublish/2.0.0");
        // Aliases are listed oldest revision first; "/Versioned" is the spelling
        // NuGet uses for the newest registration hive.
        addResource(resources, repoBase + "/v3/registrations/",
                "Package registration metadata",
                "RegistrationsBaseUrl",
                "RegistrationsBaseUrl/3.0.0-beta",
                "RegistrationsBaseUrl/3.0.0-rc",
                "RegistrationsBaseUrl/3.4.0",
                "RegistrationsBaseUrl/3.6.0",
                "RegistrationsBaseUrl/Versioned");
        addResource(resources, repoBase + "/v3/search",
                "Query endpoint of NuGet search service",
                "SearchQueryService",
                "SearchQueryService/3.0.0-beta",
                "SearchQueryService/3.0.0-rc",
                "SearchQueryService/3.5.0");

        root.set("resources", resources);

        try {
            byte[] json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
            return new ContentResponse(
                    new ByteArrayInputStream(json), "application/json", json.length, Map.of(), Map.of());
        } catch (Exception e) {
            return new FormatResponse.ErrorResponse(500, "Failed to build service index: " + e.getMessage());
        }
    }

    private void addResource(ArrayNode resources, String id, String comment, String... types) {
        for (String type : types) {
            ObjectNode resource = objectMapper.createObjectNode();
            resource.put("@id", id);
            resource.put("@type", type);
            resource.put("comment", comment);
            resources.add(resource);
        }
    }
}
