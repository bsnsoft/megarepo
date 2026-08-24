package de.bsnsoft.megarepo.format.npm.firewall;

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
 * Publication date and declared license for {@code pkg:npm}, from the registry
 * packument.
 *
 * <p>npm is the one ecosystem that publishes both facts in a single document:
 * {@code time[<version>]} is the moment the version was published and
 * {@code versions[<version>].license} is what its {@code package.json} declared
 * at publish time. That is the same declaration the tarball carries, which is
 * why there is no local lane here — the {@code package.json} inside a
 * {@code .tgz} would have to be unpacked to say the same thing, and it could not
 * supply the date at all.
 *
 * <p>Three spellings of the license field are accepted because npm has used all
 * three: the current string ({@code "license": "MIT"}), the deprecated object
 * ({@code {"type": "MIT"}}) and the long-deprecated array ({@code "licenses":
 * [...]}). A registry full of packages published in 2013 is not a hypothetical,
 * and a LICENSE rule that reads "declares nothing" off a package that declared
 * MIT twelve years ago would block builds for a parsing gap.
 */
@Component
public class NpmComponentFactsSource implements ComponentFactsSource {

    private static final Logger log = LoggerFactory.getLogger(NpmComponentFactsSource.class);

    /** Short id written to {@code firewall_component_facts.source}. */
    static final String SOURCE_ID = "npm-registry";

    static final String DEFAULT_BASE_URL = "https://registry.npmjs.org/";

    private final ComponentFactsHttpClient http;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public NpmComponentFactsSource(
            ComponentFactsHttpClient http,
            ObjectMapper objectMapper,
            @Value("${megarepo.firewall.facts.npm.base-url:" + DEFAULT_BASE_URL + "}") String baseUrl) {
        this.http = http;
        this.objectMapper = objectMapper;
        this.baseUrl = withTrailingSlash(baseUrl);
    }

    @Override
    public String purlType() {
        return PackageURL.StandardTypes.NPM;
    }

    @Override
    public Optional<ResolvedFacts> resolve(PackageURL purl) throws ComponentFactsException {
        String name = fullName(purl);
        String version = purl.getVersion();
        if (name == null || version == null || version.isBlank()) {
            return Optional.empty();
        }

        String url = baseUrl + URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        ComponentFactsHttpClient.Response response = http.get(url, Map.of("Accept", "application/json"));
        if (response.isNotFound()) {
            return Optional.empty();
        }
        if (!response.isSuccess()) {
            throw new ComponentFactsException(
                    "npm registry answered HTTP %d for %s".formatted(response.statusCode(), name));
        }

        JsonNode packument;
        try {
            packument = objectMapper.readTree(response.body());
        } catch (Exception e) {
            // Not retryable in any meaningful sense, but a registry that answers
            // 200 with something that is not a packument is exactly the transient
            // "captive portal" failure worth another attempt.
            throw new ComponentFactsException("npm packument for " + name + " is not JSON", e);
        }

        JsonNode versionDoc = packument.path("versions").path(version);
        if (versionDoc.isMissingNode()) {
            // The package exists and this version does not — unpublished, or a
            // coordinate that was only ever hosted here. UNAVAILABLE rather than
            // "RESOLVED, declares no license": the registry has no facts about
            // this version, which is not the same statement as it having none.
            return Optional.empty();
        }

        return Optional.of(new ResolvedFacts(
                publishedAt(packument, version),
                licenses(versionDoc),
                ResolvedFacts.UPSTREAM_REGISTRY,
                SOURCE_ID));
    }

    /** {@code @scope/name} or {@code name}; the purl namespace already carries the {@code @}. */
    private static String fullName(PackageURL purl) {
        String name = purl.getName();
        if (name == null || name.isBlank()) {
            return null;
        }
        String namespace = purl.getNamespace();
        if (namespace == null || namespace.isBlank()) {
            return name;
        }
        String scope = namespace.startsWith("@") ? namespace : "@" + namespace;
        return scope + "/" + name;
    }

    private static Instant publishedAt(JsonNode packument, String version) {
        JsonNode time = packument.path("time").path(version);
        if (!time.isTextual()) {
            return null;
        }
        try {
            return Instant.parse(time.asText());
        } catch (DateTimeParseException e) {
            log.debug("Unparseable npm publish time '{}'", time.asText());
            return null;
        }
    }

    static List<String> licenses(JsonNode versionDoc) {
        List<String> declared = new ArrayList<>();
        JsonNode license = versionDoc.path("license");
        if (license.isTextual()) {
            add(declared, license.asText());
        } else if (license.isObject()) {
            add(declared, license.path("type").asText(null));
        } else if (license.isArray()) {
            license.forEach(entry -> add(declared,
                    entry.isTextual() ? entry.asText() : entry.path("type").asText(null)));
        }

        JsonNode legacy = versionDoc.path("licenses");
        if (legacy.isArray()) {
            legacy.forEach(entry -> add(declared,
                    entry.isTextual() ? entry.asText() : entry.path("type").asText(null)));
        } else if (legacy.isTextual()) {
            add(declared, legacy.asText());
        }
        return declared;
    }

    private static void add(List<String> licenses, String value) {
        if (value == null) {
            return;
        }
        String trimmed = value.trim();
        if (!trimmed.isEmpty() && !licenses.contains(trimmed)) {
            licenses.add(trimmed);
        }
    }

    private static String withTrailingSlash(String value) {
        String trimmed = value == null || value.isBlank() ? DEFAULT_BASE_URL : value.trim();
        return trimmed.endsWith("/") ? trimmed : trimmed + "/";
    }
}
