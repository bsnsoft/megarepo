package de.bsnsoft.megarepo.repository.firewall.quarantine;

import de.bsnsoft.megarepo.core.firewall.FirewallAction;
import de.bsnsoft.megarepo.core.firewall.FirewallExemptionState;
import de.bsnsoft.megarepo.core.firewall.FirewallFactsState;
import de.bsnsoft.megarepo.core.firewall.FirewallFailMode;
import de.bsnsoft.megarepo.core.firewall.FirewallMode;
import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineReason;
import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineResolution;
import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineState;
import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;
import de.bsnsoft.megarepo.database.entity.FirewallPolicyEntity;
import de.bsnsoft.megarepo.database.entity.FirewallPolicyRuleEntity;
import de.bsnsoft.megarepo.database.entity.FirewallQuarantineEntity;
import de.bsnsoft.megarepo.database.entity.RepositoryEntity;
import de.bsnsoft.megarepo.database.repository.FirewallPolicyJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallPolicyRuleJpaRepository;
import de.bsnsoft.megarepo.database.repository.RepositoryJpaRepository;
import de.bsnsoft.megarepo.repository.advisory.AdvisoryLookupService;
import de.bsnsoft.megarepo.repository.firewall.FirewallEvaluationService;
import de.bsnsoft.megarepo.repository.firewall.FirewallRepositorySettings;
import de.bsnsoft.megarepo.repository.firewall.FirewallRuleViolation;
import de.bsnsoft.megarepo.repository.firewall.exemption.ExemptionService;
import de.bsnsoft.megarepo.repository.firewall.exemption.FirewallExemption;
import de.bsnsoft.megarepo.repository.firewall.facts.ComponentFacts;
import de.bsnsoft.megarepo.repository.firewall.facts.ComponentFactsService;
import de.bsnsoft.megarepo.repository.firewall.identity.ComponentIdentity;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRule;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleContext;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleOutcome;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleRegistry;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The automatic-release engine, without a database.
 *
 * <p>The {@code MIN_AGE} rule this drives is a stub, deliberately: the real one
 * belongs to another work package, and what is under test here is not whether a
 * date comparison is right but whether a "not any more" answer produces a
 * release with the right resolution and a "still" answer produces a hold
 * scheduled for the right minute.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuarantineReevaluatorTest {

    private static final UUID REPOSITORY = UUID.randomUUID();
    private static final String KEY = "pkg:maven/com.acme/util@1.0.0";
    private static final Instant NOW = Instant.parse("2026-08-24T12:00:00Z");
    private static final Instant PUBLISHED = Instant.parse("2026-08-20T12:00:00Z");

    @Mock private FirewallEvaluationService evaluationService;
    @Mock private FirewallPolicyJpaRepository policies;
    @Mock private FirewallPolicyRuleJpaRepository policyRules;
    @Mock private AdvisoryLookupService advisories;
    @Mock private RepositoryJpaRepository repositories;
    @Mock private ComponentFactsService factsService;
    @Mock private ExemptionService exemptionService;

    private final UUID policyId = UUID.randomUUID();
    private StubRule minAgeRule;

    @BeforeEach
    void setUp() {
        FirewallPolicyEntity policy = new FirewallPolicyEntity();
        policy.setId(policyId);
        policy.setName("Default");
        when(policies.findByIsDefaultTrue()).thenReturn(Optional.of(policy));
        when(policies.findById(policyId)).thenReturn(Optional.of(policy));

        RepositoryEntity repository = new RepositoryEntity();
        repository.setType("PROXY");
        when(repositories.findById(REPOSITORY)).thenReturn(Optional.of(repository));

        when(evaluationService.resolveSettings(REPOSITORY)).thenReturn(settings(FirewallFailMode.FAIL_OPEN));
        when(advisories.findAdvisories(any(ComponentIdentity.class))).thenReturn(List.of());
        when(factsService.lookup(any())).thenReturn(resolvedFacts());
        when(exemptionService.findApplicable(any(), any(), any())).thenReturn(List.of());
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("a MIN_AGE entry is released with AGE_REACHED once the rule stops objecting")
    void releasesWhenTheAgeIsReached() {
        minAgeRule = StubRule.notMatching(FirewallRuleType.MIN_AGE);
        givenRules(rule(FirewallRuleType.MIN_AGE, FirewallAction.BLOCK, Map.of("minAge", "P2D")));

        QuarantineReevaluator.Verdict verdict =
                reevaluator().reevaluate(entry(FirewallQuarantineReason.MIN_AGE_NOT_MET), NOW);

        assertThat(verdict.outcome()).isEqualTo(QuarantineReevaluator.Outcome.RELEASE);
        assertThat(verdict.resolution()).isEqualTo(FirewallQuarantineResolution.AGE_REACHED);
        assertThat(verdict.note())
                .as("the customer asked for a recorded reason, not a recorded timestamp")
                .contains("2026-08-20");
    }

    @Test
    @DisplayName("a MIN_AGE entry that is still too new is scheduled for the exact minute it ripens")
    void schedulesTheExactMoment() {
        minAgeRule = StubRule.matching(FirewallRuleType.MIN_AGE, FirewallAction.BLOCK, true);
        givenRules(rule(FirewallRuleType.MIN_AGE, FirewallAction.BLOCK, Map.of("minAge", "P7D")));

        QuarantineReevaluator.Verdict verdict =
                reevaluator().reevaluate(entry(FirewallQuarantineReason.MIN_AGE_NOT_MET), NOW);

        assertThat(verdict.outcome()).isEqualTo(QuarantineReevaluator.Outcome.HOLD);
        assertThat(verdict.nextEvaluationAt())
                .as("polling every quarter of an hour to learn nothing is the thing the column avoids")
                .isEqualTo(PUBLISHED.plus(Duration.ofDays(7)));
    }

    @Test
    @DisplayName("without a publication date a hold backs off instead of guessing a moment")
    void unresolvedFactsFallBackToBackoff() {
        minAgeRule = StubRule.matching(FirewallRuleType.MIN_AGE, FirewallAction.BLOCK, true);
        givenRules(rule(FirewallRuleType.MIN_AGE, FirewallAction.BLOCK, Map.of("minAge", "P7D")));
        when(factsService.lookup(any())).thenReturn(ComponentFacts.unknown(KEY));

        QuarantineReevaluator.Verdict verdict =
                reevaluator().reevaluate(entry(FirewallQuarantineReason.MIN_AGE_NOT_MET), NOW);

        assertThat(verdict.outcome()).isEqualTo(QuarantineReevaluator.Outcome.HOLD);
        assertThat(verdict.nextEvaluationAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
    }

    @Test
    @DisplayName("the backoff doubles and stops at the configured ceiling")
    void backoffDoublesAndIsCapped() {
        minAgeRule = StubRule.matching(FirewallRuleType.UNKNOWN_COMPONENT, FirewallAction.BLOCK, true);
        givenRules(rule(FirewallRuleType.UNKNOWN_COMPONENT, FirewallAction.BLOCK, Map.of()));

        FirewallQuarantineEntity entity = entry(FirewallQuarantineReason.UNKNOWN_COMPONENT);
        entity.setLastEvaluatedAt(NOW.minus(Duration.ofHours(2)));
        entity.setNextEvaluationAt(NOW.minus(Duration.ofHours(1)));

        assertThat(reevaluator().reevaluate(entity, NOW).nextEvaluationAt())
                .isEqualTo(NOW.plus(Duration.ofHours(2)));

        entity.setLastEvaluatedAt(NOW.minus(Duration.ofHours(10)));
        entity.setNextEvaluationAt(NOW.minus(Duration.ofHours(1)));

        assertThat(reevaluator().reevaluate(entity, NOW).nextEvaluationAt())
                .as("an UNKNOWN_COMPONENT hold has no predictable release time; the sweep must keep looking")
                .isEqualTo(NOW.plus(Duration.ofHours(6)));
    }

    @Test
    @DisplayName("a rule that turned blocking moves the entry to BLOCKED, not out of the queue")
    void aBlockingViolationBlocks() {
        minAgeRule = StubRule.matching(FirewallRuleType.CVSS_THRESHOLD, FirewallAction.BLOCK, false);
        givenRules(rule(FirewallRuleType.CVSS_THRESHOLD, FirewallAction.BLOCK, Map.of()));

        QuarantineReevaluator.Verdict verdict =
                reevaluator().reevaluate(entry(FirewallQuarantineReason.UNKNOWN_COMPONENT), NOW);

        assertThat(verdict.outcome()).isEqualTo(QuarantineReevaluator.Outcome.BLOCK);
        assertThat(verdict.resolution()).isEqualTo(FirewallQuarantineResolution.POLICY_VIOLATION);
        assertThat(verdict.note()).contains("stub rule matched");
    }

    @Test
    @DisplayName("a WARN rule that matches does not hold anything")
    void warnDoesNotHold() {
        minAgeRule = StubRule.matching(FirewallRuleType.MIN_AGE, FirewallAction.WARN, true);
        givenRules(rule(FirewallRuleType.MIN_AGE, FirewallAction.WARN, Map.of()));

        assertThat(reevaluator().reevaluate(entry(FirewallQuarantineReason.MIN_AGE_NOT_MET), NOW)
                .outcome())
                .isEqualTo(QuarantineReevaluator.Outcome.RELEASE);
    }

    @Test
    @DisplayName("INDETERMINATE holds a fail-closed repository and releases a fail-open one")
    void indeterminateFollowsTheFailMode() {
        minAgeRule = StubRule.indeterminate(FirewallRuleType.MIN_AGE);
        givenRules(rule(FirewallRuleType.MIN_AGE, FirewallAction.BLOCK, Map.of()));

        when(evaluationService.resolveSettings(REPOSITORY))
                .thenReturn(settings(FirewallFailMode.FAIL_CLOSED));
        assertThat(reevaluator().reevaluate(entry(FirewallQuarantineReason.EVALUATION_INCOMPLETE), NOW)
                .outcome())
                .isEqualTo(QuarantineReevaluator.Outcome.HOLD);

        when(evaluationService.resolveSettings(REPOSITORY))
                .thenReturn(settings(FirewallFailMode.FAIL_OPEN));
        assertThat(reevaluator().reevaluate(entry(FirewallQuarantineReason.EVALUATION_INCOMPLETE), NOW)
                .outcome())
                .as("a repository that serves what it cannot judge should not hold what it cannot judge")
                .isEqualTo(QuarantineReevaluator.Outcome.RELEASE);
    }

    @Test
    @DisplayName("the rule being disabled is POLICY_CHANGED, not AGE_REACHED")
    void aDisabledRuleIsNotAnAgeRelease() {
        minAgeRule = StubRule.notMatching(FirewallRuleType.MIN_AGE);
        givenRules(rule(FirewallRuleType.CVSS_THRESHOLD, FirewallAction.BLOCK, Map.of()));

        QuarantineReevaluator.Verdict verdict =
                reevaluator().reevaluate(entry(FirewallQuarantineReason.MIN_AGE_NOT_MET), NOW);

        assertThat(verdict.resolution()).isEqualTo(FirewallQuarantineResolution.POLICY_CHANGED);
    }

    @Test
    @DisplayName("no enabled rule at all releases everything the policy used to hold")
    void anEmptyPolicyReleases() {
        minAgeRule = StubRule.notMatching(FirewallRuleType.MIN_AGE);
        givenRules();

        QuarantineReevaluator.Verdict verdict =
                reevaluator().reevaluate(entry(FirewallQuarantineReason.MIN_AGE_NOT_MET), NOW);

        assertThat(verdict.outcome()).isEqualTo(QuarantineReevaluator.Outcome.RELEASE);
        assertThat(verdict.resolution()).isEqualTo(FirewallQuarantineResolution.POLICY_CHANGED);
    }

    @Test
    @DisplayName("an approved exemption releases before the rules are even asked")
    void anExemptionReleases() {
        minAgeRule = StubRule.matching(FirewallRuleType.MIN_AGE, FirewallAction.BLOCK, true);
        givenRules(rule(FirewallRuleType.MIN_AGE, FirewallAction.BLOCK, Map.of()));
        UUID exemptionId = UUID.randomUUID();
        when(exemptionService.findApplicable(any(), any(), any()))
                .thenReturn(List.of(exemption(exemptionId)));

        QuarantineReevaluator.Verdict verdict =
                reevaluator().reevaluate(entry(FirewallQuarantineReason.MIN_AGE_NOT_MET), NOW);

        assertThat(verdict.outcome()).isEqualTo(QuarantineReevaluator.Outcome.RELEASE);
        assertThat(verdict.resolution()).isEqualTo(FirewallQuarantineResolution.EXEMPTION_GRANTED);
        assertThat(verdict.exemptionId()).isEqualTo(exemptionId);
    }

    @Test
    @DisplayName("a re-evaluation that throws leaves the entry held")
    void aFailingReevaluationHolds() {
        minAgeRule = StubRule.notMatching(FirewallRuleType.MIN_AGE);
        when(evaluationService.resolveSettings(REPOSITORY))
                .thenThrow(new IllegalStateException("config table unreachable"));

        QuarantineReevaluator.Verdict verdict =
                reevaluator().reevaluate(entry(FirewallQuarantineReason.MIN_AGE_NOT_MET), NOW);

        assertThat(verdict.outcome())
                .as("releasing on a defect serves a component nobody cleared")
                .isEqualTo(QuarantineReevaluator.Outcome.HOLD);
    }

    @Test
    @DisplayName("component keys are read back into the identity they were written from")
    void identityRoundTrip() {
        assertThat(QuarantineReevaluator.identityOf(KEY))
                .isInstanceOf(ComponentIdentity.Purl.class)
                .extracting(ComponentIdentity::key).isEqualTo(KEY);
        assertThat(QuarantineReevaluator.identityOf("sha256:abc123"))
                .isInstanceOf(ComponentIdentity.Hash.class)
                .extracting(ComponentIdentity::key).isEqualTo("sha256:abc123");
        assertThat(QuarantineReevaluator.identityOf("unidentified:raw//thing@"))
                .isInstanceOf(ComponentIdentity.Unidentified.class);
        assertThat(QuarantineReevaluator.identityOf("pkg:not a purl at all"))
                .isInstanceOf(ComponentIdentity.Unidentified.class);
    }

    @Test
    @DisplayName("facts and exemption services that are not on this build are simply absent")
    void missingCollaboratorsAreTolerated() {
        minAgeRule = StubRule.notMatching(FirewallRuleType.MIN_AGE);
        givenRules(rule(FirewallRuleType.MIN_AGE, FirewallAction.BLOCK, Map.of()));

        QuarantineReevaluator bare = new QuarantineReevaluator(
                evaluationService, policies, policyRules, registry(), advisories, repositories,
                absent(), absent(), QuarantineProperties.defaults());

        assertThat(bare.reevaluate(entry(FirewallQuarantineReason.MIN_AGE_NOT_MET), NOW).outcome())
                .isEqualTo(QuarantineReevaluator.Outcome.RELEASE);
    }

    // ------------------------------------------------------------------

    private QuarantineReevaluator reevaluator() {
        return new QuarantineReevaluator(
                evaluationService, policies, policyRules, registry(), advisories, repositories,
                provider(factsService), provider(exemptionService), QuarantineProperties.defaults());
    }

    private FirewallRuleRegistry registry() {
        return new FirewallRuleRegistry(minAgeRule == null ? List.of() : List.of(minAgeRule));
    }

    private void givenRules(FirewallPolicyRuleEntity... rules) {
        when(policyRules.findByPolicyIdAndEnabledTrue(policyId)).thenReturn(List.of(rules));
    }

    private static FirewallPolicyRuleEntity rule(
            FirewallRuleType type, FirewallAction action, Map<String, Object> config) {
        FirewallPolicyRuleEntity rule = new FirewallPolicyRuleEntity();
        rule.setId(UUID.randomUUID());
        rule.setRuleType(type);
        rule.setAction(action);
        rule.setConfig(config);
        rule.setEnabled(true);
        return rule;
    }

    private static FirewallQuarantineEntity entry(FirewallQuarantineReason reason) {
        FirewallQuarantineEntity entity = new FirewallQuarantineEntity();
        entity.setId(UUID.randomUUID());
        entity.setRepositoryId(REPOSITORY);
        entity.setRepositoryName("maven-central");
        entity.setComponentKey(KEY);
        entity.setPath("com/acme/util/1.0.0/util-1.0.0.jar");
        entity.setState(FirewallQuarantineState.QUARANTINED);
        entity.setReasonCode(reason);
        return entity;
    }

    private static FirewallRepositorySettings settings(FirewallFailMode failMode) {
        return new FirewallRepositorySettings(FirewallMode.QUARANTINE, failMode, null, true);
    }

    private static ComponentFacts resolvedFacts() {
        return new ComponentFacts(KEY, FirewallFactsState.RESOLVED, PUBLISHED, List.of(),
                "PACKAGE_METADATA", "maven-pom", NOW);
    }

    private static FirewallExemption exemption(UUID id) {
        return new FirewallExemption(id, KEY, null, null, REPOSITORY, null, List.of(),
                FirewallExemptionState.APPROVED, null, null, "signed off by security", "alice",
                NOW, "bob", NOW, null);
    }

    private static <T> ObjectProvider<T> provider(T value) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private static <T> ObjectProvider<T> absent() {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    /** A rule with a fixed answer, standing in for the packages that own the real ones. */
    private record StubRule(
            FirewallRuleType ruleType,
            FirewallRuleOutcome outcome,
            boolean quarantineOnMatch) implements FirewallRule {

        static StubRule notMatching(FirewallRuleType type) {
            return new StubRule(type, FirewallRuleOutcome.notMatched(), true);
        }

        static StubRule matching(FirewallRuleType type, FirewallAction action, boolean holds) {
            return new StubRule(type, FirewallRuleOutcome.matched(new FirewallRuleViolation(
                    type, action, "stub rule matched", List.of())), holds);
        }

        static StubRule indeterminate(FirewallRuleType type) {
            return new StubRule(type,
                    FirewallRuleOutcome.indeterminate("the publication date has not resolved yet"),
                    true);
        }

        @Override
        public FirewallRuleOutcome evaluate(FirewallRuleContext context, FirewallRuleSettings settings) {
            return outcome;
        }

        @Override
        public boolean quarantineOnMatch() {
            return quarantineOnMatch;
        }

        @Override
        public FirewallQuarantineReason quarantineReason() {
            return ruleType == FirewallRuleType.MIN_AGE
                    ? FirewallQuarantineReason.MIN_AGE_NOT_MET
                    : FirewallQuarantineReason.UNKNOWN_COMPONENT;
        }

        @Override
        public boolean appliesToUnidentifiedComponents() {
            return true;
        }
    }
}
