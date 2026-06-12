package de.bsnsoft.megarepo.format.nuget.index;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.bsnsoft.megarepo.core.format.FormatResponse.ContentResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.NotFoundResponse;
import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.core.repository.RepositoryType;
import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FlatContainerGeneratorTest {

    private static final UUID REPO_ID = UUID.randomUUID();
    private static final RepositoryConfig REPO = new RepositoryConfig(
            REPO_ID, "nuget-hosted", "nuget", RepositoryType.HOSTED, true, "default", Map.of());

    @Mock
    private ComponentJpaRepository componentRepository;

    @Test
    void versionsIndex_returnsSortedLowercaseVersions() throws IOException {
        when(componentRepository.findByRepositoryIdAndNamespaceAndName(REPO_ID, null, "my.pkg"))
                .thenReturn(List.of(
                        component("2.0.0"), component("1.0.0-Beta1"), component("1.0.0"), component("1.10.0")));

        var response = new FlatContainerGenerator(componentRepository).versionsIndex(REPO, "my.pkg");

        ContentResponse content = assertInstanceOf(ContentResponse.class, response);
        JsonNode root = new ObjectMapper().readTree(content.content());
        List<String> versions = new ArrayList<>();
        root.path("versions").forEach(v -> versions.add(v.asText()));
        assertEquals(List.of("1.0.0-beta1", "1.0.0", "1.10.0", "2.0.0"), versions);
    }

    @Test
    void versionsIndex_unknownPackage_returns404() {
        when(componentRepository.findByRepositoryIdAndNamespaceAndName(REPO_ID, null, "missing"))
                .thenReturn(List.of());

        var response = new FlatContainerGenerator(componentRepository).versionsIndex(REPO, "missing");
        assertInstanceOf(NotFoundResponse.class, response);
    }

    private static ComponentEntity component(String version) {
        var component = new ComponentEntity();
        component.setRepositoryId(REPO_ID);
        component.setName("my.pkg");
        component.setVersion(version);
        return component;
    }
}
