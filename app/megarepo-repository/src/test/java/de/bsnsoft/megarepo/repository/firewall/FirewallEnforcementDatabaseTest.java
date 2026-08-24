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
import de.bsnsoft.megarepo.database.entity.FirewallEnforcementSettingsEntity;
import de.bsnsoft.megarepo.database.entity.FirewallPolicyEntity;
import de.bsnsoft.megarepo.database.entity.FirewallRepositoryConfigEntity;
import de.bsnsoft.megarepo.database.entity.FirewallViolationEntity;
import de.bsnsoft.megarepo.database.entity.RepositoryEntity;
import de.bsnsoft.megarepo.database.repository.AdvisoryAffectedJpaRepository;
import de.bsnsoft.megarepo.database.repository.AdvisoryJpaRepository;
import de.bsnsoft.megarepo.database.repository.AssetJpaRepository;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallEnforcementSettingsJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallPolicyJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallPolicyRuleJpaRepository;
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
 * Enforcement end to end against the real migrated schema, including the V16
 * seed.
 *
 * <p>Mocks cannot carry this one. The master switch is a row, the policy and its
 * rules are rows with a JSONB config, the grandfathering watermark is compared
 * against a stored {@code created_at}, and the seeded default policy is
 * something the migration either produced or did not. All four are exactly the
 * things a mock would assume rather than verify.
 *
 * <p>Nothing here reaches the internet beyond pulling {@code postgres:16-alpine}.
 * Advisory rows are inserted directly; no source, no HTTP client, no feed.
 */
@SpringBootTest(
        classes = FirewallEnforcementDatabaseTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class FirewallEnforcementDatabaseTest {

    private static final String USERNAME = "megarepo";
    private static final String PASSWORD = "megarepo";
    private static final String PATH =
            "org/apache/logging/log4j/log4j-core/2.14.1/log4j-core-2.14.1.jar";
    private static final String PURL = "pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1";
    private static final byte[] ARTIFACT = "PK pretend jar".getBytes(StandardCharsets.UTF_8);
    private static final FirewallRequestContext CONTEXT =
            new FirewallRequestContext("ci-build", "10.0.0.7", PATH, "GET");

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("megarepo")
                    .withUsername(USERNAME)
                    .withPassword(PASSWORD)
                    // Without this every JSONB write fails: the driver would send
                    // the payload as varchar instead of letting PostgreSQL infer
                    // jsonb. Same parameter the project's own JDBC URLs carry.
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
        registry.add("megarepo.firewall.audit.reevaluation-interval", () -> "0s");
        // The switch is flipped inside the tests; a long cache would make the
        // next test read the previous one's answer.
        registry.add("megarepo.firewall.enforcement.settings-refresh-interval", () -> "0s");
    }

    @Autowired private FirewallEnforcementService enforcement;
    @Autowired private FirewallEnforcementSettingsService enforcementSettings;
    @Autowired private FirewallEvaluationService evaluationService;
    @Autowired private FirewallViolationJpaRepository violations;
    @Autowired private FirewallRepositoryConfigJpaRepository firewallConfigs;
    @Autowired private FirewallEnforcementSettingsJpaRepository enforcementRows;
    @Autowired private FirewallPolicyJpaRepository policies;
    @Autowired private FirewallPolicyRuleJpaRepository policyRules;
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

        // Back to the shape V16 leaves behind: switch off, never stamped.
        FirewallEnforcementSettingsEntity fresh = new FirewallEnforcementSettingsEntity();
        fresh.setId(1);
        fresh.setConfigured(false);
        fresh.setEnabled(false);
        fresh.setEnforcingSince(null);
        enforcementRows.saveAndFlush(fresh);
        enforcementSettings.refresh();

        repositoryId = givenRepository("maven-central");
    }

    @Test
    @DisplayName("V16 seeds a default policy with exactly the two implemented rules")
    void migrationSeedsTheDefaultPolicy() {
        Optional<FirewallPolicyEntity> policy = policies.findByIsDefaultTrue();

        assertThat(policy).isPresent();
        assertThat(policy.get().getName()).isEqualTo("Default");
        assertThat(policyRules.findByPolicyIdAndEnabledTrue(policy.get().getId()))
                .extracting(rule -> Map.entry(rule.getRuleType(), rule.getAction()))
                .containsExactlyInAnyOrder(
                        Map.entry(FirewallRuleType.CVSS_THRESHOLD, FirewallAction.BLOCK),
                        Map.entry(FirewallRuleType.KNOWN_MALICIOUS, FirewallAction.BLOCK));
    }

    @Test
    @DisplayName("V16 leaves the switch off, so an upgrade changes nothing")
    void migrationLeavesEnforcementOff() {
        assertThat(enforcementRows.findById(1))
                .get()
                .satisfies(row -> {
                    assertThat(row.isEnabled()).isFalse();
                    assertThat(row.isConfigured()).isFalse();
                    assertThat(row.getEnforcingSince()).isNull();
                });
        assertThat(enforcementSettings.enforcementEnabled()).isFalse();
    }

    @Test
    @DisplayName("switch OFF + QUARANTINE + a critical advisory: served, and only observed")
    void switchOffServesAndOnlyRecords() {
        givenMode(FirewallMode.QUARANTINE, FirewallFailMode.FAIL_CLOSED);
        givenComponentStoredAt(Instant.now());
        givenAdvisory("GHSA-jfh8-c2jp-5v3q", "GHSA", 10.0);

        FirewallEvaluation verdict = enforcement.evaluate(repositoryId, "maven-central", PATH, CONTEXT);

        assertThat(verdict.blocked())
                .as("the master switch is the thing that keeps an upgrade from breaking builds")
                .isFalse();
        assertThat(verdict.outcome()).isEqualTo(FirewallEvaluation.Outcome.NOT_ENFORCING);
        assertThat(verdict.enforcementEvaluated()).isFalse();

        // What the router does next when enforcement declined to decide.
        assertThat(evaluationService.evaluateDownload(repositoryId, "maven-central", PATH, CONTEXT).outcome())
                .isEqualTo(FirewallEvaluation.Outcome.RECORDED);

        List<FirewallViolationEntity> recorded = violations.findAll();
        assertThat(recorded).hasSize(1);
        assertThat(recorded.get(0).getRuleType()).isEqualTo(FirewallRuleType.ADVISORY_MATCH);
        assertThat(recorded.get(0).getAction()).isEqualTo(FirewallAction.WARN);
        assertThat(recorded.get(0).getRequestContext())
                .containsEntry("enforced", false)
                .containsEntry("enforcementDeferred", true);
    }

    @Test
    @DisplayName("switch ON + QUARANTINE + a critical advisory on newly pulled content: blocked")
    void switchOnBlocksNewContent() {
        Instant since = givenEnforcementEnabled();
        givenMode(FirewallMode.QUARANTINE, FirewallFailMode.FAIL_OPEN);
        givenComponentStoredAt(since.plusSeconds(1));
        givenAdvisory("GHSA-jfh8-c2jp-5v3q", "GHSA", 10.0);

        FirewallEvaluation verdict = enforcement.evaluate(repositoryId, "maven-central", PATH, CONTEXT);

        assertThat(verdict.blocked()).isTrue();
        assertThat(verdict.decision().reason()).isEqualTo(FirewallDecision.Reason.POLICY);
        assertThat(verdict.decision().policyName()).isEqualTo("Default");
        assertThat(verdict.decision().advisoryIds()).containsExactly("GHSA-jfh8-c2jp-5v3q");

        FirewallViolationEntity row = awaitViolations(1).get(0);
        assertThat(row.getPurl()).isEqualTo(PURL);
        assertThat(row.getRuleType()).isEqualTo(FirewallRuleType.CVSS_THRESHOLD);
        assertThat(row.getAction())
                .as("the download really was denied, so the row says BLOCK")
                .isEqualTo(FirewallAction.BLOCK);
        assertThat(row.getPolicyId()).isEqualTo(policies.findByIsDefaultTrue().orElseThrow().getId());
        assertThat(row.getRequestContext())
                .containsEntry("phase", "enforcement")
                .containsEntry("enforced", true)
                .containsEntry("blocked", true)
                .containsEntry("preExisting", false)
                .containsEntry("policy", "Default");
    }

    @Test
    @DisplayName("a component already stored when the switch was flipped is recorded but served")
    void preExistingComponentIsGrandfathered() {
        Instant since = givenEnforcementEnabled();
        givenMode(FirewallMode.QUARANTINE, FirewallFailMode.FAIL_OPEN);
        givenComponentStoredAt(since.minusSeconds(3600));
        givenAdvisory("GHSA-jfh8-c2jp-5v3q", "GHSA", 10.0);

        FirewallEvaluation verdict = enforcement.evaluate(repositoryId, "maven-central", PATH, CONTEXT);

        assertThat(verdict.blocked())
                .as("otherwise switching enforcement on breaks every build against a cached artifact")
                .isFalse();
        assertThat(verdict.preExisting()).isTrue();
        assertThat(verdict.decision().reason()).isEqualTo(FirewallDecision.Reason.PRE_EXISTING);

        FirewallViolationEntity row = awaitViolations(1).get(0);
        assertThat(row.getRuleType()).isEqualTo(FirewallRuleType.CVSS_THRESHOLD);
        assertThat(row.getAction())
                .as("the download went out, so the row must not claim BLOCK")
                .isEqualTo(FirewallAction.WARN);
        assertThat(row.getRequestContext())
                .containsEntry("preExisting", true)
                .containsEntry("blocked", false)
                .containsEntry("ruleAction", "BLOCK");
    }

    @Test
    @DisplayName("switch ON + AUDIT: still served, and enforcement declines to decide")
    void auditModeIsUnaffectedByTheSwitch() {
        givenEnforcementEnabled();
        givenMode(FirewallMode.AUDIT, FirewallFailMode.FAIL_CLOSED);
        givenComponentStoredAt(Instant.now());
        givenAdvisory("GHSA-jfh8-c2jp-5v3q", "GHSA", 10.0);

        FirewallEvaluation verdict = enforcement.evaluate(repositoryId, "maven-central", PATH, CONTEXT);

        assertThat(verdict.blocked()).isFalse();
        assertThat(verdict.outcome()).isEqualTo(FirewallEvaluation.Outcome.NOT_ENFORCING);
        assertThat(violations.findAll()).isEmpty();
    }

    @Test
    @DisplayName("a component with no advisory is served even in an enforcing repository")
    void cleanComponentIsServed() {
        Instant since = givenEnforcementEnabled();
        givenMode(FirewallMode.QUARANTINE, FirewallFailMode.FAIL_CLOSED);
        givenComponentStoredAt(since.plusSeconds(1));

        FirewallEvaluation verdict = enforcement.evaluate(repositoryId, "maven-central", PATH, CONTEXT);

        assertThat(verdict.blocked()).isFalse();
        assertThat(verdict.outcome()).isEqualTo(FirewallEvaluation.Outcome.CLEAN);
        assertThat(violations.findAll()).isEmpty();
    }

    @Test
    @DisplayName("a checksum file belongs to no component and is never blocked")
    void checksumPathIsServed() {
        Instant since = givenEnforcementEnabled();
        givenMode(FirewallMode.QUARANTINE, FirewallFailMode.FAIL_CLOSED);
        givenComponentStoredAt(since.plusSeconds(1));
        givenAdvisory("GHSA-jfh8-c2jp-5v3q", "GHSA", 10.0);

        FirewallEvaluation verdict =
                enforcement.evaluate(repositoryId, "maven-central", PATH + ".sha1", CONTEXT);

        assertThat(verdict.blocked()).isFalse();
        assertThat(verdict.outcome()).isEqualTo(FirewallEvaluation.Outcome.NO_COMPONENT);
    }

    @Test
    @DisplayName("turning the switch back off serves the same download again, without a restart")
    void theSwitchWorksInBothDirections() {
        Instant since = givenEnforcementEnabled();
        givenMode(FirewallMode.QUARANTINE, FirewallFailMode.FAIL_OPEN);
        givenComponentStoredAt(since.plusSeconds(1));
        givenAdvisory("GHSA-jfh8-c2jp-5v3q", "GHSA", 10.0);

        assertThat(enforcement.evaluate(repositoryId, "maven-central", PATH, CONTEXT).blocked()).isTrue();
        // The block's audit row is written asynchronously; wait for it here so it
        // cannot land in the middle of the next test's fixture.
        awaitViolations(1);

        enforcementSettings.save(false, "admin");

        assertThat(enforcement.evaluate(repositoryId, "maven-central", PATH, CONTEXT).blocked())
                .as("the operator turning enforcement off is usually doing it while builds are failing")
                .isFalse();
    }

    @Test
    @DisplayName("turning it off and on again does not grandfather what was pulled in meanwhile")
    void thewatermarkIsNotResetByToggling() {
        givenEnforcementEnabled();
        // Read the watermark back instead of keeping the one the save returned.
        // Both are the same instant, but not the same value: the column keeps
        // microseconds, while the Instant the service stamped keeps whatever
        // resolution the platform clock has — microseconds on macOS, nanoseconds
        // on Linux. Comparing the in-memory value with its stored form therefore
        // passes on one operating system and fails on the other, which is how
        // this test stayed green locally and turned CI red.
        Instant first = storedWatermark();

        enforcementSettings.save(false, "admin");
        enforcementSettings.save(true, "admin");

        assertThat(storedWatermark())
                .as("a brief disable must not silently weaken the firewall")
                .isEqualTo(first);
    }

    private Instant storedWatermark() {
        return enforcementRows.findById(1).orElseThrow().getEnforcingSince();
    }

    /** Enables enforcement and returns the watermark it stamped. */
    private Instant givenEnforcementEnabled() {
        FirewallEnforcementSettingsEntity saved = enforcementSettings.save(true, "admin");
        assertThat(saved.getEnforcingSince()).isNotNull();
        return saved.getEnforcingSince();
    }

    /**
     * The enforcement path records asynchronously — the verdict is already given
     * and the client must not wait for the log write — so a test that asserts on
     * the row has to poll for it.
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

    private void givenMode(FirewallMode mode, FirewallFailMode failMode) {
        FirewallRepositoryConfigEntity config = new FirewallRepositoryConfigEntity();
        config.setRepositoryId(repositoryId);
        config.setMode(mode);
        config.setFailMode(failMode);
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

    /**
     * The asset's {@code created_at} is what decides whether the component counts
     * as already present, so every test that cares states it explicitly.
     */
    private void givenComponentStoredAt(Instant storedAt) {
        ComponentEntity component = new ComponentEntity();
        component.setRepositoryId(repositoryId);
        component.setFormat("maven2");
        component.setNamespace("org.apache.logging.log4j");
        component.setName("log4j-core");
        component.setVersion("2.14.1");
        component.setCreatedAt(storedAt);
        component.setUpdatedAt(storedAt);
        ComponentEntity saved = components.saveAndFlush(component);

        AssetEntity asset = new AssetEntity();
        asset.setRepositoryId(repositoryId);
        asset.setComponentId(saved.getId());
        asset.setFormat("maven2");
        asset.setPath(PATH);
        asset.setSize((long) ARTIFACT.length);
        asset.setLastModified(storedAt);
        asset.setCreatedAt(storedAt);
        asset.setUpdatedAt(storedAt);
        assets.saveAndFlush(asset);
    }

    private void givenAdvisory(String id, String source, double cvss) {
        AdvisoryEntity advisory = new AdvisoryEntity();
        advisory.setId(id);
        advisory.setSource(source);
        advisory.setSummary("Remote code execution");
        advisory.setSeverity("CRITICAL");
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
     * inserted directly. The sibling test's own {@code TestConfig} is scanned out
     * too — it lives in this package, so the scan would otherwise pull a second
     * test's context definition into this one.
     */
    @Configuration(proxyBeanMethods = false)
    @Import(JpaConfig.class)
    @ComponentScan(
            basePackageClasses = {FirewallEvaluationService.class, AdvisoryLookupService.class},
            excludeFilters = {
                @ComponentScan.Filter(
                        type = FilterType.REGEX,
                        pattern = "de\\.bsnsoft\\.megarepo\\.repository\\.advisory\\.(osv|ghsa)\\..*"),
                @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*Test\\$TestConfig")
            })
    @ImportAutoConfiguration({
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        FlywayAutoConfiguration.class
    })
    static class TestConfig {

        /** Stands in for {@code megarepo-format-maven}'s mapper, off this module's classpath. */
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
