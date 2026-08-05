package de.bsnsoft.megarepo.repository.firewall;

import com.github.packageurl.PackageURL;
import de.bsnsoft.megarepo.core.firewall.FirewallAction;
import de.bsnsoft.megarepo.core.firewall.FirewallFailMode;
import de.bsnsoft.megarepo.core.firewall.FirewallMode;
import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;
import de.bsnsoft.megarepo.database.config.JpaConfig;
import de.bsnsoft.megarepo.database.entity.AdvisoryAffectedEntity;
import de.bsnsoft.megarepo.database.entity.AdvisoryEntity;
import de.bsnsoft.megarepo.database.entity.AssetEntity;
import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import de.bsnsoft.megarepo.database.entity.FirewallRepositoryConfigEntity;
import de.bsnsoft.megarepo.database.entity.FirewallViolationEntity;
import de.bsnsoft.megarepo.database.entity.RepositoryEntity;
import de.bsnsoft.megarepo.database.repository.AdvisoryAffectedJpaRepository;
import de.bsnsoft.megarepo.database.repository.AdvisoryJpaRepository;
import de.bsnsoft.megarepo.database.repository.AssetJpaRepository;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallRepositoryConfigJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallViolationJpaRepository;
import de.bsnsoft.megarepo.database.repository.RepositoryJpaRepository;
import de.bsnsoft.megarepo.repository.advisory.AdvisoryLookupService;
import de.bsnsoft.megarepo.repository.firewall.identity.PurlMapper;
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
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The AUDIT path end to end against the real migrated schema: a vulnerable
 * component is served, and the fact that it was vulnerable ends up in
 * {@code firewall_violation}.
 *
 * <p>Mocks cannot carry this. The de-duplication rule is a query against a real
 * index, {@code advisory_ids} is a PostgreSQL {@code TEXT[]},
 * {@code request_context} is JSONB, and the whole point of AUDIT is a
 * combination of two observable facts — a row exists <em>and</em> the content
 * went out — that only a real write plus a real stream can demonstrate together.
 *
 * <p>Nothing here reaches the internet beyond pulling {@code postgres:16-alpine}.
 * The advisory rows are inserted directly; no source, no HTTP client and no feed
 * is involved.
 */
@SpringBootTest(
        classes = FirewallAuditDatabaseTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class FirewallAuditDatabaseTest {

    private static final String USERNAME = "megarepo";
    private static final String PASSWORD = "megarepo";
    private static final String PATH =
            "org/apache/logging/log4j/log4j-core/2.14.1/log4j-core-2.14.1.jar";
    private static final String PURL = "pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1";
    private static final byte[] ARTIFACT = "PK pretend jar".getBytes(StandardCharsets.UTF_8);
    private static final FirewallRequestContext CONTEXT =
            new FirewallRequestContext("ci-build", "10.0.0.7", PATH, "GET");

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("megarepo")
                    .withUsername(USERNAME)
                    .withPassword(PASSWORD)
                    // Same URL parameter the project's own JDBC URLs carry, without
                    // which every JSONB write fails: the driver would send the
                    // payload as varchar instead of letting PostgreSQL infer jsonb.
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
        // Every download in this test is its own observation; the in-memory
        // throttle would otherwise hide the second one.
        registry.add("megarepo.firewall.audit.reevaluation-interval", () -> "0s");
    }

    @Autowired private FirewallEvaluationService evaluationService;
    @Autowired private FirewallDownloadObserver observer;
    @Autowired private FirewallViolationJpaRepository violations;
    @Autowired private FirewallRepositoryConfigJpaRepository firewallConfigs;
    @Autowired private RepositoryJpaRepository repositories;
    @Autowired private ComponentJpaRepository components;
    @Autowired private AssetJpaRepository assets;
    @Autowired private AdvisoryJpaRepository advisories;
    @Autowired private AdvisoryAffectedJpaRepository affected;

    private UUID repositoryId;

    @BeforeEach
    void reset() {
        violations.deleteAllInBatch();
        firewallConfigs.deleteAllInBatch();
        assets.deleteAllInBatch();
        components.deleteAllInBatch();
        repositories.deleteAllInBatch();
        affected.deleteAllInBatch();
        advisories.deleteAllInBatch();

        repositoryId = givenRepository("maven-central");
        givenComponent(repositoryId);
    }

    @Test
    @DisplayName("AUDIT: a vulnerable component is recorded AND served anyway")
    void vulnerableComponentIsRecordedAndStillServed() throws Exception {
        givenMode(FirewallMode.AUDIT);
        givenAdvisory("GHSA-jfh8-c2jp-5v3q", "GHSA", 10.0, "CRITICAL");

        // The download, exactly as RepositoryRouter performs it: the content is
        // streamed to the client first, then the firewall is told about it —
        // through the real observer, on its real pool.
        byte[] delivered = serveArtifact();
        observer.observeDownload(repositoryId, "maven-central", PATH, CONTEXT);

        assertThat(delivered)
                .as("AUDIT records violations and serves anyway — the client got every byte")
                .isEqualTo(ARTIFACT);

        List<FirewallViolationEntity> recorded = awaitViolations(1);
        FirewallViolationEntity violation = recorded.get(0);
        assertThat(violation.getPurl()).isEqualTo(PURL);
        assertThat(violation.getAdvisoryIds()).containsExactly("GHSA-jfh8-c2jp-5v3q");
        assertThat(violation.getRuleType()).isEqualTo(FirewallRuleType.ADVISORY_MATCH);
        assertThat(violation.getAction())
                .as("WARN, never BLOCK: the download was served")
                .isEqualTo(FirewallAction.WARN);
        assertThat(violation.getRepositoryId()).isEqualTo(repositoryId);
        assertThat(violation.getRepositoryName()).isEqualTo("maven-central");
        assertThat(violation.getPolicyId())
                .as("Phase 1 evaluates no policy, so no policy may be blamed")
                .isNull();

        Map<String, Object> context = violation.getRequestContext();
        assertThat(context).containsEntry("enforced", false);
        assertThat(context).containsEntry("phase", "audit");
        assertThat(context).containsEntry("mode", "AUDIT");
        assertThat(context).containsEntry("confidence", "EXACT");
        assertThat(context).containsEntry("user", "ci-build");
        assertThat(context).containsEntry("failModeApplied", false);
        assertThat(context.get("sources")).asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.LIST).containsExactly("GHSA");
    }

    @Test
    @DisplayName("QUARANTINE behaves exactly like AUDIT — recorded, served, and marked as not enforced")
    void quarantineModeStillServes() throws Exception {
        givenMode(FirewallMode.QUARANTINE);
        givenAdvisory("GHSA-jfh8-c2jp-5v3q", "GHSA", 10.0, "CRITICAL");

        byte[] delivered = serveArtifact();
        FirewallEvaluation evaluation =
                evaluationService.evaluateDownload(repositoryId, "maven-central", PATH, CONTEXT);

        assertThat(delivered).isEqualTo(ARTIFACT);
        assertThat(evaluation.blocked()).isFalse();
        assertThat(evaluation.outcome()).isEqualTo(FirewallEvaluation.Outcome.RECORDED);
        assertThat(violations.findAll()).hasSize(1);
        Map<String, Object> context = violations.findAll().get(0).getRequestContext();
        assertThat(context).containsEntry("mode", "QUARANTINE");
        assertThat(context)
                .as("no reader may mistake a QUARANTINE observation for a block")
                .containsEntry("enforced", false)
                .containsEntry("enforcementDeferred", true);
    }

    @Test
    @DisplayName("OFF records nothing, even for a component with a critical advisory")
    void offModeRecordsNothing() {
        givenMode(FirewallMode.OFF);
        givenAdvisory("GHSA-jfh8-c2jp-5v3q", "GHSA", 10.0, "CRITICAL");

        FirewallEvaluation evaluation =
                evaluationService.evaluateDownload(repositoryId, "maven-central", PATH, CONTEXT);

        assertThat(evaluation.outcome()).isEqualTo(FirewallEvaluation.Outcome.MODE_OFF);
        assertThat(violations.findAll()).isEmpty();
    }

    @Test
    @DisplayName("a repository with no firewall config row is not observed by default")
    void withoutAConfigRowNothingIsObserved() {
        givenAdvisory("GHSA-jfh8-c2jp-5v3q", "GHSA", 10.0, "CRITICAL");

        FirewallEvaluation evaluation =
                evaluationService.evaluateDownload(repositoryId, "maven-central", PATH, CONTEXT);

        assertThat(evaluation.outcome()).isEqualTo(FirewallEvaluation.Outcome.MODE_OFF);
        assertThat(evaluation.settings().explicit()).isFalse();
        assertThat(violations.findAll()).isEmpty();
    }

    @Test
    @DisplayName("a repeated download of the same component does not write a second identical row")
    void repeatedDownloadsAreSuppressed() {
        givenMode(FirewallMode.AUDIT);
        givenAdvisory("GHSA-jfh8-c2jp-5v3q", "GHSA", 10.0, "CRITICAL");

        assertThat(evaluate().outcome()).isEqualTo(FirewallEvaluation.Outcome.RECORDED);
        for (int i = 0; i < 20; i++) {
            assertThat(evaluate().outcome()).isEqualTo(FirewallEvaluation.Outcome.SUPPRESSED);
        }

        assertThat(violations.findAll())
                .as("one row per component per window, not one per download")
                .hasSize(1);
    }

    @Test
    @DisplayName("a newly published advisory for an already-recorded component is recorded immediately")
    void aChangedAdvisorySetBreaksTheSuppression() {
        givenMode(FirewallMode.AUDIT);
        givenAdvisory("GHSA-jfh8-c2jp-5v3q", "GHSA", 10.0, "CRITICAL");
        assertThat(evaluate().outcome()).isEqualTo(FirewallEvaluation.Outcome.RECORDED);
        assertThat(evaluate().outcome()).isEqualTo(FirewallEvaluation.Outcome.SUPPRESSED);

        givenAdvisory("CVE-2021-45046", "NVD", 9.0, "CRITICAL");

        assertThat(evaluate().outcome())
                .as("the window suppresses exact repeats, never a finding that changed")
                .isEqualTo(FirewallEvaluation.Outcome.RECORDED);

        List<FirewallViolationEntity> recorded = violations.findAll();
        assertThat(recorded).hasSize(2);
        assertThat(recorded)
                .extracting(violation -> List.of(violation.getAdvisoryIds()))
                .containsExactlyInAnyOrder(
                        List.of("GHSA-jfh8-c2jp-5v3q"),
                        List.of("CVE-2021-45046", "GHSA-jfh8-c2jp-5v3q"));
    }

    @Test
    @DisplayName("a component with no advisory produces no row")
    void cleanComponentProducesNothing() {
        givenMode(FirewallMode.AUDIT);

        assertThat(evaluate().outcome()).isEqualTo(FirewallEvaluation.Outcome.CLEAN);
        assertThat(violations.findAll()).isEmpty();
    }

    @Test
    @DisplayName("a path that belongs to no component — a checksum file — is not a finding")
    void checksumPathIsNotAFinding() {
        givenMode(FirewallMode.AUDIT);
        givenAdvisory("GHSA-jfh8-c2jp-5v3q", "GHSA", 10.0, "CRITICAL");

        FirewallEvaluation evaluation = evaluationService.evaluateDownload(
                repositoryId, "maven-central", PATH + ".sha1", CONTEXT);

        assertThat(evaluation.outcome()).isEqualTo(FirewallEvaluation.Outcome.NO_COMPONENT);
        assertThat(violations.findAll()).isEmpty();
    }

    private FirewallEvaluation evaluate() {
        return evaluationService.evaluateDownload(repositoryId, "maven-central", PATH, CONTEXT);
    }

    /**
     * The observer hands its work to a pool, which is the whole point of it — so
     * a test that goes through the observer has to wait for the row rather than
     * assume it. Polls instead of sleeping a fixed amount.
     */
    private List<FirewallViolationEntity> awaitViolations(int expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        List<FirewallViolationEntity> recorded = List.of();
        while (System.nanoTime() < deadline) {
            recorded = violations.findAll();
            if (recorded.size() >= expected) {
                return recorded;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for the violation", e);
            }
        }
        assertThat(recorded).hasSize(expected);
        return recorded;
    }

    /** Streams the artifact the way {@code RepositoryRouter.writeContentResponse} does. */
    private byte[] serveArtifact() throws Exception {
        ByteArrayOutputStream client = new ByteArrayOutputStream();
        try (InputStream content = new ByteArrayInputStream(ARTIFACT)) {
            content.transferTo(client);
        }
        return client.toByteArray();
    }

    private void givenMode(FirewallMode mode) {
        FirewallRepositoryConfigEntity config = new FirewallRepositoryConfigEntity();
        config.setRepositoryId(repositoryId);
        config.setMode(mode);
        config.setFailMode(FirewallFailMode.FAIL_OPEN);
        firewallConfigs.saveAndFlush(config);
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

    private void givenComponent(UUID repositoryId) {
        ComponentEntity component = new ComponentEntity();
        component.setRepositoryId(repositoryId);
        component.setFormat("maven2");
        component.setNamespace("org.apache.logging.log4j");
        component.setName("log4j-core");
        component.setVersion("2.14.1");
        component.setCreatedAt(Instant.now());
        component.setUpdatedAt(Instant.now());
        ComponentEntity saved = components.saveAndFlush(component);

        AssetEntity asset = new AssetEntity();
        asset.setRepositoryId(repositoryId);
        asset.setComponentId(saved.getId());
        asset.setFormat("maven2");
        asset.setPath(PATH);
        asset.setLastModified(Instant.now());
        asset.setCreatedAt(Instant.now());
        asset.setUpdatedAt(Instant.now());
        assets.saveAndFlush(asset);
    }

    private void givenAdvisory(String id, String source, double cvss, String severity) {
        AdvisoryEntity advisory = new AdvisoryEntity();
        advisory.setId(id);
        advisory.setSource(source);
        advisory.setSummary("Remote code execution");
        advisory.setSeverity(severity);
        advisory.setCvssScore(cvss);
        advisory.setPublished(Instant.parse("2021-12-10T00:00:00Z"));
        advisory.setModified(Instant.parse("2021-12-10T00:00:00Z"));
        advisory.setCreatedAt(Instant.now());
        advisory.setUpdatedAt(Instant.now());
        advisories.saveAndFlush(advisory);

        AdvisoryAffectedEntity range = new AdvisoryAffectedEntity();
        range.setAdvisoryId(id);
        range.setPurlType("maven");
        range.setPurlNamespace("org.apache.logging.log4j");
        range.setPurlName("log4j-core");
        range.setVersionRange(">=2.0-beta9, <2.15.0");
        range.setIntroduced("2.0-beta9");
        range.setFixed("2.15.0");
        affected.saveAndFlush(range);
    }

    /**
     * Only the firewall and advisory beans plus the database module's JPA wiring.
     *
     * <p>The remote-backed advisory sources ({@code osv}, {@code ghsa}) are
     * scanned out: they bring HTTP clients and configuration properties this
     * context deliberately has none of, and this test drives the lookup from rows
     * inserted directly. The NVD source stays — it reads the local mirror and
     * needs no network.
     */
    @Configuration(proxyBeanMethods = false)
    @Import(JpaConfig.class)
    @ComponentScan(
            basePackageClasses = {FirewallEvaluationService.class, AdvisoryLookupService.class},
            excludeFilters =
                    @ComponentScan.Filter(
                            type = FilterType.REGEX,
                            pattern = "de\\.bsnsoft\\.megarepo\\.repository\\.advisory\\.(osv|ghsa)\\..*"))
    @ImportAutoConfiguration({
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        FlywayAutoConfiguration.class
    })
    static class TestConfig {

        /**
         * Stands in for {@code megarepo-format-maven}'s mapper, which is not on
         * this module's classpath — {@code PurlBuilder} collects mappers from the
         * format modules, and megarepo-repository depends on none of them.
         */
        @Bean
        PurlMapper mavenPurlMapper() {
            return new PurlMapper() {

                @Override
                public String format() {
                    return "maven2";
                }

                @Override
                public Optional<PackageURL> toPurl(ComponentEntity component) {
                    try {
                        return Optional.of(new PackageURL(
                                "maven",
                                component.getNamespace(),
                                component.getName(),
                                component.getVersion(),
                                null,
                                null));
                    } catch (Exception e) {
                        return Optional.empty();
                    }
                }
            };
        }
    }

    /**
     * The container reports readiness from its own log, but on runtimes where
     * containers live in a VM the host port forward can lag behind that. Poll for
     * a real JDBC connection rather than let the race decide.
     */
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
