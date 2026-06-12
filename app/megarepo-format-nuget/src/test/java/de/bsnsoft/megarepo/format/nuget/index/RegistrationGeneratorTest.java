package de.bsnsoft.megarepo.format.nuget.index;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.bsnsoft.megarepo.core.format.FormatResponse.ContentResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.NotFoundResponse;
import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.core.repository.RepositoryType;
import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RegistrationGeneratorTest {

    private static final UUID REPO_ID = UUID.randomUUID();
    private static final RepositoryConfig REPO = new RepositoryConfig(
            REPO_ID, "nuget-hosted", "nuget", RepositoryType.HOSTED, true, "default", Map.of());
    private static final String BASE = "https://repo.example.com";

    @Mock
    private ComponentJpaRepository componentRepository;

    private RegistrationGenerator generator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        generator = new RegistrationGenerator(componentRepository);
    }

    @Test
    void registrationIndex_buildsSinglePageWithCatalogEntries() throws IOException {
        when(componentRepository.findByRepositoryIdAndNamespaceAndName(REPO_ID, null, "my.pkg"))
                .thenReturn(List.of(
                        component("1.0.0", Map.of(
                                "originalId", "My.Pkg",
                                "description", "First",
                                "dependencies", "[{\"id\":\"Newtonsoft.Json\",\"range\":\"[13.0.3, )\",\"targetFramework\":\"net8.0\"}]")),
                        component("2.0.0", Map.of("originalId", "My.Pkg", "description", "Second"))));

        var response = generator.registrationIndex(REPO, "my.pkg", BASE);

        ContentResponse content = assertInstanceOf(ContentResponse.class, response);
        JsonNode root = objectMapper.readTree(content.content());

        assertEquals(1, root.path("count").asInt());
        JsonNode page = root.path("items").get(0);
        assertEquals(2, page.path("count").asInt());
        assertEquals("1.0.0", page.path("lower").asText());
        assertEquals("2.0.0", page.path("upper").asText());

        JsonNode leaf = page.path("items").get(0);
        JsonNode catalogEntry = leaf.path("catalogEntry");
        assertEquals("My.Pkg", catalogEntry.path("id").asText());
        assertEquals("1.0.0", catalogEntry.path("version").asText());
        assertEquals("First", catalogEntry.path("description").asText());
        assertEquals(BASE + "/repository/nuget-hosted/v3-flatcontainer/my.pkg/1.0.0/my.pkg.1.0.0.nupkg",
                leaf.path("packageContent").asText());

        JsonNode group = catalogEntry.path("dependencyGroups").get(0);
        assertEquals("net8.0", group.path("targetFramework").asText());
        assertEquals("Newtonsoft.Json", group.path("dependencies").get(0).path("id").asText());
        assertEquals("[13.0.3, )", group.path("dependencies").get(0).path("range").asText());
    }

    @Test
    void registrationIndex_unknownPackage_returns404() {
        when(componentRepository.findByRepositoryIdAndNamespaceAndName(REPO_ID, null, "ghost"))
                .thenReturn(List.of());
        assertInstanceOf(NotFoundResponse.class, generator.registrationIndex(REPO, "ghost", BASE));
    }

    @Test
    void registrationLeaf_returnsSingleVersion() throws IOException {
        when(componentRepository.findByRepositoryIdAndNamespaceAndName(REPO_ID, null, "my.pkg"))
                .thenReturn(List.of(component("1.0.0", Map.of()), component("2.0.0", Map.of())));

        var response = generator.registrationLeaf(REPO, "my.pkg", "2.0.0", BASE);

        ContentResponse content = assertInstanceOf(ContentResponse.class, response);
        JsonNode leaf = objectMapper.readTree(content.content());
        assertEquals("2.0.0", leaf.path("catalogEntry").path("version").asText());
        assertTrue(leaf.path("@id").asText().endsWith("/v3/registrations/my.pkg/2.0.0.json"));
    }

    @Test
    void registrationLeaf_unknownVersion_returns404() {
        when(componentRepository.findByRepositoryIdAndNamespaceAndName(REPO_ID, null, "my.pkg"))
                .thenReturn(List.of(component("1.0.0", Map.of())));
        assertInstanceOf(NotFoundResponse.class, generator.registrationLeaf(REPO, "my.pkg", "9.9.9", BASE));
    }

    private static ComponentEntity component(String version, Map<String, Object> attributes) {
        var component = new ComponentEntity();
        component.setRepositoryId(REPO_ID);
        component.setName("my.pkg");
        component.setVersion(version);
        component.setAttributes(attributes);
        component.setCreatedAt(Instant.parse("2026-06-01T12:00:00Z"));
        return component;
    }
}
