package de.bsnsoft.megarepo.repository.firewall.facts;

import com.github.packageurl.PackageURL;
import de.bsnsoft.megarepo.core.firewall.FirewallFactsState;
import de.bsnsoft.megarepo.database.entity.FirewallComponentFactsEntity;
import de.bsnsoft.megarepo.repository.firewall.facts.ComponentFactsSource.ComponentFactsException;
import de.bsnsoft.megarepo.repository.firewall.facts.ComponentFactsSource.ResolvedFacts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How the background resolver settles a row.
 *
 * <p>The distinctions being asserted are the ones the two facts rules read:
 * {@code RESOLVED} with nothing declared is not {@code UNAVAILABLE}, an
 * ecosystem nobody answers for is settled rather than pending, and a failure is
 * retried a bounded number of times and then settled — because a row that is
 * retried forever holds a component in quarantine forever under a fail-closed
 * repository.
 */
class ComponentFactsResolverTest {

    private static final ComponentFactsProperties DEFAULTS = ComponentFactsProperties.defaults();

    private InMemoryComponentFacts table;

    @BeforeEach
    void setUp() {
        table = new InMemoryComponentFacts();
    }

    @Test
    @DisplayName("a package that declares no license resolves, and is not UNAVAILABLE")
    void resolvedWithoutLicensesIsSettledAndDistinct() {
        ComponentFactsResolver resolver = resolver(DEFAULTS, source("maven",
                purl -> Optional.of(new ResolvedFacts(
                        Instant.parse("2020-05-05T00:00:00Z"),
                        List.of(),
                        ResolvedFacts.UPSTREAM_REGISTRY,
                        "maven-pom"))));

        FirewallComponentFactsEntity row = resolve(resolver, "pkg:maven/com.acme/util@1.0.0");

        assertThat(row.getState()).isEqualTo(FirewallFactsState.RESOLVED);
        assertThat(row.getDeclaredLicenses()).isEmpty();
        assertThat(row.getPublishedAt()).isEqualTo(Instant.parse("2020-05-05T00:00:00Z"));
        assertThat(row.getErrorMessage()).isNull();
    }

    @Test
    @DisplayName("RESOLVED with no publication date is a settled answer, not a pending one")
    void resolvedWithoutADateIsStillSettled() {
        ComponentFactsResolver resolver = resolver(DEFAULTS, source("maven",
                purl -> Optional.of(new ResolvedFacts(
                        null, List.of("Apache-2.0"), ResolvedFacts.PACKAGE_METADATA, "maven-pom"))));

        FirewallComponentFactsEntity row = resolve(resolver, "pkg:maven/com.acme/util@1.0.0");

        assertThat(row.getState()).isEqualTo(FirewallFactsState.RESOLVED);
        assertThat(row.getState().isSettled()).isTrue();
        assertThat(row.getState().isIndeterminate()).isFalse();
        assertThat(row.getPublishedAt()).isNull();
        assertThat(row.getLicenseSource()).isEqualTo("PACKAGE_METADATA");
    }

    @Test
    @DisplayName("an ecosystem that publishes nothing for the version settles UNAVAILABLE")
    void emptyAnswerSettlesUnavailable() {
        ComponentFactsResolver resolver = resolver(DEFAULTS, source("npm", purl -> Optional.empty()));

        FirewallComponentFactsEntity row = resolve(resolver, "pkg:npm/left-pad@0.0.0-never");

        assertThat(row.getState()).isEqualTo(FirewallFactsState.UNAVAILABLE);
        assertThat(row.getFetchedAt()).isNotNull();
    }

    @Test
    @DisplayName("a purl type no source claims settles UNAVAILABLE rather than staying indeterminate")
    void unknownPurlTypeSettlesUnavailable() {
        ComponentFactsResolver resolver = resolver(DEFAULTS, source("maven", purl -> Optional.empty()));

        FirewallComponentFactsEntity row = resolve(resolver, "pkg:generic/some-blob@1");

        assertThat(row.getState())
                .as("a raw file has no ecosystem to ask; leaving it UNKNOWN would quarantine it "
                        + "forever under a fail-closed repository")
                .isEqualTo(FirewallFactsState.UNAVAILABLE);
        assertThat(row.getErrorMessage()).contains("no component-facts source");
    }

    @Test
    @DisplayName("a failing source marks an attempt and leaves the row unresolved")
    void aFailureIsCountedAndRetried() {
        ComponentFactsResolver resolver = resolver(DEFAULTS, source("npm", purl -> {
            throw new ComponentFactsException("registry returned 503");
        }));

        FirewallComponentFactsEntity row = resolve(resolver, "pkg:npm/left-pad@1.3.0");

        assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(row.getState()).isEqualTo(FirewallFactsState.UNKNOWN);
        assertThat(row.getState().isIndeterminate()).isTrue();
        assertThat(row.getErrorMessage()).contains("503");
        assertThat(row.getFetchedAt())
                .as("nothing was fetched, so the staleness sweep must not treat it as fresh")
                .isNull();
    }

    @Test
    @DisplayName("after max-attempts the row settles UNAVAILABLE instead of being retried forever")
    void giveUpAfterMaxAttempts() {
        ComponentFactsProperties threeAttempts = new ComponentFactsProperties(
                true, false, 1, 100, Duration.ofSeconds(10), 3, Duration.ofDays(30));
        ComponentFactsResolver resolver = resolver(threeAttempts, source("npm", purl -> {
            throw new ComponentFactsException("still 503");
        }));

        FirewallComponentFactsEntity row = null;
        for (int i = 0; i < 3; i++) {
            row = resolve(resolver, "pkg:npm/left-pad@1.3.0");
        }

        assertThat(row).isNotNull();
        assertThat(row.getAttempts()).isEqualTo(3);
        assertThat(row.getState()).isEqualTo(FirewallFactsState.UNAVAILABLE);
        assertThat(row.getFetchedAt()).isNotNull();
    }

    @Test
    @DisplayName("a source that throws an unchecked exception does not take the other ecosystems down")
    void oneBrokenSourceDoesNotStopTheOthers() {
        ComponentFactsResolver resolver = resolver(
                DEFAULTS,
                source("npm", purl -> {
                    throw new IllegalStateException("NPE in a format module");
                }),
                source("maven", purl -> Optional.of(new ResolvedFacts(
                        Instant.parse("2019-01-01T00:00:00Z"),
                        List.of("MIT"),
                        ResolvedFacts.UPSTREAM_REGISTRY,
                        "maven-pom"))));

        FirewallComponentFactsEntity broken = resolve(resolver, "pkg:npm/left-pad@1.3.0");
        FirewallComponentFactsEntity healthy = resolve(resolver, "pkg:maven/com.acme/util@1.0.0");

        assertThat(broken.getAttempts()).isEqualTo(1);
        assertThat(broken.getState()).isEqualTo(FirewallFactsState.UNKNOWN);
        assertThat(healthy.getState()).isEqualTo(FirewallFactsState.RESOLVED);
        assertThat(healthy.getDeclaredLicenses()).containsExactly("MIT");
    }

    @Test
    @DisplayName("a license source the schema forbids is dropped, not written")
    void unsupportedLicenseSourceIsDropped() {
        ComponentFactsResolver resolver = resolver(DEFAULTS, source("maven",
                purl -> Optional.of(new ResolvedFacts(
                        null, List.of("MIT"), "FILE_CONTENTS", "maven-scan"))));

        FirewallComponentFactsEntity row = resolve(resolver, "pkg:maven/com.acme/util@1.0.0");

        assertThat(row.getLicenseSource())
                .as("declared metadata only is a scope promise with a CHECK constraint behind it")
                .isNull();
        assertThat(row.getDeclaredLicenses()).containsExactly("MIT");
        assertThat(row.getState()).isEqualTo(FirewallFactsState.RESOLVED);
    }

    @Test
    @DisplayName("something that is not a purl can never resolve and is settled at once")
    void nonPurlCoordinatesSettleUnavailable() {
        ComponentFactsResolver resolver = resolver(DEFAULTS, source("maven", purl -> Optional.empty()));

        FirewallComponentFactsEntity row = resolve(resolver, "sha256:deadbeef");

        assertThat(row.getState()).isEqualTo(FirewallFactsState.UNAVAILABLE);
    }

    @Test
    @DisplayName("aliases are indexed alongside the primary purl type")
    void aliasesAreIndexed() {
        ComponentFactsSource aliased = new StubSource(
                "docker", Set.of("oci"), purl -> Optional.empty());
        ComponentFactsResolver resolver = resolver(DEFAULTS, aliased);

        assertThat(resolver.supportedPurlTypes()).containsExactlyInAnyOrder("docker", "oci");
        assertThat(resolver.sourceFor("OCI")).isSameAs(aliased);
    }

    @Test
    @DisplayName("two sources claiming one purl type is a startup failure, not a coin toss")
    void duplicateSourcesAreRejected() {
        assertThatThrownBy(() -> resolver(
                        DEFAULTS,
                        source("npm", purl -> Optional.empty()),
                        source("npm", purl -> Optional.empty())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("npm");
    }

    @Test
    @DisplayName("the sweep queues unresolved rows first and stale settled rows after")
    void sweepQueuesUnresolvedAndStale() {
        table.given("pkg:npm/a@1.0.0", "npm", FirewallFactsState.UNKNOWN);
        table.given("pkg:npm/b@1.0.0", "npm", FirewallFactsState.PENDING);
        FirewallComponentFactsEntity fresh =
                table.given("pkg:npm/c@1.0.0", "npm", FirewallFactsState.RESOLVED);
        fresh.setFetchedAt(Instant.now());
        FirewallComponentFactsEntity stale =
                table.given("pkg:npm/d@1.0.0", "npm", FirewallFactsState.RESOLVED);
        stale.setFetchedAt(Instant.now().minus(Duration.ofDays(400)));

        ComponentFactsResolver resolver = resolver(DEFAULTS, source("npm", purl -> Optional.empty()));

        assertThat(resolver.sweep()).isEqualTo(3);
        assertThat(resolver.queueDepth()).isEqualTo(3);
    }

    @Test
    @DisplayName("disabled: nothing is queued and nothing is swept")
    void disabledResolverIsInert() {
        ComponentFactsProperties off = new ComponentFactsProperties(
                false, true, 2, 100, Duration.ofSeconds(10), 5, Duration.ofDays(30));
        table.given("pkg:npm/a@1.0.0", "npm", FirewallFactsState.UNKNOWN);
        ComponentFactsResolver resolver = resolver(off, source("npm", purl -> Optional.empty()));

        resolver.enqueue("pkg:npm/a@1.0.0");

        assertThat(resolver.queueDepth()).isZero();
        assertThat(resolver.sweep()).isZero();
    }

    @Test
    @DisplayName("a source that hangs costs one attempt, not a drain thread")
    void requestTimeoutIsEnforcedFromOutsideTheSource() {
        ComponentFactsProperties impatient = new ComponentFactsProperties(
                true, false, 1, 100, Duration.ofMillis(50), 5, Duration.ofDays(30));
        ComponentFactsResolver resolver = resolver(impatient, source("npm", purl -> {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        }));
        resolver.start();
        try {
            FirewallComponentFactsEntity row = resolve(resolver, "pkg:npm/slow@1.0.0");

            assertThat(row.getAttempts()).isEqualTo(1);
            assertThat(row.getState()).isEqualTo(FirewallFactsState.UNKNOWN);
            assertThat(row.getErrorMessage()).contains("Timed out");
        } finally {
            resolver.stop();
        }
    }

    private ComponentFactsResolver resolver(
            ComponentFactsProperties properties, ComponentFactsSource... sources) {
        return new ComponentFactsResolver(table.repository(), properties, List.of(sources));
    }

    private FirewallComponentFactsEntity resolve(ComponentFactsResolver resolver, String coordinates) {
        return resolver.resolveNow(coordinates).orElseThrow();
    }

    private static ComponentFactsSource source(
            String purlType, ThrowingResolver resolver) {
        return new StubSource(purlType, Set.of(), resolver);
    }

    @FunctionalInterface
    private interface ThrowingResolver {
        Optional<ResolvedFacts> resolve(PackageURL purl) throws ComponentFactsException;
    }

    private record StubSource(String type, Set<String> aliases, ThrowingResolver delegate)
            implements ComponentFactsSource {

        @Override
        public String purlType() {
            return type;
        }

        @Override
        public Set<String> purlTypeAliases() {
            return aliases;
        }

        @Override
        public Optional<ResolvedFacts> resolve(PackageURL purl) throws ComponentFactsException {
            return delegate.resolve(purl);
        }
    }
}
