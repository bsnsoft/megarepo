package de.bsnsoft.megarepo.repository.firewall.quarantine;

import com.github.packageurl.MalformedPackageURLException;
import com.github.packageurl.PackageURL;
import de.bsnsoft.megarepo.core.firewall.FirewallAction;
import de.bsnsoft.megarepo.core.firewall.FirewallFailMode;
import de.bsnsoft.megarepo.core.firewall.FirewallMode;
import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineReason;
import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineResolution;
import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineState;
import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;
import de.bsnsoft.megarepo.database.config.JpaConfig;
import de.bsnsoft.megarepo.database.entity.FirewallPolicyRuleEntity;
import de.bsnsoft.megarepo.database.entity.FirewallQuarantineEntity;
import de.bsnsoft.megarepo.database.entity.FirewallRepositoryConfigEntity;
import de.bsnsoft.megarepo.database.entity.RepositoryEntity;
import de.bsnsoft.megarepo.database.repository.FirewallPolicyJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallPolicyRuleJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallQuarantineJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallRepositoryConfigJpaRepository;
import de.bsnsoft.megarepo.database.repository.RepositoryJpaRepository;
import de.bsnsoft.megarepo.repository.advisory.AdvisoryLookupService;
import de.bsnsoft.megarepo.repository.firewall.FirewallDecision;
import de.bsnsoft.megarepo.repository.firewall.FirewallEvaluation;
import de.bsnsoft.megarepo.repository.firewall.FirewallEvaluationService;
import de.bsnsoft.megarepo.repository.firewall.FirewallRepositorySettings;
import de.bsnsoft.megarepo.repository.firewall.FirewallRequestContext;
import de.bsnsoft.megarepo.repository.firewall.FirewallRuleViolation;
import de.bsnsoft.megarepo.repository.firewall.identity.ComponentIdentity;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRule;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleContext;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleOutcome;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Quarantine against the real migrated schema.
 *
 * <p>Mocks cannot carry these. The due-list is a JPQL query with a deliberate
 * NULLS FIRST, the hit counter is an {@code UPDATE … SET hit_count = hit_count + 1}
 * that must not load the row, the natural key is a unique constraint, and V17
 * carries a CHECK saying a decided entry has to name its resolution and its
 * moment. Every one of those is a thing a mock would assume rather than verify —
 * and the CHECK in particular is the one that turns a state-machine bug into a
 * constraint violation instead of a silently malformed queue.
 *
 * <p>Nothing here reaches the internet beyond pulling {@code postgres:16-alpine}.
 */
@SpringBootTest(
        classes = FirewallQuarantineDatabaseTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class FirewallQuarantineDatabaseTest {

    private static final String USERNAME = "megarepo";
    private static final String PASSWORD = "megarepo";
    private static final String PATH = "com/acme/util/1.0.0/util-1.0.0.jar";
    private static final String KEY = "pkg:maven/com.acme/util@1.0.0";
    private static final FirewallRequestContext CONTEXT =
            new FirewallRequestContext("ci-build", "10.0.0.7", PATH, "GET");

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

    @Autowired private DefaultQuarantineService service;
    @Autowired private QuarantineMapper mapper;
    @Autowired private QuarantineReevaluator reevaluator;
    @Autowired private FirewallQuarantineJpaRepository rows;
    @Autowired private FirewallRepositoryConfigJpaRepository firewallConfigs;
    @Autowired private FirewallPolicyJpaRepository policies;
    @Autowired private FirewallPolicyRuleJpaRepository policyRules;
    @Autowired private RepositoryJpaRepository repositories;
    @Autowired private MinAgeStub minAge;

    private UUID repositoryId;

    @BeforeEach
    void reset() {
        rows.deleteAllInBatch();
        firewallConfigs.deleteAllInBatch();
        policyRules.deleteAll(policyRules.findAll().stream()
                .filter(rule -> rule.getRuleType() == FirewallRuleType.MIN_AGE)
                .toList());
        repositories.deleteAllInBatch();
        minAge.matching = true;

        repositoryId = givenRepository("maven-hosted");
        givenMode(FirewallMode.QUARANTINE, FirewallFailMode.FAIL_OPEN);
        givenMinAgeRule();
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("holding the same component twice keeps one row — the queue is per component")
    void oneRowPerComponent() {
        service.quarantine(evaluation(false), FirewallQuarantineReason.MIN_AGE_NOT_MET, CONTEXT);
        service.quarantine(evaluation(false), FirewallQuarantineReason.MIN_AGE_NOT_MET, CONTEXT);

        assertThat(rows.findAll()).hasSize(1);
        assertThat(rows.findAll().get(0).getHitCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("a pre-existing component leaves no row at all")
    void preExistingWritesNothing() {
        assertThat(service.quarantine(
                evaluation(true), FirewallQuarantineReason.MIN_AGE_NOT_MET, CONTEXT)).isEmpty();

        assertThat(rows.findAll()).isEmpty();
    }

    @Test
    @DisplayName("recordHit increments the counter without loading the entity")
    void recordHitIncrements() {
        UUID id = held().id();
        Instant seenAt = Instant.parse("2026-08-24T10:15:30Z");

        service.recordHit(id, seenAt);
        service.recordHit(id, seenAt);

        FirewallQuarantineEntity row = rows.findById(id).orElseThrow();
        assertThat(row.getHitCount()).isEqualTo(3);
        assertThat(row.getLastSeen()).isEqualTo(seenAt);
    }

    @Test
    @DisplayName("the sweep picks up due and never-scheduled entries, and nothing else")
    void dueListIsScopedCorrectly() {
        FirewallQuarantineEntity due = row(FirewallQuarantineState.QUARANTINED,
                "pkg:maven/com.acme/due@1", Instant.parse("2026-08-24T11:00:00Z"));
        FirewallQuarantineEntity never = row(FirewallQuarantineState.QUARANTINED,
                "pkg:maven/com.acme/never@1", null);
        FirewallQuarantineEntity later = row(FirewallQuarantineState.QUARANTINED,
                "pkg:maven/com.acme/later@1", Instant.parse("2026-08-24T23:00:00Z"));
        FirewallQuarantineEntity decided = row(FirewallQuarantineState.RELEASED,
                "pkg:maven/com.acme/decided@1", Instant.parse("2026-08-24T11:00:00Z"));

        List<FirewallQuarantineEntity> work = rows.findDueForReevaluation(
                Instant.parse("2026-08-24T12:00:00Z"), PageRequest.of(0, 50));

        assertThat(work).extracting(FirewallQuarantineEntity::getId)
                .containsExactlyInAnyOrder(due.getId(), never.getId())
                .doesNotContain(later.getId(), decided.getId());
    }

    @Test
    @DisplayName("the sweep releases a MIN_AGE entry once the rule stops objecting, with AGE_REACHED")
    void sweepReleasesWhenTheRuleStopsObjecting() {
        UUID id = held().id();

        // Still too new: held, and scheduled rather than decided.
        assertThat(service.reevaluateDue(Instant.now(), 50)).isZero();
        FirewallQuarantineEntity stillHeld = rows.findById(id).orElseThrow();
        assertThat(stillHeld.getState()).isEqualTo(FirewallQuarantineState.QUARANTINED);
        assertThat(stillHeld.getNextEvaluationAt()).isAfter(Instant.now());

        // The clock moved on, so the rule no longer objects.
        minAge.matching = false;
        assertThat(service.reevaluateDue(
                Instant.now().plus(Duration.ofDays(30)), 50)).isEqualTo(1);

        FirewallQuarantineEntity released = rows.findById(id).orElseThrow();
        assertThat(released.getState()).isEqualTo(FirewallQuarantineState.RELEASED);
        assertThat(released.getResolution()).isEqualTo(FirewallQuarantineResolution.AGE_REACHED);
        assertThat(released.getDecidedBy()).isEqualTo(QuarantineDecision.SYSTEM);
        assertThat(released.getDecisionReason())
                .as("the customer asked for a recorded reason, and V17's CHECK insists on one")
                .isNotBlank();
        assertThat(released.getDecidedAt()).isNotNull();
        assertThat(released.getNextEvaluationAt()).isNull();
    }

    @Test
    @DisplayName("an entry that is not due is not touched by the sweep")
    void anEntryThatIsNotDueIsLeftAlone() {
        UUID id = held().id();
        FirewallQuarantineEntity entity = rows.findById(id).orElseThrow();
        entity.setNextEvaluationAt(Instant.now().plus(Duration.ofHours(5)));
        rows.saveAndFlush(entity);
        minAge.matching = false;

        assertThat(service.reevaluateDue(Instant.now(), 50)).isZero();
        assertThat(rows.findById(id).orElseThrow().getState())
                .isEqualTo(FirewallQuarantineState.QUARANTINED);
    }

    @Test
    @DisplayName("invalidatePolicy makes held entries due, and the next sweep decides them")
    void invalidatePolicyBringsEntriesForward() {
        UUID id = held().id();
        FirewallQuarantineEntity entity = rows.findById(id).orElseThrow();
        entity.setNextEvaluationAt(Instant.now().plus(Duration.ofHours(5)));
        rows.saveAndFlush(entity);
        minAge.matching = false;

        UUID policyId = policies.findByIsDefaultTrue().orElseThrow().getId();
        assertThat(service.invalidatePolicy(policyId)).isEqualTo(1);

        assertThat(service.reevaluateDue(Instant.now(), 50)).isEqualTo(1);
        assertThat(rows.findById(id).orElseThrow().getState())
                .isEqualTo(FirewallQuarantineState.RELEASED);
    }

    @Test
    @DisplayName("a decided entry satisfies V17's 'a decision is complete' constraint")
    void decidedRowsSatisfyTheCheckConstraint() {
        UUID id = held().id();

        service.release(id, QuarantineDecision.manual(
                FirewallQuarantineResolution.MANUAL_RELEASE, "alice", "reviewed"));

        FirewallQuarantineEntity row = rows.findById(id).orElseThrow();
        assertThat(row.getResolution()).isNotNull();
        assertThat(row.getDecidedAt()).isNotNull();
    }

    @Test
    @DisplayName("the queue filters on state, repository, reason and a substring of the key")
    void queueFilters() {
        held();
        row(FirewallQuarantineState.QUARANTINED, "pkg:npm/left-pad@1.0.0", null,
                FirewallQuarantineReason.UNKNOWN_COMPONENT);
        row(FirewallQuarantineState.RELEASED, "pkg:npm/right-pad@1.0.0", null,
                FirewallQuarantineReason.UNKNOWN_COMPONENT);

        assertThat(service.queue(QuarantineQuery.held(), PageRequest.of(0, 50)).getTotalElements())
                .isEqualTo(2);
        assertThat(service.queue(QuarantineQuery.all(), PageRequest.of(0, 50)).getTotalElements())
                .isEqualTo(3);
        assertThat(service.queue(QuarantineQuery.heldIn(repositoryId), PageRequest.of(0, 50))
                .getTotalElements()).isEqualTo(2);

        Page<FirewallQuarantineEntry> byReason = service.queue(
                new QuarantineQuery(null, null, FirewallQuarantineReason.MIN_AGE_NOT_MET, null),
                PageRequest.of(0, 50));
        assertThat(byReason.getTotalElements()).isEqualTo(1);
        assertThat(byReason.getContent().get(0).componentKey()).isEqualTo(KEY);

        Page<FirewallQuarantineEntry> bySearch = service.queue(
                new QuarantineQuery(null, null, null, "LEFT-PAD"), PageRequest.of(0, 50));
        assertThat(bySearch.getTotalElements())
                .as("the search box is case-insensitive or it is not a search box")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("summary counts what the badge shows")
    void summaryCounts() {
        UUID id = held().id();
        row(FirewallQuarantineState.QUARANTINED, "pkg:npm/left-pad@1.0.0", null);
        service.block(id, QuarantineDecision.manual(
                FirewallQuarantineResolution.MANUAL_BLOCK, "alice", "no"));

        assertThat(service.summary())
                .isEqualTo(new QuarantineService.QuarantineSummary(1, 0, 1));
    }

    @Test
    @DisplayName("switched off: find() answers nothing and the stored row stays exactly as it was")
    void disabledLeavesRowsAlone() {
        UUID id = held().id();
        DefaultQuarantineService off = new DefaultQuarantineService(
                rows, mapper, reevaluator, QuarantineProperties.disabled());

        assertThat(off.find(repositoryId, KEY)).isEmpty();
        assertThat(off.reevaluateDue(Instant.now(), 50)).isZero();

        FirewallQuarantineEntity untouched = rows.findById(id).orElseThrow();
        assertThat(untouched.getState()).isEqualTo(FirewallQuarantineState.QUARANTINED);
        assertThat(untouched.getResolution()).isNull();
    }

    @Test
    @DisplayName("the request path finds a held component by its identity key")
    void findByIdentity() {
        held();

        Optional<FirewallQuarantineEntry> found = service.find(repositoryId, KEY);

        assertThat(found).isPresent();
        assertThat(found.get().denies()).isTrue();
        assertThat(found.get().path()).isEqualTo(PATH);
    }

    // ------------------------------------------------------------------

    private FirewallQuarantineEntry held() {
        return service.quarantine(
                evaluation(false), FirewallQuarantineReason.MIN_AGE_NOT_MET, CONTEXT).orElseThrow();
    }

    private FirewallQuarantineEntity row(
            FirewallQuarantineState state, String key, Instant nextEvaluationAt) {
        return row(state, key, nextEvaluationAt, FirewallQuarantineReason.UNKNOWN_COMPONENT);
    }

    private FirewallQuarantineEntity row(
            FirewallQuarantineState state,
            String key,
            Instant nextEvaluationAt,
            FirewallQuarantineReason reason) {

        FirewallQuarantineEntity entity = new FirewallQuarantineEntity();
        entity.setRepositoryId(repositoryId);
        entity.setRepositoryName("maven-hosted");
        entity.setComponentKey(key);
        entity.setState(state);
        entity.setReasonCode(reason);
        entity.setNextEvaluationAt(nextEvaluationAt);
        if (state != FirewallQuarantineState.QUARANTINED) {
            entity.setResolution(FirewallQuarantineResolution.MANUAL_RELEASE);
            entity.setDecidedAt(Instant.now());
            entity.setDecidedBy("alice");
        }
        return rows.saveAndFlush(entity);
    }

    private FirewallEvaluation evaluation(boolean preExisting) {
        return new FirewallEvaluation(
                repositoryId, "maven-hosted", PATH,
                new FirewallRepositorySettings(FirewallMode.QUARANTINE, FirewallFailMode.FAIL_OPEN,
                        null, true),
                identity(), List.of(), FirewallEvaluation.Outcome.MATCHED, preExisting,
                FirewallDecision.allowed(
                        policies.findByIsDefaultTrue().orElseThrow().getId(), "Default", List.of()));
    }

    private static ComponentIdentity identity() {
        try {
            return new ComponentIdentity.Purl(new PackageURL(KEY));
        } catch (MalformedPackageURLException e) {
            throw new IllegalStateException(e);
        }
    }

    private UUID givenRepository(String name) {
        RepositoryEntity repository = new RepositoryEntity();
        repository.setName(name);
        repository.setFormat("maven2");
        repository.setType("HOSTED");
        repository.setOnline(true);
        repository.setBlobStoreName("default");
        repository.setCreatedAt(Instant.now());
        repository.setUpdatedAt(Instant.now());
        return repositories.saveAndFlush(repository).getId();
    }

    private void givenMode(FirewallMode mode, FirewallFailMode failMode) {
        FirewallRepositoryConfigEntity config = new FirewallRepositoryConfigEntity();
        config.setRepositoryId(repositoryId);
        config.setMode(mode);
        config.setFailMode(failMode);
        firewallConfigs.saveAndFlush(config);
    }

    private void givenMinAgeRule() {
        FirewallPolicyRuleEntity rule = new FirewallPolicyRuleEntity();
        rule.setPolicyId(policies.findByIsDefaultTrue().orElseThrow().getId());
        rule.setRuleType(FirewallRuleType.MIN_AGE);
        rule.setAction(FirewallAction.BLOCK);
        rule.setConfig(Map.of("minAge", "P7D"));
        rule.setEnabled(true);
        policyRules.saveAndFlush(rule);
    }

    /**
     * Stands in for the {@code MIN_AGE} rule, which another work package owns.
     *
     * <p>What this test is about is the state machine and the SQL around a rule,
     * not the date arithmetic inside one, so the rule's answer is a switch the
     * test flips.
     */
    static class MinAgeStub implements FirewallRule {

        volatile boolean matching = true;

        @Override
        public FirewallRuleType ruleType() {
            return FirewallRuleType.MIN_AGE;
        }

        @Override
        public FirewallRuleOutcome evaluate(FirewallRuleContext context, FirewallRuleSettings settings) {
            return matching
                    ? FirewallRuleOutcome.matched(new FirewallRuleViolation(
                            FirewallRuleType.MIN_AGE, settings.action(),
                            "published less than 7 days ago", List.of()))
                    : FirewallRuleOutcome.notMatched();
        }

        @Override
        public boolean quarantineOnMatch() {
            return true;
        }

        @Override
        public FirewallQuarantineReason quarantineReason() {
            return FirewallQuarantineReason.MIN_AGE_NOT_MET;
        }
    }

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
    // TransactionAutoConfiguration, unlike the sibling firewall database tests:
    // this one exercises a service whose @Transactional boundaries are load
    // bearing — the hit counter is a @Modifying bulk update and needs a real
    // transaction, exactly as it has one in the application.
    @ImportAutoConfiguration({
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        TransactionAutoConfiguration.class,
        FlywayAutoConfiguration.class
    })
    static class TestConfig {

        @Bean
        MinAgeStub minAgeStub() {
            return new MinAgeStub();
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
                    throw new IllegalStateException(interrupted);
                }
            }
        }
        throw new IllegalStateException("PostgreSQL did not accept JDBC connections in time", last);
    }
}
