package de.bsnsoft.megarepo.repository.firewall.exemption;

import com.github.packageurl.PackageURL;
import de.bsnsoft.megarepo.core.firewall.FirewallComponentKeyKind;
import de.bsnsoft.megarepo.core.firewall.FirewallExemptionScope;
import de.bsnsoft.megarepo.core.firewall.FirewallExemptionState;
import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;
import de.bsnsoft.megarepo.database.config.JpaConfig;
import de.bsnsoft.megarepo.database.entity.FirewallExemptionEntity;
import de.bsnsoft.megarepo.database.entity.RepositoryEntity;
import de.bsnsoft.megarepo.database.repository.FirewallExemptionJpaRepository;
import de.bsnsoft.megarepo.database.repository.RepositoryJpaRepository;
import de.bsnsoft.megarepo.repository.firewall.identity.ComponentIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ExemptionService} against the real migrated schema.
 *
 * <p>What is being tested here and not in the unit test is everything the store
 * decides: the partial index the request-path lookup rides on, the {@code IN}
 * over four key forms, the {@code TEXT[]} of advisory ids, and — the one that
 * matters most — that an exemption which lapsed at noon stops applying at noon
 * rather than at the next sweep. That last one is a claim about a query and a
 * clock together, and a mocked repository cannot make it.
 *
 * <p>Nothing here reaches the internet beyond pulling {@code postgres:16-alpine}.
 */
@SpringBootTest(
        classes = ExemptionDatabaseTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ExemptionDatabaseTest {

    private static final String USERNAME = "megarepo";
    private static final String PASSWORD = "megarepo";

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("megarepo")
                    .withUsername(USERNAME)
                    .withPassword(PASSWORD)
                    .withUrlParam("stringtype", "unspecified")
                    .waitingFor(Wait.forListeningPort());

    static {
        POSTGRES.start();
        awaitJdbcReady();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> USERNAME);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.flyway.placeholder-replacement", () -> "false");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.open-in-view", () -> "false");
    }

    @Autowired private ExemptionService service;
    @Autowired private FirewallExemptionJpaRepository exemptions;
    @Autowired private RepositoryJpaRepository repositories;

    private UUID repositoryA;
    private UUID repositoryB;

    @BeforeEach
    void reset() {
        exemptions.deleteAllInBatch();
        repositories.deleteAllInBatch();
        repositoryA = givenRepository("maven-central");
        repositoryB = givenRepository("maven-releases");
    }

    // ── Scope matrix ────────────────────────────────────────────────────

    @Test
    @DisplayName("scope matrix: version × component crossed with this repository × all of them")
    void scopeMatrix() throws Exception {
        UUID versionHere = approved("pkg:maven/com.acme/util@1.0.0", FirewallExemptionScope.VERSION, repositoryA);
        UUID componentHere = approved("pkg:maven/com.acme/util", FirewallExemptionScope.COMPONENT, repositoryA);
        UUID versionEverywhere = approved("pkg:maven/com.acme/util@1.0.0", FirewallExemptionScope.VERSION, null);
        UUID componentEverywhere = approved("pkg:maven/com.acme/util", FirewallExemptionScope.COMPONENT, null);

        Instant now = Instant.now();

        assertThat(idsOf(service.findApplicable(repositoryA, util("1.0.0"), now)))
                .as("the named version in the named repository is covered by all four")
                .containsExactlyInAnyOrder(
                        versionHere, componentHere, versionEverywhere, componentEverywhere);

        assertThat(idsOf(service.findApplicable(repositoryB, util("1.0.0"), now)))
                .as("another repository sees only the global ones")
                .containsExactlyInAnyOrder(versionEverywhere, componentEverywhere);

        assertThat(idsOf(service.findApplicable(repositoryA, util("2.0.0"), now)))
                .as("another version sees only the component-scoped ones")
                .containsExactlyInAnyOrder(componentHere, componentEverywhere);

        assertThat(idsOf(service.findApplicable(repositoryB, util("2.0.0"), now)))
                .as("another version in another repository sees only the widest one")
                .containsExactly(componentEverywhere);

        assertThat(service.findApplicable(repositoryA, util("1.0.0"), now).get(0).id())
                .as("narrowest first, so the violation log names the decision somebody took")
                .isEqualTo(versionHere);
    }

    @Test
    @DisplayName("a component nobody exempted is covered by nothing")
    void unrelatedComponentIsNotCovered() throws Exception {
        approved("pkg:maven/com.acme/util@1.0.0", FirewallExemptionScope.VERSION, null);

        assertThat(service.findApplicable(repositoryA, maven("org.other", "util", "1.0.0"), Instant.now()))
                .isEmpty();
    }

    // ── Expiry ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("an expired exemption blocks again — before the sweep, and after it")
    void expiredBlocksAgain() throws Exception {
        Instant lapsedAt = Instant.now().minus(Duration.ofHours(2));
        UUID id = approved("pkg:maven/com.acme/util@1.0.0", FirewallExemptionScope.VERSION, repositoryA, lapsedAt);

        // Before the sweep: the stored state still says APPROVED.
        assertThat(exemptions.findById(id).orElseThrow().getState())
                .isEqualTo(FirewallExemptionState.APPROVED);
        assertThat(service.findApplicable(repositoryA, util("1.0.0"), Instant.now()))
                .as("an exemption that lapsed at noon stops applying at noon, not at 06:00 tomorrow")
                .isEmpty();

        // And after it, so the list and the download path agree.
        assertThat(service.expireLapsed(Instant.now())).isEqualTo(1);
        assertThat(exemptions.findById(id).orElseThrow().getState())
                .isEqualTo(FirewallExemptionState.EXPIRED);
        assertThat(service.findApplicable(repositoryA, util("1.0.0"), Instant.now())).isEmpty();
    }

    @Test
    @DisplayName("an exemption with time left on it still applies, and the sweep leaves it alone")
    void unexpiredSurvivesTheSweep() throws Exception {
        approved(
                "pkg:maven/com.acme/util@1.0.0",
                FirewallExemptionScope.VERSION,
                repositoryA,
                Instant.now().plus(Duration.ofDays(1)));

        assertThat(service.expireLapsed(Instant.now())).isZero();
        assertThat(service.findApplicable(repositoryA, util("1.0.0"), Instant.now())).hasSize(1);
    }

    @Test
    @DisplayName("the lapses-soon notice goes out once, not on every sweep")
    void noticeFiresOnce() throws Exception {
        approved(
                "pkg:maven/com.acme/util@1.0.0",
                FirewallExemptionScope.VERSION,
                repositoryA,
                Instant.now().plus(Duration.ofDays(3)));

        assertThat(service.notifyUpcomingExpiry(Instant.now(), Duration.ofDays(7))).hasSize(1);
        assertThat(service.notifyUpcomingExpiry(Instant.now(), Duration.ofDays(7)))
                .as("expiry_notified_at is what stops the second announcement")
                .isEmpty();
    }

    @Test
    @DisplayName("a non-expiring exemption is never announced and never swept")
    void permanentExemptionIsLeftAlone() throws Exception {
        approved("pkg:maven/com.acme/util@1.0.0", FirewallExemptionScope.VERSION, repositoryA, null);

        assertThat(service.notifyUpcomingExpiry(Instant.now(), Duration.ofDays(3650))).isEmpty();
        assertThat(service.expireLapsed(Instant.now().plus(Duration.ofDays(10_000)))).isZero();
    }

    // ── Rules and advisories ────────────────────────────────────────────

    @Test
    @DisplayName("a rule-scoped exemption suppresses its rule and no other")
    void ruleScopedExemption() throws Exception {
        FirewallExemptionEntity entity = row(
                "pkg:maven/com.acme/util@1.0.0", FirewallExemptionScope.VERSION, repositoryA, null);
        entity.setRuleType(FirewallRuleType.MIN_AGE);
        exemptions.saveAndFlush(entity);

        Instant now = Instant.now();
        assertThat(service.findApplicable(repositoryA, util("1.0.0"), FirewallRuleType.MIN_AGE, now))
                .isPresent();
        assertThat(service.findApplicable(
                        repositoryA, util("1.0.0"), FirewallRuleType.KNOWN_MALICIOUS, now))
                .as("exempt from MIN_AGE is not a blanket pass")
                .isEmpty();
    }

    @Test
    @DisplayName("advisory ids survive the TEXT[] round trip")
    void advisoryIdsRoundTrip() throws Exception {
        FirewallExemptionEntity entity = row(
                "pkg:maven/com.acme/util@1.0.0", FirewallExemptionScope.VERSION, repositoryA, null);
        entity.setAdvisoryIds(new String[] {"CVE-2021-44228", "GHSA-jfh8-c2jp-5v3q"});
        exemptions.saveAndFlush(entity);

        FirewallExemption applicable =
                service.findApplicable(repositoryA, util("1.0.0"), Instant.now()).get(0);

        assertThat(applicable.advisoryIds())
                .containsExactly("CVE-2021-44228", "GHSA-jfh8-c2jp-5v3q");
        assertThat(applicable.coversAdvisory("CVE-2021-44228")).isTrue();
        assertThat(applicable.coversAdvisory("CVE-2022-00000")).isFalse();
    }

    // ── Legacy rows through the real query ──────────────────────────────

    @Test
    @DisplayName("a migrated legacy row is matched by the request path, both scopes")
    void legacyRowsMatchThroughTheQuery() throws Exception {
        FirewallExemptionEntity pinned =
                row("maven2:com.acme:util:1.0.0", FirewallExemptionScope.VERSION, null, null);
        pinned.setKeyKind(FirewallComponentKeyKind.LEGACY_COORDINATE);
        exemptions.saveAndFlush(pinned);

        FirewallExemptionEntity everyVersion =
                row("maven2:com.acme:widget", FirewallExemptionScope.COMPONENT, null, null);
        everyVersion.setKeyKind(FirewallComponentKeyKind.LEGACY_COORDINATE);
        exemptions.saveAndFlush(everyVersion);

        Instant now = Instant.now();
        assertThat(service.findApplicable(repositoryA, util("1.0.0"), now)).hasSize(1);
        assertThat(service.findApplicable(repositoryA, util("1.0.1"), now))
                .as("three colons pinned one version")
                .isEmpty();
        assertThat(service.findApplicable(repositoryA, maven("com.acme", "widget", "7.0.0"), now))
                .as("two colons matched every version")
                .hasSize(1);
    }

    // ── Workflow through the store ──────────────────────────────────────

    @Test
    @DisplayName("a request lets nothing through until it is approved")
    void requestThenApprove() throws Exception {
        FirewallExemption requested = service.request(new ExemptionRequest(
                "pkg:maven/com.acme/util@1.0.0",
                FirewallExemptionScope.VERSION,
                repositoryA,
                null,
                List.of(),
                null,
                "needed for the 4.2 release",
                "dev"));

        assertThat(service.findApplicable(repositoryA, util("1.0.0"), Instant.now()))
                .as("a request that took effect on submission would be a self-service bypass")
                .isEmpty();

        service.approve(requested.id(), "ops", "audited", Instant.now().plus(Duration.ofDays(30)));

        assertThat(service.findApplicable(repositoryA, util("1.0.0"), Instant.now())).hasSize(1);

        service.revoke(requested.id(), "ops", "supplier compromised");

        assertThat(service.findApplicable(repositoryA, util("1.0.0"), Instant.now()))
                .as("a revoked exemption stops applying immediately")
                .isEmpty();
        assertThat(exemptions.findById(requested.id()).orElseThrow().getExpiresAt())
                .as("and keeps its expiry, so the log does not claim it lapsed by itself")
                .isNotNull();
    }

    @Test
    @DisplayName("the management list filters and pages")
    void listFilters() throws Exception {
        approved("pkg:maven/com.acme/util@1.0.0", FirewallExemptionScope.VERSION, repositoryA);
        approved("pkg:maven/com.acme/other@2.0.0", FirewallExemptionScope.VERSION, repositoryB);
        service.request(new ExemptionRequest(
                "pkg:maven/com.acme/pending@3.0.0",
                FirewallExemptionScope.VERSION,
                repositoryA,
                null,
                List.of(),
                null,
                "waiting for a decision",
                "dev"));

        assertThat(service.list(ExemptionQuery.all(), PageRequest.of(0, 50)).getTotalElements())
                .isEqualTo(3);
        assertThat(service.list(ExemptionQuery.pending(), PageRequest.of(0, 50)).getContent())
                .extracting(FirewallExemption::componentKey)
                .containsExactly("pkg:maven/com.acme/pending@3.0.0");
        assertThat(service.list(
                        new ExemptionQuery(null, repositoryB, null, false), PageRequest.of(0, 50))
                        .getContent())
                .extracting(FirewallExemption::componentKey)
                .containsExactly("pkg:maven/com.acme/other@2.0.0");
        assertThat(service.list(
                        new ExemptionQuery(null, null, "PENDING", false), PageRequest.of(0, 50))
                        .getContent())
                .as("the search box is case-insensitive")
                .hasSize(1);
        assertThat(service.list(ExemptionQuery.all(), PageRequest.of(0, 2)).getContent()).hasSize(2);

        var summary = service.summary();
        assertThat(summary.approved()).isEqualTo(2);
        assertThat(summary.requested()).isEqualTo(1);
        assertThat(summary.legacy()).isZero();
    }

    // ── Fixtures ────────────────────────────────────────────────────────

    private static List<UUID> idsOf(List<FirewallExemption> exemptions) {
        return exemptions.stream().map(FirewallExemption::id).toList();
    }

    private static ComponentIdentity util(String version) throws Exception {
        return maven("com.acme", "util", version);
    }

    private static ComponentIdentity maven(String group, String artifact, String version)
            throws Exception {
        return new ComponentIdentity.Purl(new PackageURL("maven", group, artifact, version, null, null));
    }

    private UUID approved(String key, FirewallExemptionScope scope, UUID repositoryId) {
        return approved(key, scope, repositoryId, null);
    }

    private UUID approved(String key, FirewallExemptionScope scope, UUID repositoryId, Instant expiresAt) {
        return exemptions.saveAndFlush(row(key, scope, repositoryId, expiresAt)).getId();
    }

    private static FirewallExemptionEntity row(
            String key, FirewallExemptionScope scope, UUID repositoryId, Instant expiresAt) {
        FirewallExemptionEntity entity = new FirewallExemptionEntity();
        entity.setComponentKey(key);
        entity.setKeyKind(FirewallComponentKeyKind.PURL);
        entity.setScopeType(scope);
        entity.setRepositoryId(repositoryId);
        entity.setState(FirewallExemptionState.APPROVED);
        entity.setExpiresAt(expiresAt);
        entity.setJustification("audited");
        entity.setRequestedBy("dev");
        entity.setApprovedBy("ops");
        entity.setApprovedAt(Instant.now());
        return entity;
    }

    private UUID givenRepository(String name) {
        RepositoryEntity repository = new RepositoryEntity();
        repository.setName(name);
        repository.setFormat("maven2");
        repository.setType("PROXY");
        repository.setOnline(true);
        repository.setBlobStoreName("default");
        repository.setCreatedAt(Instant.now());
        repository.setUpdatedAt(Instant.now());
        return repositories.saveAndFlush(repository).getId();
    }

    /** Only the exemption package plus the database module's JPA wiring. */
    @Configuration(proxyBeanMethods = false)
    @Import(JpaConfig.class)
    @ComponentScan(basePackageClasses = ExemptionService.class)
    @ImportAutoConfiguration({
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        FlywayAutoConfiguration.class
    })
    static class TestConfig {

        /**
         * The defaults, bound by hand: {@code @ConfigurationProperties} would drag
         * the whole property binder into a context that has no properties.
         */
        @Bean
        ExemptionProperties exemptionProperties() {
            return ExemptionProperties.defaults();
        }
    }

    private static void awaitJdbcReady() {
        long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        SQLException last = null;
        while (System.nanoTime() < deadline) {
            try (Connection ignored =
                    DriverManager.getConnection(POSTGRES.getJdbcUrl(), USERNAME, PASSWORD)) {
                return;
            } catch (SQLException e) {
                last = e;
                try {
                    Thread.sleep(250);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for PostgreSQL", interrupted);
                }
            }
        }
        throw new IllegalStateException("PostgreSQL container never became reachable", last);
    }
}
