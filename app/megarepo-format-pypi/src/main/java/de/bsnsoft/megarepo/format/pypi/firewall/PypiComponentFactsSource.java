package de.bsnsoft.megarepo.format.pypi.firewall;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.packageurl.PackageURL;
import de.bsnsoft.megarepo.repository.firewall.facts.ComponentFactsHttpClient;
import de.bsnsoft.megarepo.repository.firewall.facts.ComponentFactsSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Publication date and declared license for {@code pkg:pypi}, from the JSON API
 * document for the exact version.
 *
 * <h2>PyPI declares its license in three places</h2>
 *
 * In descending order of how much it can be trusted to be an identifier:
 *
 * <ol>
 *   <li>{@code info.license_expression} — PEP 639, an SPDX expression. Present
 *       on anything published recently, and the only one of the three that is
 *       guaranteed to be a license identifier at all.</li>
 *   <li>{@code info.license} — free text. Usually {@code "MIT"}, but the field
 *       has no format and packages exist that paste their entire license text
 *       into it. Anything long or multi-line is therefore not treated as a
 *       declaration: a 12 kB "license identifier" in an allow-list comparison is
 *       noise, and the classifiers below say the same thing in a usable form.</li>
 *   <li>{@code info.classifiers} — the {@code License ::} trove classifiers.
 *       Verbatim, not translated: "MIT License" is what the package declared and
 *       inventing the SPDX id "MIT" from it would be this source deciding a
 *       question the LICENSE rule owns.</li>
 * </ol>
 *
 * <p>The date is the earliest {@code upload_time_iso_8601} across the version's
 * files. Earliest rather than latest because a wheel added for a new platform
 * three years later does not make the release younger, and a MIN_AGE rule that
 * could be reset by a late upload would be trivially defeatable.
 */
@Component
public class PypiComponentFactsSource implements ComponentFactsSource {

    private static final Logger log = LoggerFactory.getLogger(PypiComponentFactsSource.class);

    /** Short id written to {@code firewall_component_facts.source}. */
    static final String SOURCE_ID = "pypi-json";

    static final String DEFAULT_BASE_URL = "https://pypi.org/";

    /** Past this, {@code info.license} is a license text and not an identifier. */
    static final int MAX_LICENSE_FIELD = 100;

    private static final String CLASSIFIER_PREFIX = "License ::";

    private final ComponentFactsHttpClient http;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public PypiComponentFactsSource(
            ComponentFactsHttpClient http,
            ObjectMapper objectMapper,
            @Value("${megarepo.firewall.facts.pypi.base-url:" + DEFAULT_BASE_URL + "}") String baseUrl) {
        this.http = http;
        this.objectMapper = objectMapper;
        this.baseUrl = withTrailingSlash(baseUrl);
    }

    @Override
    public String purlType() {
        return PackageURL.StandardTypes.PYPI;
    }

    @Override
    public Optional<ResolvedFacts> resolve(PackageURL purl) throws ComponentFactsException {
        String name = purl.getName();
        String version = purl.getVersion();
        if (name == null || name.isBlank() || version == null || version.isBlank()) {
            return Optional.empty();
        }

        String url = baseUrl + "pypi/" + encode(name) + "/" + encode(version) + "/json";
        ComponentFactsHttpClient.Response response = http.get(url, Map.of("Accept", "application/json"));
        if (response.isNotFound()) {
            return Optional.empty();
        }
        if (!response.isSuccess()) {
            throw new ComponentFactsException(
                    "PyPI answered HTTP %d for %s %s".formatted(response.statusCode(), name, version));
        }

        JsonNode document;
        try {
            document = objectMapper.readTree(response.body());
        } catch (Exception e) {
            throw new ComponentFactsException(
                    "PyPI metadata for %s %s is not JSON".formatted(name, version), e);
        }

        return Optional.of(new ResolvedFacts(
                uploadedAt(document),
                licenses(document.path("info")),
                ResolvedFacts.UPSTREAM_REGISTRY,
                SOURCE_ID));
    }

    static Instant uploadedAt(JsonNode document) {
        JsonNode urls = document.path("urls");
        if (!urls.isArray()) {
            return null;
        }
        Instant earliest = null;
        for (JsonNode file : urls) {
            JsonNode uploaded = file.path("upload_time_iso_8601");
            if (!uploaded.isTextual()) {
                continue;
            }
            try {
                Instant candidate = Instant.parse(uploaded.asText());
                if (earliest == null || candidate.isBefore(earliest)) {
                    earliest = candidate;
                }
            } catch (DateTimeParseException e) {
                log.debug("Unparseable PyPI upload time '{}'", uploaded.asText());
            }
        }
        return earliest;
    }

    static List<String> licenses(JsonNode info) {
        List<String> declared = new ArrayList<>();

        String expression = text(info.path("license_expression"));
        if (expression != null) {
            declared.add(expression);
            return declared;
        }

        String free = text(info.path("license"));
        if (free != null && free.length() <= MAX_LICENSE_FIELD && free.indexOf('\n') < 0) {
            declared.add(free);
        }

        JsonNode classifiers = info.path("classifiers");
        if (classifiers.isArray()) {
            for (JsonNode classifier : classifiers) {
                String value = text(classifier);
                if (value == null || !value.startsWith(CLASSIFIER_PREFIX)) {
                    continue;
                }
                int lastSeparator = value.lastIndexOf("::");
                String tail = lastSeparator < 0 ? null : text(value.substring(lastSeparator + 2));
                // "License :: OSI Approved" is a category header, not a license.
                if (tail != null && !tail.equals("OSI Approved") && !declared.contains(tail)) {
                    declared.add(tail);
                }
            }
        }
        return declared;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String text(JsonNode node) {
        return node != null && node.isTextual() ? text(node.asText()) : null;
    }

    private static String text(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String withTrailingSlash(String value) {
        String trimmed = value == null || value.isBlank() ? DEFAULT_BASE_URL : value.trim();
        return trimmed.endsWith("/") ? trimmed : trimmed + "/";
    }
}
