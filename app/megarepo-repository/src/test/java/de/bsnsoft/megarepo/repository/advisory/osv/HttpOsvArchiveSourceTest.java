package de.bsnsoft.megarepo.repository.advisory.osv;

import de.bsnsoft.megarepo.repository.advisory.AdvisorySyncException;
import de.bsnsoft.megarepo.repository.proxy.OutboundProxyProperties;
import de.bsnsoft.megarepo.repository.proxy.RemoteHttpClient;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The egress side. The recording client below is a {@link RemoteHttpClient} subclass and
 * not a hand-rolled HTTP stub on purpose: it is the type that carries
 * {@code megarepo.outbound-proxy}, so a refactor that quietly swaps in a private
 * {@code HttpClient} stops compiling here rather than shipping a second, unproxied egress
 * path that only fails in the customer's network.
 *
 * <p>No request leaves the JVM: every call is answered from memory.
 */
class HttpOsvArchiveSourceTest {

    @Test
    void buildsTheBulkExportUrlPerEcosystem() {
        HttpOsvArchiveSource source =
                new HttpOsvArchiveSource(new RecordingClient(200, new byte[0]), "https://osv.example/");

        assertEquals("https://osv.example/Maven/all.zip", source.archiveUrl(OsvEcosystem.MAVEN));
        assertEquals("https://osv.example/npm/all.zip", source.archiveUrl(OsvEcosystem.NPM));
        assertEquals("https://osv.example/PyPI/all.zip", source.archiveUrl(OsvEcosystem.PYPI));
        assertEquals("https://osv.example/NuGet/all.zip", source.archiveUrl(OsvEcosystem.NUGET));
    }

    @Test
    void defaultsToTheOsvBucket() {
        HttpOsvArchiveSource source = new HttpOsvArchiveSource(new RecordingClient(200, new byte[0]), null);
        assertTrue(source.archiveUrl(OsvEcosystem.MAVEN).startsWith(HttpOsvArchiveSource.DEFAULT_BASE_URL));
    }

    @Test
    void goesThroughTheClientThatCarriesTheOutboundProxy() throws Exception {
        RecordingClient client = new RecordingClient(200, "zip bytes".getBytes(StandardCharsets.UTF_8));
        HttpOsvArchiveSource source = new HttpOsvArchiveSource(client, "https://osv.example");

        try (InputStream stream = source.openArchive(OsvEcosystem.NPM)) {
            assertEquals("zip bytes", new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }

        assertEquals(List.of("https://osv.example/npm/all.zip"), client.requested);
    }

    @Test
    void anErrorStatusIsASyncFailureRatherThanAnEmptyArchive() {
        HttpOsvArchiveSource source = new HttpOsvArchiveSource(
                new RecordingClient(503, "upstream busy".getBytes(StandardCharsets.UTF_8)),
                "https://osv.example");

        AdvisorySyncException thrown =
                assertThrows(AdvisorySyncException.class, () -> source.openArchive(OsvEcosystem.MAVEN));
        assertTrue(thrown.getMessage().contains("503"), thrown.getMessage());
    }

    @Test
    void aTransportFailureIsASyncFailure() {
        HttpOsvArchiveSource source = new HttpOsvArchiveSource(
                new RecordingClient(200, new byte[0]) {
                    @Override
                    public RemoteResponse fetch(String remoteUrl, Map<String, String> extraHeaders)
                            throws IOException {
                        throw new IOException("proxy refused the connection");
                    }
                },
                "https://osv.example");

        AdvisorySyncException thrown =
                assertThrows(AdvisorySyncException.class, () -> source.openArchive(OsvEcosystem.MAVEN));
        assertEquals("proxy refused the connection", thrown.getCause().getMessage());
    }

    /** A {@link RemoteHttpClient} that answers from memory and remembers what was asked. */
    private static class RecordingClient extends RemoteHttpClient {

        private final List<String> requested = new ArrayList<>();
        private final int statusCode;
        private final byte[] body;

        RecordingClient(int statusCode, byte[] body) {
            super(
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1),
                    "MegaRepo/test",
                    0,
                    OutboundProxyProperties.disabled());
            this.statusCode = statusCode;
            this.body = body;
        }

        @Override
        public RemoteResponse fetch(String remoteUrl, Map<String, String> extraHeaders) throws IOException {
            requested.add(remoteUrl);
            return new RemoteResponse(
                    statusCode, new ByteArrayInputStream(body), body.length, "application/zip");
        }
    }
}
