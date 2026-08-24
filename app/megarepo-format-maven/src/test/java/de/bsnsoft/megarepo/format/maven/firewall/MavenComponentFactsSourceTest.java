package de.bsnsoft.megarepo.format.maven.firewall;

import com.github.packageurl.PackageURL;
import de.bsnsoft.megarepo.core.storage.Blob;
import de.bsnsoft.megarepo.core.storage.BlobProperties;
import de.bsnsoft.megarepo.core.storage.BlobRef;
import de.bsnsoft.megarepo.database.entity.AssetEntity;
import de.bsnsoft.megarepo.database.entity.RepositoryEntity;
import de.bsnsoft.megarepo.database.repository.RepositoryJpaRepository;
import de.bsnsoft.megarepo.repository.AssetService;
import de.bsnsoft.megarepo.repository.firewall.facts.ComponentFactsHttpClient;
import de.bsnsoft.megarepo.repository.firewall.facts.ComponentFactsProperties;
import de.bsnsoft.megarepo.repository.firewall.facts.ComponentFactsSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The Maven facts source against recorded POMs, never against a live repository.
 *
 * <p>Two things are being pinned here. The first is that a POM's
 * {@code <licenses>} block is the whole license answer and its absence is a fact
 * — "declares no license" is exactly what a deny-by-default policy is looking
 * for, and reporting it as "unavailable" would hide it. The second is that the
 * publication date comes off {@code Last-Modified}, because no Maven descriptor
 * records when it was released.
 */
class MavenComponentFactsSourceTest {

    private static final String PATH = "com/acme/util/1.0.0/util-1.0.0.pom";
    private static final String CENTRAL = "https://repo1.maven.org/maven2/";

    private static final String POM_WITH_LICENSE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.acme</groupId>
              <artifactId>util</artifactId>
              <version>1.0.0</version>
              <licenses>
                <license>
                  <name>Apache License, Version 2.0</name>
                  <url>https://www.apache.org/licenses/LICENSE-2.0.txt</url>
                </license>
              </licenses>
            </project>
            """;

    private static final String POM_WITHOUT_LICENSE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.acme</groupId>
              <artifactId>util</artifactId>
              <version>1.0.0</version>
            </project>
            """;

    private static final String POM_LOCAL_ONLY = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <groupId>com.acme</groupId>
              <artifactId>util</artifactId>
              <version>1.0.0</version>
              <licenses>
                <license><name>MIT</name></license>
              </licenses>
            </project>
            """;

    private StubHttp http;
    private RepositoryJpaRepository repositories;
    private AssetService assets;

    @BeforeEach
    void setUp() {
        http = new StubHttp();
        repositories = mock(RepositoryJpaRepository.class);
        when(repositories.findByFormat(anyString())).thenReturn(List.of());
        assets = mock(AssetService.class);
        when(assets.getAsset(any(), anyString())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("Last-Modified is the publication date and <licenses> is the declaration")
    void resolvesFromTheRemotePom() throws Exception {
        http.respond(CENTRAL + PATH, 200, POM_WITH_LICENSE,
                Optional.of(Instant.parse("2020-03-04T05:06:07Z")));

        ComponentFactsSource.ResolvedFacts facts = source(false).resolve(purl()).orElseThrow();

        assertThat(facts.publishedAt()).isEqualTo(Instant.parse("2020-03-04T05:06:07Z"));
        assertThat(facts.declaredLicenses()).containsExactly("Apache License, Version 2.0");
        assertThat(facts.licenseSource()).isEqualTo(ComponentFactsSource.ResolvedFacts.UPSTREAM_REGISTRY);
        assertThat(facts.source()).isEqualTo("maven-pom");
    }

    @Test
    @DisplayName("a POM with no <licenses> resolves with an empty list, which is not UNAVAILABLE")
    void aPomWithoutLicensesStillResolves() throws Exception {
        http.respond(CENTRAL + PATH, 200, POM_WITHOUT_LICENSE,
                Optional.of(Instant.parse("2015-01-01T00:00:00Z")));

        ComponentFactsSource.ResolvedFacts facts = source(false).resolve(purl()).orElseThrow();

        assertThat(facts.declaredLicenses()).isEmpty();
        assertThat(facts.publishedAt()).isEqualTo(Instant.parse("2015-01-01T00:00:00Z"));
    }

    @Test
    @DisplayName("no Last-Modified means no date — a settled 'cannot know', not a failure")
    void aMissingHeaderIsNotAFailure() throws Exception {
        http.respond(CENTRAL + PATH, 200, POM_WITH_LICENSE, Optional.empty());

        ComponentFactsSource.ResolvedFacts facts = source(false).resolve(purl()).orElseThrow();

        assertThat(facts.publishedAt()).isNull();
        assertThat(facts.declaredLicenses()).containsExactly("Apache License, Version 2.0");
    }

    @Test
    @DisplayName("a 404 with nothing stored locally is UNAVAILABLE, not a retry")
    void notFoundIsEmpty() throws Exception {
        http.respond(CENTRAL + PATH, 404, "", Optional.empty());

        assertThat(source(false).resolve(purl())).isEmpty();
    }

    @Test
    @DisplayName("a 5xx is retryable and says so")
    void serverErrorsAreRetryable() {
        http.respond(CENTRAL + PATH, 503, "", Optional.empty());

        assertThatThrownBy(() -> source(false).resolve(purl()))
                .isInstanceOf(ComponentFactsSource.ComponentFactsException.class)
                .hasMessageContaining("503");
    }

    @Test
    @DisplayName("with prefer-local-metadata the stored POM supplies the licenses")
    void theStoredPomIsPreferredForLicenses() throws Exception {
        givenStoredPom("maven-hosted", POM_LOCAL_ONLY);
        http.respond(CENTRAL + PATH, 200, POM_WITH_LICENSE,
                Optional.of(Instant.parse("2020-03-04T05:06:07Z")));

        ComponentFactsSource.ResolvedFacts facts = source(true).resolve(purl()).orElseThrow();

        assertThat(facts.declaredLicenses())
                .as("the artifact this instance serves, not what the remote says today")
                .containsExactly("MIT");
        assertThat(facts.licenseSource())
                .isEqualTo(ComponentFactsSource.ResolvedFacts.PACKAGE_METADATA);
        assertThat(facts.publishedAt())
                .as("a POM carries no release date, so the remote still supplies it")
                .isEqualTo(Instant.parse("2020-03-04T05:06:07Z"));
    }

    @Test
    @DisplayName("a hosted-only artifact resolves from the stored POM alone, without a date")
    void aHostedOnlyArtifactResolvesLocally() throws Exception {
        givenStoredPom("maven-hosted", POM_LOCAL_ONLY);
        http.respond(CENTRAL + PATH, 404, "", Optional.empty());

        ComponentFactsSource.ResolvedFacts facts = source(true).resolve(purl()).orElseThrow();

        assertThat(facts.declaredLicenses()).containsExactly("MIT");
        assertThat(facts.publishedAt()).isNull();
        assertThat(facts.licenseSource())
                .isEqualTo(ComponentFactsSource.ResolvedFacts.PACKAGE_METADATA);
    }

    @Test
    @DisplayName("prefer-local-metadata off does not read the stored POM at all")
    void theLocalLaneIsOptional() throws Exception {
        givenStoredPom("maven-hosted", POM_LOCAL_ONLY);
        http.respond(CENTRAL + PATH, 200, POM_WITH_LICENSE, Optional.empty());

        ComponentFactsSource.ResolvedFacts facts = source(false).resolve(purl()).orElseThrow();

        assertThat(facts.declaredLicenses()).containsExactly("Apache License, Version 2.0");
    }

    @Test
    @DisplayName("a versionless coordinate names no artifact and is never fetched")
    void aCoordinateWithoutAVersionIsNotLookedUp() throws Exception {
        PackageURL noVersion = new PackageURL("maven", "com.acme", "util", null, null, null);

        assertThat(source(false).resolve(noVersion))
                .as("facts are per published version; there is nothing to ask about")
                .isEmpty();
        assertThat(http.calls)
                .as("a coordinate that cannot name a file must not produce a request")
                .isZero();
    }

    @Test
    @DisplayName("a license element with only a url still counts as a declaration")
    void urlOnlyLicensesAreKept() {
        String pom = """
                <project><licenses><license>
                  <url>https://example.test/LICENSE</url>
                </license></licenses></project>
                """;

        assertThat(MavenComponentFactsSource.parseLicenses(pom))
                .containsExactly("https://example.test/LICENSE");
    }

    @Test
    @DisplayName("a POM that will not parse declares nothing readable rather than failing")
    void unparseablePomsDeclareNothing() {
        assertThat(MavenComponentFactsSource.parseLicenses("<project")).isEmpty();
        assertThat(MavenComponentFactsSource.parseLicenses("")).isEmpty();
    }

    private void givenStoredPom(String repositoryName, String pom) {
        RepositoryEntity repository = mock(RepositoryEntity.class);
        UUID id = UUID.randomUUID();
        when(repository.getId()).thenReturn(id);
        when(repository.getName()).thenReturn(repositoryName);
        when(repositories.findByFormat("maven2")).thenReturn(List.of(repository));

        AssetEntity asset = new AssetEntity();
        asset.setBlobRef("default@blob-1");
        when(assets.getAsset(eq(id), eq(PATH))).thenReturn(Optional.of(asset));
        when(assets.getAssetContent(asset)).thenReturn(Optional.of(new Blob(
                new BlobRef("default", "blob-1"),
                new ByteArrayInputStream(pom.getBytes(StandardCharsets.UTF_8)),
                new BlobProperties(pom.length(), "application/xml", Map.of(), Instant.now(), Map.of()))));
    }

    private MavenComponentFactsSource source(boolean preferLocal) {
        ComponentFactsProperties properties = new ComponentFactsProperties(
                true, preferLocal, 1, 100, Duration.ofSeconds(10), 5, Duration.ofDays(30));
        return new MavenComponentFactsSource(http, properties, repositories, assets, CENTRAL);
    }

    private static PackageURL purl() throws Exception {
        return new PackageURL("pkg:maven/com.acme/util@1.0.0");
    }

    /** Answers from a map; anything not stubbed is a test that asked for the internet. */
    private static final class StubHttp implements ComponentFactsHttpClient {

        private final Map<String, Response> responses = new HashMap<>();
        private int calls;

        void respond(String url, int status, String body, Optional<Instant> lastModified) {
            responses.put(url, new Response(status, body, lastModified));
        }

        @Override
        public Response get(String url, Map<String, String> headers) {
            calls++;
            Response response = responses.get(url);
            if (response == null) {
                throw new AssertionError("Unstubbed upstream request: " + url);
            }
            return response;
        }
    }
}
