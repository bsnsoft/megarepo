package de.bsnsoft.megarepo.format.nuget.index;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.bsnsoft.megarepo.core.format.FormatResponse.ContentResponse;
import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.core.repository.RepositoryType;
import de.bsnsoft.megarepo.format.nuget.proxy.UpstreamServiceIndexResolver;
import de.bsnsoft.megarepo.format.nuget.proxy.UpstreamServiceIndexResolver.UpstreamResources;
import de.bsnsoft.megarepo.repository.proxy.RemoteHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServiceIndexGeneratorTest {

    private final ServiceIndexGenerator generator = new ServiceIndexGenerator();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private RemoteHttpClient remoteHttpClient;

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

    @Test
    void generate_publishesEveryTypeAliasInAStableOrder() throws IOException {
        // Pinned in full: a client may probe for exactly one spelling, so both the
        // legacy unversioned aliases and the current versioned ones must be present.
        // The order is fixed as well — the document must not shuffle between calls.
        assertEquals(List.of(
                        "PackageBaseAddress/3.0.0",
                        "PackagePublish/2.0.0",
                        "RegistrationsBaseUrl",
                        "RegistrationsBaseUrl/3.0.0-beta",
                        "RegistrationsBaseUrl/3.0.0-rc",
                        "RegistrationsBaseUrl/3.4.0",
                        "RegistrationsBaseUrl/3.6.0",
                        "RegistrationsBaseUrl/Versioned",
                        "SearchQueryService",
                        "SearchQueryService/3.0.0-beta",
                        "SearchQueryService/3.0.0-rc",
                        "SearchQueryService/3.5.0"),
                publishedTypes("nuget-hosted", "https://repo.example.com"));
    }

    @Test
    void generate_isDeterministic() throws IOException {
        assertEquals(publishedTypes("nuget-hosted", "https://repo.example.com"),
                publishedTypes("nuget-hosted", "https://repo.example.com"));
    }

    @Test
    void generate_versionedAliasesShareTheEndpointOfTheirUnversionedName() throws IOException {
        JsonNode root = objectMapper.readTree(indexBytes("f", "https://repo.example.com"));

        String registrations = null;
        String search = null;
        for (JsonNode resource : root.path("resources")) {
            String type = resource.path("@type").asText();
            String id = resource.path("@id").asText();
            if (type.startsWith("RegistrationsBaseUrl")) {
                registrations = assertSameEndpoint(registrations, id, type);
            } else if (type.startsWith("SearchQueryService")) {
                search = assertSameEndpoint(search, id, type);
            }
        }

        assertEquals("https://repo.example.com/repository/f/v3/registrations/", registrations);
        assertEquals("https://repo.example.com/repository/f/v3/search", search);
    }

    /**
     * Round trip: MegaRepo's own {@code UpstreamServiceIndexResolver} — the code a
     * proxy repository uses to read a remote feed — must be able to consume the
     * index this generator produces. That proves the writing and the reading side
     * agree on the {@code @type} spellings. No network access: the resolver's HTTP
     * client is stubbed with the generated document.
     */
    @Test
    void generatedIndex_isConsumableByOurOwnUpstreamResolver() throws IOException {
        String upstreamIndexUrl = "https://repo.example.com/repository/nuget-hosted/index.json";
        byte[] generated = indexBytes("nuget-hosted", "https://repo.example.com");
        when(remoteHttpClient.fetch(eq(upstreamIndexUrl), any())).thenReturn(
                new RemoteHttpClient.RemoteResponse(
                        200, new ByteArrayInputStream(generated), generated.length, "application/json"));

        RepositoryConfig proxyOfUs = new RepositoryConfig(
                UUID.randomUUID(), "nuget-proxy", "nuget", RepositoryType.PROXY, true, "default",
                Map.of("proxy", Map.of("remoteUrl", upstreamIndexUrl)));

        Optional<UpstreamResources> resolved =
                new UpstreamServiceIndexResolver(remoteHttpClient).resolve(proxyOfUs);

        assertTrue(resolved.isPresent(), "our own index must resolve to a complete resource map");
        assertEquals("https://repo.example.com/repository/nuget-hosted/v3-flatcontainer",
                resolved.get().flatContainerBase());
        assertEquals("https://repo.example.com/repository/nuget-hosted/v3/registrations",
                resolved.get().registrationsBase());
        assertEquals("https://repo.example.com/repository/nuget-hosted/v3/search",
                resolved.get().searchBase());
    }

    private static String assertSameEndpoint(String seen, String id, String type) {
        if (seen != null) {
            assertEquals(seen, id, type + " must point at the same endpoint as its sibling aliases");
        }
        return id;
    }

    private List<String> publishedTypes(String repoName, String baseUrl) throws IOException {
        JsonNode root = objectMapper.readTree(indexBytes(repoName, baseUrl));
        List<String> types = new ArrayList<>();
        root.path("resources").forEach(r -> types.add(r.path("@type").asText()));
        return types;
    }

    private byte[] indexBytes(String repoName, String baseUrl) throws IOException {
        ContentResponse content =
                assertInstanceOf(ContentResponse.class, generator.generate(repoName, baseUrl));
        try (InputStream in = content.content()) {
            return in.readAllBytes();
        }
    }
}
