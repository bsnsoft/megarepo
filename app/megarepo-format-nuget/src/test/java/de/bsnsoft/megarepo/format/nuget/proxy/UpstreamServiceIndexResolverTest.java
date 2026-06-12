package de.bsnsoft.megarepo.format.nuget.proxy;

import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.core.repository.RepositoryType;
import de.bsnsoft.megarepo.format.nuget.proxy.UpstreamServiceIndexResolver.UpstreamResources;
import de.bsnsoft.megarepo.repository.proxy.RemoteHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UpstreamServiceIndexResolverTest {

    private static final String UPSTREAM_INDEX = "https://api.nuget.org/v3/index.json";

    private static final String SERVICE_INDEX_JSON = """
            {
              "version": "3.0.0",
              "resources": [
                {"@id": "https://azuresearch-usnc.nuget.org/query", "@type": "SearchQueryService"},
                {"@id": "https://api.nuget.org/v3/registration5-semver1/", "@type": "RegistrationsBaseUrl"},
                {"@id": "https://api.nuget.org/v3-flatcontainer/", "@type": "PackageBaseAddress/3.0.0"},
                {"@id": "https://api.nuget.org/v3/registration5-gz-semver2/", "@type": "RegistrationsBaseUrl/3.6.0"}
              ]
            }
            """;

    @Mock
    private RemoteHttpClient remoteHttpClient;

    private RepositoryConfig proxyRepo() {
        return new RepositoryConfig(
                UUID.randomUUID(), "nuget-proxy", "nuget", RepositoryType.PROXY, true, "default",
                Map.of("proxy", Map.of("remoteUrl", UPSTREAM_INDEX)));
    }

    @Test
    void resolve_extractsResourceBasesAndPrefersSemver2Registrations() throws IOException {
        when(remoteHttpClient.fetch(eq(UPSTREAM_INDEX), any())).thenReturn(response(200, SERVICE_INDEX_JSON));

        var resolver = new UpstreamServiceIndexResolver(remoteHttpClient);
        Optional<UpstreamResources> resources = resolver.resolve(proxyRepo());

        assertTrue(resources.isPresent());
        assertEquals("https://api.nuget.org/v3-flatcontainer", resources.get().flatContainerBase());
        assertEquals("https://api.nuget.org/v3/registration5-gz-semver2", resources.get().registrationsBase());
        assertEquals("https://azuresearch-usnc.nuget.org/query", resources.get().searchBase());
    }

    @Test
    void resolve_cachesResultWithinTtl() throws IOException {
        when(remoteHttpClient.fetch(eq(UPSTREAM_INDEX), any())).thenReturn(response(200, SERVICE_INDEX_JSON));

        var resolver = new UpstreamServiceIndexResolver(remoteHttpClient);
        RepositoryConfig repo = proxyRepo();
        resolver.resolve(repo);
        resolver.resolve(repo);

        verify(remoteHttpClient, times(1)).fetch(eq(UPSTREAM_INDEX), any());
    }

    @Test
    void resolve_upstreamError_returnsEmpty() throws IOException {
        when(remoteHttpClient.fetch(eq(UPSTREAM_INDEX), any())).thenReturn(response(503, ""));

        var resolver = new UpstreamServiceIndexResolver(remoteHttpClient);
        assertTrue(resolver.resolve(proxyRepo()).isEmpty());
    }

    @Test
    void resolve_missingFlatContainer_returnsEmpty() throws IOException {
        when(remoteHttpClient.fetch(eq(UPSTREAM_INDEX), any()))
                .thenReturn(response(200, "{\"resources\":[]}"));

        var resolver = new UpstreamServiceIndexResolver(remoteHttpClient);
        assertTrue(resolver.resolve(proxyRepo()).isEmpty());
    }

    @Test
    void resolve_usesConfiguredUpstreamCredentials() throws IOException {
        RepositoryConfig repo = new RepositoryConfig(
                UUID.randomUUID(), "nuget-proxy", "nuget", RepositoryType.PROXY, true, "default",
                Map.of("proxy", Map.of(
                        "remoteUrl", UPSTREAM_INDEX, "username", "alice", "password", "secret")));
        when(remoteHttpClient.fetchWithAuth(eq(UPSTREAM_INDEX), eq("alice"), eq("secret")))
                .thenReturn(response(200, SERVICE_INDEX_JSON));

        var resolver = new UpstreamServiceIndexResolver(remoteHttpClient);
        assertTrue(resolver.resolve(repo).isPresent());
        verify(remoteHttpClient, times(1)).fetchWithAuth(eq(UPSTREAM_INDEX), eq("alice"), eq("secret"));
    }

    private static RemoteHttpClient.RemoteResponse response(int status, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return new RemoteHttpClient.RemoteResponse(
                status, new ByteArrayInputStream(bytes), bytes.length, "application/json");
    }
}
