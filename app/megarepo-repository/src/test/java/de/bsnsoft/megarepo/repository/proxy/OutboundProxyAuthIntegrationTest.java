package de.bsnsoft.megarepo.repository.proxy;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for the global outbound proxy with authentication: runs a minimal
 * authenticating forward proxy (HTTP/1.1, plain proxying) that answers 407 until the
 * client presents correct Basic credentials, then serves the response.
 *
 * <p>This exercises the full 407-then-200 flow through the JDK HttpClient's
 * {@code AuthenticationFilter} with the {@code ProxyAuthenticator} wired in by
 * {@code megarepo.outbound-proxy.*}. The CONNECT/tunneling path (https upstreams)
 * is not covered here because it would require a TLS-terminating test setup; the
 * {@code jdk.http.auth.tunneling.disabledSchemes} handling for that path is asserted
 * in {@link RemoteHttpClientOutboundProxyTest}.
 */
class OutboundProxyAuthIntegrationTest {

    private static final String USERNAME = "megarepo";
    private static final String PASSWORD = "pr0xy-s3cret";

    private MiniAuthProxy proxy;
    private String originalTunnelingSchemes;

    @BeforeEach
    void startProxy() throws IOException {
        originalTunnelingSchemes = System.getProperty("jdk.http.auth.tunneling.disabledSchemes");
        proxy = new MiniAuthProxy(USERNAME, PASSWORD);
        proxy.start();
    }

    @AfterEach
    void stopProxy() {
        proxy.close();
        if (originalTunnelingSchemes == null) {
            System.clearProperty("jdk.http.auth.tunneling.disabledSchemes");
        } else {
            System.setProperty("jdk.http.auth.tunneling.disabledSchemes", originalTunnelingSchemes);
        }
    }

    private RemoteHttpClient clientWith(OutboundProxyProperties props) {
        return new RemoteHttpClient(
                Duration.ofSeconds(5), Duration.ofSeconds(5), "MegaRepo/test", 0, props);
    }

    @Test
    void fetchThroughAuthenticatedProxy_handles407ThenSucceeds() throws Exception {
        var props = new OutboundProxyProperties(
                true, "127.0.0.1", proxy.port(), USERNAME, PASSWORD, List.of());
        RemoteHttpClient client = clientWith(props);

        // The upstream host is fictional on purpose: for plain-http proxying the
        // client must NOT resolve it but send the absolute URI to the proxy.
        RemoteHttpClient.RemoteResponse response =
                client.fetch("http://upstream.invalid/org/example/artifact-1.0.jar", Map.of());

        assertEquals(200, response.statusCode());
        assertEquals("proxied-content", new String(response.body().readAllBytes(), StandardCharsets.UTF_8));

        // The proxy must have challenged once (407) and then accepted Basic credentials.
        assertTrue(proxy.unauthorizedRequests.get() >= 1, "proxy should have sent at least one 407");
        assertEquals(1, proxy.authorizedRequests.get());
        String expected = "Basic " + Base64.getEncoder()
                .encodeToString((USERNAME + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, proxy.lastAuthorizationHeader);
        assertTrue(proxy.requestLines.stream()
                        .anyMatch(line -> line.startsWith("GET http://upstream.invalid/")),
                "client should send the absolute URI to the proxy");
    }

    @Test
    void fetchWithoutCredentials_surfaces407ToCaller() throws Exception {
        var props = new OutboundProxyProperties(
                true, "127.0.0.1", proxy.port(), null, null, List.of());
        RemoteHttpClient client = clientWith(props);

        RemoteHttpClient.RemoteResponse response =
                client.fetch("http://upstream.invalid/org/example/artifact-1.0.jar", Map.of());

        // No credentials configured -> the proxy's 407 is passed through (the
        // pre-existing customer symptom, now fixable via megarepo.outbound-proxy.*).
        assertEquals(407, response.statusCode());
    }

    @Test
    void nonProxyHosts_bypassesProxyEntirely() throws Exception {
        HttpServer directServer = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        directServer.createContext("/direct", exchange -> {
            byte[] body = "direct-content".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        directServer.start();
        try {
            var props = new OutboundProxyProperties(
                    true, "127.0.0.1", proxy.port(), USERNAME, PASSWORD,
                    List.of("localhost", "127.0.0.1"));
            RemoteHttpClient client = clientWith(props);

            RemoteHttpClient.RemoteResponse response = client.fetch(
                    "http://127.0.0.1:" + directServer.getAddress().getPort() + "/direct", Map.of());

            assertEquals(200, response.statusCode());
            assertEquals("direct-content", new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
            assertEquals(0, proxy.connections.get(), "proxy must not see bypassed requests");
        } finally {
            directServer.stop(0);
        }
    }

    /**
     * Minimal authenticating HTTP forward proxy for tests: handles plain (absolute-URI)
     * proxying, answers 407 with a Basic challenge until correct credentials arrive,
     * then fabricates a 200 response itself (no real upstream needed).
     */
    private static final class MiniAuthProxy implements Closeable {

        private final String expectedAuthorization;
        private ServerSocket serverSocket;
        private Thread acceptThread;
        private volatile boolean running = true;

        final AtomicInteger connections = new AtomicInteger();
        final AtomicInteger unauthorizedRequests = new AtomicInteger();
        final AtomicInteger authorizedRequests = new AtomicInteger();
        final List<String> requestLines = new CopyOnWriteArrayList<>();
        volatile String lastAuthorizationHeader;

        MiniAuthProxy(String username, String password) {
            this.expectedAuthorization = "Basic " + Base64.getEncoder()
                    .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        }

        void start() throws IOException {
            serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            acceptThread = new Thread(this::acceptLoop, "mini-auth-proxy");
            acceptThread.setDaemon(true);
            acceptThread.start();
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        private void acceptLoop() {
            while (running) {
                try (Socket socket = serverSocket.accept()) {
                    connections.incrementAndGet();
                    handle(socket);
                } catch (IOException e) {
                    if (running) {
                        // Unexpected; surface in test output
                        e.printStackTrace();
                    }
                    return;
                }
            }
        }

        private void handle(Socket socket) throws IOException {
            var reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            OutputStream out = socket.getOutputStream();

            String requestLine = reader.readLine();
            if (requestLine == null) {
                return;
            }
            requestLines.add(requestLine);

            String proxyAuthorization = null;
            String header;
            while ((header = reader.readLine()) != null && !header.isEmpty()) {
                if (header.regionMatches(true, 0, "Proxy-Authorization:", 0, 20)) {
                    proxyAuthorization = header.substring(20).trim();
                }
            }

            if (expectedAuthorization.equals(proxyAuthorization)) {
                authorizedRequests.incrementAndGet();
                lastAuthorizationHeader = proxyAuthorization;
                byte[] body = "proxied-content".getBytes(StandardCharsets.UTF_8);
                out.write(("""
                        HTTP/1.1 200 OK\r
                        Content-Type: text/plain\r
                        Content-Length: %d\r
                        Connection: close\r
                        \r
                        """.formatted(body.length)).getBytes(StandardCharsets.ISO_8859_1));
                out.write(body);
            } else {
                unauthorizedRequests.incrementAndGet();
                out.write("""
                        HTTP/1.1 407 Proxy Authentication Required\r
                        Proxy-Authenticate: Basic realm="megarepo-test"\r
                        Content-Length: 0\r
                        Connection: close\r
                        \r
                        """.getBytes(StandardCharsets.ISO_8859_1));
            }
            out.flush();
        }

        @Override
        public void close() {
            running = false;
            try {
                serverSocket.close();
            } catch (IOException ignored) {
                // best effort
            }
            try {
                acceptThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
