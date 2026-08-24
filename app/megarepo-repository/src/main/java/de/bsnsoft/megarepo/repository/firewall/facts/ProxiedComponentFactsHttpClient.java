package de.bsnsoft.megarepo.repository.firewall.facts;

import de.bsnsoft.megarepo.repository.firewall.facts.ComponentFactsSource.ComponentFactsException;
import de.bsnsoft.megarepo.repository.proxy.RemoteHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;

/**
 * The production {@link ComponentFactsHttpClient}: every upstream metadata fetch
 * on {@code RemoteHttpClient}'s client, and therefore through
 * {@code megarepo.outbound-proxy}.
 *
 * <p>{@link RemoteHttpClient#upstreamHttpClient()} rather than
 * {@link RemoteHttpClient#fetch} because this caller needs the
 * {@code Last-Modified} header, which {@code RemoteResponse} does not carry —
 * the same reason the GHSA advisory source reaches for it. The client is asked
 * for per request, never cached in a field, so a proxy change made in the UI
 * after startup reaches the resolver.
 *
 * <p>Bodies are read into memory with a hard cap. These are metadata documents,
 * but an npm packument for a popular package is measured in megabytes and an
 * upstream that answers a resolver request with a gigabyte is a background job
 * that takes the heap down with it.
 *
 * <p>{@code @Lazy} because this is the firewall's only bean that reaches outside
 * the firewall package for a collaborator. Several narrow test contexts scan
 * {@code repository.firewall} without {@code repository.proxy}, and an eagerly
 * built egress client would make each of them fail on a dependency nothing in
 * them uses. Nothing is deferred that matters: the only injection points are the
 * per-format facts sources, which exist solely to be called from the resolver's
 * background pool.
 */
@Lazy
@Component
public class ProxiedComponentFactsHttpClient implements ComponentFactsHttpClient {

    private static final Logger log = LoggerFactory.getLogger(ProxiedComponentFactsHttpClient.class);

    /** Metadata documents are text; anything past this is not one. */
    static final int MAX_BODY_BYTES = 32 * 1024 * 1024;

    private final RemoteHttpClient remoteHttpClient;
    private final Duration requestTimeout;

    public ProxiedComponentFactsHttpClient(
            RemoteHttpClient remoteHttpClient, ComponentFactsProperties properties) {
        this.remoteHttpClient = remoteHttpClient;
        this.requestTimeout = properties.requestTimeout();
    }

    @Override
    public Response get(String url, Map<String, String> headers) throws ComponentFactsException {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            // Not retryable in any useful sense, but the resolver's contract has
            // exactly one failure channel and a malformed URL is a source bug
            // that must not take the pool down.
            throw new ComponentFactsException("Not a usable metadata URL: " + url, e);
        }

        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(uri)
                .GET()
                .timeout(requestTimeout)
                .header("Accept-Encoding", "gzip");
        if (headers != null) {
            headers.forEach(request::header);
        }

        HttpClient client = remoteHttpClient.upstreamHttpClient();
        try {
            HttpResponse<InputStream> response =
                    client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            String body = readBody(response, url);
            return new Response(response.statusCode(), body, lastModified(response));
        } catch (IOException e) {
            throw new ComponentFactsException("Could not read component metadata from " + url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ComponentFactsException("Interrupted while reading component metadata from " + url, e);
        }
    }

    private static String readBody(HttpResponse<InputStream> response, String url)
            throws IOException, ComponentFactsException {
        InputStream body = response.body();
        if (body == null) {
            return "";
        }
        boolean gzip = response.headers()
                .firstValue("Content-Encoding")
                .map(value -> value.trim().equalsIgnoreCase("gzip"))
                .orElse(false);
        try (InputStream stream = gzip ? new java.util.zip.GZIPInputStream(body) : body) {
            byte[] bytes = stream.readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) {
                throw new ComponentFactsException(
                        "Component metadata at %s exceeds %d bytes".formatted(url, MAX_BODY_BYTES));
            }
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /**
     * {@code Last-Modified} as an instant.
     *
     * <p>Silently absent when the header is missing or unparseable: a source that
     * uses it treats a missing publication date as a settled "cannot know", which
     * is a better answer than failing the whole resolution over a header.
     */
    private static Optional<Instant> lastModified(HttpResponse<?> response) {
        return response.headers()
                .firstValue("Last-Modified")
                .flatMap(ProxiedComponentFactsHttpClient::parseHttpDate);
    }

    static Optional<Instant> parseHttpDate(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(
                    ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
        } catch (DateTimeParseException e) {
            log.debug("Unparseable Last-Modified header: {}", value);
            return Optional.empty();
        }
    }
}
