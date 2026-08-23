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

    private final RemoteHttpClient client = new RemoteHttpClient(
            Duration.ofSeconds(10),
            Duration.ofSeconds(30),
            "MegaRepo/1.0-test",
            1,
            OutboundProxyProperties.disabled());

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

    /**
     * Upstream compression: the JDK HTTP client neither advertises nor decodes gzip on its own,
     * which meant metadata was always transferred raw — an npm packument such as
     * {@code @typescript-eslint/parser} is ~15 MB uncompressed versus ~2.9 MB gzipped
     * (GitHub #1). These tests use a real loopback server, because the behaviour under test is
     * the wire negotiation itself.
     */
    @Test
    void fetch_advertisesGzipAndInflatesCompressedResponse() throws Exception {
        String payload = "{\"name\":\"lodash\",\"versions\":{}}".repeat(200);
        var recordedAcceptEncoding = new java.util.concurrent.atomic.AtomicReference<String>();

        var server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/pkg", exchange -> {
            recordedAcceptEncoding.set(exchange.getRequestHeaders().getFirst("Accept-Encoding"));
            byte[] compressed;
            try (var bytes = new java.io.ByteArrayOutputStream();
                    var gzip = new java.util.zip.GZIPOutputStream(bytes)) {
                gzip.write(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                gzip.finish();
                compressed = bytes.toByteArray();
            }
            exchange.getResponseHeaders().add("Content-Encoding", "gzip");
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, compressed.length);
            try (var out = exchange.getResponseBody()) {
                out.write(compressed);
            }
        });
        server.start();

        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/pkg";
            RemoteHttpClient.RemoteResponse response = client.fetch(url, java.util.Map.of());

            assertEquals(200, response.statusCode());
            assertEquals("gzip", recordedAcceptEncoding.get(), "gzip must be advertised upstream");

            String body = new String(
                    response.body().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(payload, body, "gzip body must be transparently inflated");

            // The upstream Content-Length described the compressed payload and no longer
            // matches the stream the caller reads.
            assertEquals(-1, response.contentLength());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fetch_uncompressedResponseIsPassedThroughUnchanged() throws Exception {
        String payload = "{\"plain\":true}";

        var server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/plain", exchange -> {
            byte[] bytes = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();

        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/plain";
            RemoteHttpClient.RemoteResponse response = client.fetch(url, java.util.Map.of());

            assertEquals(200, response.statusCode());
            assertEquals(
                    payload,
                    new String(response.body().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(payload.length(), response.contentLength());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fetch_forwardsCallerSuppliedHeaders() throws Exception {
        var recordedAccept = new java.util.concurrent.atomic.AtomicReference<String>();

        var server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/pkg", exchange -> {
            recordedAccept.set(exchange.getRequestHeaders().getFirst("Accept"));
            exchange.sendResponseHeaders(200, 2);
            try (var out = exchange.getResponseBody()) {
                out.write("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        });
        server.start();

        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/pkg";
            client.fetch(url, java.util.Map.of("Accept", "application/vnd.npm.install-v1+json"));

            assertEquals("application/vnd.npm.install-v1+json", recordedAccept.get());
        } finally {
            server.stop(0);
        }
    }
}
