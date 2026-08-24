package de.bsnsoft.megarepo.format.npm.firewall;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.packageurl.PackageURL;
import de.bsnsoft.megarepo.repository.firewall.facts.ComponentFactsHttpClient;
import de.bsnsoft.megarepo.repository.firewall.facts.ComponentFactsSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The npm facts source against recorded packuments, offline.
 *
 * <p>npm is the one registry that states both facts in one document, so the
 * cases worth pinning are the ones where its own history gets in the way: three
 * spellings of the license field across a decade, and a version that the
 * packument does not list at all.
 */
class NpmComponentFactsSourceTest {

    private static final String REGISTRY = "https://registry.npmjs.org/";

    private StubHttp http;
    private NpmComponentFactsSource source;

    @BeforeEach
    void setUp() {
        http = new StubHttp();
        source = new NpmComponentFactsSource(http, new ObjectMapper(), REGISTRY);
    }

    @Test
    @DisplayName("time[version] is the publication date and license is the declaration")
    void resolvesFromThePackument() throws Exception {
        http.respond(REGISTRY + "left-pad", 200, """
                {
                  "name": "left-pad",
                  "time": {
                    "created": "2014-01-01T00:00:00.000Z",
                    "1.3.0": "2018-03-04T05:06:07.000Z"
                  },
                  "versions": {
                    "1.3.0": { "name": "left-pad", "version": "1.3.0", "license": "WTFPL" }
                  }
                }
                """);

        ComponentFactsSource.ResolvedFacts facts =
                source.resolve(new PackageURL("pkg:npm/left-pad@1.3.0")).orElseThrow();

        assertThat(facts.publishedAt()).isEqualTo(Instant.parse("2018-03-04T05:06:07Z"));
        assertThat(facts.declaredLicenses()).containsExactly("WTFPL");
        assertThat(facts.licenseSource())
                .isEqualTo(ComponentFactsSource.ResolvedFacts.UPSTREAM_REGISTRY);
        assertThat(facts.source()).isEqualTo("npm-registry");
    }

    @Test
    @DisplayName("the deprecated object and array license spellings are read too")
    void legacyLicenseSpellingsAreUnderstood() throws Exception {
        http.respond(REGISTRY + "old-object", 200, """
                {
                  "time": { "1.0.0": "2013-01-01T00:00:00.000Z" },
                  "versions": { "1.0.0": { "license": { "type": "BSD-2-Clause", "url": "x" } } }
                }
                """);
        http.respond(REGISTRY + "old-array", 200, """
                {
                  "time": { "1.0.0": "2013-01-01T00:00:00.000Z" },
                  "versions": { "1.0.0": { "licenses": [ { "type": "MIT" }, { "type": "Apache-2.0" } ] } }
                }
                """);

        assertThat(source.resolve(new PackageURL("pkg:npm/old-object@1.0.0"))
                        .orElseThrow().declaredLicenses())
                .containsExactly("BSD-2-Clause");
        assertThat(source.resolve(new PackageURL("pkg:npm/old-array@1.0.0"))
                        .orElseThrow().declaredLicenses())
                .containsExactly("MIT", "Apache-2.0");
    }

    @Test
    @DisplayName("a package that declares nothing resolves with an empty list")
    void aPackageWithoutALicenseStillResolves() throws Exception {
        http.respond(REGISTRY + "bare", 200, """
                {
                  "time": { "1.0.0": "2020-01-01T00:00:00.000Z" },
                  "versions": { "1.0.0": { "name": "bare", "version": "1.0.0" } }
                }
                """);

        ComponentFactsSource.ResolvedFacts facts =
                source.resolve(new PackageURL("pkg:npm/bare@1.0.0")).orElseThrow();

        assertThat(facts.declaredLicenses()).isEmpty();
        assertThat(facts.publishedAt()).isEqualTo(Instant.parse("2020-01-01T00:00:00Z"));
    }

    @Test
    @DisplayName("a scoped package is asked for under its encoded name")
    void scopedPackagesAreEncoded() throws Exception {
        http.respond(REGISTRY + "%40acme%2Ftooling", 200, """
                {
                  "time": { "2.0.0": "2024-06-01T00:00:00.000Z" },
                  "versions": { "2.0.0": { "license": "MIT" } }
                }
                """);

        ComponentFactsSource.ResolvedFacts facts =
                source.resolve(new PackageURL("pkg:npm/%40acme/tooling@2.0.0")).orElseThrow();

        assertThat(facts.declaredLicenses()).containsExactly("MIT");
    }

    @Test
    @DisplayName("a version the packument does not list is UNAVAILABLE, not 'declares no license'")
    void anUnknownVersionIsEmpty() throws Exception {
        http.respond(REGISTRY + "left-pad", 200, """
                { "time": {}, "versions": { "1.3.0": { "license": "WTFPL" } } }
                """);

        assertThat(source.resolve(new PackageURL("pkg:npm/left-pad@9.9.9"))).isEmpty();
    }

    @Test
    @DisplayName("a 404 settles the row rather than retrying it forever")
    void notFoundIsEmpty() throws Exception {
        http.respond(REGISTRY + "gone", 404, "");

        assertThat(source.resolve(new PackageURL("pkg:npm/gone@1.0.0"))).isEmpty();
    }

    @Test
    @DisplayName("a rate limit is retryable")
    void rateLimitsAreRetryable() {
        http.respond(REGISTRY + "busy", 429, "");

        assertThatThrownBy(() -> source.resolve(new PackageURL("pkg:npm/busy@1.0.0")))
                .isInstanceOf(ComponentFactsSource.ComponentFactsException.class)
                .hasMessageContaining("429");
    }

    @Test
    @DisplayName("a 200 that is not a packument is retryable, not a parse crash")
    void garbageBodiesAreRetryable() {
        http.respond(REGISTRY + "captive", 200, "<html>sign in to the wifi</html>");

        assertThatThrownBy(() -> source.resolve(new PackageURL("pkg:npm/captive@1.0.0")))
                .isInstanceOf(ComponentFactsSource.ComponentFactsException.class);
    }

    private static final class StubHttp implements ComponentFactsHttpClient {

        private final Map<String, Response> responses = new HashMap<>();

        void respond(String url, int status, String body) {
            responses.put(url, new Response(status, body, Optional.empty()));
        }

        @Override
        public Response get(String url, Map<String, String> headers) {
            Response response = responses.get(url);
            if (response == null) {
                throw new AssertionError("Unstubbed upstream request: " + url);
            }
            return response;
        }
    }
}
