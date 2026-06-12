package de.bsnsoft.megarepo.format.nuget.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.bsnsoft.megarepo.core.format.FormatResponse.ContentResponse;
import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.core.repository.RepositoryType;
import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NugetSearchServiceTest {

    private static final UUID REPO_ID = UUID.randomUUID();
    private static final RepositoryConfig REPO = new RepositoryConfig(
            REPO_ID, "nuget-hosted", "nuget", RepositoryType.HOSTED, true, "default", Map.of());

    @Mock
    private ComponentJpaRepository componentRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void search_groupsByPackageAndReturnsLatestVersion() throws IOException {
        when(componentRepository.findByRepositoryId(eq(REPO_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(
                        component("pkg.a", "1.0.0", Map.of("originalId", "Pkg.A", "description", "Package A")),
                        component("pkg.a", "2.0.0", Map.of("originalId", "Pkg.A", "description", "Package A v2")),
                        component("pkg.b", "0.1.0", Map.of("originalId", "Pkg.B")))));

        var response = new NugetSearchService(componentRepository)
                .search(REPO, null, 0, 20, "https://repo.example.com");

        ContentResponse content = assertInstanceOf(ContentResponse.class, response);
        JsonNode root = objectMapper.readTree(content.content());
        assertEquals(2, root.path("totalHits").asInt());

        JsonNode pkgA = root.path("data").get(0);
        assertEquals("Pkg.A", pkgA.path("id").asText());
        assertEquals("2.0.0", pkgA.path("version").asText());
        assertEquals("Package A v2", pkgA.path("description").asText());
        assertEquals(2, pkgA.path("versions").size());
        assertEquals(
                "https://repo.example.com/repository/nuget-hosted/v3/registrations/pkg.a/index.json",
                pkgA.path("registration").asText());
    }

    @Test
    void search_withQuery_usesFilter() throws IOException {
        when(componentRepository.findByRepositoryIdAndFilter(eq(REPO_ID), eq("json"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(
                        component("newtonsoft.json", "13.0.3", Map.of("originalId", "Newtonsoft.Json")))));

        var response = new NugetSearchService(componentRepository)
                .search(REPO, "json", 0, 20, "https://repo.example.com");

        JsonNode root = objectMapper.readTree(((ContentResponse) response).content());
        assertEquals(1, root.path("totalHits").asInt());
        assertEquals("Newtonsoft.Json", root.path("data").get(0).path("id").asText());
    }

    @Test
    void search_appliesSkipAndTake() throws IOException {
        when(componentRepository.findByRepositoryId(eq(REPO_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(
                        component("a", "1.0.0", Map.of()),
                        component("b", "1.0.0", Map.of()),
                        component("c", "1.0.0", Map.of()))));

        var response = new NugetSearchService(componentRepository)
                .search(REPO, null, 1, 1, "https://repo.example.com");

        JsonNode root = objectMapper.readTree(((ContentResponse) response).content());
        assertEquals(3, root.path("totalHits").asInt());
        assertEquals(1, root.path("data").size());
        assertEquals("b", root.path("data").get(0).path("id").asText());
    }

    private static ComponentEntity component(String name, String version, Map<String, Object> attributes) {
        var component = new ComponentEntity();
        component.setRepositoryId(REPO_ID);
        component.setName(name);
        component.setVersion(version);
        component.setAttributes(attributes);
        return component;
    }
}
