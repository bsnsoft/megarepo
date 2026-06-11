package de.bsnsoft.megarepo.repository.proxy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the global outbound proxy configuration of {@link RemoteHttpClient}
 * ({@code megarepo.outbound-proxy.*}).
 */
class RemoteHttpClientOutboundProxyTest {

    private static final String TUNNELING_SCHEMES_PROP = "jdk.http.auth.tunneling.disabledSchemes";

    private String originalTunnelingSchemes;

    @BeforeEach
    void rememberSystemProperty() {
        originalTunnelingSchemes = System.getProperty(TUNNELING_SCHEMES_PROP);
        System.clearProperty(TUNNELING_SCHEMES_PROP);
    }

    @AfterEach
    void restoreSystemProperty() {
        if (originalTunnelingSchemes == null) {
            System.clearProperty(TUNNELING_SCHEMES_PROP);
        } else {
            System.setProperty(TUNNELING_SCHEMES_PROP, originalTunnelingSchemes);
        }
    }

    private static RemoteHttpClient newClient(OutboundProxyProperties props) {
        return new RemoteHttpClient(
                Duration.ofSeconds(5), Duration.ofSeconds(5), "MegaRepo/test", 0, props);
    }

    private static HttpClient defaultHttpClient(RemoteHttpClient client) throws Exception {
        Field field = RemoteHttpClient.class.getDeclaredField("defaultHttpClient");
        field.setAccessible(true);
        return (HttpClient) field.get(client);
    }

    @Test
    void disabled_defaultClientHasNoExplicitProxyOrAuthenticator() throws Exception {
        HttpClient httpClient = defaultHttpClient(newClient(OutboundProxyProperties.disabled()));

        // No explicit proxy selector -> JDK default applies (JAVA_TOOL_OPTIONS keeps working)
        assertTrue(httpClient.proxy().isEmpty());
        assertTrue(httpClient.authenticator().isEmpty());
        // The tunneling system property must not be touched when the feature is off
        assertNull(System.getProperty(TUNNELING_SCHEMES_PROP));
    }

    @Test
    void enabled_defaultClientRoutesThroughConfiguredProxy() throws Exception {
        var props = new OutboundProxyProperties(
                true, "proxy.corp.example.com", 3128, null, null, List.of());
        HttpClient httpClient = defaultHttpClient(newClient(props));

        assertTrue(httpClient.proxy().isPresent());
        List<Proxy> proxies = httpClient.proxy().get().select(URI.create("https://repo1.maven.org/maven2/"));
        assertEquals(1, proxies.size());
        assertEquals(Proxy.Type.HTTP, proxies.get(0).type());
        var address = (InetSocketAddress) proxies.get(0).address();
        assertEquals("proxy.corp.example.com", address.getHostString());
        assertEquals(3128, address.getPort());

        // No credentials configured -> no authenticator, no system property fiddling
        assertTrue(httpClient.authenticator().isEmpty());
        assertNull(System.getProperty(TUNNELING_SCHEMES_PROP));
    }

    @Test
    void enabled_withAuth_setsAuthenticatorAndEnablesBasicForTunneling() throws Exception {
        var props = new OutboundProxyProperties(
                true, "proxy.corp.example.com", 3128, "user", "secret", List.of());
        HttpClient httpClient = defaultHttpClient(newClient(props));

        assertTrue(httpClient.authenticator().isPresent());
        // Basic auth over CONNECT (HTTPS upstreams) requires clearing the JDK default
        assertEquals("", System.getProperty(TUNNELING_SCHEMES_PROP));
    }

    @Test
    void enabled_withAuth_doesNotOverrideOperatorProvidedTunnelingSchemes() throws Exception {
        System.setProperty(TUNNELING_SCHEMES_PROP, "Digest");
        var props = new OutboundProxyProperties(
                true, "proxy.corp.example.com", 3128, "user", "secret", List.of());
        newClient(props);

        assertEquals("Digest", System.getProperty(TUNNELING_SCHEMES_PROP));
    }

    @Test
    void enabled_nonProxyHostsBypassTheProxy() throws Exception {
        var props = new OutboundProxyProperties(
                true, "proxy.corp.example.com", 3128, null, null,
                List.of("localhost", "*.internal.example.com"));
        HttpClient httpClient = defaultHttpClient(newClient(props));

        var selector = httpClient.proxy().orElseThrow();
        assertEquals(List.of(Proxy.NO_PROXY), selector.select(URI.create("http://localhost:8081/repo")));
        assertEquals(
                List.of(Proxy.NO_PROXY),
                selector.select(URI.create("https://nexus.internal.example.com/maven2/")));

        List<Proxy> external = selector.select(URI.create("https://repo1.maven.org/maven2/"));
        assertEquals(Proxy.Type.HTTP, external.get(0).type());
    }

    @Test
    void enabled_withoutHost_failsFastWithClearMessage() {
        var props = new OutboundProxyProperties(true, "", 3128, null, null, List.of());
        var ex = assertThrows(IllegalStateException.class, () -> newClient(props));
        assertTrue(ex.getMessage().contains("megarepo.outbound-proxy.host"));
    }

    @Test
    void proxyAuthenticator_answersOnlyProxyChallenges() {
        var authenticator = new RemoteHttpClient.ProxyAuthenticator("user", "secret");

        PasswordAuthentication proxyAuth = authenticator.authFor(Authenticator.RequestorType.PROXY);
        assertNotNull(proxyAuth);
        assertEquals("user", proxyAuth.getUserName());
        assertArrayEquals("secret".toCharArray(), proxyAuth.getPassword());

        // Upstream/server challenges must NEVER receive the proxy credentials
        assertNull(authenticator.authFor(Authenticator.RequestorType.SERVER));
    }

    @Test
    void outboundProxySelector_reportsProxyAddressUnresolved() {
        // The proxy address must be created unresolved so DNS resolution happens
        // at connect time (the proxy host may not be resolvable at startup).
        var props = new OutboundProxyProperties(
                true, "proxy.corp.example.com", 3128, null, null, List.of());
        var selector = new RemoteHttpClient.OutboundProxySelector(props);

        var address = (InetSocketAddress) selector.select(URI.create("https://example.com/")).get(0).address();
        assertTrue(address.isUnresolved());
        assertFalse(selector.select(URI.create("https://example.com/")).isEmpty());
    }
}
