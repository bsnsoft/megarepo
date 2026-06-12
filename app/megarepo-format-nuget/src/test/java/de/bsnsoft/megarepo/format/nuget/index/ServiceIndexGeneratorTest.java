package de.bsnsoft.megarepo.format.nuget.index;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.bsnsoft.megarepo.core.format.FormatResponse.ContentResponse;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceIndexGeneratorTest {

    private final ServiceIndexGenerator generator = new ServiceIndexGenerator();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void generate_containsAllRequiredResources() throws IOException {
        var response = generator.generate("nuget-hosted", "https://repo.example.com");
        ContentResponse content = assertInstanceOf(ContentResponse.class, response);
        assertEquals("application/json", content.contentType());

        JsonNode root = objectMapper.readTree(content.content());
        assertEquals("3.0.0", root.path("version").asText());

        List<String> types = new ArrayList<>();
        root.path("resources").forEach(r -> types.add(r.path("@type").asText()));

        assertTrue(types.contains("PackageBaseAddress/3.0.0"));
        assertTrue(types.contains("PackagePublish/2.0.0"));
        assertTrue(types.contains("RegistrationsBaseUrl"));
        assertTrue(types.contains("RegistrationsBaseUrl/3.6.0"));
        assertTrue(types.contains("SearchQueryService"));
    }

    @Test
    void generate_urlsPointAtTheRepository() throws IOException {
        var response = generator.generate("my-feed", "https://repo.example.com");
        JsonNode root = objectMapper.readTree(((ContentResponse) response).content());

        for (JsonNode resource : root.path("resources")) {
            String id = resource.path("@id").asText();
            assertTrue(id.startsWith("https://repo.example.com/repository/my-feed/"),
                    "resource @id must point at the repository, got: " + id);
        }
    }

    @Test
    void generate_flatContainerEndsWithSlash_publishDoesNot() throws IOException {
        var response = generator.generate("f", "http://localhost:8080");
        JsonNode root = objectMapper.readTree(((ContentResponse) response).content());

        for (JsonNode resource : root.path("resources")) {
            String type = resource.path("@type").asText();
            String id = resource.path("@id").asText();
            if (type.startsWith("PackageBaseAddress") || type.startsWith("RegistrationsBaseUrl")) {
                assertTrue(id.endsWith("/"), type + " @id must end with a slash: " + id);
            }
            if (type.startsWith("PackagePublish") || type.startsWith("SearchQueryService")) {
                assertTrue(!id.endsWith("/"), type + " @id must not end with a slash: " + id);
            }
        }
    }
}
