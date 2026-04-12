package de.bsnsoft.megarepo.repository.proxy;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class RemoteHttpClientTest {

    private final RemoteHttpClient client =
            new RemoteHttpClient(Duration.ofSeconds(10), Duration.ofSeconds(30), "MegaRepo/1.0-test", 1);

    @Test
    void fetchViaProxy_createsProxiedClient() throws Exception {
        var config = new RemoteHttpClient.HttpProxyConfig("proxy.example.com", 8080);

        // Access the internal proxy client cache to verify client creation
        var cacheField = RemoteHttpClient.class.getDeclaredField("proxyClientCache");
        cacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var cache = (ConcurrentHashMap<String, HttpClient>) cacheField.get(client);

        // Before: cache should be empty
        assertEquals(0, cache.size());

        // Trigger proxy client creation by attempting a fetch (will fail due to no real proxy,
        // but the client should be created in the cache before the actual network call)
        try {
            client.fetchViaProxy("http://example.com/test", java.util.Map.of(), config);
        } catch (Exception e) {
            // Expected - no real proxy running
        }

        // After: cache should have one entry
        assertEquals(1, cache.size());
        assertNotNull(cache.get("proxy.example.com:8080"));
    }

    @Test
    void fetchViaProxy_cachedClientsAreReused() throws Exception {
        var config = new RemoteHttpClient.HttpProxyConfig("proxy.example.com", 3128);

        var cacheField = RemoteHttpClient.class.getDeclaredField("proxyClientCache");
        cacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var cache = (ConcurrentHashMap<String, HttpClient>) cacheField.get(client);

        // Make two calls with the same proxy config
        try {
            client.fetchViaProxy("http://example.com/a", java.util.Map.of(), config);
        } catch (Exception e) {
            // Expected
        }
        HttpClient first = cache.get("proxy.example.com:3128");

        try {
            client.fetchViaProxy("http://example.com/b", java.util.Map.of(), config);
        } catch (Exception e) {
            // Expected
        }
        HttpClient second = cache.get("proxy.example.com:3128");

        // Same HttpClient instance should be reused
        assertSame(first, second);
        assertEquals(1, cache.size());
    }

    @Test
    void fetchViaProxy_differentConfigs_createDifferentClients() throws Exception {
        var config1 = new RemoteHttpClient.HttpProxyConfig("proxy1.example.com", 8080);
        var config2 = new RemoteHttpClient.HttpProxyConfig("proxy2.example.com", 3128);

        var cacheField = RemoteHttpClient.class.getDeclaredField("proxyClientCache");
        cacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var cache = (ConcurrentHashMap<String, HttpClient>) cacheField.get(client);

        try {
            client.fetchViaProxy("http://example.com/a", java.util.Map.of(), config1);
        } catch (Exception e) {
            // Expected
        }
        try {
            client.fetchViaProxy("http://example.com/b", java.util.Map.of(), config2);
        } catch (Exception e) {
            // Expected
        }

        assertEquals(2, cache.size());
        HttpClient client1 = cache.get("proxy1.example.com:8080");
        HttpClient client2 = cache.get("proxy2.example.com:3128");
        assertNotNull(client1);
        assertNotNull(client2);
        assertNotSame(client1, client2);
    }

    @Test
    void httpProxyConfig_withAuth_includesUsernameInCacheKey() throws Exception {
        var config = new RemoteHttpClient.HttpProxyConfig("proxy.example.com", 8080, "user", "pass");

        var cacheField = RemoteHttpClient.class.getDeclaredField("proxyClientCache");
        cacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var cache = (ConcurrentHashMap<String, HttpClient>) cacheField.get(client);

        try {
            client.fetchViaProxy("http://example.com/test", java.util.Map.of(), config);
        } catch (Exception e) {
            // Expected
        }

        // Cache key includes username
        assertNotNull(cache.get("proxy.example.com:8080:user"));
        assertEquals(1, cache.size());
    }

    @Test
    void httpProxyConfig_convenienceConstructor_setsNullAuth() {
        var config = new RemoteHttpClient.HttpProxyConfig("proxy.example.com", 8080);
        assertEquals("proxy.example.com", config.host());
        assertEquals(8080, config.port());
        assertEquals(null, config.username());
        assertEquals(null, config.password());
    }

    @Test
    void defaultClient_isNotNull() throws Exception {
        var field = RemoteHttpClient.class.getDeclaredField("defaultHttpClient");
        field.setAccessible(true);
        assertNotNull(field.get(client));
    }
}
