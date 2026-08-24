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
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void resolve_matchesVersionedResourceTypes() throws IOException {
        // A feed that publishes only the versioned spellings — Visual Studio's
        // search failed with 502 because none of these used to match.
        String index = serviceIndex(
                resource("https://upstream.example/query", "SearchQueryService/3.5.0"),
                resource("https://upstream.example/registration/", "RegistrationsBaseUrl/3.6.0"),
                resource("https://upstream.example/flat/", "PackageBaseAddress/3.0.0"));
        when(remoteHttpClient.fetch(eq(UPSTREAM_INDEX), any())).thenReturn(response(200, index));

        var resolver = new UpstreamServiceIndexResolver(remoteHttpClient);
        Optional<UpstreamResources> resources = resolver.resolve(proxyRepo());

        assertTrue(resources.isPresent());
        assertEquals("https://upstream.example/flat", resources.get().flatContainerBase());
        assertEquals("https://upstream.example/registration", resources.get().registrationsBase());
        assertEquals("https://upstream.example/query", resources.get().searchBase());
    }

    @Test
    void resolve_matchesUnversionedResourceTypes() throws IOException {
        String index = serviceIndex(
                resource("https://upstream.example/query", "SearchQueryService"),
                resource("https://upstream.example/registration/", "RegistrationsBaseUrl"),
                resource("https://upstream.example/flat/", "PackageBaseAddress"));
        when(remoteHttpClient.fetch(eq(UPSTREAM_INDEX), any())).thenReturn(response(200, index));

        var resolver = new UpstreamServiceIndexResolver(remoteHttpClient);
        Optional<UpstreamResources> resources = resolver.resolve(proxyRepo());

        assertTrue(resources.isPresent());
        assertEquals("https://upstream.example/flat", resources.get().flatContainerBase());
        assertEquals("https://upstream.example/registration", resources.get().registrationsBase());
        assertEquals("https://upstream.example/query", resources.get().searchBase());
    }

    @Test
    void resolve_mixedVersions_picksHighestIndependentOfDocumentOrder() throws IOException {
        String[] entries = {
                resource("https://upstream.example/flat/", "PackageBaseAddress/3.0.0"),
                resource("https://upstream.example/query-plain", "SearchQueryService"),
                resource("https://upstream.example/query-beta", "SearchQueryService/3.0.0-beta"),
                resource("https://upstream.example/query-300", "SearchQueryService/3.0.0"),
                resource("https://upstream.example/query-350", "SearchQueryService/3.5.0"),
        };
        String[] reversed = new String[entries.length];
        for (int i = 0; i < entries.length; i++) {
            reversed[i] = entries[entries.length - 1 - i];
        }
        when(remoteHttpClient.fetch(eq(UPSTREAM_INDEX), any()))
                .thenReturn(response(200, serviceIndex(entries)))
                .thenReturn(response(200, serviceIndex(reversed)));

        var resolver = new UpstreamServiceIndexResolver(remoteHttpClient);
        Optional<UpstreamResources> forward = resolver.resolve(proxyRepo());
        Optional<UpstreamResources> backward = resolver.resolve(proxyRepo());

        assertTrue(forward.isPresent());
        assertTrue(backward.isPresent());
        assertEquals("https://upstream.example/query-350", forward.get().searchBase());
        assertEquals("https://upstream.example/query-350", backward.get().searchBase());
    }

    @Test
    void resolve_prefersNamedVariantOverNumericAndPlainRegistrations() throws IOException {
        String index = serviceIndex(
                resource("https://upstream.example/flat/", "PackageBaseAddress/3.0.0"),
                resource("https://upstream.example/registration-semver1/", "RegistrationsBaseUrl"),
                resource("https://upstream.example/registration-360/", "RegistrationsBaseUrl/3.6.0"),
                resource("https://upstream.example/registration-versioned/", "RegistrationsBaseUrl/Versioned"));
        when(remoteHttpClient.fetch(eq(UPSTREAM_INDEX), any())).thenReturn(response(200, index));

        var resolver = new UpstreamServiceIndexResolver(remoteHttpClient);
        Optional<UpstreamResources> resources = resolver.resolve(proxyRepo());

        assertTrue(resources.isPresent());
        assertEquals("https://upstream.example/registration-versioned",
                resources.get().registrationsBase());
    }

    @Test
    void resolve_duplicateEqualTypes_keepsFirstListedEndpoint() throws IOException {
        String index = serviceIndex(
                resource("https://upstream.example/flat/", "PackageBaseAddress/3.0.0"),
                resource("https://upstream.example/query-primary", "SearchQueryService/3.5.0"),
                resource("https://upstream.example/query-secondary", "SearchQueryService/3.5.0"));
        when(remoteHttpClient.fetch(eq(UPSTREAM_INDEX), any())).thenReturn(response(200, index));

        var resolver = new UpstreamServiceIndexResolver(remoteHttpClient);
        Optional<UpstreamResources> resources = resolver.resolve(proxyRepo());

        assertTrue(resources.isPresent());
        assertEquals("https://upstream.example/query-primary", resources.get().searchBase());
    }

    @Test
    void resolve_versionedFlatContainerOnly_isEnough() throws IOException {
        String index = serviceIndex(
                resource("https://upstream.example/flat-old/", "PackageBaseAddress/2.0.0"),
                resource("https://upstream.example/flat-new/", "PackageBaseAddress/3.0.0"));
        when(remoteHttpClient.fetch(eq(UPSTREAM_INDEX), any())).thenReturn(response(200, index));

        var resolver = new UpstreamServiceIndexResolver(remoteHttpClient);
        Optional<UpstreamResources> resources = resolver.resolve(proxyRepo());

        assertTrue(resources.isPresent());
        assertEquals("https://upstream.example/flat-new", resources.get().flatContainerBase());
        assertNull(resources.get().registrationsBase());
        assertNull(resources.get().searchBase());
    }

    @Test
    void resolve_unrelatedResourceTypesAreIgnored() throws IOException {
        String index = serviceIndex(
                resource("https://upstream.example/flat/", "PackageBaseAddress/3.0.0"),
                resource("https://upstream.example/push", "PackagePublish/2.0.0"),
                resource("https://upstream.example/vulns", "VulnerabilityInfo/6.7.0"));
        when(remoteHttpClient.fetch(eq(UPSTREAM_INDEX), any())).thenReturn(response(200, index));

        var resolver = new UpstreamServiceIndexResolver(remoteHttpClient);
        Optional<UpstreamResources> resources = resolver.resolve(proxyRepo());

        assertTrue(resources.isPresent());
        assertEquals("https://upstream.example/flat", resources.get().flatContainerBase());
        assertNull(resources.get().searchBase());
    }

    private static String serviceIndex(String... resourceEntries) {
        return "{\"version\":\"3.0.0\",\"resources\":[" + String.join(",", resourceEntries) + "]}";
    }

    private static String resource(String id, String type) {
        return "{\"@id\":\"" + id + "\",\"@type\":\"" + type + "\"}";
    }

    private static RemoteHttpClient.RemoteResponse response(int status, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return new RemoteHttpClient.RemoteResponse(
                status, new ByteArrayInputStream(bytes), bytes.length, "application/json");
    }
}
