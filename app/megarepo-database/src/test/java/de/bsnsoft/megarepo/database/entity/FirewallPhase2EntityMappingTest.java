package de.bsnsoft.megarepo.database.entity;

import de.bsnsoft.megarepo.core.firewall.FirewallComponentKeyKind;
import de.bsnsoft.megarepo.core.firewall.FirewallExemptionScope;
import de.bsnsoft.megarepo.core.firewall.FirewallExemptionState;
import de.bsnsoft.megarepo.core.firewall.FirewallFactsState;
import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineReason;
import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineResolution;
import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineState;
import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;
import de.bsnsoft.megarepo.database.PostgresTestSupport;
import de.bsnsoft.megarepo.database.repository.FirewallComponentFactsJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallExemptionJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallQuarantineJpaRepository;
import de.bsnsoft.megarepo.database.repository.RepositoryJpaRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the Phase 2 firewall entities — quarantine, exemptions and component
 * facts — against the real migrated schema.
 *
 * <p>The context runs with {@code ddl-auto=validate}, exactly as
 * {@link FirewallEntityMappingTest} does for Phase 1: an entity that disagrees
 * with the Flyway schema fails the context, and every test in the class with it.
 * That is half the value of the class; the round-trips below are the other half,
 * and they concentrate on the things a plain getter/setter test would not catch —
 * the {@code TEXT[]} columns, the JSONB snapshot, the enum-by-name persistence
 * and the derived queries the request path depends on.
 */
@SpringBootTest
@Transactional
class FirewallPhase2EntityMappingTest {

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

    @Autowired private FirewallQuarantineJpaRepository quarantine;
    @Autowired private FirewallExemptionJpaRepository exemptions;
    @Autowired private FirewallComponentFactsJpaRepository facts;
    @Autowired private RepositoryJpaRepository repositories;
    @Autowired private EntityManager entityManager;

    @Test
    @DisplayName("quarantine entry round-trips, including the JSONB evaluation snapshot")
    void quarantineRoundTrip() {
        RepositoryEntity repository = newRepository();
        String key = "pkg:npm/left-pad@" + UUID.randomUUID();

        FirewallQuarantineEntity entry = new FirewallQuarantineEntity();
        entry.setRepositoryId(repository.getId());
        entry.setRepositoryName(repository.getName());
        entry.setComponentKey(key);
        entry.setPath("left-pad/-/left-pad-1.3.0.tgz");
        entry.setReasonCode(FirewallQuarantineReason.MIN_AGE_NOT_MET);
        entry.setEvaluation(Map.of(
                "rule", "MIN_AGE",
                "publishedAt", "2026-08-24T09:00:00Z",
                "minAge", "P7D"));
        entry.setNextEvaluationAt(Instant.now().plus(Duration.ofDays(7)).truncatedTo(ChronoUnit.MILLIS));
        quarantine.saveAndFlush(entry);

        entityManager.clear();

        FirewallQuarantineEntity loaded =
                quarantine.findByRepositoryIdAndComponentKey(repository.getId(), key).orElseThrow();
        assertThat(loaded.getState()).isEqualTo(FirewallQuarantineState.QUARANTINED);
        assertThat(loaded.getReasonCode()).isEqualTo(FirewallQuarantineReason.MIN_AGE_NOT_MET);
        assertThat(loaded.getResolution()).as("nothing has decided yet").isNull();
        assertThat(loaded.getEvaluation()).containsEntry("rule", "MIN_AGE");
        assertThat(loaded.getHitCount()).isEqualTo(1);
        assertThat(loaded.getNextEvaluationAt()).isNotNull();
    }

    @Test
    @DisplayName("enums are persisted by name, never by ordinal")
    void quarantineEnumsArePersistedAsStrings() {
        RepositoryEntity repository = newRepository();

        FirewallQuarantineEntity entry = new FirewallQuarantineEntity();
        entry.setRepositoryId(repository.getId());
        entry.setRepositoryName(repository.getName());
        entry.setComponentKey("pkg:pypi/requests@" + UUID.randomUUID());
        entry.setReasonCode(FirewallQuarantineReason.UNKNOWN_COMPONENT);
        entry.setState(FirewallQuarantineState.RELEASED);
        entry.setResolution(FirewallQuarantineResolution.ADVISORY_DATA_ARRIVED);
        entry.setDecidedAt(Instant.now());
        entry.setDecidedBy("system");
        entry = quarantine.saveAndFlush(entry);

        Object[] row = (Object[]) entityManager
                .createNativeQuery(
                        "SELECT state, reason_code, resolution FROM firewall_quarantine WHERE id = :id")
                .setParameter("id", entry.getId())
                .getSingleResult();

        assertThat(row[0]).isEqualTo("RELEASED");
        assertThat(row[1]).isEqualTo("UNKNOWN_COMPONENT");
        assertThat(row[2]).isEqualTo("ADVISORY_DATA_ARRIVED");
    }

    @Test
    @DisplayName("the re-evaluation sweep finds due entries, and never decided ones")
    void dueForReevaluation() {
        RepositoryEntity repository = newRepository();
        Instant now = Instant.now();

        FirewallQuarantineEntity due = held(repository, "pkg:maven/com.acme/due@1.0");
        due.setNextEvaluationAt(now.minus(Duration.ofMinutes(1)));
        quarantine.saveAndFlush(due);

        FirewallQuarantineEntity notYet = held(repository, "pkg:maven/com.acme/later@1.0");
        notYet.setNextEvaluationAt(now.plus(Duration.ofHours(2)));
        quarantine.saveAndFlush(notYet);

        // Never scheduled: has to be picked up, or an entry whose schedule was
        // lost to a restart would sit in the queue forever.
        quarantine.saveAndFlush(held(repository, "pkg:maven/com.acme/unscheduled@1.0"));

        FirewallQuarantineEntity released = held(repository, "pkg:maven/com.acme/released@1.0");
        released.setState(FirewallQuarantineState.RELEASED);
        released.setResolution(FirewallQuarantineResolution.RE_EVALUATED_CLEAN);
        released.setDecidedAt(now);
        released.setDecidedBy("system");
        quarantine.saveAndFlush(released);

        entityManager.clear();

        List<FirewallQuarantineEntity> found =
                quarantine.findDueForReevaluation(now, PageRequest.of(0, 50));

        assertThat(found)
                .extracting(FirewallQuarantineEntity::getComponentKey)
                .contains("pkg:maven/com.acme/due@1.0", "pkg:maven/com.acme/unscheduled@1.0")
                .doesNotContain("pkg:maven/com.acme/later@1.0", "pkg:maven/com.acme/released@1.0");
    }

    @Test
    @DisplayName("a hit is counted without loading the entry")
    void recordHitIncrements() {
        RepositoryEntity repository = newRepository();
        FirewallQuarantineEntity entry =
                quarantine.saveAndFlush(held(repository, "pkg:npm/hot@1.0.0"));
        Instant seenAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        assertThat(quarantine.recordHit(entry.getId(), seenAt)).isEqualTo(1);
        entityManager.clear();

        assertThat(quarantine.findById(entry.getId()).orElseThrow().getHitCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("exemption round-trips, including TEXT[] advisory ids and a null expiry")
    void exemptionRoundTrip() {
        String key = "pkg:maven/com.acme/util@" + UUID.randomUUID();

        FirewallExemptionEntity exemption = new FirewallExemptionEntity();
        exemption.setComponentKey(key);
        exemption.setScopeType(FirewallExemptionScope.VERSION);
        exemption.setRuleType(FirewallRuleType.CVSS_THRESHOLD);
        exemption.setAdvisoryIds(new String[] {"CVE-2021-44228", "GHSA-jfh8-c2jp-5v3q"});
        exemption.setState(FirewallExemptionState.APPROVED);
        exemption.setJustification("mitigated by configuration, tracked in TICKET-4711");
        exemption.setRequestedBy("dev");
        exemption.setApprovedBy("security");
        exemption.setApprovedAt(Instant.now());
        exemptions.saveAndFlush(exemption);

        entityManager.clear();

        FirewallExemptionEntity loaded = exemptions.findById(exemption.getId()).orElseThrow();
        assertThat(loaded.getKeyKind())
                .as("anything Phase 2 creates is purl-keyed")
                .isEqualTo(FirewallComponentKeyKind.PURL);
        assertThat(loaded.getAdvisoryIds()).containsExactly("CVE-2021-44228", "GHSA-jfh8-c2jp-5v3q");
        assertThat(loaded.getRuleType()).isEqualTo(FirewallRuleType.CVSS_THRESHOLD);
        assertThat(loaded.getExpiresAt()).as("null expiry means never").isNull();
        assertThat(loaded.getRepositoryId()).as("null repository means all of them").isNull();
    }

    @Test
    @DisplayName("the applicability query respects repository scope and expiry")
    void findApplicableRespectsScopeAndExpiry() {
        RepositoryEntity repository = newRepository();
        RepositoryEntity other = newRepository();
        Instant now = Instant.now();
        String key = "pkg:npm/scoped@" + UUID.randomUUID();

        exemptions.saveAndFlush(approved(key, null, null));
        exemptions.saveAndFlush(approved(key, other.getId(), null));
        exemptions.saveAndFlush(approved(key, repository.getId(), now.minus(Duration.ofHours(1))));

        FirewallExemptionEntity requested = approved(key, repository.getId(), null);
        requested.setState(FirewallExemptionState.REQUESTED);
        requested.setApprovedBy(null);
        requested.setApprovedAt(null);
        exemptions.saveAndFlush(requested);

        entityManager.clear();

        List<FirewallExemptionEntity> applicable =
                exemptions.findApplicable(List.of(key), repository.getId(), now);

        // The global one, and nothing else: the other repository's is out of
        // scope, the lapsed one is filtered by expiry even though the daily sweep
        // has not flipped its state, and a request nobody approved lets nothing
        // through.
        assertThat(applicable).hasSize(1);
        assertThat(applicable.get(0).getRepositoryId()).isNull();
    }

    @Test
    @DisplayName("the expiry sweep finds lapsed exemptions and announces upcoming ones once")
    void expirySweepQueries() {
        Instant now = Instant.now();
        String lapsedKey = "pkg:npm/lapsed@" + UUID.randomUUID();
        String soonKey = "pkg:npm/soon@" + UUID.randomUUID();
        String announcedKey = "pkg:npm/announced@" + UUID.randomUUID();

        exemptions.saveAndFlush(approved(lapsedKey, null, now.minus(Duration.ofMinutes(5))));
        exemptions.saveAndFlush(approved(soonKey, null, now.plus(Duration.ofDays(3))));

        FirewallExemptionEntity announced = approved(announcedKey, null, now.plus(Duration.ofDays(3)));
        announced.setExpiryNotifiedAt(now.minus(Duration.ofDays(1)));
        exemptions.saveAndFlush(announced);

        entityManager.clear();

        assertThat(exemptions.findExpired(now))
                .extracting(FirewallExemptionEntity::getComponentKey)
                .contains(lapsedKey)
                .doesNotContain(soonKey);

        assertThat(exemptions.findDueForExpiryNotice(now, now.plus(Duration.ofDays(7))))
                .extracting(FirewallExemptionEntity::getComponentKey)
                .contains(soonKey)
                .as("a notice already sent is not sent again")
                .doesNotContain(announcedKey);
    }

    @Test
    @DisplayName("component facts round-trip, including an empty declared-license array")
    void componentFactsRoundTrip() {
        String purl = "pkg:maven/com.acme/util@" + UUID.randomUUID();

        FirewallComponentFactsEntity resolved = new FirewallComponentFactsEntity();
        resolved.setPurl(purl);
        resolved.setPurlType("maven");
        resolved.setState(FirewallFactsState.RESOLVED);
        resolved.setPublishedAt(Instant.now().minus(Duration.ofDays(400)).truncatedTo(ChronoUnit.MILLIS));
        resolved.setDeclaredLicenses(new String[] {"Apache-2.0"});
        resolved.setLicenseSource("PACKAGE_METADATA");
        resolved.setSource("maven-pom");
        resolved.setFetchedAt(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        facts.saveAndFlush(resolved);

        String silentPurl = "pkg:generic/mystery@" + UUID.randomUUID();
        FirewallComponentFactsEntity silent = new FirewallComponentFactsEntity();
        silent.setPurl(silentPurl);
        silent.setPurlType("generic");
        silent.setState(FirewallFactsState.RESOLVED);
        facts.saveAndFlush(silent);

        entityManager.clear();

        FirewallComponentFactsEntity loaded = facts.findById(purl).orElseThrow();
        assertThat(loaded.getDeclaredLicenses()).containsExactly("Apache-2.0");
        assertThat(loaded.getState()).isEqualTo(FirewallFactsState.RESOLVED);
        assertThat(loaded.getAttempts()).isZero();

        // Resolved with nothing to say is a settled answer, not a missing one:
        // the metadata was read and is silent, and a MIN_AGE rule must not hold
        // the component forever waiting for a date that is never coming.
        FirewallComponentFactsEntity loadedSilent = facts.findById(silentPurl).orElseThrow();
        assertThat(loadedSilent.getPublishedAt()).isNull();
        assertThat(loadedSilent.getDeclaredLicenses()).isEmpty();
        assertThat(loadedSilent.getLicenseSource()).isNull();
    }

    @Test
    @DisplayName("the resolver picks up both unsettled states, oldest first")
    void unresolvedFactsQuery() {
        String unknown = "pkg:npm/unknown@" + UUID.randomUUID();
        String pending = "pkg:npm/pending@" + UUID.randomUUID();
        String settled = "pkg:npm/settled@" + UUID.randomUUID();

        facts.saveAndFlush(fact(unknown, FirewallFactsState.UNKNOWN));
        facts.saveAndFlush(fact(pending, FirewallFactsState.PENDING));
        facts.saveAndFlush(fact(settled, FirewallFactsState.RESOLVED));

        entityManager.clear();

        assertThat(facts.findUnresolved(
                List.of(FirewallFactsState.UNKNOWN, FirewallFactsState.PENDING),
                PageRequest.of(0, 50)))
                .extracting(FirewallComponentFactsEntity::getPurl)
                .contains(unknown, pending)
                .doesNotContain(settled);
    }

    private FirewallQuarantineEntity held(RepositoryEntity repository, String componentKey) {
        FirewallQuarantineEntity entry = new FirewallQuarantineEntity();
        entry.setRepositoryId(repository.getId());
        entry.setRepositoryName(repository.getName());
        entry.setComponentKey(componentKey);
        entry.setReasonCode(FirewallQuarantineReason.MIN_AGE_NOT_MET);
        return entry;
    }

    private static FirewallExemptionEntity approved(String key, UUID repositoryId, Instant expiresAt) {
        FirewallExemptionEntity exemption = new FirewallExemptionEntity();
        exemption.setComponentKey(key);
        exemption.setRepositoryId(repositoryId);
        exemption.setExpiresAt(expiresAt);
        exemption.setState(FirewallExemptionState.APPROVED);
        exemption.setJustification("test");
        exemption.setRequestedBy("dev");
        exemption.setApprovedBy("security");
        exemption.setApprovedAt(Instant.now());
        return exemption;
    }

    private static FirewallComponentFactsEntity fact(String purl, FirewallFactsState state) {
        FirewallComponentFactsEntity entity = new FirewallComponentFactsEntity();
        entity.setPurl(purl);
        entity.setPurlType("npm");
        entity.setState(state);
        return entity;
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
