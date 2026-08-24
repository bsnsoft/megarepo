package de.bsnsoft.megarepo.format.nuget.firewall;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.packageurl.PackageURL;
import de.bsnsoft.megarepo.repository.firewall.facts.ComponentFactsHttpClient;
import de.bsnsoft.megarepo.repository.firewall.facts.ComponentFactsSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The NuGet facts source against recorded registration documents, offline.
 *
 * <p>NuGet splits the two facts across a registration leaf and a catalog entry
 * and inlines the second one only sometimes, so both shapes are pinned. So is
 * the {@code 1900-01-01} sentinel: reported as a real date it would make every
 * unlisted package look 125 years old and wave it straight past a MIN_AGE rule.
 */
class NuGetComponentFactsSourceTest {

    private static final String BASE = "https://api.nuget.org/v3/registration5-gz-semver2/";
    private static final String LEAF = BASE + "newtonsoft.json/13.0.1.json";
    private static final String CATALOG =
            "https://api.nuget.org/v3/catalog0/data/2021.03.08/newtonsoft.json.13.0.1.json";

    private StubHttp http;
    private NuGetComponentFactsSource source;

    @BeforeEach
    void setUp() {
        http = new StubHttp();
        source = new NuGetComponentFactsSource(http, new ObjectMapper(), BASE);
    }

    @Test
    @DisplayName("the leaf dates the version and the referenced catalog entry declares the license")
    void resolvesAcrossLeafAndCatalogEntry() throws Exception {
        http.respond(LEAF, """
                {
                  "catalogEntry": "%s",
                  "listed": true,
                  "published": "2021-03-08T18:22:41.28+00:00"
                }
                """.formatted(CATALOG));
        http.respond(CATALOG, """
                { "id": "Newtonsoft.Json", "version": "13.0.1", "licenseExpression": "MIT" }
                """);

        ComponentFactsSource.ResolvedFacts facts =
                source.resolve(new PackageURL("pkg:nuget/newtonsoft.json@13.0.1")).orElseThrow();

        assertThat(facts.publishedAt()).isEqualTo(Instant.parse("2021-03-08T18:22:41.28Z"));
        assertThat(facts.declaredLicenses()).containsExactly("MIT");
        assertThat(facts.licenseSource())
                .isEqualTo(ComponentFactsSource.ResolvedFacts.UPSTREAM_REGISTRY);
        assertThat(facts.source()).isEqualTo("nuget-registration");
        assertThat(http.requested).containsExactly(LEAF, CATALOG);
    }

    @Test
    @DisplayName("an inlined catalog entry costs one request, not two")
    void anInlinedCatalogEntryIsUsedDirectly() throws Exception {
        http.respond(LEAF, """
                {
                  "published": "2021-03-08T18:22:41.28+00:00",
                  "catalogEntry": { "licenseExpression": "MIT" }
                }
                """);

        ComponentFactsSource.ResolvedFacts facts =
                source.resolve(new PackageURL("pkg:nuget/newtonsoft.json@13.0.1")).orElseThrow();

        assertThat(facts.declaredLicenses()).containsExactly("MIT");
        assertThat(http.requested).containsExactly(LEAF);
    }

    @Test
    @DisplayName("the 1900 sentinel for an unlisted package is no date at all")
    void theUnlistedSentinelIsNotADate() throws Exception {
        http.respond(LEAF, """
                {
                  "published": "1900-01-01T00:00:00+00:00",
                  "listed": false,
                  "catalogEntry": { "licenseExpression": "MIT" }
                }
                """);

        ComponentFactsSource.ResolvedFacts facts =
                source.resolve(new PackageURL("pkg:nuget/newtonsoft.json@13.0.1")).orElseThrow();

        assertThat(facts.publishedAt())
                .as("reported as a date it would make an unlisted package look 125 years old")
                .isNull();
    }

    @Test
    @DisplayName("the deprecated licenseUrl is still a declaration")
    void licenseUrlIsKeptWhenThereIsNoExpression() throws Exception {
        http.respond(LEAF, """
                {
                  "published": "2016-01-01T00:00:00+00:00",
                  "catalogEntry": { "licenseUrl": "https://example.test/license" }
                }
                """);

        assertThat(source.resolve(new PackageURL("pkg:nuget/newtonsoft.json@13.0.1"))
                        .orElseThrow().declaredLicenses())
                .containsExactly("https://example.test/license");
    }

    @Test
    @DisplayName("a package that declares nothing resolves with an empty list and no license source")
    void nothingDeclaredStillResolves() throws Exception {
        http.respond(LEAF, """
                { "published": "2016-01-01T00:00:00+00:00", "catalogEntry": { } }
                """);

        ComponentFactsSource.ResolvedFacts facts =
                source.resolve(new PackageURL("pkg:nuget/newtonsoft.json@13.0.1")).orElseThrow();

        assertThat(facts.declaredLicenses()).isEmpty();
        assertThat(facts.licenseSource()).isNull();
        assertThat(facts.publishedAt()).isEqualTo(Instant.parse("2016-01-01T00:00:00Z"));
    }

    @Test
    @DisplayName("the id and the version are lowercased, the way V3 builds every URL")
    void urlsAreLowercased() throws Exception {
        http.respond(BASE + "acme.tooling/1.0.0-beta1.json", """
                { "published": "2022-02-02T00:00:00+00:00", "catalogEntry": { } }
                """);

        assertThat(source.resolve(new PackageURL("pkg:nuget/acme.tooling@1.0.0-Beta1"))).isPresent();
    }

    @Test
    @DisplayName("a 404 settles the row")
    void notFoundIsEmpty() throws Exception {
        http.respond(BASE + "gone/1.0.0.json", 404, "");

        assertThat(source.resolve(new PackageURL("pkg:nuget/gone@1.0.0"))).isEmpty();
    }

    @Test
    @DisplayName("a 5xx on the leaf is retryable")
    void serverErrorsAreRetryable() {
        http.respond(BASE + "busy/1.0.0.json", 502, "");

        assertThatThrownBy(() -> source.resolve(new PackageURL("pkg:nuget/busy@1.0.0")))
                .isInstanceOf(ComponentFactsSource.ComponentFactsException.class)
                .hasMessageContaining("502");
    }

    private static final class StubHttp implements ComponentFactsHttpClient {

        private final Map<String, Response> responses = new HashMap<>();
        private final List<String> requested = new ArrayList<>();

        void respond(String url, String body) {
            respond(url, 200, body);
        }

        void respond(String url, int status, String body) {
            responses.put(url, new Response(status, body, Optional.empty()));
        }

        @Override
        public Response get(String url, Map<String, String> headers) {
            requested.add(url);
            Response response = responses.get(url);
            if (response == null) {
                throw new AssertionError("Unstubbed upstream request: " + url);
            }
            return response;
        }
    }
}
