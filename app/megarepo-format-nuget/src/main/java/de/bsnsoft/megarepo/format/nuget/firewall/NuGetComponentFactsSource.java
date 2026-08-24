package de.bsnsoft.megarepo.format.nuget.firewall;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.packageurl.PackageURL;
import de.bsnsoft.megarepo.repository.firewall.facts.ComponentFactsHttpClient;
import de.bsnsoft.megarepo.repository.firewall.facts.ComponentFactsSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Publication date and declared license for {@code pkg:nuget}, from the V3
 * registration leaf.
 *
 * <h2>Two documents, because NuGet splits the two facts</h2>
 *
 * The registration leaf ({@code {id}/{version}.json}) carries {@code published};
 * the license lives on the catalog entry, which the leaf references. Where the
 * leaf inlines the catalog entry — the service does both, depending on the
 * resource — only one request is made.
 *
 * <p>An unlisted package is published at {@code 1900-01-01}, NuGet's sentinel
 * for "delisted". It is reported as no date at all rather than as a
 * 125-year-old package, because a MIN_AGE rule reading the sentinel would wave
 * through exactly the packages an author pulled.
 *
 * <h2>License fields</h2>
 *
 * {@code licenseExpression} is SPDX and is used as-is. {@code licenseUrl} is the
 * deprecated predecessor and is kept as the declaration when there is no
 * expression: a URL is a poor identifier, but it is what the package declared,
 * and dropping it would make a package with a license look unlicensed to a
 * deny-by-default policy.
 */
@Component
public class NuGetComponentFactsSource implements ComponentFactsSource {

    private static final Logger log = LoggerFactory.getLogger(NuGetComponentFactsSource.class);

    /** Short id written to {@code firewall_component_facts.source}. */
    static final String SOURCE_ID = "nuget-registration";

    static final String DEFAULT_BASE_URL = "https://api.nuget.org/v3/registration5-gz-semver2/";

    /** NuGet's "this package is unlisted" publication date. */
    static final int UNLISTED_YEAR = 1900;

    private final ComponentFactsHttpClient http;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public NuGetComponentFactsSource(
            ComponentFactsHttpClient http,
            ObjectMapper objectMapper,
            @Value("${megarepo.firewall.facts.nuget.base-url:" + DEFAULT_BASE_URL + "}") String baseUrl) {
        this.http = http;
        this.objectMapper = objectMapper;
        this.baseUrl = withTrailingSlash(baseUrl);
    }

    @Override
    public String purlType() {
        return PackageURL.StandardTypes.NUGET;
    }

    @Override
    public Optional<ResolvedFacts> resolve(PackageURL purl) throws ComponentFactsException {
        String id = purl.getName();
        String version = purl.getVersion();
        if (id == null || id.isBlank() || version == null || version.isBlank()) {
            return Optional.empty();
        }

        // V3 lowercases both segments in every URL it builds; the purl already
        // lowercases the id, the version is lowercased here for the same reason.
        String url = baseUrl
                + id.toLowerCase(Locale.ROOT) + "/"
                + version.toLowerCase(Locale.ROOT) + ".json";

        JsonNode leaf = fetchJson(url, "registration leaf for " + id + " " + version);
        if (leaf == null) {
            return Optional.empty();
        }

        Instant published = publishedAt(leaf.path("published"));
        JsonNode catalogEntry = leaf.path("catalogEntry");
        if (catalogEntry.isTextual()) {
            JsonNode entry = fetchJson(catalogEntry.asText(), "catalog entry for " + id + " " + version);
            if (entry != null) {
                catalogEntry = entry;
                if (published == null) {
                    published = publishedAt(catalogEntry.path("published"));
                }
            } else {
                catalogEntry = objectMapper.createObjectNode();
            }
        }

        List<String> licenses = licenses(catalogEntry);
        return Optional.of(new ResolvedFacts(
                published,
                licenses,
                licenses.isEmpty() ? null : ResolvedFacts.UPSTREAM_REGISTRY,
                SOURCE_ID));
    }

    /** @return the parsed document, or {@code null} for a 404 */
    private JsonNode fetchJson(String url, String what) throws ComponentFactsException {
        ComponentFactsHttpClient.Response response = http.get(url, Map.of("Accept", "application/json"));
        if (response.isNotFound()) {
            return null;
        }
        if (!response.isSuccess()) {
            throw new ComponentFactsException(
                    "NuGet answered HTTP %d for the %s".formatted(response.statusCode(), what));
        }
        try {
            return objectMapper.readTree(response.body());
        } catch (Exception e) {
            throw new ComponentFactsException("The NuGet %s is not JSON".formatted(what), e);
        }
    }

    static Instant publishedAt(JsonNode published) {
        if (published == null || !published.isTextual()) {
            return null;
        }
        try {
            OffsetDateTime parsed = OffsetDateTime.parse(published.asText());
            if (parsed.getYear() <= UNLISTED_YEAR) {
                return null;
            }
            return parsed.toInstant();
        } catch (DateTimeParseException e) {
            log.debug("Unparseable NuGet publication date '{}'", published.asText());
            return null;
        }
    }

    static List<String> licenses(JsonNode catalogEntry) {
        List<String> declared = new ArrayList<>();
        String expression = text(catalogEntry.path("licenseExpression"));
        if (expression != null) {
            declared.add(expression);
            return declared;
        }
        String licenseUrl = text(catalogEntry.path("licenseUrl"));
        if (licenseUrl != null) {
            declared.add(licenseUrl);
        }
        return declared;
    }

    private static String text(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        String trimmed = node.asText().trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String withTrailingSlash(String value) {
        String trimmed = value == null || value.isBlank() ? DEFAULT_BASE_URL : value.trim();
        return trimmed.endsWith("/") ? trimmed : trimmed + "/";
    }
}
