package de.bsnsoft.megarepo.repository.firewall;

import com.github.packageurl.MalformedPackageURLException;
import com.github.packageurl.PackageURL;
import de.bsnsoft.megarepo.core.firewall.FirewallAction;
import de.bsnsoft.megarepo.core.firewall.FirewallFailMode;
import de.bsnsoft.megarepo.core.firewall.FirewallMode;
import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineReason;
import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;
import de.bsnsoft.megarepo.core.repository.RepositoryType;
import de.bsnsoft.megarepo.database.entity.AssetEntity;
import de.bsnsoft.megarepo.database.entity.FirewallPolicyEntity;
import de.bsnsoft.megarepo.database.entity.FirewallPolicyRuleEntity;
import de.bsnsoft.megarepo.database.repository.AssetJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallPolicyJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallPolicyRuleJpaRepository;
import de.bsnsoft.megarepo.repository.advisory.AdvisoryLookupService;
import de.bsnsoft.megarepo.repository.firewall.facts.ComponentFactsService;
import de.bsnsoft.megarepo.repository.firewall.identity.ComponentIdentity;
import de.bsnsoft.megarepo.repository.firewall.identity.PurlBuilder;
import de.bsnsoft.megarepo.repository.firewall.quarantine.QuarantineService;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRule;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleContext;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleOutcome;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleRegistry;
import de.bsnsoft.megarepo.repository.firewall.rule.FirewallRuleSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Uploads into a hosted repository, judged the way a download is.
 *
 * <p>The rule is a stub — what is under test is the gate around it: the two
 * switches, the repository type, grandfathering, and that a refused publish
 * leaves a queue entry an operator can act on rather than only a 403 in a CI
 * log.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FirewallUploadEvaluatorTest {

    private static final UUID REPOSITORY = UUID.randomUUID();
    private static final String NAME = "maven-releases";
    private static final String PATH = "com/acme/util/1.0.0/util-1.0.0.jar";
    private static final String KEY = "pkg:maven/com.acme/util@1.0.0";
    private static final FirewallRequestContext CONTEXT =
            new FirewallRequestContext("release-bot", "10.0.0.7", PATH, "PUT");

    @Mock private FirewallEvaluationService evaluationService;
    @Mock private FirewallEnforcementSettingsService enforcementSettings;
    @Mock private FirewallPolicyJpaRepository policies;
    @Mock private FirewallPolicyRuleJpaRepository policyRules;
    @Mock private AdvisoryLookupService advisories;
    @Mock private AssetJpaRepository assets;
    @Mock private PurlBuilder purlBuilder;
    @Mock private QuarantineService quarantine;
    @Mock private ComponentFactsService facts;

    private final UUID policyId = UUID.randomUUID();
    private StubRule rule;

    @BeforeEach
    void setUp() {
        FirewallPolicyEntity policy = new FirewallPolicyEntity();
        policy.setId(policyId);
        policy.setName("Default");
        when(policies.findByIsDefaultTrue()).thenReturn(Optional.of(policy));

        when(enforcementSettings.enforcementEnabled()).thenReturn(true);
        when(enforcementSettings.enforcingSince()).thenReturn(Instant.parse("2026-01-01T00:00:00Z"));
        when(evaluationService.resolveSettings(REPOSITORY))
                .thenReturn(settings(FirewallMode.QUARANTINE, FirewallFailMode.FAIL_OPEN));
        when(advisories.findAdvisories(any(ComponentIdentity.class))).thenReturn(List.of());
        when(assets.findByRepositoryIdAndPath(any(), any())).thenReturn(Optional.empty());
        when(facts.lookup(any())).thenReturn(null);
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("a policy-denied upload is refused, with the policy and rule the 403 needs")
    void deniedUploadIsRefused() {
        rule = StubRule.matching(FirewallRuleType.KNOWN_MALICIOUS, FirewallAction.BLOCK, false);
        givenRules(rule(FirewallRuleType.KNOWN_MALICIOUS, FirewallAction.BLOCK));

        FirewallEvaluation verdict = evaluator().evaluate(candidate(RepositoryType.HOSTED), CONTEXT);

        assertThat(verdict.blocked()).isTrue();
        assertThat(verdict.decision().reason()).isEqualTo(FirewallDecision.Reason.POLICY);
        assertThat(verdict.decision().policyName()).isEqualTo("Default");
        assertThat(verdict.decision().blockingViolations()).hasSize(1);
    }

    @Test
    @DisplayName("a clean upload is not refused")
    void cleanUploadIsAllowed() {
        rule = StubRule.notMatching(FirewallRuleType.KNOWN_MALICIOUS);
        givenRules(rule(FirewallRuleType.KNOWN_MALICIOUS, FirewallAction.BLOCK));

        FirewallEvaluation verdict = evaluator().evaluate(candidate(RepositoryType.HOSTED), CONTEXT);

        assertThat(verdict.blocked()).isFalse();
        assertThat(verdict.decision().reason()).isEqualTo(FirewallDecision.Reason.ALLOWED);
        verify(quarantine, never()).quarantine(any(), any(), any());
    }

    @Test
    @DisplayName("a WARN rule records but does not stop the publish")
    void warnDoesNotRefuse() {
        rule = StubRule.matching(FirewallRuleType.KNOWN_MALICIOUS, FirewallAction.WARN, false);
        givenRules(rule(FirewallRuleType.KNOWN_MALICIOUS, FirewallAction.WARN));

        FirewallEvaluation verdict = evaluator().evaluate(candidate(RepositoryType.HOSTED), CONTEXT);

        assertThat(verdict.blocked()).isFalse();
        assertThat(verdict.decision().violations()).hasSize(1);
    }

    @Test
    @DisplayName("the master switch off means no query and no verdict")
    void masterSwitchOffDoesNothing() {
        when(enforcementSettings.enforcementEnabled()).thenReturn(false);
        rule = StubRule.matching(FirewallRuleType.KNOWN_MALICIOUS, FirewallAction.BLOCK, false);

        FirewallEvaluation verdict = evaluator().evaluate(candidate(RepositoryType.HOSTED), CONTEXT);

        assertThat(verdict.blocked()).isFalse();
        assertThat(verdict.outcome()).isEqualTo(FirewallEvaluation.Outcome.NOT_ENFORCING);
        verify(evaluationService, never()).resolveSettings(any());
    }

    @Test
    @DisplayName("a repository in AUDIT mode publishes what a QUARANTINE one would refuse")
    void auditModeDoesNotRefuse() {
        when(evaluationService.resolveSettings(REPOSITORY))
                .thenReturn(settings(FirewallMode.AUDIT, FirewallFailMode.FAIL_CLOSED));
        rule = StubRule.matching(FirewallRuleType.KNOWN_MALICIOUS, FirewallAction.BLOCK, false);

        assertThat(evaluator().evaluate(candidate(RepositoryType.HOSTED), CONTEXT).blocked()).isFalse();
    }

    @Test
    @DisplayName("nothing is published into a proxy, so nothing is evaluated for one")
    void proxyIsNotEvaluated() {
        rule = StubRule.matching(FirewallRuleType.KNOWN_MALICIOUS, FirewallAction.BLOCK, false);

        FirewallEvaluation verdict = evaluator().evaluate(candidate(RepositoryType.PROXY), CONTEXT);

        assertThat(verdict.outcome()).isEqualTo(FirewallEvaluation.Outcome.NOT_ENFORCING);
    }

    @Test
    @DisplayName("re-publishing a path stored before the switch was flipped still works")
    void preExistingPathIsAllowed() {
        rule = StubRule.matching(FirewallRuleType.KNOWN_MALICIOUS, FirewallAction.BLOCK, false);
        givenRules(rule(FirewallRuleType.KNOWN_MALICIOUS, FirewallAction.BLOCK));
        AssetEntity asset = new AssetEntity();
        asset.setCreatedAt(Instant.parse("2025-06-01T00:00:00Z"));
        when(assets.findByRepositoryIdAndPath(REPOSITORY, PATH)).thenReturn(Optional.of(asset));

        FirewallEvaluation verdict = evaluator().evaluate(candidate(RepositoryType.HOSTED), CONTEXT);

        assertThat(verdict.blocked()).isFalse();
        assertThat(verdict.decision().reason()).isEqualTo(FirewallDecision.Reason.PRE_EXISTING);
        assertThat(verdict.preExisting()).isTrue();
    }

    @Test
    @DisplayName("a quarantining rule refuses the publish and leaves a queue entry")
    void quarantiningRuleRecordsAnEntry() {
        rule = StubRule.matching(FirewallRuleType.MIN_AGE, FirewallAction.BLOCK, true);
        givenRules(rule(FirewallRuleType.MIN_AGE, FirewallAction.BLOCK));

        FirewallEvaluation verdict = evaluator().evaluate(candidate(RepositoryType.HOSTED), CONTEXT);

        assertThat(verdict.blocked()).isTrue();
        ArgumentCaptor<FirewallQuarantineReason> reason =
                ArgumentCaptor.forClass(FirewallQuarantineReason.class);
        verify(quarantine).quarantine(any(), reason.capture(), any());
        assertThat(reason.getValue()).isEqualTo(FirewallQuarantineReason.MIN_AGE_NOT_MET);
    }

    @Test
    @DisplayName("INDETERMINATE: fail-closed refuses and holds, fail-open publishes")
    void indeterminateFollowsTheFailMode() {
        rule = StubRule.indeterminate(FirewallRuleType.MIN_AGE);
        givenRules(rule(FirewallRuleType.MIN_AGE, FirewallAction.BLOCK));

        when(evaluationService.resolveSettings(REPOSITORY))
                .thenReturn(settings(FirewallMode.QUARANTINE, FirewallFailMode.FAIL_CLOSED));
        FirewallEvaluation closed = evaluator().evaluate(candidate(RepositoryType.HOSTED), CONTEXT);
        assertThat(closed.blocked()).isTrue();
        verify(quarantine).quarantine(any(),
                org.mockito.ArgumentMatchers.eq(FirewallQuarantineReason.EVALUATION_INCOMPLETE), any());

        when(evaluationService.resolveSettings(REPOSITORY))
                .thenReturn(settings(FirewallMode.QUARANTINE, FirewallFailMode.FAIL_OPEN));
        assertThat(evaluator().evaluate(candidate(RepositoryType.HOSTED), CONTEXT).blocked()).isFalse();
    }

    @Test
    @DisplayName("a firewall defect publishes the artifact rather than failing the release")
    void aDefectAllowsThePublish() {
        rule = StubRule.notMatching(FirewallRuleType.KNOWN_MALICIOUS);
        when(evaluationService.resolveSettings(REPOSITORY))
                .thenThrow(new IllegalStateException("config table unreachable"));

        FirewallEvaluation verdict = evaluator().evaluate(candidate(RepositoryType.HOSTED), CONTEXT);

        assertThat(verdict.blocked()).isFalse();
        assertThat(verdict.outcome()).isEqualTo(FirewallEvaluation.Outcome.FAILED);
    }

    @Test
    @DisplayName("with no policy row at all the built-in rules still apply")
    void builtInRulesApplyWithoutAPolicy() {
        when(policies.findByIsDefaultTrue()).thenReturn(Optional.empty());
        rule = StubRule.matching(FirewallRuleType.KNOWN_MALICIOUS, FirewallAction.BLOCK, false);

        FirewallEvaluation verdict = evaluator().evaluate(candidate(RepositoryType.HOSTED), CONTEXT);

        assertThat(verdict.blocked())
                .as("an armed repository that allows every publish is not what the operator armed")
                .isTrue();
        assertThat(verdict.decision().policyName()).isEqualTo("built-in default");
    }

    @Test
    @DisplayName("the rule sees an upload as an upload")
    void ruleContextSaysUpload() {
        rule = StubRule.notMatching(FirewallRuleType.KNOWN_MALICIOUS);
        givenRules(rule(FirewallRuleType.KNOWN_MALICIOUS, FirewallAction.BLOCK));

        evaluator().evaluate(candidate(RepositoryType.HOSTED), CONTEXT);

        assertThat(rule.seen).isNotNull();
        assertThat(rule.seen.upload()).isTrue();
        assertThat(rule.seen.repositoryType()).isEqualTo(RepositoryType.HOSTED);
    }

    // ------------------------------------------------------------------

    private FirewallUploadEvaluator evaluator() {
        return new FirewallUploadEvaluator(
                evaluationService, enforcementSettings, policies, policyRules,
                new FirewallRuleRegistry(rule == null ? List.of() : List.of(rule)),
                advisories, assets, purlBuilder, quarantine, provider(facts));
    }

    private void givenRules(FirewallPolicyRuleEntity... rules) {
        when(policyRules.findByPolicyIdAndEnabledTrue(policyId)).thenReturn(List.of(rules));
    }

    private static FirewallPolicyRuleEntity rule(FirewallRuleType type, FirewallAction action) {
        FirewallPolicyRuleEntity entity = new FirewallPolicyRuleEntity();
        entity.setId(UUID.randomUUID());
        entity.setRuleType(type);
        entity.setAction(action);
        entity.setConfig(Map.of());
        entity.setEnabled(true);
        return entity;
    }

    private static FirewallUploadEvaluator.UploadCandidate candidate(RepositoryType type) {
        return new FirewallUploadEvaluator.UploadCandidate(REPOSITORY, NAME, type, PATH, identity());
    }

    private static ComponentIdentity identity() {
        try {
            return new ComponentIdentity.Purl(new PackageURL(KEY));
        } catch (MalformedPackageURLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static FirewallRepositorySettings settings(FirewallMode mode, FirewallFailMode failMode) {
        return new FirewallRepositorySettings(mode, failMode, null, true);
    }

    private static <T> ObjectProvider<T> provider(T value) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    /** A rule with a fixed answer that remembers the context it was handed. */
    private static final class StubRule implements FirewallRule {

        private final FirewallRuleType ruleType;
        private final FirewallRuleOutcome outcome;
        private final boolean holds;
        private FirewallRuleContext seen;

        private StubRule(FirewallRuleType ruleType, FirewallRuleOutcome outcome, boolean holds) {
            this.ruleType = ruleType;
            this.outcome = outcome;
            this.holds = holds;
        }

        static StubRule notMatching(FirewallRuleType type) {
            return new StubRule(type, FirewallRuleOutcome.notMatched(), false);
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
        public FirewallRuleType ruleType() {
            return ruleType;
        }

        @Override
        public FirewallRuleOutcome evaluate(FirewallRuleContext context, FirewallRuleSettings settings) {
            this.seen = context;
            return outcome;
        }

        @Override
        public boolean quarantineOnMatch() {
            return holds;
        }

        @Override
        public FirewallQuarantineReason quarantineReason() {
            return FirewallQuarantineReason.MIN_AGE_NOT_MET;
        }
    }
}
