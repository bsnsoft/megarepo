package de.bsnsoft.megarepo.database.entity;

import de.bsnsoft.megarepo.core.firewall.FirewallAction;
import de.bsnsoft.megarepo.core.firewall.FirewallFailMode;
import de.bsnsoft.megarepo.core.firewall.FirewallMode;
import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;
import de.bsnsoft.megarepo.database.PostgresTestSupport;
import de.bsnsoft.megarepo.database.repository.AdvisoryAffectedJpaRepository;
import de.bsnsoft.megarepo.database.repository.AdvisoryJpaRepository;
import de.bsnsoft.megarepo.database.repository.AdvisorySyncStateJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallPolicyJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallPolicyRuleJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallRepositoryConfigJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallViolationJpaRepository;
import de.bsnsoft.megarepo.database.repository.RepositoryJpaRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the Phase 1 firewall entities against the real migrated schema.
 *
 * <p>The context runs with {@code ddl-auto=validate}, the same setting
 * megarepo-app uses in production: if any entity disagrees with the Flyway
 * schema — wrong column name, wrong type, missing column — the context fails to
 * start and every test here fails. That is the point of the class as much as
 * the round-trips below.
 */
@SpringBootTest
@Transactional
class FirewallEntityMappingTest {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestSupport::defaultJdbcUrl);
        registry.add("spring.datasource.username", () -> PostgresTestSupport.USERNAME);
        registry.add("spring.datasource.password", () -> PostgresTestSupport.PASSWORD);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.flyway.placeholder-replacement", () -> "false");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.open-in-view", () -> "false");
    }

    @Autowired private FirewallPolicyJpaRepository policies;
    @Autowired private FirewallPolicyRuleJpaRepository rules;
    @Autowired private FirewallRepositoryConfigJpaRepository configs;
    @Autowired private AdvisoryJpaRepository advisories;
    @Autowired private AdvisoryAffectedJpaRepository affected;
    @Autowired private AdvisorySyncStateJpaRepository syncStates;
    @Autowired private FirewallViolationJpaRepository violations;
    @Autowired private RepositoryJpaRepository repositories;
    @Autowired private EntityManager entityManager;

    @Test
    @DisplayName("policy and rule round-trip, including JSONB config and enums")
    void policyAndRuleRoundTrip() {
        FirewallPolicyEntity policy = new FirewallPolicyEntity();
        policy.setName("Default " + UUID.randomUUID());
        policy.setDescription("seeded by test");
        policy.setDefault(false);
        policy.setCreatedBy("admin");
        policy = policies.saveAndFlush(policy);
        assertThat(policy.getId()).isNotNull();

        FirewallPolicyRuleEntity rule = new FirewallPolicyRuleEntity();
        rule.setPolicyId(policy.getId());
        rule.setRuleType(FirewallRuleType.CVSS_THRESHOLD);
        rule.setAction(FirewallAction.BLOCK);
        rule.setConfig(Map.of("threshold", 7.0, "ignoreWithdrawn", true));
        rule.setEnabled(true);
        rules.saveAndFlush(rule);

        entityManager.clear();

        List<FirewallPolicyRuleEntity> loaded = rules.findByPolicyIdAndEnabledTrue(policy.getId());
        assertThat(loaded).hasSize(1);
        FirewallPolicyRuleEntity reloaded = loaded.get(0);
        assertThat(reloaded.getRuleType()).isEqualTo(FirewallRuleType.CVSS_THRESHOLD);
        assertThat(reloaded.getAction()).isEqualTo(FirewallAction.BLOCK);
        assertThat(reloaded.getConfig())
                .containsEntry("threshold", 7.0)
                .containsEntry("ignoreWithdrawn", true);
    }

    @Test
    @DisplayName("enums are persisted by name, never by ordinal")
    void enumsArePersistedAsStrings() {
        FirewallPolicyEntity policy = new FirewallPolicyEntity();
        policy.setName("Enum check " + UUID.randomUUID());
        policy = policies.saveAndFlush(policy);

        FirewallPolicyRuleEntity rule = new FirewallPolicyRuleEntity();
        rule.setPolicyId(policy.getId());
        rule.setRuleType(FirewallRuleType.KNOWN_MALICIOUS);
        rule.setAction(FirewallAction.BLOCK);
        rule = rules.saveAndFlush(rule);

        Object[] row = (Object[]) entityManager
                .createNativeQuery("SELECT rule_type, action FROM firewall_policy_rule WHERE id = :id")
                .setParameter("id", rule.getId())
                .getSingleResult();

        assertThat(row[0]).isEqualTo("KNOWN_MALICIOUS");
        assertThat(row[1]).isEqualTo("BLOCK");
    }

    @Test
    @DisplayName("repository config round-trip with mode and fail_mode")
    void repositoryConfigRoundTrip() {
        RepositoryEntity repository = newRepository();

        FirewallRepositoryConfigEntity config = new FirewallRepositoryConfigEntity();
        config.setRepositoryId(repository.getId());
        config.setMode(FirewallMode.AUDIT);
        config.setFailMode(FirewallFailMode.FAIL_OPEN);
        configs.saveAndFlush(config);

        entityManager.clear();

        Optional<FirewallRepositoryConfigEntity> loaded = configs.findById(repository.getId());
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getMode()).isEqualTo(FirewallMode.AUDIT);
        assertThat(loaded.get().getFailMode()).isEqualTo(FirewallFailMode.FAIL_OPEN);
        assertThat(loaded.get().getPolicyId()).isNull();

        assertThat(configs.findByMode(FirewallMode.AUDIT))
                .extracting(FirewallRepositoryConfigEntity::getRepositoryId)
                .contains(repository.getId());
    }

    @Test
    @DisplayName("advisory and affected-range round-trip, namespace nullable")
    void advisoryRoundTrip() {
        String advisoryId = "GHSA-" + UUID.randomUUID();

        AdvisoryEntity advisory = new AdvisoryEntity();
        advisory.setId(advisoryId);
        advisory.setSource("GHSA");
        advisory.setSummary("Remote code execution in log4j-core");
        advisory.setSeverity("CRITICAL");
        advisory.setCvssScore(10.0);
        advisory.setCvssVector("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:C/C:H/I:H/A:H");
        advisory.setPublished(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        advisories.saveAndFlush(advisory);

        AdvisoryAffectedEntity withNamespace = new AdvisoryAffectedEntity();
        withNamespace.setAdvisoryId(advisoryId);
        withNamespace.setPurlType("maven");
        withNamespace.setPurlNamespace("org.apache.logging.log4j");
        withNamespace.setPurlName("log4j-core");
        withNamespace.setVersionRange(">=2.0-beta9, <2.15.0");
        withNamespace.setIntroduced("2.0-beta9");
        withNamespace.setFixed("2.15.0");
        affected.saveAndFlush(withNamespace);

        // npm without a scope: namespace is genuinely NULL.
        AdvisoryAffectedEntity withoutNamespace = new AdvisoryAffectedEntity();
        withoutNamespace.setAdvisoryId(advisoryId);
        withoutNamespace.setPurlType("npm");
        withoutNamespace.setPurlName("event-stream");
        withoutNamespace.setIntroduced("3.3.6");
        affected.saveAndFlush(withoutNamespace);

        entityManager.clear();

        assertThat(advisories.findByIdInAndWithdrawnAtIsNull(List.of(advisoryId))).hasSize(1);
        assertThat(affected.findByAdvisoryId(advisoryId)).hasSize(2);

        List<AdvisoryAffectedEntity> maven =
                affected.findByPurlCoordinates("maven", "org.apache.logging.log4j", "log4j-core");
        assertThat(maven).hasSize(1);
        assertThat(maven.get(0).getFixed()).isEqualTo("2.15.0");

        // The IS NULL branch of the query must match the unscoped row.
        List<AdvisoryAffectedEntity> npm = affected.findByPurlCoordinates("npm", null, "event-stream");
        assertThat(npm).hasSize(1);
        assertThat(npm.get(0).getPurlNamespace()).isNull();

        // ...and must not match the namespaced row.
        assertThat(affected.findByPurlCoordinates("maven", null, "log4j-core")).isEmpty();
    }

    @Test
    @DisplayName("advisory without a CVSS score stores NULL, not 0.0")
    void advisoryScoreIsNullable() {
        String advisoryId = "MAL-" + UUID.randomUUID();
        AdvisoryEntity advisory = new AdvisoryEntity();
        advisory.setId(advisoryId);
        advisory.setSource("OSV");
        advisory.setSummary("Malicious package");
        advisories.saveAndFlush(advisory);

        entityManager.clear();

        assertThat(advisories.findById(advisoryId)).isPresent()
                .get()
                .extracting(AdvisoryEntity::getCvssScore)
                .isNull();
    }

    @Test
    @DisplayName("sync state is keyed per source")
    void syncStateRoundTrip() {
        AdvisorySyncStateEntity osv = new AdvisorySyncStateEntity();
        osv.setSource("OSV-" + UUID.randomUUID().toString().substring(0, 8));
        osv.setStatus("RUNNING");
        osv.setCursor("snapshot-etag-abc123");
        osv.setLastSuccessAt(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        syncStates.saveAndFlush(osv);

        entityManager.clear();

        Optional<AdvisorySyncStateEntity> loaded = syncStates.findById(osv.getSource());
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getStatus()).isEqualTo("RUNNING");
        assertThat(loaded.get().getCursor()).isEqualTo("snapshot-etag-abc123");
    }

    @Test
    @DisplayName("violation round-trip, including TEXT[] advisory ids and JSONB context")
    void violationRoundTrip() {
        RepositoryEntity repository = newRepository();

        FirewallViolationEntity violation = new FirewallViolationEntity();
        violation.setRepositoryId(repository.getId());
        violation.setRepositoryName(repository.getName());
        violation.setPurl("pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1");
        violation.setRuleType(FirewallRuleType.CVSS_THRESHOLD);
        violation.setAction(FirewallAction.WARN);
        violation.setAdvisoryIds(new String[] {"CVE-2021-44228", "GHSA-jfh8-c2jp-5v3q"});
        violation.setRequestContext(Map.of("user", "ci-runner", "ip", "10.0.0.7", "method", "GET"));
        violations.saveAndFlush(violation);

        entityManager.clear();

        FirewallViolationEntity loaded = violations.findById(violation.getId()).orElseThrow();
        assertThat(loaded.getAdvisoryIds()).containsExactly("CVE-2021-44228", "GHSA-jfh8-c2jp-5v3q");
        assertThat(loaded.getRequestContext()).containsEntry("user", "ci-runner");
        assertThat(loaded.getRuleType()).isEqualTo(FirewallRuleType.CVSS_THRESHOLD);
        assertThat(loaded.getAction()).isEqualTo(FirewallAction.WARN);
        assertThat(loaded.getOccurredAt()).isNotNull();
    }

    @Test
    @DisplayName("violation defaults: empty advisory array and empty context")
    void violationDefaultsAreEmptyNotNull() {
        FirewallViolationEntity violation = new FirewallViolationEntity();
        violation.setRepositoryName("maven-central");
        violation.setPurl("pkg:npm/left-pad@1.3.0");
        violation.setRuleType(FirewallRuleType.UNKNOWN_COMPONENT);
        violation.setAction(FirewallAction.WARN);
        violations.saveAndFlush(violation);

        entityManager.clear();

        FirewallViolationEntity loaded = violations.findById(violation.getId()).orElseThrow();
        assertThat(loaded.getRepositoryId()).as("violations survive without a repository row").isNull();
        assertThat(loaded.getAdvisoryIds()).isEmpty();
        assertThat(loaded.getRequestContext()).isEmpty();
    }

    private RepositoryEntity newRepository() {
        RepositoryEntity repository = new RepositoryEntity();
        repository.setName("repo-" + UUID.randomUUID());
        repository.setFormat("maven2");
        repository.setType("PROXY");
        repository.setBlobStoreName("default");
        repository.setCreatedAt(Instant.now());
        repository.setUpdatedAt(Instant.now());
        return repositories.saveAndFlush(repository);
    }
}
