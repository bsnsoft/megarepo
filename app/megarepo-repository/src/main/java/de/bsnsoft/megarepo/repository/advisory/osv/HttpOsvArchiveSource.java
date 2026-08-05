package de.bsnsoft.megarepo.repository.advisory.osv;

import de.bsnsoft.megarepo.repository.advisory.AdvisorySyncException;
import de.bsnsoft.megarepo.repository.proxy.RemoteHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * Streams OSV's per-ecosystem bulk exports over {@link RemoteHttpClient}.
 *
 * <p>Going through {@code RemoteHttpClient} rather than a private {@code HttpClient} is
 * the point of this class: it is the one place where {@code megarepo.outbound-proxy} is
 * applied, including proxy authentication and the non-proxy-host bypass, and it picks up
 * a proxy change made in the UI without a restart. A second HTTP client next to it would
 * be a second egress path for an operator to discover the hard way — in a network where
 * direct egress is blocked, an advisory sync that quietly bypasses the proxy does not
 * fail loudly, it just never updates.
 *
 * <p>The archive URL is {@code <base>/<Ecosystem>/all.zip}. The ecosystem segment comes
 * from the {@link OsvEcosystem} enum, never from feed data, so no user- or
 * upstream-controlled string is ever spliced into the URL.
 */
@Component
public class HttpOsvArchiveSource implements OsvArchiveSource {

    private static final Logger log = LoggerFactory.getLogger(HttpOsvArchiveSource.class);

    /** OSV's public export bucket. */
    public static final String DEFAULT_BASE_URL = "https://osv-vulnerabilities.storage.googleapis.com";

    private final RemoteHttpClient httpClient;
    private final String baseUrl;

    public HttpOsvArchiveSource(
            RemoteHttpClient httpClient,
            @Value("${megarepo.firewall.advisory.osv.base-url:" + DEFAULT_BASE_URL + "}") String baseUrl) {
        this.httpClient = httpClient;
        this.baseUrl = stripTrailingSlash(baseUrl);
    }

    @Override
    public InputStream openArchive(OsvEcosystem ecosystem) throws AdvisorySyncException {
        String url = archiveUrl(ecosystem);
        log.debug("Fetching OSV export {}", url);
        RemoteHttpClient.RemoteResponse response;
        try {
            response = httpClient.fetch(url, Map.of("Accept", "application/zip"));
        } catch (IOException e) {
            throw new AdvisorySyncException("Could not reach the OSV export at " + url, e);
        }
        if (response.statusCode() != 200) {
            closeQuietly(response.body());
            throw new AdvisorySyncException(
                    "OSV export at %s returned HTTP %d".formatted(url, response.statusCode()));
        }
        InputStream body = response.body();
        if (body == null) {
            throw new AdvisorySyncException("OSV export at " + url + " returned an empty body");
        }
        return body;
    }

    /** Visible for tests. */
    String archiveUrl(OsvEcosystem ecosystem) {
        return baseUrl + "/" + ecosystem.osvName() + "/all.zip";
    }

    private static void closeQuietly(InputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException e) {
            log.debug("Could not close the OSV error response body", e);
        }
    }

    private static String stripTrailingSlash(String value) {
        String trimmed = value == null ? DEFAULT_BASE_URL : value.trim();
        if (trimmed.isEmpty()) {
            return DEFAULT_BASE_URL;
        }
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
