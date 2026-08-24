package de.bsnsoft.megarepo.repository.firewall.facts;

import com.github.packageurl.PackageURL;
import de.bsnsoft.megarepo.core.firewall.FirewallFactsState;
import de.bsnsoft.megarepo.database.entity.FirewallComponentFactsEntity;
import de.bsnsoft.megarepo.database.repository.FirewallComponentFactsJpaRepository;
import de.bsnsoft.megarepo.repository.firewall.identity.ComponentIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The request path's contract with the facts store.
 *
 * <p>Every test here is about one of two promises: a lookup does not fetch, and
 * a lookup does not throw. Both are the customer's constraints rather than
 * design preferences — the first is the 20 ms budget and "no outbound traffic
 * from a request thread", the second is "a firewall fault serves the artifact".
 */
class DefaultComponentFactsServiceTest {

    private InMemoryComponentFacts table;
    private ExplodingSource source;
    private ComponentFactsResolver resolver;
    private DefaultComponentFactsService service;

    @BeforeEach
    void setUp() {
        table = new InMemoryComponentFacts();
        source = new ExplodingSource();
        // Deliberately not started: nothing drains the queue, so its depth is an
        // observable fact rather than a race.
        resolver = new ComponentFactsResolver(
                table.repository(), ComponentFactsProperties.defaults(), List.of(source));
        service = new DefaultComponentFactsService(table.repository(), resolver);
    }

    @Test
    @DisplayName("a miss answers UNKNOWN and issues no outbound call whatsoever")
    void lookupNeverFetches() throws Exception {
        ComponentFacts facts = service.lookup(purl("pkg:maven/com.acme/util@1.0.0"));

        assertThat(facts.state()).isEqualTo(FirewallFactsState.UNKNOWN);
        assertThat(facts.isIndeterminate()).isTrue();
        assertThat(facts.publishedAt()).isNull();
        assertThat(facts.declaredLicenses()).isEmpty();
        assertThat(source.calls)
                .as("a facts lookup that resolves is a request thread waiting on a registry")
                .isZero();
    }

    @Test
    @DisplayName("a miss leaves the placeholder row the resolver works from")
    void lookupRecordsTheWorkItem() throws Exception {
        service.lookup(purl("pkg:maven/com.acme/util@1.0.0"));

        assertThat(table.row("pkg:maven/com.acme/util@1.0.0"))
                .get()
                .satisfies(row -> {
                    assertThat(row.getState()).isEqualTo(FirewallFactsState.UNKNOWN);
                    assertThat(row.getPurlType()).isEqualTo("maven");
                });
    }

    @Test
    @DisplayName("a resolved row is returned as it stands, empty licenses included")
    void lookupReturnsTheStoredFacts() throws Exception {
        FirewallComponentFactsEntity row =
                table.given("pkg:npm/left-pad@1.3.0", "npm", FirewallFactsState.RESOLVED);
        row.setPublishedAt(Instant.parse("2018-01-02T03:04:05Z"));
        row.setDeclaredLicenses(new String[] {"MIT"});
        row.setLicenseSource("UPSTREAM_REGISTRY");
        row.setSource("npm-registry");

        ComponentFacts facts = service.lookup(purl("pkg:npm/left-pad@1.3.0"));

        assertThat(facts.state()).isEqualTo(FirewallFactsState.RESOLVED);
        assertThat(facts.isSettled()).isTrue();
        assertThat(facts.declaredLicenses()).containsExactly("MIT");
        assertThat(facts.age(Instant.parse("2018-01-09T03:04:05Z")))
                .contains(java.time.Duration.ofDays(7));
    }

    @Test
    @DisplayName("requestResolution on every download of the same component queues it once")
    void requestResolutionIsIdempotent() throws Exception {
        ComponentIdentity identity = purl("pkg:pypi/requests@2.31.0");
        for (int i = 0; i < 50; i++) {
            service.requestResolution(identity);
        }

        assertThat(resolver.queueDepth())
                .as("one queue entry, not one per download")
                .isEqualTo(1);
        assertThat(table.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("a settled component is not re-queued by a download")
    void requestResolutionSkipsSettledRows() throws Exception {
        table.given("pkg:pypi/requests@2.31.0", "pypi", FirewallFactsState.RESOLVED);
        table.given("pkg:pypi/flask@3.0.0", "pypi", FirewallFactsState.UNAVAILABLE);

        service.requestResolution(purl("pkg:pypi/requests@2.31.0"));
        service.requestResolution(purl("pkg:pypi/flask@3.0.0"));

        assertThat(resolver.queueDepth())
                .as("a row resolved three years ago is refreshed by the staleness sweep, "
                        + "not by every request for it")
                .isZero();
    }

    @Test
    @DisplayName("an unsettled row is re-queued")
    void requestResolutionQueuesUnsettledRows() throws Exception {
        table.given("pkg:pypi/requests@2.31.0", "pypi", FirewallFactsState.UNKNOWN);

        service.requestResolution(purl("pkg:pypi/requests@2.31.0"));

        assertThat(resolver.queueDepth()).isEqualTo(1);
    }

    @Test
    @DisplayName("a hash or unidentified component has no ecosystem to ask")
    void nonPurlIdentitiesAnswerUnknownAndQueueNothing() {
        ComponentIdentity hash = ComponentIdentity.Hash.sha256("e3b0c442");
        ComponentIdentity unidentified =
                new ComponentIdentity.Unidentified("raw", null, "notes.txt", null);

        assertThat(service.lookup(hash).state()).isEqualTo(FirewallFactsState.UNKNOWN);
        assertThat(service.lookup(hash).purl()).isEqualTo(hash.key());
        assertThat(service.lookup(unidentified).state()).isEqualTo(FirewallFactsState.UNKNOWN);

        service.requestResolution(hash);
        service.requestResolution(unidentified);

        assertThat(resolver.queueDepth()).isZero();
        assertThat(table.size()).isZero();
    }

    @Test
    @DisplayName("lookupAll answers every requested identity key, hits and misses alike")
    void lookupAllIsNeverPartial() throws Exception {
        FirewallComponentFactsEntity known =
                table.given("pkg:maven/com.acme/util@1.0.0", "maven", FirewallFactsState.RESOLVED);
        known.setDeclaredLicenses(new String[] {"Apache-2.0"});

        ComponentIdentity plain = purl("pkg:maven/com.acme/util@1.0.0");
        ComponentIdentity sources = qualified("com.acme", "util", "1.0.0", "sources");
        ComponentIdentity missing = purl("pkg:npm/left-pad@1.3.0");
        ComponentIdentity hash = ComponentIdentity.Hash.sha256("deadbeef");

        Map<String, ComponentFacts> facts =
                service.lookupAll(List.of(plain, sources, missing, hash));

        assertThat(facts).containsOnlyKeys(plain.key(), sources.key(), missing.key(), hash.key());
        assertThat(facts.get(plain.key()).declaredLicenses()).containsExactly("Apache-2.0");
        assertThat(facts.get(sources.key()).declaredLicenses())
                .as("the sources jar and the main jar are two artifacts and one published version")
                .containsExactly("Apache-2.0");
        assertThat(facts.get(missing.key()).state()).isEqualTo(FirewallFactsState.UNKNOWN);
        assertThat(facts.get(hash.key()).state()).isEqualTo(FirewallFactsState.UNKNOWN);
        assertThat(source.calls).isZero();
    }

    @Test
    @DisplayName("lookupAll leaves a placeholder for each miss")
    void lookupAllRecordsMisses() throws Exception {
        service.lookupAll(List.of(purl("pkg:npm/a@1.0.0"), purl("pkg:npm/b@1.0.0")));

        assertThat(table.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("a facts table that is down answers UNKNOWN rather than failing the download")
    void aBrokenTableDegradesInsteadOfThrowing() throws Exception {
        FirewallComponentFactsJpaRepository broken = mock(FirewallComponentFactsJpaRepository.class);
        when(broken.findById(anyString())).thenThrow(new IllegalStateException("connection pool exhausted"));
        DefaultComponentFactsService degraded = new DefaultComponentFactsService(broken, resolver);

        ComponentFacts facts = degraded.lookup(purl("pkg:maven/com.acme/util@1.0.0"));

        assertThat(facts.state()).isEqualTo(FirewallFactsState.UNKNOWN);
        assertThat(facts.purl()).isEqualTo("pkg:maven/com.acme/util@1.0.0");
    }

    @Test
    @DisplayName("requestResolution never propagates a storage failure to the caller")
    void requestResolutionNeverThrows() throws Exception {
        FirewallComponentFactsJpaRepository broken = mock(FirewallComponentFactsJpaRepository.class);
        when(broken.findById(anyString())).thenThrow(new IllegalStateException("gone"));
        DefaultComponentFactsService degraded = new DefaultComponentFactsService(broken, resolver);

        degraded.requestResolution(purl("pkg:maven/com.acme/util@1.0.0"));
    }

    private static ComponentIdentity purl(String canonical) throws Exception {
        return new ComponentIdentity.Purl(new PackageURL(canonical));
    }

    private static ComponentIdentity qualified(
            String namespace, String name, String version, String classifier) throws Exception {
        TreeMap<String, String> qualifiers = new TreeMap<>();
        qualifiers.put("classifier", classifier);
        return new ComponentIdentity.Purl(
                new PackageURL("maven", namespace, name, version, qualifiers, null));
    }

    /** A source that fails the test if the request path ever reaches it. */
    private static final class ExplodingSource implements ComponentFactsSource {

        private int calls;

        @Override
        public String purlType() {
            return "maven";
        }

        @Override
        public Optional<ComponentFactsSource.ResolvedFacts> resolve(PackageURL purl) {
            calls++;
            throw new AssertionError("A component-facts lookup fetched from " + purl);
        }
    }
}
